package dev.itemloom.paper.compat.script;

import static dev.itemloom.paper.compat.script.ActionHelperValues.*;

import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.script.LegacyActionManager;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.compat.ni.script.LegacySectionUtils;
import dev.itemloom.paper.compat.nbt.LegacyNbt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;
import org.openjdk.nashorn.api.scripting.ScriptUtils;

import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.script.Bindings;

/** One action's helper dispatch table. It never becomes an engine-global service or singleton. */
public final class ActionHelperScope {
    private static final Object NO_RESULT = new Object();
    private final Bindings bindings;
    private final Map<String, Function<Object[], Object>> functions = new LinkedHashMap<>();
    private LegacyScheduler scheduler;
    private ScriptObjectMirror eventGetter;

    ActionHelperScope(Bindings bindings) {
        this.bindings = bindings;
        installActions();
        installPlayer();
        installCalendar();
    }

    public Set<String> getNames() {
        return Collections.unmodifiableSet(functions.keySet());
    }

    public Object getNoResult() {
        return NO_RESULT;
    }

    public void captureScheduler(LegacyScheduler owner) {
        if (scheduler != null) throw new IllegalStateException("Action scheduler already captured");
        scheduler = java.util.Objects.requireNonNull(owner);
    }

    public void captureEventGetter(Object getter) {
        if (eventGetter != null)
            throw new IllegalStateException("Action event getter already captured");
        eventGetter = ScriptUtils.wrap(getter);
    }

    public Object call(String name, Object[] arguments) {
        // Java.to(arguments, Object[]) can retain native Nashorn arrays and functions inside
        // the Java array. Capture public mirrors while this action's global is active; callbacks
        // then retain their home scope and nested array reads also return public values.
        Object result = functions.get(name).apply(ScriptUtils.wrapArray(arguments));
        // Optional setters return the supplied value. Unwrap same-scope mirrors on the way back
        // so JavaScript object identity is preserved; Java values and undefined pass through.
        return ScriptUtils.unwrap(result);
    }

    private void bind(String name, Function<Object[], Object> function) {
        functions.put(name, function);
    }

    private void read(String name, Supplier<?> getter) {
        bind(name, ignored -> getter.get());
    }

    private Player player() {
        return (Player) bindings.get("player");
    }

    private LegacyItemUtils items() {
        return (LegacyItemUtils) bindings.get("ItemUtils");
    }

    private LegacyPlayerUtils players() {
        return (LegacyPlayerUtils) bindings.get("PlayerUtils");
    }

    private LegacyHooks hooks() {
        return (LegacyHooks) bindings.get("HookerManager");
    }

    private void installActions() {
        bind("perm", args -> player().hasPermission(text(argument(args, 0))));
        bind("color", args -> ChatColor.translateAlternateColorCodes('&', text(argument(args, 0))));
        bind(
                "tell",
                args -> {
                    player().sendMessage(text(argument(args, 0)));
                    return NO_RESULT;
                });
        bind(
                "command",
                args -> {
                    Bukkit.dispatchCommand(player(), text(argument(args, 0)));
                    return NO_RESULT;
                });
        bind(
                "console",
                args -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text(argument(args, 0)));
                    return NO_RESULT;
                });
        bind("papi", args -> hooks().papi(player(), text(argument(args, 0))));
        bind(
                "node",
                args ->
                        ((LegacyActionManager) bindings.get("ActionManager"))
                                .parseNode(
                                        text(argument(args, 0)),
                                        (NiActionContext) bindings.get("context")));
        bind("parse", args -> parse(text(argument(args, 0))));
        bind("parseItem", args -> parseItem(text(argument(args, 0))));
        bind("getNBTTag", args -> tag(text(argument(args, 0))));
        bind(
                "getNBT",
                args -> {
                    LegacyNbt value = tag(text(argument(args, 0)));
                    return value == null ? null : items().toValue(value).toString();
                });
        bind(
                "random",
                args ->
                        ThreadLocalRandom.current()
                                .nextDouble(
                                        orNumber(argument(args, 0), 0),
                                        orNumber(argument(args, 1), 1)));
        bind(
                "chance",
                args ->
                        number(argument(args, 0))
                                > ThreadLocalRandom.current()
                                        .nextDouble(orNumber(argument(args, 1), 1)));
        bind("combo", args -> combo(text(argument(args, 0)), argument(args, 1)));
        bind(
                "comboSize",
                args -> players().comboSnapshot(player(), text(argument(args, 0))).size());
        read("hand", () -> getter(bindings.get("event"), "getHand").toString());
        read(
                "isMainHand",
                () ->
                        org.bukkit.inventory.EquipmentSlot.HAND.equals(
                                getter(bindings.get("event"), "getHand")));
        read(
                "isOffHand",
                () ->
                        org.bukkit.inventory.EquipmentSlot.OFF_HAND.equals(
                                getter(bindings.get("event"), "getHand")));
        bind("niItemAmount", args -> itemAmount(text(argument(args, 0))));
        bind(
                "checkNiItemAmount",
                args -> consume(text(argument(args, 0)), number(argument(args, 1)), false));
        bind(
                "takeNiItem",
                args -> consume(text(argument(args, 0)), number(argument(args, 1)), true));
        bind("sync", args -> schedule(args, false));
        bind("async", args -> schedule(args, true));
        read("getAttackerMobId", () -> mobId("getDamager"));
        read("getDefenderMobId", () -> mobId("getEntity"));
        bind(
                "checkCooldown",
                args ->
                        players()
                                .checkCooldown(
                                        player(),
                                        text(argument(args, 0)),
                                        longNumber(argument(args, 1))));
        bind("getCooldown", args -> players().getCooldown(player(), text(argument(args, 0))));
        bind(
                "setCooldown",
                args -> {
                    players()
                            .setCooldown(
                                    player(),
                                    text(argument(args, 0)),
                                    longNumber(argument(args, 1)));
                    return NO_RESULT;
                });
    }

    @SuppressWarnings("unchecked")
    private String parse(String text) {
        Object sections = bindings.get("sections");
        if (sections instanceof LegacyConfigReader reader) sections = reader.getHandle();
        return LegacySectionUtils.parseSection(
                text,
                (Map<String, String>) optionalBinding("cache"),
                player(),
                absent(sections) ? null : (ConfigurationSection) sections);
    }

    @SuppressWarnings("unchecked")
    private String parseItem(String text) {
        Object tag = bindings.get("itemTag");
        if (!bindings.containsKey("itemTag")
                || org.openjdk.nashorn.api.scripting.ScriptObjectMirror.isUndefined(tag))
            return "未传入物品";
        return LegacySectionUtils.parseItemSection(
                text,
                (ItemStack) bindings.get("itemStack"),
                tag,
                (Map<String, String>) optionalBinding("data"),
                player());
    }

    private Object optionalBinding(String name) {
        Object value = bindings.get(name);
        return absent(value) ? null : value;
    }

    private LegacyNbt tag(String key) {
        return ((LegacyNbt.Compound) bindings.get("itemTag")).getDeep(key);
    }

    private boolean combo(String group, Object requested) {
        List<?> history = players().comboSnapshot(player(), group);
        int size = size(requested);
        if (history.size() < size) return false;
        for (int index = 0; index < size; index++) {
            Object actual = getter(history.get(history.size() - size + index), "getType");
            Object expected = element(requested, index);
            // A numeric or boxed JavaScript value must not be coerced into a string here.
            if (!(expected instanceof CharSequence) || !actual.equals(expected.toString()))
                return false;
        }
        return true;
    }

    private double itemAmount(String id) {
        double amount = 0;
        for (ItemStack stack : player().getInventory().getContents())
            if (matches(stack, id)) amount += stack.getAmount();
        return amount;
    }

    private boolean matches(ItemStack stack, String id) {
        return stack != null && id != null && id.equals(items().getItemId(stack));
    }

    /** A legacy removal is deliberately sequential and can exhaust a partial inventory. */
    private boolean consume(String id, double remaining, boolean change) {
        PlayerInventory inventory = player().getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (!matches(stack, id)) continue;
            double removed = Math.min(remaining, stack.getAmount());
            remaining -= removed;
            if (change) {
                int amount = (int) (stack.getAmount() - removed);
                if (amount == 0) inventory.setItem(slot, null);
                else {
                    stack.setAmount(amount);
                    inventory.setItem(slot, stack);
                }
            }
            if (remaining == 0) return true;
        }
        return remaining == 0;
    }

    private Object schedule(Object[] arguments, boolean async) {
        if (arguments.length != 1 && arguments.length != 2)
            throw new IllegalArgumentException(
                    "Action scheduling accepts a callable and optional Plugin owner");
        Runnable callback = task(arguments[arguments.length - 1]);
        if (arguments.length == 1) {
            if (async) scheduler.async(callback);
            else scheduler.sync(callback);
        } else {
            Plugin owner = (Plugin) arguments[0];
            if (async) scheduler.async(owner, callback);
            else scheduler.sync(owner, callback);
        }
        return NO_RESULT;
    }

    private String mobId(String eventMethod) {
        var mythic = hooks().getMythicMobsHooker();
        return mythic == null
                ? null
                : mythic.getMythicId((Entity) eventGetter.call(null, eventMethod));
    }

    private void property(String name, Function<Player, ?> get, BiConsumer<Player, Object> set) {
        bind(
                name,
                args -> {
                    Object value = argument(args, 0);
                    if (absent(value)) return get.apply(player());
                    set.accept(player(), value);
                    return value;
                });
    }

    @SuppressWarnings("deprecation")
    private void installPlayer() {
        read("address", () -> player().getAddress().getHostString());
        read("attackCooldown", () -> player().getAttackCooldown());
        read("bedSpawn", () -> player().getBedSpawnLocation());
        read("bedSpawnX", () -> player().getBedSpawnLocation().getX());
        read("bedSpawnY", () -> player().getBedSpawnLocation().getY());
        read("bedSpawnZ", () -> player().getBedSpawnLocation().getZ());
        read("blocking", () -> player().isBlocking());
        read("compassTargetX", () -> player().getCompassTarget().getX());
        read("compassTargetY", () -> player().getCompassTarget().getY());
        read("compassTargetZ", () -> player().getCompassTarget().getZ());
        read("dead", () -> player().isDead());
        read("firstPlay", () -> !player().hasPlayedBefore());
        read("health", () -> player().getHealth());
        read("maxHealth", () -> player().getMaxHealth());
        read("name", () -> player().getName());
        read("sleeping", () -> player().isSleeping());
        read("world", () -> player().getWorld().getName());
        read("mainHandItem", () -> player().getInventory().getItemInMainHand());
        read("offHandItem", () -> player().getInventory().getItemInOffHand());
        property(
                "allowFlight",
                Player::getAllowFlight,
                (p, value) -> p.setAllowFlight(truth(value)));
        property(
                "compassTarget",
                Player::getCompassTarget,
                (p, value) -> p.setCompassTarget((Location) value));
        property(
                "exhaustion",
                Player::getExhaustion,
                (p, value) -> p.setExhaustion((float) number(value)));
        property(
                "exp",
                Player::getTotalExperience,
                (p, value) -> p.setTotalExperience(integer(value)));
        property("level", Player::getLevel, (p, value) -> p.setLevel(integer(value)));
        property("fly", Player::isFlying, (p, value) -> p.setFlying(truth(value)));
        property(
                "flySpeed",
                Player::getFlySpeed,
                (p, value) -> p.setFlySpeed((float) number(value)));
        property(
                "walkSpeed",
                Player::getWalkSpeed,
                (p, value) -> p.setWalkSpeed((float) number(value)));
        property("food", Player::getFoodLevel, (p, value) -> p.setFoodLevel(integer(value)));
        property(
                "gamemode",
                p -> p.getGameMode().name(),
                (p, value) ->
                        p.setGameMode(GameMode.valueOf(text(value).toUpperCase(Locale.ROOT))));
        property("guilding", Player::isGliding, (p, value) -> p.setGliding(truth(value)));
        property("glowing", Player::isGlowing, (p, value) -> p.setGlowing(truth(value)));
        property("gravity", Player::hasGravity, (p, value) -> p.setGravity(truth(value)));
        property(
                "remainingAir",
                Player::getRemainingAir,
                (p, value) -> p.setRemainingAir(integer(value)));
        property("sneaking", Player::isSneaking, (p, value) -> p.setSneaking(truth(value)));
        property("sprinting", Player::isSprinting, (p, value) -> p.setSprinting(truth(value)));
        property("swimming", Player::isSwimming, (p, value) -> p.setSwimming(truth(value)));
        for (boolean subtract : new boolean[] {false, true}) {
            String prefix = subtract ? "take" : "add";
            int direction = subtract ? -1 : 1;
            bind(
                    prefix + "Exp",
                    args -> {
                        player().giveExp(direction * integer(argument(args, 0)));
                        return NO_RESULT;
                    });
            bind(
                    prefix + "Level",
                    args -> {
                        player().giveExpLevels(direction * integer(argument(args, 0)));
                        return NO_RESULT;
                    });
            bind(
                    prefix + "Food",
                    args -> {
                        double food =
                                player().getFoodLevel() + direction * number(argument(args, 0));
                        player().setFoodLevel((int) Math.clamp(food, 0, 20));
                        return NO_RESULT;
                    });
        }
    }

    private void calendar(String name, int field) {
        read(name, () -> Calendar.getInstance().get(field));
    }

    private void installCalendar() {
        calendar("day", Calendar.DAY_OF_MONTH);
        calendar("dayOfMonth", Calendar.DAY_OF_MONTH);
        read("dayOfWeek", () -> (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1);
        calendar("dayOfYear", Calendar.DAY_OF_YEAR);
        read("month", () -> Calendar.getInstance().get(Calendar.MONTH) + 1);
        calendar("year", Calendar.YEAR);
        calendar("hour", Calendar.HOUR_OF_DAY);
        calendar("minute", Calendar.MINUTE);
        calendar("second", Calendar.SECOND);
        calendar("weekOfMonth", Calendar.WEEK_OF_MONTH);
        calendar("weekOfYear", Calendar.WEEK_OF_YEAR);
        calendar("amOrPm", Calendar.AM_PM);
        read("time", System::currentTimeMillis);
    }
}
