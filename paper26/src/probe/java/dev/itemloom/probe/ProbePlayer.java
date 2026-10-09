package dev.itemloom.probe;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Deliberately synthetic player for helper/event probes; never represents a connected client. */
final class ProbePlayer {
    static Player create(String name) {
        var initial = new net.minecraft.nbt.CompoundTag();
        var identity = new net.minecraft.nbt.CompoundTag();
        identity.putString("id", "fixture");
        identity.putString("data", "{}");
        initial.put("NeigeItems", identity);
        ItemStack[] contents = new ItemStack[41];
        java.util.Arrays.setAll(contents, ignored -> new ItemStack(Material.AIR));
        contents[0] =
                dev.itemloom.paper.nms.NmsItems.withCustomData(
                        new ItemStack(Material.STONE, 3), initial);
        PlayerInventory inventory =
                (PlayerInventory)
                        Proxy.newProxyInstance(
                                PlayerInventory.class.getClassLoader(),
                                new Class<?>[] {PlayerInventory.class},
                                (self, method, args) ->
                                        switch (method.getName()) {
                                            case "getContents" -> contents;
                                            case "getItemInMainHand" -> contents[0];
                                            case "getItemInOffHand" -> contents[40];
                                            case "getHeldItemSlot" -> 0;
                                            case "getItem" ->
                                                    contents[
                                                            args[0] instanceof Integer slot
                                                                    ? slot
                                                                    : slot(
                                                                            (EquipmentSlot)
                                                                                    args[0])];
                                            case "setItem" -> {
                                                contents[
                                                                args[0] instanceof Integer slot
                                                                        ? slot
                                                                        : slot(
                                                                                (EquipmentSlot)
                                                                                        args[0])] =
                                                        empty((ItemStack) args[1]);
                                                yield null;
                                            }
                                            case "setItemInMainHand" -> {
                                                contents[0] = empty((ItemStack) args[0]);
                                                yield null;
                                            }
                                            case "setItemInOffHand" -> {
                                                contents[40] = empty((ItemStack) args[0]);
                                                yield null;
                                            }
                                            case "addItem" -> {
                                                var leftovers = new HashMap<Integer, ItemStack>();
                                                for (ItemStack addition : (ItemStack[]) args[0]) {
                                                    boolean stored = false;
                                                    for (int i = 1; i < 36; i++)
                                                        if (contents[i].isEmpty()) {
                                                            contents[i] = addition.clone();
                                                            stored = true;
                                                            break;
                                                        }
                                                    if (!stored)
                                                        leftovers.put(leftovers.size(), addition);
                                                }
                                                yield leftovers;
                                            }
                                            default -> defaultValue(method.getReturnType());
                                        });
        Map<String, Object> values = new HashMap<>();
        values.put("AllowFlight", false);
        values.put("AttackCooldown", 0.75f);
        values.put("Exhaustion", 1f);
        values.put("TotalExperience", 10);
        values.put("Level", 2);
        values.put("Flying", false);
        values.put("FlySpeed", 0.1f);
        values.put("WalkSpeed", 0.2f);
        values.put("FoodLevel", 18);
        values.put("GameMode", org.bukkit.GameMode.SURVIVAL);
        values.put("Gliding", false);
        values.put("Glowing", false);
        values.put("Gravity", true);
        values.put("Health", 20d);
        values.put("MaxHealth", 20d);
        values.put("RemainingAir", 300);
        Location location = new Location(Bukkit.getWorlds().getFirst(), 12, 64, 8);
        values.put("BedSpawnLocation", location);
        values.put("CompassTarget", location);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return (Player)
                Proxy.newProxyInstance(
                        Player.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (self, method, args) -> {
                            String key = method.getName();
                            switch (key) {
                                case "getName", "getDisplayName" -> {
                                    return name;
                                }
                                case "getUniqueId" -> {
                                    return id;
                                }
                                case "getPlayer" -> {
                                    return self;
                                }
                                case "isOnline", "isValid" -> {
                                    return true;
                                }
                                case "getInventory" -> {
                                    return inventory;
                                }
                                case "getWorld" -> {
                                    return location.getWorld();
                                }
                                case "getLocation" -> {
                                    return location.clone();
                                }
                                case "getServer" -> {
                                    return Bukkit.getServer();
                                }
                                case "getAddress" -> {
                                    return java.net.InetSocketAddress.createUnresolved(
                                            "127.0.0.1", 25565);
                                }
                                case "hasPermission" -> {
                                    return "allowed".equals(args[0]);
                                }
                                case "sendMessage" -> {
                                    return null;
                                }
                                case "giveExp" -> {
                                    values.compute(
                                            "TotalExperience",
                                            (k, v) ->
                                                    ((Number) v).intValue()
                                                            + ((Number) args[0]).intValue());
                                    return null;
                                }
                                case "giveExpLevels" -> {
                                    values.compute(
                                            "Level",
                                            (k, v) ->
                                                    ((Number) v).intValue()
                                                            + ((Number) args[0]).intValue());
                                    return null;
                                }
                                case "toString" -> {
                                    return "ProbePlayer[" + name + "]";
                                }
                                case "hashCode" -> {
                                    return System.identityHashCode(self);
                                }
                                case "equals" -> {
                                    return self == args[0];
                                }
                            }
                            if (key.startsWith("set") && args != null && args.length == 1) {
                                values.put(key.substring(3), args[0]);
                                return null;
                            }
                            String property =
                                    key.startsWith("get") || key.startsWith("has")
                                            ? key.substring(3)
                                            : key.startsWith("is") ? key.substring(2) : key;
                            return values.getOrDefault(
                                    property, defaultValue(method.getReturnType()));
                        });
    }

    private static ItemStack empty(ItemStack item) {
        return item == null ? new ItemStack(Material.AIR) : item;
    }

    private static int slot(EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> 0;
            case OFF_HAND -> 40;
            case HEAD -> 39;
            case CHEST -> 38;
            case LEGS -> 37;
            case FEET -> 36;
            default -> throw new IllegalArgumentException(slot.name());
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }
}
