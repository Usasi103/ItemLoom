package dev.keystone.log;

import dev.keystone.Keystone;
import dev.keystone.lang.Lang;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

/**
 * The plugin's console voice, in the "category | fact" shape TrMenu and TrChat print: {@code
 * [DeathChest] 载入 | 0 个死亡箱已恢复}.
 *
 * <p>Everything the operator reads goes through here - the startup report, ordinary notices,
 * warnings, errors and the console half of command feedback - so the tag and the colours are
 * consistent instead of only appearing at boot. {@link #error} is the exception to the console
 * sender rule: it uses the plugin logger, so the line carries a real ERROR level.
 *
 * <p>Output goes to the console sender rather than the plugin logger: the logger would prepend its
 * own uncoloured {@code [Name]} and leave us unable to tint the name. Paper's file appender strips
 * the colour, so {@code logs/latest.log} stays plain text.
 *
 * <p>Ported from the per-plugin {@code Log.kt} template (DeathChest was the source).
 */
public final class Log {

    /**
     * The plugin's name colour, one of the sixteen legacy codes. A real Windows console downsamples
     * everything to the basic sixteen, so hex would collapse anyway. No plugin uses the red family:
     * red is what {@link #warn} prints. {@code tools/gen_plugin_banner.py} holds the per-plugin
     * assignment.
     */
    private static volatile String nameColor = "§b";

    private static final List<String> startup = new ArrayList<>();

    /**
     * Whether the startup report is still being assembled. Outside the boot window the same calls
     * (a reload re-running the config load, for example) print straight away.
     */
    private static boolean collecting;

    private Log() {}

    public static void setNameColor(String color) {
        nameColor = color;
    }

    public static String nameColor() {
        return nameColor;
    }

    // ---- ordinary output ----------------------------------------------------

    /** A plain notice: what a config reload found, what a subsystem started. */
    public static void info(String text) {
        console(line("§b信息", "§f" + text));
    }

    /** Something the operator should look at that did not stop us. */
    public static void warn(String text) {
        console(line("§c警告", "§f" + text));
    }

    /**
     * An ERROR line (0.3.5): through the plugin's own logger at {@code SEVERE}, so the console
     * shows {@code [HH:mm:ss ERROR]: [Name] text} in red and {@code logs/latest.log} records the
     * level - the shape of the user-approved three-line report for a file that could not be used
     * (09-29): cause with line and column, what is disabled and how to recover, the verified
     * backup. {@code text} is plain (no colour codes). Before {@link Keystone#init} (unit tests of
     * a plugin) it goes to a plain JUL logger instead of failing.
     */
    public static void error(String text) {
        logger().severe(text);
    }

    /** {@link #error(String)} with the stack trace of {@code error}. */
    public static void error(String text, Throwable error) {
        logger().log(Level.SEVERE, text, error);
    }

    /**
     * {@link Lang#send}, except console output carries the plugin tag (every line of a list node)
     * and loses CraftEngine's client-only {@code <shift:N>}/{@code <image:..>} tags even while
     * CraftEngine is installed ({@link Lang#forConsole}). Players get the untagged text unchanged.
     */
    public static void lang(CommandSender sender, String node, Object... args) {
        if (sender instanceof ConsoleCommandSender) {
            for (String text : Lang.lines(sender, node, args)) {
                // The console cannot draw CraftEngine's client-only tags, installed or not.
                info(Lang.forConsole(sender, text));
            }
        } else {
            Lang.send(sender, node, args);
        }
    }

    // ---- startup report -----------------------------------------------------

    /**
     * Begins the startup report. Call first thing in onEnable, so a re-enable cannot print lines
     * left over from the previous one.
     */
    public static synchronized void start() {
        startup.clear();
        collecting = true;
    }

    /** One line per content group, e.g. {@code loaded("9 个菜单")}. */
    public static void loaded(String text) {
        record(line("§a载入", "§f" + text));
    }

    /** Same, plus how long that load took. Only for work the caller actually brackets. */
    public static void loaded(String text, long millis) {
        record(line("§a载入", "§f" + text + " §8(" + millis + " ms)"));
    }

    public static void storage(String text) {
        record(line("§b存储", "§f" + text));
    }

    /** Anything in the startup report that is neither a count nor a backend. */
    public static void note(String text) {
        record(line("§b信息", "§f" + text));
    }

    private static synchronized void record(String text) {
        if (collecting) {
            startup.add(text);
        } else {
            console(text);
        }
    }

    /**
     * Flushes the startup report: the buffered lines, the hook table, then the enable line. Call
     * once the counts are final and the soft dependencies are guaranteed to be up (first tick).
     */
    @SuppressWarnings("deprecation")
    public static synchronized void ready() {
        for (String text : startup) {
            console(text);
        }
        startup.clear();
        collecting = false;
        PluginDescriptionFile meta = Keystone.plugin().getDescription();
        for (String name : meta.getDepend()) {
            console(line("§6挂钩", "§7硬依赖 §f" + name + " §7已就绪."));
        }
        List<String> missing = new ArrayList<>();
        for (String name : meta.getSoftDepend()) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null && plugin.isEnabled()) {
                console(line("§6挂钩", "§7软依赖 §f" + name + " §7已兼容."));
            } else {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            String names = String.join("§8, §7", missing);
            console(line("§8挂钩", "§8未检测到 §7" + names + "§8, 相关功能已跳过."));
        }
        console(line("§a良好", "§7插件启用. 当前运行版本 §f" + Keystone.version() + "§7."));
    }

    // ---- formatting ---------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static String line(String label, String text) {
        String name = Keystone.plugin().getDescription().getName();
        return "§8[" + nameColor + name + "§8] " + label + " §8| " + text;
    }

    private static void console(String text) {
        Bukkit.getConsoleSender().sendMessage(text);
    }

    private static Logger logger() {
        if (Keystone.isInitialized()) {
            Logger logger = Keystone.plugin().getLogger();
            if (logger != null) {
                return logger;
            }
        }
        return Logger.getLogger("Keystone");
    }
}
