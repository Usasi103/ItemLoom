package dev.itemloom.paper.action;

import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.compat.ni.script.LegacyActionManager;
import dev.itemloom.compat.ni.script.LegacySectionUtils;
import dev.itemloom.core.ActionFlow.Result;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.compat.NiItemNodes;
import dev.itemloom.paper.compat.script.LegacyActionTrigger;
import dev.itemloom.paper.compat.script.LegacyActionTrigger.ConsumeInfo;
import dev.itemloom.paper.compat.script.LegacyItemActionEvent;
import dev.itemloom.paper.compat.script.LegacyItemActionType;
import dev.itemloom.paper.compat.script.LegacyItemInfo;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/** Compiles NI ItemActions into independent steps and commits trigger gates on the server thread. */
public final class ItemTriggers implements AutoCloseable {
    private final PaperActions actions;
    private final PlayerActionState players;
    private final BiFunction<Object, Map<String, Object>, NiActionContext> contexts;
    private final JavaPlugin plugin;
    private final Map<String, Map<String, LegacyActionTrigger>> items;
    private final Set<String> keys;
    private final boolean tickPrefix;
    private final long tickSlots;
    private final String cooldownMessage;
    private final String brokenMessage;
    private final ReturnLedger returns;
    private final UUID returnOwner = UUID.randomUUID();
    private final Set<UUID> returnAfterCommit = new HashSet<>();
    private final Map<ConsumeSlot, EatCommit> eating = new LinkedHashMap<>();
    private final Map<Object, SourceCommit> sources = new LinkedHashMap<>();
    private int delayedEventDepth;
    private boolean closed;

    public ItemTriggers(
            NiRepository.Input input,
            PaperActions actions,
            PlayerActionState players,
            BiFunction<Object, Map<String, Object>, NiActionContext> contexts,
            JavaPlugin plugin) {
        this(
                input,
                actions,
                players,
                contexts,
                plugin,
                new LegacyActionTrigger.Factory(
                        actions,
                        new LegacyActionManager(actions.compiler(), actions::run),
                        source ->
                                contexts.apply(null, null)
                                        .evaluation()
                                        .scripts()
                                        .validate(source)));
    }

    public ItemTriggers(
            NiRepository.Input input,
            PaperActions actions,
            PlayerActionState players,
            BiFunction<Object, Map<String, Object>, NiActionContext> contexts,
            JavaPlugin plugin,
            LegacyActionTrigger.Factory factory) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.players = Objects.requireNonNull(players, "players");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        returns = players.returns(plugin);
        cooldownMessage = input.settings().string("Messages.itemCooldown");
        brokenMessage = input.settings().string("Messages.brokenItem");
        Objects.requireNonNull(factory, "factory");
        Map<String, Map<String, LegacyActionTrigger>> compiled = new LinkedHashMap<>();
        Set<String> registered = new HashSet<>();
        for (var entry : input.actions().entrySet()) {
            NiConfig config = config(entry.getValue());
            if (config == null) continue;
            config = normalize(config, input.settings().bool("ItemAction.upgrade", false));
            Map<String, LegacyActionTrigger> triggers = new LinkedHashMap<>();
            for (String type : config.keys()) {
                NiConfig trigger = config.section(type);
                if (trigger == null) continue;
                String key = key(type);
                try {
                    triggers.put(
                            key, factory.create(entry.getKey(), type, NiYaml.toSection(trigger)));
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(
                            "ItemActions / "
                                    + entry.getKey()
                                    + " / "
                                    + type
                                    + ": "
                                    + error.getMessage(),
                            error);
                }
            }
            registered.addAll(triggers.keySet());
            compiled.put(entry.getKey(), Map.copyOf(triggers));
        }
        items = Map.copyOf(compiled);
        keys = Set.copyOf(registered);
        tickPrefix = keys.stream().anyMatch(value -> value.startsWith("tick_"));
        long slots = 0;
        for (int slot = 0; slot <= 40; slot++)
            if (keys.contains("tick_" + slot)) slots |= 1L << slot;
        tickSlots = slots;
    }

    public boolean hasItem(String id) {
        return id != null && items.containsKey(id);
    }

    public boolean hasKey(String type) {
        return type != null && keys.contains(key(type));
    }

    public boolean hasKey(String id, String type) {
        Map<String, LegacyActionTrigger> triggers = id == null ? null : items.get(id);
        return triggers != null && type != null && triggers.containsKey(key(type));
    }

    public boolean hasKeyPrefix(String prefix) {
        if (prefix == null) return false;
        String expected = key(prefix);
        if (expected.equals("tick_")) return tickPrefix;
        return keys.stream().anyMatch(value -> value.startsWith(expected));
    }

    /** Immutable revision index; does not skip maintenance or change per-slot trigger counters. */
    public boolean hasTickSlot(int slot) {
        return slot >= 0 && slot <= 40 && (tickSlots & (1L << slot)) != 0;
    }

    public boolean hasTickEquipment(String type) {
        return type != null && keys.contains(type);
    }

    public String itemId(ItemStack item) {
        requireThread();
        return NiItemNodes.itemId(item);
    }

    /** The listener cancels the vanilla event; NI sends this message without a separate cooldown. */
    @SuppressWarnings("deprecation")
    public void broken(Player player) {
        requireThread();
        if (active() && brokenMessage != null && !brokenMessage.isEmpty())
            player.sendActionBar(brokenMessage);
    }

    public void interact(Player player, ItemStack item, PlayerInteractEvent event) {
        requireThread();
        if (!active() || item == null || item.isEmpty()) return;
        String direction =
                switch (event.getAction()) {
                    case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> "left";
                    case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> "right";
                    default -> null;
                };
        if (direction == null) return;
        String prefix = player.isSneaking() ? "shift_" : "";
        String basicKey = prefix + direction, allKey = prefix + "all";
        if (!hasKey(basicKey) && !hasKey(allKey)) return;
        String id = NiItemNodes.itemId(item);
        Map<String, LegacyActionTrigger> triggers = id == null ? null : items.get(id);
        if (triggers == null) return;
        LegacyActionTrigger basic = triggers.get(basicKey), all = triggers.get(allKey);
        if (basic == null && all == null) return;
        ItemIdentity identity = NiItemNodes.identity(item);
        if (identity == null) return;
        event.setCancelled(true);
        if (cooldown(basic == null ? all : basic, player)) return;
        if (basic != null && !allow(basic, player, item, identity)) return;
        if (all != null && !allow(all, player, item, identity)) return;
        if (!active()) return;
        NiActionContext context = context(player, item, event);
        if (context == null) return;
        ConsumeInfo consume =
                basic != null && basic.getConsume() != null
                        ? basic.getConsume()
                        : all == null ? null : all.getConsume();
        if (!consume(consume, player, item, context, false, identity.id())) return;
        if (basic != null) run(basic, context);
        if (all != null) run(all, context);
    }

    /** Listener entry with a physical hand source; the public overload remains caller-owned. */
    void interact(
            Player player, ItemStack supplied, PlayerInteractEvent event, TriggerSource source) {
        requireThread();
        String direction =
                switch (event.getAction()) {
                    case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> "left";
                    case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> "right";
                    default -> null;
                };
        if (direction == null || !active()) return;
        String prefix = player.isSneaking() ? "shift_" : "";
        String basicKey = prefix + direction, allKey = prefix + "all";
        if (!hasKey(basicKey) && !hasKey(allKey)) return;
        String id = NiItemNodes.itemId(supplied);
        Map<String, LegacyActionTrigger> configured = id == null ? null : items.get(id);
        if (configured == null) return;
        LegacyActionTrigger basic = configured.get(basicKey), all = configured.get(allKey);
        if (basic == null && all == null) return;
        event.setCancelled(true);
        SourceCommit commit = beginSource(source, supplied);
        if (commit == null) return;
        try {
            ItemStack item = source.candidate();
            ItemIdentity identity = NiItemNodes.identity(item);
            if (identity == null
                    || cooldown(basic == null ? all : basic, player)
                    || !stable(commit)) return;
            if (basic != null && (!allow(basic, player, item, identity) || !stable(commit))) return;
            if (all != null && (!allow(all, player, item, identity) || !stable(commit))) return;
            NiActionContext context = context(player, item, event);
            if (context == null) return;
            ConsumeInfo consume =
                    basic != null && basic.getConsume() != null
                            ? basic.getConsume()
                            : all == null ? null : all.getConsume();
            if (!consume(
                    consume,
                    player,
                    item,
                    context,
                    remainder -> stageReturn(commit, remainder),
                    id,
                    () -> stable(commit))) return;
            ItemStack actual = commitSource(commit, item);
            if (!commit.committed || !active() || commit.interrupted) return;
            bindActual(context, actual);
            if (basic != null) run(basic, context);
            if (all != null && !commit.interrupted) run(all, context);
        } finally {
            endSource(commit);
        }
    }

    /**
     * Owns the food event's inventory commit. Scripts may move or replace the live hand;
     * neither a stale event copy nor its split remainder may overwrite those changes.
     */
    public void eat(PlayerItemConsumeEvent event) {
        requireThread();
        Player player = event.getPlayer();
        ItemStack candidate = event.getItem();
        if (!active() || !hasKey("eat") || candidate == null || candidate.isEmpty()) return;
        String id = NiItemNodes.itemId(candidate);
        LegacyActionTrigger trigger = id == null ? null : find(id, "eat");
        if (trigger == null) return;
        ItemIdentity identity = NiItemNodes.identity(candidate);
        if (identity == null) return;
        event.setCancelled(true);
        PlayerInventory inventory = player.getInventory();
        long session = players.session(player.getUniqueId());
        int selected = inventory.getHeldItemSlot();
        int slot = event.getHand() == EquipmentSlot.OFF_HAND ? 40 : selected;
        ConsumeSlot key = new ConsumeSlot(player.getUniqueId(), slot);
        if (eating.containsKey(key)
                || sources.containsKey(new TriggerSource.PlayerSlot(player.getUniqueId(), slot)))
            return;
        EatCommit commit =
                new EatCommit(
                        player,
                        inventory,
                        slot,
                        selected,
                        event.getHand() == EquipmentSlot.HAND,
                        inventory.getItem(slot));
        returns.begin(returnOwner);
        eating.put(key, commit);
        delayedEventDepth++;
        try {
            // A preceding listener can change the event's copy independently of the source.
            if (!commit.unchanged() || !sameStack(commit.snapshot, candidate)) return;
            if (cooldown(trigger, player)
                    || !allow(trigger, player, candidate, identity)
                    || !active()) return;
            NiActionContext context = context(player, candidate, event);
            if (context == null) return;
            if (!consume(
                    trigger.getConsume(),
                    player,
                    candidate,
                    context,
                    remainder -> {
                        commit.pending =
                                returns.register(
                                        returnOwner,
                                        player,
                                        remainder,
                                        false,
                                        true,
                                        "eat slot " + slot,
                                        commit.snapshot);
                    },
                    identity.id())) return;
            if (!active()
                    || commit.interrupted
                    || !commit.unchanged()
                    || session == 0
                    || players.session(player.getUniqueId()) != session) return;
            ItemStack actual;
            try {
                returns.preparing(commit.pending, candidate);
                inventory.setItem(slot, candidate);
                actual = inventory.getItem(slot);
                if (!sameStack(candidate, actual))
                    throw new IllegalStateException(
                            "Food source write did not retain its prepared value");
                commit.committed = true;
            } catch (RuntimeException error) {
                // setItem may have written before throwing. Keep evidence, never guess whether
                // restoring the source or issuing the remainder would duplicate an item.
                if (commit.pending == null) {
                    commit.pending =
                            returns.register(
                                    returnOwner,
                                    player,
                                    candidate,
                                    false,
                                    false,
                                    "eat slot " + slot,
                                    commit.snapshot);
                }
                returns.sourceUnknown(commit.pending, candidate, error);
                return;
            }
            if (commit.pending != null) {
                returns.ready(commit.pending);
                scheduleReturn(commit.pending);
            }
            if (!active()
                    || commit.interrupted
                    || !player.isOnline()
                    || players.session(player.getUniqueId()) != session) return;
            // Keep invocation globals from pre, but item/NBT writes in the body target the
            // committed inventory stack. Exhausted items have no live identity or NBT view.
            context.set(NiContextKeys.ITEM_STACK, actual);
            LegacyItemInfo info = inspect(actual);
            if (info != null) {
                Map<String, String> priorData = context.getData();
                HashMap<String, String> data =
                        priorData instanceof HashMap<?, ?>
                                ? (HashMap<String, String>) priorData
                                : priorData == null ? null : new HashMap<>(priorData);
                info =
                        new LegacyItemInfo(
                                actual,
                                info.getNbtItemStack(),
                                info.getItemTag(),
                                info.getNeigeItems(),
                                info.getId(),
                                data);
            }
            context.set(NiContextKeys.ITEM_INFO, info);
            context.set(NiContextKeys.NBT, info == null ? null : info.getItemTag());
            run(trigger, context);
        } finally {
            // A body may set this false; vanilla must never charge the committed item again.
            event.setCancelled(true);
            eating.remove(key);
            delayedEventDepth--;
            if (!commit.committed && commit.pending != null && !commit.pending.outcomeUnknown)
                returns.abort(commit.pending);
            returns.end(returnOwner);
            finishEvent();
        }
    }

    /** Mutates a caller-owned stack. Food inventory events must use {@link #eat}. */
    public void handle(
            String type,
            Player player,
            ItemStack item,
            Event event,
            boolean cancel,
            boolean cancelOnCooldown,
            boolean consume,
            boolean delayReturn) {
        requireThread();
        if (!active()) return;
        if (delayReturn) {
            returns.begin(returnOwner);
            delayedEventDepth++;
        }
        try {
            if (!active() || !hasKey(type) || item == null || item.isEmpty()) return;
            String id = NiItemNodes.itemId(item);
            LegacyActionTrigger trigger = id == null ? null : find(id, type);
            if (trigger == null) return;
            ItemIdentity identity = NiItemNodes.identity(item);
            if (identity == null) return;
            if (cooldown(trigger, player)) {
                if (cancel || cancelOnCooldown) cancel(event);
                return;
            }
            if (!allow(trigger, player, item, identity) || !active()) return;
            if (cancel) cancel(event);
            NiActionContext context = context(player, item, event);
            if (context == null) return;
            if (consume
                    && !consume(
                            trigger.getConsume(),
                            player,
                            item,
                            context,
                            delayReturn,
                            identity.id())) return;
            run(trigger, context);
        } finally {
            if (delayReturn) {
                delayedEventDepth--;
                returns.end(returnOwner);
            }
        }
    }

    /** Physical event entry. Candidate edits commit once, before any trigger body can move items. */
    void handle(
            String type,
            Player player,
            ItemStack supplied,
            Event event,
            boolean cancel,
            boolean cancelOnCooldown,
            boolean consume,
            TriggerSource source) {
        requireThread();
        if (!active() || !hasKey(type) || supplied == null || supplied.isEmpty()) return;
        String id = NiItemNodes.itemId(supplied);
        LegacyActionTrigger trigger = id == null ? null : find(id, type);
        if (trigger == null) return;
        SourceCommit commit = beginSource(source, supplied);
        if (commit == null) {
            if (cancel) cancel(event);
            return;
        }
        ItemStack actual = null;
        try {
            ItemStack item = source.candidate();
            ItemIdentity identity = NiItemNodes.identity(item);
            if (identity == null) return;
            if (cooldown(trigger, player)) {
                if (cancel || cancelOnCooldown) cancel(event);
                return;
            }
            if (!stable(commit) || !allow(trigger, player, item, identity) || !stable(commit))
                return;
            if (cancel) cancel(event);
            NiActionContext context = context(player, item, event);
            if (context == null) return;
            if (consume
                    && !consume(
                            trigger.getConsume(),
                            player,
                            item,
                            context,
                            remainder -> stageReturn(commit, remainder),
                            id,
                            () -> stable(commit))) return;
            actual = commitSource(commit, item);
            if (!commit.committed || !active() || commit.interrupted) return;
            bindActual(context, actual);
            run(trigger, context);
        } finally {
            try {
                // Entity metadata needs an explicit update after synchronous live NBT edits.
                // Re-read only our still-owned committed handle; never write an old value over
                // a body replacement, a removed entity, or another catalog's work.
                if (commit.committed && active() && !commit.interrupted)
                    source.synchronizeEntity(actual);
                if (source.entityEmpty()) cancel(event);
            } finally {
                endSource(commit);
            }
        }
    }

    private SourceCommit beginSource(TriggerSource source, ItemStack supplied) {
        if (source == null || sources.containsKey(source.key)) return null;
        if (source.key instanceof TriggerSource.PlayerSlot slot
                && eating.containsKey(new ConsumeSlot(slot.player(), slot.slot()))) return null;
        if (!source.accepts(supplied)) return null;
        SourceCommit commit = new SourceCommit(source);
        returns.begin(returnOwner);
        sources.put(source.key, commit);
        delayedEventDepth++;
        return commit;
    }

    private boolean stable(SourceCommit commit) {
        return active() && !commit.interrupted && commit.source.unchanged();
    }

    private void stageReturn(SourceCommit commit, ItemStack remainder) {
        commit.pending =
                returns.register(
                        returnOwner,
                        commit.source.player,
                        remainder,
                        false,
                        true,
                        commit.source.key.toString(),
                        commit.source.candidate());
    }

    private ItemStack commitSource(SourceCommit commit, ItemStack candidate) {
        if (!stable(commit)) return null;
        ItemStack actual;
        try {
            returns.preparing(commit.pending, candidate);
            actual = commit.source.commit(candidate);
            commit.committed = true;
        } catch (RuntimeException failure) {
            if (commit.pending == null) {
                commit.pending =
                        returns.register(
                                returnOwner,
                                commit.source.player,
                                candidate,
                                false,
                                false,
                                commit.source.key.toString(),
                                commit.source.candidate());
            }
            returns.sourceUnknown(commit.pending, candidate, failure);
            return null;
        }
        if (commit.pending != null) {
            returns.ready(commit.pending);
        }
        return actual;
    }

    private void endSource(SourceCommit commit) {
        sources.remove(commit.source.key, commit);
        delayedEventDepth--;
        if (!commit.committed && commit.pending != null && !commit.pending.outcomeUnknown)
            returns.abort(commit.pending);
        returns.end(returnOwner);
        // Keep the committed source stable through the synchronous body. Returning into an
        // exhausted source slot before rebinding would make the context point at a dead handle.
        try {
            if (commit.committed && commit.pending != null) {
                returns.deliver(commit.pending);
                returns.schedule(commit.pending);
            }
        } finally {
            finishEvent();
        }
    }

    private void bindActual(NiActionContext context, ItemStack actual) {
        context.set(NiContextKeys.ITEM_STACK, actual);
        LegacyItemInfo info = inspect(actual);
        if (info != null) {
            Map<String, String> priorData = context.getData();
            HashMap<String, String> data =
                    priorData instanceof HashMap<?, ?>
                            ? (HashMap<String, String>) priorData
                            : priorData == null ? null : new HashMap<>(priorData);
            info =
                    new LegacyItemInfo(
                            actual,
                            info.getNbtItemStack(),
                            info.getItemTag(),
                            info.getNeigeItems(),
                            info.getId(),
                            data);
        }
        context.set(NiContextKeys.ITEM_INFO, info);
        context.set(NiContextKeys.NBT, info == null ? null : info.getItemTag());
    }

    /** Tick groups count matching slot visits, rather than elapsed wall-clock time. */
    public void tick(String type, Player player, ItemStack item) {
        requireThread();
        if (!active() || !hasKey(type) || item == null || item.isEmpty()) return;
        String id = NiItemNodes.itemId(item);
        LegacyActionTrigger trigger = id == null ? null : find(id, type);
        if (trigger == null) return;
        ItemIdentity identity = NiItemNodes.identity(item);
        if (identity == null) return;
        long interval = trigger.getTick().value(() -> context(player, item, null), 10);
        if (!active() || !trigger.getTick().isConstant() && !sameIdentity(item, identity)) return;
        String group = "TICK-" + trigger.getGroup();
        if (interval > 0) {
            long remaining = (Long) players.getMetadata(player.getUniqueId(), group, 0L);
            if (remaining > 0) {
                players.setMetadata(player.getUniqueId(), group, remaining - 1);
                return;
            }
        }
        if (!allow(trigger, player, item, identity) || !active()) return;
        NiActionContext context = context(player, item, null);
        if (context == null) return;
        players.setMetadata(player.getUniqueId(), group, interval);
        run(trigger, context);
    }

    @SuppressWarnings("deprecation")
    private boolean cooldown(LegacyActionTrigger trigger, Player player) {
        // The old cooldown evaluator deliberately receives only the player, not item context.
        long duration = trigger.getCooldown().value(() -> contexts.apply(player, null), 1000);
        if (!active()) return true;
        long remaining =
                players.checkCooldown(player.getUniqueId(), "ni:" + trigger.getGroup(), duration);
        if (remaining <= 0) return false;
        if (cooldownMessage != null)
            player.sendActionBar(
                    cooldownMessage.replace("{time}", String.format("%.1f", remaining / 1000.0)));
        return true;
    }

    private boolean allow(
            LegacyActionTrigger trigger, Player player, ItemStack item, ItemIdentity identity) {
        if (!active() || !sameIdentity(item, identity)) return false;
        LegacyItemActionType type = LegacyItemActionType.matchType(key(trigger.getType()));
        if (type == null) return true;
        LegacyItemInfo info = inspect(item);
        if (info == null) return false;
        LegacyItemActionEvent event = new LegacyItemActionEvent(player, item, info, type, trigger);
        Bukkit.getPluginManager().callEvent(event);
        // A listener may clear/replace the stack or reload this revision. Do not consume a
        // different identity, build a context from missing metadata, or call the next gate.
        return !event.isCancelled() && active() && sameIdentity(item, identity);
    }

    private static boolean sameIdentity(ItemStack item, ItemIdentity expected) {
        try {
            return expected.equals(NiItemNodes.identity(item));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private NiActionContext context(Player player, ItemStack item, Event event) {
        LegacyItemInfo info = inspect(item);
        // A legacy projection may also reject conflicting modern/legacy records even when
        // the primary identity is unchanged. Such an item must not enter consumption.
        if (info == null) return null;
        NiActionContext context = contexts.apply(player, null);
        context.set(NiContextKeys.ITEM_STACK, item);
        context.set(NiContextKeys.ITEM_INFO, info);
        context.set(NiContextKeys.DATA, info.getData());
        context.set(NiContextKeys.NBT, info.getItemTag());
        if (event != null) context.set(NiContextKeys.EVENT, event);
        return context;
    }

    private static LegacyItemInfo inspect(ItemStack item) {
        try {
            return LegacyItemInfo.inspect(item);
        } catch (RuntimeException invalidItem) {
            return null;
        }
    }

    private boolean consume(
            ConsumeInfo consume,
            Player player,
            ItemStack item,
            NiActionContext context,
            boolean delayReturn,
            String id) {
        return consume(
                consume,
                player,
                item,
                context,
                remainder -> {
                    if (delayReturn) returnLater(player, remainder);
                    else returnImmediately(player, remainder);
                },
                id);
    }

    private boolean consume(
            ConsumeInfo consume,
            Player player,
            ItemStack item,
            NiActionContext context,
            Consumer<ItemStack> returnRemainder,
            String id) {
        return consume(consume, player, item, context, returnRemainder, id, this::active);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean consume(
            ConsumeInfo consume,
            Player player,
            ItemStack item,
            NiActionContext context,
            Consumer<ItemStack> returnRemainder,
            String id,
            BooleanSupplier validSource) {
        if (consume == null) return active() && validSource.getAsBoolean();
        if (consume.hasPre()) consume.getPre().eval(context);
        // pre may launch delayed work or return STOP. Neither outcome is the consume condition.
        if (!active() || !validSource.getAsBoolean()) return false;
        if (consume.getCondition() != null) {
            boolean accepted = context.condition(consume.getCondition());
            if (!active() || !validSource.getAsBoolean()) return false;
            if (!accepted) return deny(consume, context);
        }
        int amount = 1;
        if (consume.getAmount() != null) {
            String text =
                    context.invoke(
                            () ->
                                    LegacySectionUtils.parseItemSection(
                                            consume.getAmount(),
                                            item,
                                            context.getNbt(),
                                            context.getData(),
                                            player,
                                            (Map) context.getGlobal(),
                                            null));
            try {
                amount = Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                /* NI falls back to one for non-integer text. */
            }
        }
        if (!active() || !validSource.getAsBoolean()) return false;
        if (amount <= 0) {
            // Intentional correction: invalid consume counts must never increase the stack/charge.
            plugin.getLogger()
                    .warning(
                            "ItemActions / "
                                    + id
                                    + ": consume.amount must be positive; rejected "
                                    + amount);
            return deny(consume, context);
        }
        if (!deduct(item, amount, context, returnRemainder)) return deny(consume, context);
        return true;
    }

    private boolean deny(ConsumeInfo consume, NiActionContext context) {
        if (active() && consume.hasDeny()) consume.getDeny().eval(context);
        return false;
    }

    private boolean deduct(
            ItemStack item,
            int amount,
            NiActionContext context,
            Consumer<ItemStack> returnRemainder) {
        requireThread();
        if (item.isEmpty()) return false;
        LegacyItemInfo info = (LegacyItemInfo) context.get(NiContextKeys.ITEM_INFO);
        Integer charge = info.getNeigeItems().getIntOrNull("charge");
        if (charge == null) {
            if (item.getAmount() < amount) return false;
            item.setAmount(item.getAmount() - amount);
            return true;
        }
        if (charge < amount) return false;
        ItemStack remainder = item.getAmount() > 1 ? item.clone() : null;
        if (remainder != null) remainder.setAmount(item.getAmount() - 1);
        if (charge == amount) item.setAmount(0);
        else {
            // The live facade commits only CUSTOM_DATA and preserves the independent state,
            // including compat_ni_rolls, while updating script references to this same tag.
            info.getNeigeItems().putInt("charge", charge - amount);
            item.setAmount(1);
        }
        if (remainder != null) returnRemainder.accept(remainder);
        return true;
    }

    private void run(LegacyActionTrigger trigger, NiActionContext context) {
        if (active()) trigger.run(context);
    }

    /** Takes ownership of a copy before scheduling; a scheduling failure leaves the copy pending. */
    public void returnLater(Player player, ItemStack item) {
        requireThread();
        if (closed) throw new IllegalStateException("Item trigger revision is closed");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(item, "item");
        if (item.isEmpty()) return;
        ReturnLedger.Entry pending =
                returns.register(
                        returnOwner, player, item, true, true, "caller-owned delayed return", null);
        scheduleReturn(pending);
    }

    private void scheduleReturn(ReturnLedger.Entry pending) {
        returns.schedule(pending);
    }

    private void returnImmediately(Player player, ItemStack item) {
        ReturnLedger.Entry pending =
                returns.register(
                        returnOwner,
                        player,
                        item,
                        true,
                        true,
                        "caller-owned immediate return",
                        null);
        returns.deliver(pending);
        returns.schedule(pending);
    }

    /** Flush deferred close/quit returns once all enclosing source commits have finished. */
    public void finishEvent() {
        requireThread();
        if (delayedEventDepth != 0) return;
        for (ReturnLedger.Entry pending : returns.entries(returnOwner)) {
            if (closed || returnAfterCommit.contains(pending.player)) returns.deliver(pending);
        }
        returnAfterCommit.clear();
    }

    /** Complete inventory conservation before a player's quit state is removed. */
    public void flushReturns(Player player) {
        requireThread();
        UUID id = player.getUniqueId();
        for (var entry : eating.entrySet())
            if (entry.getKey().player.equals(id)) entry.getValue().interrupted = true;
        for (SourceCommit commit : sources.values())
            if (commit.source.player.getUniqueId().equals(id)) commit.interrupted = true;
        if (delayedEventDepth > 0) {
            returnAfterCommit.add(id);
            return;
        }
        returns.flush(player);
    }

    private boolean active() {
        return !closed && actions.active() && players.active();
    }

    private LegacyActionTrigger find(String id, String type) {
        Map<String, LegacyActionTrigger> triggers = items.get(id);
        return triggers == null ? null : triggers.get(key(type));
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.getDefault());
    }

    private static void cancel(Event event) {
        if (event instanceof Cancellable cancellable) cancellable.setCancelled(true);
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Item trigger operation requires the server thread");
    }

    @Override
    public void close() {
        requireThread();
        if (closed) return;
        closed = true;
        // In-flight food transactions abort before writing, or already own a committed source.
        // Their finally blocks flush only returns explicitly marked ready after that write.
        if (delayedEventDepth == 0) finishEvent();
    }

    private static NiConfig config(Object source) {
        if (source instanceof NiConfig config) return config;
        if (!(source instanceof Map<?, ?> map)) return null;
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> values.put(String.valueOf(key), value));
        return new NiConfig(values);
    }

    /** Implements the optional old format upgrade in memory, retaining every input file verbatim. */
    private static NiConfig normalize(NiConfig source, boolean upgrade) {
        if (!upgrade) return source;
        Map<String, Object> values = new LinkedHashMap<>(source.values());
        NiConfig consume = source.section("consume");
        boolean changed = false;
        for (String type : List.of("left", "right", "all", "eat", "drop", "pick")) {
            if (!source.contains(type) || source.section(type) != null) continue;
            changed = true;
            Map<String, Object> trigger = new LinkedHashMap<>();
            boolean hasConsume =
                    consume != null
                            && (consume.bool(type, false)
                                    || type.equals("all")
                                            && (consume.bool("left", false)
                                                    || consume.bool("right", false)));
            if (hasConsume) {
                Map<String, Object> amount = new LinkedHashMap<>();
                if (consume.get("amount") != null) amount.put("amount", consume.get("amount"));
                trigger.put("consume", amount);
            }
            if (source.get("cooldown") != null) trigger.put("cooldown", source.get("cooldown"));
            if (source.get("group") != null) trigger.put("group", source.get("group"));
            trigger.put("sync", source.strings(type));
            values.put(type, trigger);
        }
        if (changed) {
            values.remove("consume");
            values.remove("cooldown");
            values.remove("group");
        }
        return new NiConfig(values);
    }

    private record ConsumeSlot(UUID player, int slot) {}

    private static final class SourceCommit {
        final TriggerSource source;
        ReturnLedger.Entry pending;
        boolean interrupted, committed;

        SourceCommit(TriggerSource source) {
            this.source = source;
        }
    }

    private static boolean sameStack(ItemStack first, ItemStack second) {
        return first == null || first.isEmpty()
                ? second == null || second.isEmpty()
                : first.equals(second);
    }

    private static final class EatCommit {
        final Player player;
        final PlayerInventory inventory;
        final int slot, selected;
        final boolean mainHand;
        final ItemStack source, snapshot;
        final net.minecraft.world.item.ItemStack handle;
        ReturnLedger.Entry pending;
        boolean interrupted, committed;

        EatCommit(
                Player player,
                PlayerInventory inventory,
                int slot,
                int selected,
                boolean mainHand,
                ItemStack source) {
            this.player = player;
            this.inventory = inventory;
            this.slot = slot;
            this.selected = selected;
            this.mainHand = mainHand;
            this.source = source;
            snapshot = source == null ? null : source.clone();
            handle = source instanceof CraftItemStack ? CraftItemStack.unwrap(source) : null;
        }

        boolean unchanged() {
            if (!player.isOnline() || mainHand && inventory.getHeldItemSlot() != selected)
                return false;
            ItemStack current = inventory.getItem(slot);
            boolean sameReference =
                    handle == null
                            ? current == source
                            : current instanceof CraftItemStack
                                    && CraftItemStack.unwrap(current) == handle;
            return sameReference && sameStack(snapshot, current);
        }
    }
}
