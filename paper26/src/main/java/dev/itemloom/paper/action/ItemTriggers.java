package dev.itemloom.paper.action;

import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.action.NiContextKeys;
import dev.itemloom.compat.ni.script.LegacyActionManager;
import dev.itemloom.compat.ni.script.LegacySectionUtils;
import dev.itemloom.core.ItemIdentity;
import dev.itemloom.paper.ItemsService;
import dev.itemloom.paper.compat.script.LegacyActionTrigger;
import dev.itemloom.paper.compat.script.LegacyItemActionEvent;
import dev.itemloom.paper.compat.script.LegacyItemActionType;
import dev.itemloom.paper.compat.script.LegacyItemInfo;
import dev.itemloom.paper.nms.ItemStateCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class ItemTriggers implements AutoCloseable {
    private final PaperActions actions;
    private final PlayerActionState players;
    private final BiFunction<Object, Map<String, Object>, NiActionContext> contexts;
    private final ItemStateCodec codec = new ItemStateCodec();
    private final Map<String, Map<String, LegacyActionTrigger>> triggers;
    private final Set<String> keys;
    private final long tickSlots;
    private final ReturnLedger ledger;
    private final UUID owner = UUID.randomUUID();
    private final Map<Object, Invocation> occupied = new HashMap<>();
    private final List<Invocation> running = new ArrayList<>();
    private final Set<Player> deferredFlush = new HashSet<>();
    private final String cooldownMessage;
    private final String brokenMessage;
    private int depth;
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
                        script ->
                                contexts.apply(null, Map.of())
                                        .evaluation()
                                        .scripts()
                                        .validate(script)));
    }

    public ItemTriggers(
            NiRepository.Input input,
            PaperActions actions,
            PlayerActionState players,
            BiFunction<Object, Map<String, Object>, NiActionContext> contexts,
            JavaPlugin plugin,
            LegacyActionTrigger.Factory factory) {
        this.actions = actions;
        this.players = players;
        this.contexts = contexts;
        ledger = players.returns(plugin);
        cooldownMessage = input.settings().string("Messages.itemCooldown", "");
        brokenMessage = input.settings().string("Messages.brokenItem", "");
        Map<String, Map<String, LegacyActionTrigger>> indexed = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (var item : input.actions().entrySet()) {
            NiConfig config;
            if (item.getValue() instanceof NiConfig section) config = section;
            else if (item.getValue() instanceof Map<?, ?> map) config = config(map);
            else continue;
            config =
                    TriggerRules.normalize(
                            config, input.settings().bool("ItemAction.upgrade", false));
            Map<String, LegacyActionTrigger> itemTriggers = new LinkedHashMap<>();
            for (String type : config.keys()) {
                NiConfig body = config.section(type);
                if (body == null) continue;
                try {
                    YamlConfiguration yaml = new YamlConfiguration();
                    copy(body.values(), yaml);
                    String key = lower(type);
                    itemTriggers.put(key, factory.create(item.getKey(), type, yaml));
                    names.add(key);
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(
                            "ItemActions/"
                                    + item.getKey()
                                    + "/"
                                    + type
                                    + ": "
                                    + failure.getMessage(),
                            failure);
                }
            }
            indexed.put(item.getKey(), Map.copyOf(itemTriggers));
        }
        triggers = Map.copyOf(indexed);
        keys = Set.copyOf(names);
        long slots = 0;
        for (int slot = 0; slot <= 40; slot++)
            if (keys.contains("tick_" + slot)) slots |= 1L << slot;
        tickSlots = slots;
    }

    private static NiConfig config(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return new NiConfig(result);
    }

    private static void copy(Map<String, Object> values, ConfigurationSection target) {
        values.forEach(
                (key, value) -> {
                    if (value instanceof Map<?, ?> nested)
                        copy(config(nested).values(), target.createSection(key));
                    else target.set(key, value);
                });
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.getDefault());
    }

    private boolean active() {
        return !closed && actions.active() && players.active();
    }

    public boolean hasItem(String id) {
        return triggers.containsKey(id);
    }

    public boolean hasKey(String type) {
        return keys.contains(lower(type));
    }

    public boolean hasKey(String id, String type) {
        return triggers.getOrDefault(id, Map.of()).containsKey(lower(type));
    }

    public boolean hasKeyPrefix(String prefix) {
        return keys.stream().anyMatch(key -> key.startsWith(lower(prefix)));
    }

    public boolean hasTickSlot(int slot) {
        return slot >= 0 && slot <= 40 && (tickSlots & 1L << slot) != 0;
    }

    public boolean hasTickEquipment(String type) {
        return keys.contains(type);
    }

    public String itemId(ItemStack item) {
        ItemsService.requireThread();
        return item == null || item.isEmpty() ? null : codec.readId(item);
    }

    public void broken(Player player) {
        ItemsService.requireThread();
        if (active() && !brokenMessage.isEmpty()) player.sendActionBar(brokenMessage);
    }

    public void interact(Player player, ItemStack item, PlayerInteractEvent event) {
        interact(player, item, event, null);
    }

    void interact(Player player, ItemStack item, PlayerInteractEvent event, TriggerSource source) {
        ItemsService.requireThread();
        if (!active() || empty(item) || event.getAction() == Action.PHYSICAL) return;
        String direction =
                switch (event.getAction()) {
                    case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> "left";
                    case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> "right";
                    default -> null;
                };
        if (direction == null) return;
        String prefix = player.isSneaking() ? "shift_" : "";
        if (!hasKey(prefix + direction) && !hasKey(prefix + "all")) return;
        String id = itemId(item);
        if (id == null) return;
        Map<String, LegacyActionTrigger> configured = triggers.get(id);
        if (configured == null) return;
        List<LegacyActionTrigger> selected = new ArrayList<>(2);
        LegacyActionTrigger first = configured.get(prefix + direction);
        LegacyActionTrigger all = configured.get(prefix + "all");
        if (first != null) selected.add(first);
        if (all != null) selected.add(all);
        if (selected.isEmpty()) return;
        event.setCancelled(true);
        invoke(selected, player, item, event, true, true, true, false, source, false);
    }

    public void handle(
            String type,
            Player player,
            ItemStack item,
            Event event,
            boolean cancel,
            boolean cooldownCancel,
            boolean consume,
            boolean delayReturn) {
        handleSelected(
                type, player, item, event, cancel, cooldownCancel, consume, delayReturn, null);
    }

    void handle(
            String type,
            Player player,
            ItemStack item,
            Event event,
            boolean cancel,
            boolean cooldownCancel,
            boolean consume,
            TriggerSource source) {
        handleSelected(type, player, item, event, cancel, cooldownCancel, consume, false, source);
    }

    private void handleSelected(
            String type,
            Player player,
            ItemStack item,
            Event event,
            boolean cancel,
            boolean cooldownCancel,
            boolean consume,
            boolean delay,
            TriggerSource source) {
        ItemsService.requireThread();
        LegacyActionTrigger trigger = lookup(type, item);
        if (trigger != null)
            invoke(
                    List.of(trigger),
                    player,
                    item,
                    event,
                    cancel,
                    cooldownCancel,
                    consume,
                    delay,
                    source,
                    false);
    }

    private LegacyActionTrigger lookup(String type, ItemStack item) {
        if (!active() || type == null || empty(item) || !hasKey(type)) return null;
        String id = itemId(item);
        return id == null ? null : triggers.getOrDefault(id, Map.of()).get(lower(type));
    }

    public void eat(PlayerItemConsumeEvent event) {
        ItemsService.requireThread();
        ItemStack candidate = event.getItem();
        LegacyActionTrigger trigger = lookup("eat", candidate);
        if (trigger == null) return;
        event.setCancelled(true);
        TriggerSource source = TriggerSource.equipment(event.getPlayer(), event.getHand());
        invoke(
                List.of(trigger),
                event.getPlayer(),
                candidate,
                event,
                true,
                true,
                true,
                true,
                source,
                true);
    }

    private void invoke(
            List<LegacyActionTrigger> selected,
            Player player,
            ItemStack supplied,
            Event event,
            boolean cancel,
            boolean cooldownCancel,
            boolean consume,
            boolean delay,
            TriggerSource source,
            boolean food) {
        ItemIdentity identity = identity(supplied);
        if (identity == null) return;
        if (source != null && occupied.containsKey(source.key)) {
            if (cancel) cancel(event);
            return;
        }
        if (source != null
                && (!source.accepts(food ? source.original : supplied)
                        || food && !Objects.equals(source.candidate(), supplied))) return;
        long session = players.session(player.getUniqueId());
        if (session == 0) return;
        Invocation invocation =
                new Invocation(player, session, identity, source, food || delay || source != null);
        if (invocation.leased) ledger.begin(owner);
        if (source != null) occupied.put(source.key, invocation);
        running.add(invocation);
        if (invocation.leased) depth++;
        ItemStack candidate = source == null || food ? supplied : source.candidate();
        ItemStack actual = candidate;
        try {
            if (!live(invocation)
                    || !cooldown(selected.getFirst(), player, cancel || cooldownCancel, event))
                return;
            if (!valid(invocation, candidate)) return;
            for (LegacyActionTrigger trigger : selected) {
                if (!allow(trigger, invocation, candidate)) return;
            }
            if (cancel) cancel(event);
            NiActionContext context = context(player, candidate, event);
            if (context == null) return;
            LegacyActionTrigger.ConsumeInfo consumption = selected.getFirst().getConsume();
            if (consumption == null && selected.size() > 1)
                consumption = selected.get(1).getConsume();
            if (consume && !consume(consumption, invocation, candidate, context, delay)) return;
            if (!live(invocation) || !empty(candidate) && !valid(invocation, candidate)) return;
            if (source != null) {
                if (invocation.returned != null) ledger.preparing(invocation.returned, candidate);
                try {
                    actual = source.commit(candidate, food);
                    invocation.committed = true;
                    if (invocation.returned != null) ledger.ready(invocation.returned);
                } catch (RuntimeException failure) {
                    if (invocation.returned == null) {
                        invocation.returned =
                                ledger.register(
                                        owner,
                                        player,
                                        source.candidate(),
                                        false,
                                        false,
                                        sourceName(source, food),
                                        source.candidate());
                    }
                    ledger.sourceUnknown(invocation.returned, candidate, failure);
                    return;
                }
                if (!sessionLive(invocation)) return;
                if (!bind(context, actual)) return;
            }
            for (LegacyActionTrigger trigger : selected) {
                if (!bodyLive(invocation, actual)) break;
                trigger.run(context);
            }
        } finally {
            if (food || invocation.consumed) cancel(event);
            try {
                if (source != null) {
                    if (invocation.committed) source.synchronizeEntity(actual);
                    if (source.entityEmpty()) cancel(event);
                }
            } finally {
                if (source != null) occupied.remove(source.key, invocation);
                running.remove(invocation);
                try {
                    if (invocation.returned != null && source != null && !invocation.committed)
                        ledger.abort(invocation.returned);
                } finally {
                    if (invocation.leased) {
                        depth--;
                        ledger.end(owner);
                    }
                }
                if (invocation.returned != null && (source == null || invocation.committed)) {
                    if (!food && !delay) ledger.deliver(invocation.returned);
                    ledger.schedule(invocation.returned);
                }
                finishEvent();
            }
        }
    }

    private boolean consume(
            LegacyActionTrigger.ConsumeInfo consume,
            Invocation invocation,
            ItemStack item,
            NiActionContext context,
            boolean delay) {
        if (!valid(invocation, item)) return false;
        if (consume == null) return true;
        if (consume.hasPre()) consume.getPre().eval(context);
        if (!valid(invocation, item)) return false;
        boolean allowed =
                consume.getCondition() == null || context.condition(consume.getCondition());
        if (!valid(invocation, item)) return false;
        if (!allowed) return deny(consume, context);
        String expanded =
                context.invoke(
                        () ->
                                LegacySectionUtils.parseItemSection(
                                        consume.getAmount(),
                                        item,
                                        context.getNbt(),
                                        context.getData(),
                                        invocation.player));
        if (!valid(invocation, item)) return false;
        LegacyItemInfo info = projection(item, invocation.identity);
        if (info == null) return false;
        Integer charge = info.getNeigeItems().getIntOrNull("charge");
        TriggerRules.Deduction plan =
                TriggerRules.deduct(item.getAmount(), charge, TriggerRules.amount(expanded));
        if (plan == null) return deny(consume, context);
        ItemStack untouched = null;
        if (plan.returnedCount() > 0) {
            untouched = item.clone();
            untouched.setAmount(plan.returnedCount());
        }
        if (charge != null && plan.affectedCount() > 0) {
            // The facade already exposed to pre must observe this same mutation.
            LegacyItemInfo exposed = (LegacyItemInfo) context.get(NiContextKeys.ITEM_INFO);
            exposed.getNeigeItems().putInt("charge", plan.charge());
        }
        item.setAmount(plan.affectedCount());
        invocation.consumed = true;
        if (untouched != null) {
            invocation.returned =
                    ledger.register(
                            owner,
                            invocation.player,
                            untouched,
                            invocation.source == null,
                            true,
                            sourceName(invocation.source, false),
                            invocation.source == null ? null : invocation.source.candidate());
            if (invocation.source == null && !delay) {
                ledger.deliver(invocation.returned);
                ledger.schedule(invocation.returned);
            }
        }
        return true;
    }

    private boolean deny(LegacyActionTrigger.ConsumeInfo consume, NiActionContext context) {
        if (active() && consume.hasDeny()) consume.getDeny().eval(context);
        return false;
    }

    private boolean cooldown(
            LegacyActionTrigger trigger, Player player, boolean cancel, Event event) {
        long duration = trigger.getCooldown().value(() -> contexts.apply(player, null), 1000);
        if (!active()) return false;
        long remaining =
                players.checkCooldown(player.getUniqueId(), "ni:" + trigger.getGroup(), duration);
        if (remaining <= 0) return true;
        if (cancel) cancel(event);
        if (!cooldownMessage.isEmpty())
            player.sendActionBar(
                    cooldownMessage.replace(
                            "{time}", String.format(Locale.ROOT, "%.1f", remaining / 1000d)));
        return false;
    }

    private boolean allow(LegacyActionTrigger trigger, Invocation invocation, ItemStack item) {
        if (!valid(invocation, item)) return false;
        LegacyItemInfo info = projection(item, invocation.identity);
        if (info == null) return false;
        LegacyItemActionType type = LegacyItemActionType.matchType(trigger.getType());
        if (type != null
                && !new LegacyItemActionEvent(invocation.player, item, info, type, trigger).call())
            return false;
        return valid(invocation, item);
    }

    private LegacyItemInfo projection(ItemStack item, ItemIdentity expected) {
        if (empty(item)) return null;
        try {
            LegacyItemInfo info = LegacyItemInfo.inspect(item);
            return info != null && expected.id().equals(info.getId()) ? info : null;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private NiActionContext context(Player player, ItemStack item, Event event) {
        ItemIdentity identity = identity(item);
        LegacyItemInfo info = identity == null ? null : projection(item, identity);
        if (info == null) return null;
        NiActionContext context = contexts.apply(player, new HashMap<>());
        context.set(NiContextKeys.ITEM_STACK, item);
        context.set(NiContextKeys.ITEM_INFO, info);
        context.set(NiContextKeys.NBT, info.getItemTag());
        context.set(NiContextKeys.DATA, info.getData());
        if (event != null) context.set(NiContextKeys.EVENT, event);
        return context;
    }

    private boolean bind(NiActionContext context, ItemStack actual) {
        Map<String, String> originalData = context.getData();
        HashMap<String, String> data =
                originalData instanceof HashMap<String, String> map
                        ? map
                        : new HashMap<>(originalData);
        context.set(NiContextKeys.ITEM_STACK, actual);
        context.set(NiContextKeys.DATA, data);
        if (empty(actual)) {
            context.set(NiContextKeys.NBT, null);
            context.set(NiContextKeys.ITEM_INFO, null);
            return true;
        }
        LegacyItemInfo info = LegacyItemInfo.inspect(actual);
        if (info == null) return false;
        LegacyItemInfo rebound =
                new LegacyItemInfo(
                        actual,
                        info.getNbtItemStack(),
                        info.getItemTag(),
                        info.getNeigeItems(),
                        info.getId(),
                        data);
        context.set(NiContextKeys.ITEM_INFO, rebound);
        context.set(NiContextKeys.NBT, rebound.getItemTag());
        return true;
    }

    private boolean sessionLive(Invocation invocation) {
        return active()
                && !invocation.interrupted
                && invocation.player.isOnline()
                && players.session(invocation.player.getUniqueId()) == invocation.session;
    }

    private boolean live(Invocation invocation) {
        return sessionLive(invocation)
                && (invocation.source == null || invocation.source.unchanged());
    }

    private boolean valid(Invocation invocation, ItemStack item) {
        return live(invocation)
                && invocation.identity.equals(identity(item))
                && projection(item, invocation.identity) != null;
    }

    private boolean bodyLive(Invocation invocation, ItemStack item) {
        if (!sessionLive(invocation)) return false;
        if (invocation.source != null && !invocation.source.ownsCommitted(item)) return false;
        return empty(item) || invocation.identity.equals(identity(item));
    }

    private ItemIdentity identity(ItemStack item) {
        try {
            return empty(item) ? null : codec.read(item).orElse(null);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public void tick(String type, Player player, ItemStack item) {
        ItemsService.requireThread();
        LegacyActionTrigger trigger = lookup(type, item);
        if (trigger == null) return;
        ItemIdentity identity = identity(item);
        long session = players.session(player.getUniqueId());
        if (identity == null || session == 0) return;
        Invocation invocation = new Invocation(player, session, identity, null, false);
        long interval = trigger.getTick().value(() -> context(player, item, null), 10);
        if (!valid(invocation, item)) return;
        String key = "TICK-" + trigger.getGroup();
        long remaining = (Long) players.getMetadata(player.getUniqueId(), key, 0L);
        if (TriggerRules.skipVisit(interval, remaining)) {
            players.setMetadata(player.getUniqueId(), key, remaining - 1);
            return;
        }
        if (!allow(trigger, invocation, item)) return;
        NiActionContext context = context(player, item, null);
        if (context == null) return;
        players.setMetadata(player.getUniqueId(), key, interval);
        trigger.run(context);
    }

    public void returnLater(Player player, ItemStack item) {
        ItemsService.requireThread();
        if (closed || player == null || empty(item)) return;
        ReturnLedger.Entry entry =
                ledger.register(owner, player, item, true, true, "owned-return", null);
        ledger.schedule(entry);
    }

    public void finishEvent() {
        ItemsService.requireThread();
        if (depth != 0) return;
        if (closed) for (ReturnLedger.Entry entry : ledger.entries(owner)) ledger.deliver(entry);
        List<Player> pending = new ArrayList<>(deferredFlush);
        deferredFlush.clear();
        for (Player player : pending) ledger.flush(player);
    }

    public void flushReturns(Player player) {
        ItemsService.requireThread();
        for (Invocation invocation : running) {
            if (invocation.player.getUniqueId().equals(player.getUniqueId()))
                invocation.interrupted = true;
        }
        if (depth != 0) deferredFlush.add(player);
        else ledger.flush(player);
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        if (closed) return;
        closed = true;
        finishEvent();
    }

    private static void cancel(Event event) {
        if (event instanceof Cancellable cancellable) cancellable.setCancelled(true);
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    private static String sourceName(TriggerSource source, boolean food) {
        return source == null ? "caller-item" : (food ? "eat:" : "physical:") + source.key;
    }

    private static final class Invocation {
        final Player player;
        final long session;
        final ItemIdentity identity;
        final TriggerSource source;
        final boolean leased;
        ReturnLedger.Entry returned;
        boolean interrupted;
        boolean consumed;
        boolean committed;

        Invocation(
                Player player,
                long session,
                ItemIdentity identity,
                TriggerSource source,
                boolean leased) {
            this.player = player;
            this.session = session;
            this.identity = identity;
            this.source = source;
            this.leased = leased;
        }
    }
}
