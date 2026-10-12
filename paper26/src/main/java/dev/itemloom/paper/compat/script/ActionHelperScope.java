package dev.itemloom.paper.compat.script;

import static dev.itemloom.paper.compat.script.ActionHelperValues.*;

import dev.itemloom.compat.ni.action.NiActionContext;
import dev.itemloom.compat.ni.script.LegacyActionManager;
import dev.itemloom.compat.ni.script.LegacyConfigReader;
import dev.itemloom.compat.ni.script.LegacySectionUtils;
import dev.itemloom.paper.compat.nbt.LegacyNbt;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.script.Bindings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.openjdk.nashorn.api.scripting.JSObject;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;
import org.openjdk.nashorn.api.scripting.ScriptUtils;

/** Each installed helper keeps its home bindings while looking up live action values on every call. */
public final class ActionHelperScope {
    private static final Object NO_RESULT = new Object();
    private final Bindings bindings;
    private final Set<String> names;
    private LegacyScheduler scheduler;
    private JSObject eventGetter;

    ActionHelperScope(Bindings bindings) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        names =
                Collections.unmodifiableSet(
                        new LinkedHashSet<>(
                                Arrays.asList(
                                        "perm",
                                        "color",
                                        "tell",
                                        "command",
                                        "console",
                                        "papi",
                                        "node",
                                        "parse",
                                        "parseItem",
                                        "getNBTTag",
                                        "getNBT",
                                        "random",
                                        "chance",
                                        "combo",
                                        "comboSize",
                                        "hand",
                                        "isMainHand",
                                        "isOffHand",
                                        "niItemAmount",
                                        "checkNiItemAmount",
                                        "takeNiItem",
                                        "sync",
                                        "async",
                                        "getAttackerMobId",
                                        "getDefenderMobId",
                                        "checkCooldown",
                                        "getCooldown",
                                        "setCooldown",
                                        "address",
                                        "attackCooldown",
                                        "bedSpawn",
                                        "bedSpawnX",
                                        "bedSpawnY",
                                        "bedSpawnZ",
                                        "blocking",
                                        "compassTargetX",
                                        "compassTargetY",
                                        "compassTargetZ",
                                        "dead",
                                        "firstPlay",
                                        "health",
                                        "maxHealth",
                                        "name",
                                        "sleeping",
                                        "world",
                                        "mainHandItem",
                                        "offHandItem",
                                        "allowFlight",
                                        "compassTarget",
                                        "exhaustion",
                                        "exp",
                                        "level",
                                        "fly",
                                        "flySpeed",
                                        "walkSpeed",
                                        "food",
                                        "gamemode",
                                        "guilding",
                                        "glowing",
                                        "gravity",
                                        "remainingAir",
                                        "sneaking",
                                        "sprinting",
                                        "swimming",
                                        "addExp",
                                        "takeExp",
                                        "addLevel",
                                        "takeLevel",
                                        "addFood",
                                        "takeFood",
                                        "day",
                                        "dayOfMonth",
                                        "dayOfYear",
                                        "year",
                                        "hour",
                                        "minute",
                                        "second",
                                        "weekOfMonth",
                                        "weekOfYear",
                                        "amOrPm",
                                        "month",
                                        "dayOfWeek",
                                        "time")));
    }

    public Set<String> getNames() {
        return names;
    }

    public Object getNoResult() {
        return NO_RESULT;
    }

    public void captureScheduler(LegacyScheduler owner) {
        if (scheduler != null) throw new IllegalStateException("Scheduler already captured");
        scheduler = Objects.requireNonNull(owner, "owner");
    }

    public void captureEventGetter(Object getter) {
        if (eventGetter != null) throw new IllegalStateException("Event getter already captured");
        Object wrapped =
                ScriptUtils.wrapArray(new Object[] {Objects.requireNonNull(getter, "getter")})[0];
        if (!(wrapped instanceof JSObject function) || !function.isFunction()) {
            throw new IllegalArgumentException("Event getter must be callable");
        }
        eventGetter = function;
    }

    public Object call(String name, Object[] arguments) {
        if (!names.contains(name))
            throw new IllegalArgumentException("Unknown action helper: " + name);
        Object result = invoke(name, ScriptUtils.wrapArray(arguments));
        return ScriptUtils.unwrap(result);
    }

    @SuppressWarnings("deprecation")
    private Object invoke(String name, Object[] arguments) {
        Object value = argument(arguments, 0);
        Object second = argument(arguments, 1);
        return switch (name) {
            case "perm" -> player().hasPermission(text(value));
            case "color" -> ChatColor.translateAlternateColorCodes('&', text(value));
            case "tell" -> {
                player().sendMessage(text(value));
                yield NO_RESULT;
            }
            case "command" -> {
                Bukkit.dispatchCommand(player(), text(value));
                yield NO_RESULT;
            }
            case "console" -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text(value));
                yield NO_RESULT;
            }
            case "papi" -> hooks().papi(player(), text(value));
            case "node" ->
                    ((LegacyActionManager) bindings.get("ActionManager"))
                            .parseNode(text(value), (NiActionContext) bindings.get("context"));
            case "parse" ->
                    LegacySectionUtils.parseSection(
                            text(value), map("cache"), player(), sections());
            case "parseItem" -> parseItem(text(value));
            case "getNBTTag" -> ((LegacyNbt.Compound) bindings.get("itemTag")).getDeep(text(value));
            case "getNBT" -> {
                LegacyNbt tag = ((LegacyNbt.Compound) bindings.get("itemTag")).getDeep(text(value));
                yield tag == null ? null : items().toValue(tag).toString();
            }
            case "random" ->
                    ThreadLocalRandom.current().nextDouble(orNumber(value, 0), orNumber(second, 1));
            case "chance" ->
                    number(value) > ThreadLocalRandom.current().nextDouble(orNumber(second, 1));
            case "combo" -> combo(text(value), second);
            case "comboSize" -> players().comboSnapshot(player(), text(value)).size();
            case "hand" -> event("getHand").toString();
            case "isMainHand" -> EquipmentSlot.HAND.equals(event("getHand"));
            case "isOffHand" -> EquipmentSlot.OFF_HAND.equals(event("getHand"));
            case "niItemAmount" -> itemAmount(text(value));
            case "checkNiItemAmount" -> take(text(value), number(second), false);
            case "takeNiItem" -> take(text(value), number(second), true);
            case "sync", "async" -> schedule(name.equals("sync"), arguments);
            case "getAttackerMobId", "getDefenderMobId" -> {
                var mythic = hooks().getMythicMobsHooker();
                yield mythic == null
                        ? null
                        : mythic.getMythicId(
                                (Entity)
                                        event(
                                                name.equals("getAttackerMobId")
                                                        ? "getDamager"
                                                        : "getEntity"));
            }
            case "checkCooldown" ->
                    players().checkCooldown(player(), text(value), longNumber(second));
            case "getCooldown" -> players().getCooldown(player(), text(value));
            case "setCooldown" -> {
                players().setCooldown(player(), text(value), longNumber(second));
                yield NO_RESULT;
            }
            case "address" -> player().getAddress().getHostString();
            case "attackCooldown" -> player().getAttackCooldown();
            case "bedSpawn" -> player().getBedSpawnLocation();
            case "bedSpawnX" -> player().getBedSpawnLocation().getX();
            case "bedSpawnY" -> player().getBedSpawnLocation().getY();
            case "bedSpawnZ" -> player().getBedSpawnLocation().getZ();
            case "blocking" -> player().isBlocking();
            case "compassTargetX" -> player().getCompassTarget().getX();
            case "compassTargetY" -> player().getCompassTarget().getY();
            case "compassTargetZ" -> player().getCompassTarget().getZ();
            case "dead" -> player().isDead();
            case "firstPlay" -> !player().hasPlayedBefore();
            case "health" -> player().getHealth();
            case "maxHealth" -> player().getMaxHealth();
            case "name" -> player().getName();
            case "sleeping" -> player().isSleeping();
            case "world" -> player().getWorld().getName();
            case "mainHandItem" -> player().getInventory().getItemInMainHand();
            case "offHandItem" -> player().getInventory().getItemInOffHand();
            case "allowFlight" -> {
                if (absent(value)) yield player().getAllowFlight();
                player().setAllowFlight(truth(value));
                yield value;
            }
            case "compassTarget" -> {
                if (absent(value)) yield player().getCompassTarget();
                player().setCompassTarget((Location) value);
                yield value;
            }
            case "exhaustion" -> {
                if (absent(value)) yield player().getExhaustion();
                player().setExhaustion((float) number(value));
                yield value;
            }
            case "exp" -> {
                if (absent(value)) yield player().getTotalExperience();
                player().setTotalExperience(integer(value));
                yield value;
            }
            case "level" -> {
                if (absent(value)) yield player().getLevel();
                player().setLevel(integer(value));
                yield value;
            }
            case "fly" -> {
                if (absent(value)) yield player().isFlying();
                player().setFlying(truth(value));
                yield value;
            }
            case "flySpeed" -> {
                if (absent(value)) yield player().getFlySpeed();
                player().setFlySpeed((float) number(value));
                yield value;
            }
            case "walkSpeed" -> {
                if (absent(value)) yield player().getWalkSpeed();
                player().setWalkSpeed((float) number(value));
                yield value;
            }
            case "food" -> {
                if (absent(value)) yield player().getFoodLevel();
                player().setFoodLevel(integer(value));
                yield value;
            }
            case "gamemode" -> {
                if (absent(value)) yield player().getGameMode().name();
                player().setGameMode(GameMode.valueOf(text(value).toUpperCase(Locale.ROOT)));
                yield value;
            }
            case "guilding" -> {
                if (absent(value)) yield player().isGliding();
                player().setGliding(truth(value));
                yield value;
            }
            case "glowing" -> {
                if (absent(value)) yield player().isGlowing();
                player().setGlowing(truth(value));
                yield value;
            }
            case "gravity" -> {
                if (absent(value)) yield player().hasGravity();
                player().setGravity(truth(value));
                yield value;
            }
            case "remainingAir" -> {
                if (absent(value)) yield player().getRemainingAir();
                player().setRemainingAir(integer(value));
                yield value;
            }
            case "sneaking" -> {
                if (absent(value)) yield player().isSneaking();
                player().setSneaking(truth(value));
                yield value;
            }
            case "sprinting" -> {
                if (absent(value)) yield player().isSprinting();
                player().setSprinting(truth(value));
                yield value;
            }
            case "swimming" -> {
                if (absent(value)) yield player().isSwimming();
                player().setSwimming(truth(value));
                yield value;
            }
            case "addExp", "takeExp" -> {
                player().giveExp((name.equals("addExp") ? 1 : -1) * integer(value));
                yield NO_RESULT;
            }
            case "addLevel", "takeLevel" -> {
                player().giveExpLevels((name.equals("addLevel") ? 1 : -1) * integer(value));
                yield NO_RESULT;
            }
            case "addFood", "takeFood" -> {
                double changed =
                        player().getFoodLevel() + (name.equals("addFood") ? 1 : -1) * number(value);
                player().setFoodLevel((int) Math.max(0, Math.min(20, changed)));
                yield NO_RESULT;
            }
            case "time" -> System.currentTimeMillis();
            default -> calendar(name);
        };
    }

    private int calendar(String name) {
        Calendar calendar = Calendar.getInstance();
        return switch (name) {
            case "day", "dayOfMonth" -> calendar.get(Calendar.DAY_OF_MONTH);
            case "dayOfYear" -> calendar.get(Calendar.DAY_OF_YEAR);
            case "year" -> calendar.get(Calendar.YEAR);
            case "hour" -> calendar.get(Calendar.HOUR_OF_DAY);
            case "minute" -> calendar.get(Calendar.MINUTE);
            case "second" -> calendar.get(Calendar.SECOND);
            case "weekOfMonth" -> calendar.get(Calendar.WEEK_OF_MONTH);
            case "weekOfYear" -> calendar.get(Calendar.WEEK_OF_YEAR);
            case "amOrPm" -> calendar.get(Calendar.AM_PM);
            case "month" -> calendar.get(Calendar.MONTH) + 1;
            case "dayOfWeek" -> (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
            default -> throw new IllegalArgumentException("Unknown calendar helper: " + name);
        };
    }

    private Player player() {
        return (Player) bindings.get("player");
    }

    private LegacyHooks hooks() {
        return (LegacyHooks) bindings.get("HookerManager");
    }

    private LegacyItemUtils items() {
        return (LegacyItemUtils) bindings.get("ItemUtils");
    }

    private LegacyPlayerUtils players() {
        return (LegacyPlayerUtils) bindings.get("PlayerUtils");
    }

    private Object event(String method) {
        return Objects.requireNonNull(eventGetter, "event getter").call(null, method);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> map(String name) {
        Object value = bindings.get(name);
        return absent(value) ? null : (Map<String, String>) value;
    }

    private ConfigurationSection sections() {
        Object value = bindings.get("sections");
        if (value instanceof LegacyConfigReader reader) value = reader.getHandle();
        return absent(value) ? null : (ConfigurationSection) value;
    }

    private String parseItem(String text) {
        Object tag = bindings.get("itemTag");
        if (!bindings.containsKey("itemTag") || ScriptObjectMirror.isUndefined(tag)) return "未传入物品";
        return LegacySectionUtils.parseItemSection(
                text, (ItemStack) bindings.get("itemStack"), tag, map("data"), player());
    }

    private boolean combo(String group, Object expected) {
        List<?> history = players().comboSnapshot(player(), group);
        int length = size(expected);
        if (length > history.size()) return false;
        for (int index = 0; index < length; index++) {
            Object wanted = element(expected, index);
            Object actual = getter(history.get(history.size() - length + index), "getType");
            if (!(wanted instanceof CharSequence chars) || !chars.toString().equals(actual))
                return false;
        }
        return true;
    }

    private double itemAmount(String id) {
        double result = 0;
        for (ItemStack item : player().getInventory().getContents()) {
            if (item != null && Objects.equals(id, items().getItemId(item)))
                result += item.getAmount();
        }
        return result;
    }

    private boolean take(String id, double remaining, boolean mutate) {
        ItemStack[] contents = player().getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining != 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || !Objects.equals(id, items().getItemId(item))) continue;
            double amount = Math.min(remaining, item.getAmount());
            remaining -= amount;
            if (mutate) {
                int next = (int) (item.getAmount() - amount);
                if (next == 0) player().getInventory().setItem(slot, null);
                else {
                    item.setAmount(next);
                    player().getInventory().setItem(slot, item);
                }
            }
        }
        return remaining == 0;
    }

    private Object schedule(boolean sync, Object[] arguments) {
        if (arguments.length != 1 && arguments.length != 2) {
            throw new IllegalArgumentException(
                    "Scheduling helper requires a task and optional plugin");
        }
        Runnable callback = task(arguments[arguments.length - 1]);
        LegacyScheduler captured = Objects.requireNonNull(scheduler, "scheduler");
        if (arguments.length == 1) captured.run(sync, callback);
        else captured.run((Plugin) arguments[0], sync, callback);
        return NO_RESULT;
    }
}
