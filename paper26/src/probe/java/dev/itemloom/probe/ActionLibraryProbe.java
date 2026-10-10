package dev.itemloom.probe;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.script.Bindings;
import javax.script.Compilable;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.nms.ItemStateCodec;
import dev.itemloom.core.ItemIdentity;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Real Nashorn/library comparison; proxy players exercise calls without a client. */
final class ActionLibraryProbe {
    private record Outcome(String value, String error) {}

    private interface Checked {
        Object call() throws Exception;
    }

    static Map<String, Object> run(JavaPlugin probe, Plugin reference) throws Exception {
        if (reference == null || !reference.isEnabled())
            throw new IllegalStateException("NI reference required");
        ClassLoader loader = reference.getClass().getClassLoader();
        Class<?> managerClass = loader.loadClass("pers.neige.neigeitems.manager.ActionManager");
        Object manager = managerClass.getField("INSTANCE").get(null);
        var engine =
                (javax.script.ScriptEngine) managerClass.getMethod("getEngine").invoke(manager);
        Class<?> contextClass = loader.loadClass("pers.neige.neigeitems.action.ActionContext");
        var constructor = contextClass.getConstructor(Object.class, Map.class, Map.class);
        var oldBindings = contextClass.getMethod("getBindings");
        var input =
                new NiRepository.Input(
                        new NiConfig(Map.of("Language", "en_us")),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(
                                "combo",
                                List.of("combo: fixture left", "combo: fixture right"),
                                "combo-clear",
                                "combo-clear: fixture"),
                        Map.of("isolated.js", "function helper(){return typeof chance;}"),
                        Map.of(),
                        Map.of());
        var differences = new LinkedHashMap<String, Object>();
        int checked = 0;
        try (var players = new PlayerActionState();
                var catalog = new NiCatalog(1, input, probe, (p, text) -> null, players)) {
            List<String> expressions =
                    List.of(
                            "perm('allowed')",
                            "perm('denied')",
                            "color('&aHello')",
                            "color.call(null,'&aHello') === '§aHello'",
                            "typeof chance === 'function' && chance.apply(null,[1,1])",
                            "chance.bind(null,1,1)()",
                            "(function(){var x={valueOf:function(){return 3;}};return level(x)===x && level()===3;})()",
                            "(function(){var x=function(){};return allowFlight(x)===x && allowFlight();})()",
                            "typeof tell('hello') === 'undefined'",
                            "tell('hello'); true",
                            "papi('%missing_value%')",
                            "node('<number::1_1>')",
                            "parse('<number::1_1>')",
                            "parseItem('x')",
                            "chance(0)",
                            "chance(1)",
                            "chance(100,100)",
                            "random(2,3) >= 2 && random(2,3) < 3",
                            "address()",
                            "allowFlight()",
                            "allowFlight(true); allowFlight()",
                            "attackCooldown()",
                            "bedSpawnX()",
                            "bedSpawnY()",
                            "bedSpawnZ()",
                            "blocking()",
                            "compassTargetX()",
                            "compassTargetY()",
                            "compassTargetZ()",
                            "day() >= 1 && day() <= 31",
                            "dayOfMonth() == day()",
                            "dayOfWeek() >= 1 && dayOfWeek() <= 7",
                            "dayOfYear() >= 1 && dayOfYear() <= 366",
                            "month() >= 1 && month() <= 12",
                            "year() >= 2026",
                            "hour() >= 0 && hour() <= 23",
                            "minute() >= 0 && minute() <= 59",
                            "second() >= 0 && second() <= 59",
                            "weekOfMonth() >= 1 && weekOfMonth() <= 6",
                            "weekOfYear() >= 1 && weekOfYear() <= 53",
                            "amOrPm() >= 0 && amOrPm() <= 1",
                            "Math.abs(time() - Packages.java.lang.System.currentTimeMillis()) < 1000",
                            "dead()",
                            "exhaustion()",
                            "exhaustion(2); exhaustion()",
                            "exp()",
                            "exp(15); exp()",
                            "addExp(7); takeExp(2); exp()",
                            "level()",
                            "level(5); level()",
                            "addLevel(4); takeLevel(1); level()",
                            "firstPlay()",
                            "fly()",
                            "fly(true); fly()",
                            "flySpeed()",
                            "flySpeed(0.5); flySpeed()",
                            "walkSpeed()",
                            "walkSpeed(0.3); walkSpeed()",
                            "food()",
                            "food(12); food()",
                            "addFood(50); takeFood(3); food()",
                            "takeFood(50); food()",
                            "gamemode()",
                            "gamemode('creative'); gamemode()",
                            "guilding(true); guilding()",
                            "glowing(true); glowing()",
                            "gravity(false); gravity()",
                            "health()",
                            "maxHealth()",
                            "name()",
                            "remainingAir(100); remainingAir()",
                            "sleeping()",
                            "sneaking(true); sneaking()",
                            "sprinting(true); sprinting()",
                            "swimming(true); swimming()",
                            "world()",
                            "mainHandItem().getType().toString()",
                            "offHandItem().getType().toString()",
                            "niItemAmount('fixture')",
                            "checkNiItemAmount('fixture',2)",
                            "checkNiItemAmount('fixture',5)",
                            "takeNiItem('fixture',2); niItemAmount('fixture')",
                            "takeNiItem('fixture',8)",
                            "getAttackerMobId()",
                            "getDefenderMobId()",
                            "sync(function(){global.put('sync',true)});global.sync",
                            "new NbtItemStack(mainHandItem()).asItemStack().getAmount()",
                            "NbtUtils.of(NbtUtils.save(mainHandItem())).getType().toString()");
            for (String expression : expressions) {
                Player oldPlayer = player("LibraryProbe"), newPlayer = player("LibraryProbe");
                players.join(newPlayer.getUniqueId());
                var params = new HashMap<String, Object>();
                Object oldContext = constructor.newInstance(oldPlayer, null, params);
                Bindings bindings = (Bindings) oldBindings.invoke(oldContext);
                Outcome expected =
                        outcome(() -> ((Compilable) engine).compile(expression).eval(bindings));
                var context = catalog.actionContext(newPlayer, params);
                Outcome actual = outcome(() -> context.evaluate(expression));
                checked++;
                if (!expected.equals(actual))
                    differences.put(expression, Map.of("expected", expected, "actual", actual));
            }
            var alpha = catalog.actionContext(player("Alpha"), Map.of());
            var beta = catalog.actionContext(player("Beta"), Map.of());
            check(
                    alpha.evaluate("name()").equals("Alpha")
                            && beta.evaluate("name()").equals("Beta")
                            && alpha.evaluate("name()").equals("Alpha"),
                    "scope isolation");
            check(
                    alpha.evaluate("parse('<js::isolated.js::helper>')").equals("undefined"),
                    "action library must not leak into Scripts files");
            Player owner = player("Modern");
            var modern =
                    new ItemStateCodec()
                            .write(
                                    new ItemStack(Material.STONE, 3),
                                    new ItemIdentity("fixture", Map.of()),
                                    new net.minecraft.nbt.CompoundTag());
            owner.getInventory().setItem(0, modern);
            check(
                    ((Number)
                                            catalog.actionContext(owner, Map.of())
                                                    .evaluate("niItemAmount('fixture')"))
                                    .intValue()
                            == 3,
                    "independent identity lookup");
            players.join(owner.getUniqueId());
            catalog.runFunction("combo", owner, Map.of()).toCompletableFuture().join();
            var comboContext = catalog.actionContext(owner, Map.of());
            check(
                    Boolean.TRUE.equals(
                            comboContext.evaluate(
                                    "combo('fixture',['left','right']) && comboSize('fixture') == 2")),
                    "combo actions and snapshot helpers share ordered history");
            check(
                    Boolean.FALSE.equals(
                            comboContext.evaluate("combo('fixture',['right','left'])")),
                    "combo order is significant");
            catalog.runFunction("combo-clear", owner, Map.of()).toCompletableFuture().join();
            check(
                    ((Number) comboContext.evaluate("comboSize('fixture')")).intValue() == 0,
                    "combo-clear is visible to existing action scopes");
            checked += 6;
        }
        return Map.of("checked", checked, "differences", differences);
    }

    private static Outcome outcome(Checked call) {
        try {
            return new Outcome(String.valueOf(call.call()), null);
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            return new Outcome(null, error.getClass().getSimpleName());
        }
    }

    static Player player(String name) {
        return ProbePlayer.create(name);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
