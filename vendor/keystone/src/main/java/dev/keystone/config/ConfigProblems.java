package dev.keystone.config;

import dev.keystone.Keystone;
import dev.keystone.event.Events;
import dev.keystone.lang.Lang;
import dev.keystone.lang.LangNode;
import dev.keystone.log.Log;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The files this plugin could not use, and telling admins about them (0.3.5, user decision
 * 2026-10-01: a broken file found on startup or by a reload is replaced with the jar default or
 * left as it is, and admins are told which file, the line and column, and where the backup is).
 *
 * <p>{@link ConfigFile} records a {@link LoadProblem} here for every load that cannot use its file
 * - settings, {@code Lang}'s language files, menu files, content and data files - and removes it on
 * the next load that reads the file cleanly. Plugins that parse a file themselves {@link #report}
 * their own and {@link #resolve} them.
 *
 * <p>Two lines per plugin:
 *
 * <pre>{@code
 * // onEnable, after the files are loaded: online admins (a plugin reload) and later joins.
 * ConfigProblems.notifyAdmins();
 * // the reload command, after reloading: the sender gets every problem in detail.
 * ConfigProblems.notifyAdmins(sender);
 * }</pre>
 *
 * <p>What admins get (user decisions 2026-10-01): the one who runs the reload gets every problem in
 * detail - file, line and column, cause, what was done, backup ({@link #send}). Other admins get
 * only a short reminder ({@link #reminderLine}: which files, see the console), at most {@link
 * #reminderTimes()} times per server run, {@link #reminderInterval()} apart (default 3, 120 s): the
 * first on joining (two seconds after it) or at once when online, the others
 * while they stay online; leaving keeps the count and interval, the next join resumes it. New
 * problems and files repaired and broken again never reset the count; reminders stop
 * once nothing stands, and on plugin disable. Non-admins get nothing.
 *
 * <p>Admins are players with any of the {@link #adminPermissions()} ({@code <plugin>.admin} unless
 * set; an undeclared node defaults to operators). The texts are lang keys ({@link #defaults()},
 * Chinese) that a plugin can override in its own {@code lang/<code>.yml}; an empty entry switches
 * a line off.
 *
 * <p>Per plugin, like the rest of Keystone: each plugin's shaded copy has its own registry.
 */
public final class ConfigProblems {

    /** {@code {0}} plugin name (coloured), {@code {1}} file, {@code {2}} {@code （第 n 行，第 m 列）} or "". */
    public static final String HEADER = "keystone-config-problem-header";

    /** {@code {0}} the cause with its positions. */
    public static final String CAUSE = "keystone-config-problem-cause";

    /** {@code {0}} what is disabled and how to recover ({@link LoadProblem#note()}). */
    public static final String EFFECT = "keystone-config-problem-effect";

    /** {@code {0}} one of the four backup texts below. */
    public static final String BACKUP = "keystone-config-problem-backup";

    /** {@code {0}} the backup path: a copy this load made, read back and compared. */
    public static final String BACKUP_CREATED = "keystone-config-problem-backup-created";

    /** {@code {0}} the backup path: an existing backup with the same bytes. */
    public static final String BACKUP_EXISTING = "keystone-config-problem-backup-existing";

    /** {@code {0}} why no copy could be made. */
    public static final String BACKUP_FAILED = "keystone-config-problem-backup-failed";

    /** No arguments: the bytes could not be read, so there was nothing to copy. */
    public static final String BACKUP_UNREADABLE = "keystone-config-problem-backup-unreadable";

    /** The short reminder: {@code {0}} plugin name, {@code {1}} the files, joined with 、. */
    public static final String REMINDER = "keystone-config-problem-reminder";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(HEADER, "&8[{0}&8] &c{1} 出错{2}");
        DEFAULTS.put(CAUSE, " &7原因：&f{0}");
        DEFAULTS.put(EFFECT, " &7处理：&f{0}");
        DEFAULTS.put(BACKUP, " &7备份：&f{0}");
        DEFAULTS.put(BACKUP_CREATED, "{0}&7（已读回核对）");
        DEFAULTS.put(BACKUP_EXISTING, "{0}&7（与已有备份相同）");
        DEFAULTS.put(BACKUP_FAILED, "&c没能备份（{0}），原文件保持不动");
        DEFAULTS.put(BACKUP_UNREADABLE, "&c文件读不出来，没能备份，原文件保持不动");
        DEFAULTS.put(REMINDER, "&c[{0}] 配置文件出错（{1}），详情请查看服务器后台。");
    }

    /** Ticks between a join and the notice: past the MOTD and the other join messages. */
    static long joinDelayTicks = 40L;

    /** A recorded problem. */
    private static final class Entry {
        final LoadProblem problem;

        Entry(LoadProblem problem) {
            this.problem = problem;
        }
    }

    private static final Map<String, Entry> CURRENT = new LinkedHashMap<>();

    /** The admin nodes set by {@link #adminPermission(String...)}; empty = the default one. */
    private static volatile List<String> permissions = List.of();

    private static volatile String reloadCommand;
    private static Plugin listeningFor;

    private static final Map<UUID, Reminder> REMINDERS = new HashMap<>();

    /** Monotonic clock; tests advance it with their scheduler. */
    static LongSupplier reminderClock = System::nanoTime;

    private static volatile int reminderTimes = 3;
    private static volatile Duration reminderInterval = Duration.ofSeconds(120);

    private ConfigProblems() {}

    // ---- the registry ---------------------------------------------------------------------

    /** The problems that still stand, oldest first. */
    public static synchronized List<LoadProblem> current() {
        List<LoadProblem> result = new ArrayList<>();
        for (Entry entry : CURRENT.values()) {
            result.add(entry.problem);
        }
        return Collections.unmodifiableList(result);
    }

    public static synchronized boolean any() {
        return !CURRENT.isEmpty();
    }

    /** The standing problem of {@code file}, or null. */
    public static synchronized LoadProblem get(File file) {
        Entry entry = CURRENT.get(LoadProblem.key(file));
        return entry == null ? null : entry.problem;
    }

    /**
     * Records {@code problem} and prints its three ERROR lines ({@link LoadProblem#consoleLines}),
     * every time - also when a reload finds the same problem again (the third line then names the
     * existing backup). Reminder counts are retained for this server run.
     */
    public static void report(LoadProblem problem) {
        ReloadTransaction transaction = ReloadTransaction.current();
        if (transaction != null) {
            transaction.problem(problem);
            return;
        }
        record(problem);
        for (String line : problem.consoleLines()) {
            Log.error(line);
        }
    }

    /**
     * Records {@code problem} without printing anything (for a plugin that prints its own lines),
     * replacing what stood for that file. Returns whether it is {@linkplain LoadProblem#sameAs the
     * same} as that. New problems do not reset admin reminder counts.
     */
    public static synchronized boolean record(LoadProblem problem) {
        ReloadTransaction transaction = ReloadTransaction.current();
        if (transaction != null) {
            transaction.problem(problem);
            return false;
        }
        String key = LoadProblem.key(problem.file());
        Entry previous = CURRENT.get(key);
        boolean repeat = previous != null && previous.problem.sameAs(problem);
        CURRENT.put(key, new Entry(problem));
        return repeat;
    }

    /**
     * The file loaded cleanly: forgets its problem; when none is left, pending reminders stop.
     * Returns whether there was one.
     */
    public static synchronized boolean resolve(File file) {
        ReloadTransaction transaction = ReloadTransaction.current();
        if (transaction != null) {
            transaction.resolve(file);
            return false;
        }
        boolean removed = CURRENT.remove(LoadProblem.key(file)) != null;
        if (removed && CURRENT.isEmpty()) {
            cancelAll();
        }
        return removed;
    }

    // ---- settings -------------------------------------------------------------------------

    /** Who counts as an admin; default {@code <plugin name in lower case>.admin}. */
    public static void adminPermission(String node) {
        adminPermission(new String[] {node});
    }

    /**
     * Several admin nodes: a player holding any of them is told (0.3.5; Ambience has {@code
     * example.admin}, {@code lootbeam.admin} and {@code pickupnotifier.admin}). Blank and
     * duplicate nodes are skipped; none at all restores the default.
     */
    public static void adminPermission(String... nodes) {
        Set<String> kept = new LinkedHashSet<>();
        if (nodes != null) {
            for (String node : nodes) {
                if (node != null && !node.isBlank()) {
                    kept.add(node.trim());
                }
            }
        }
        permissions = List.copyOf(kept);
    }

    /** The first admin node (the default {@code <plugin name in lower case>.admin} when unset). */
    public static String adminPermission() {
        return adminPermissions().get(0);
    }

    /** Every admin node, in the order set; one element, the default, when unset. */
    public static List<String> adminPermissions() {
        List<String> nodes = permissions;
        if (!nodes.isEmpty()) {
            return nodes;
        }
        String name = Keystone.isInitialized() ? Keystone.name() : "keystone";
        return List.of(name.toLowerCase(Locale.ROOT) + ".admin");
    }

    /** Whether {@code player} holds any of the {@link #adminPermissions()}. */
    public static boolean isAdmin(Player player) {
        for (String node : adminPermissions()) {
            if (player.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reload command the texts name ({@code /friend reload}); without one they say "重载插件".
     * Set it before the files are loaded so the console lines name it too.
     */
    public static void reloadCommand(String command) {
        if (command == null || command.isBlank()) {
            reloadCommand = null;
        } else {
            String trimmed = command.trim();
            reloadCommand = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        }
    }

    public static String reloadCommand() {
        return reloadCommand;
    }

    /** {@code 执行 /friend reload（或重启服务器）}, or {@code 重载插件（或重启服务器）}. */
    public static String reloadHint() {
        String command = reloadCommand;
        return command == null ? "重载插件（或重启服务器）" : "执行 " + command + "（或重启服务器）";
    }

    // ---- telling admins -------------------------------------------------------------------

    /**
     * {@link #notifyAdmins(CommandSender)} without a sender: for onEnable, after the files are
     * loaded. The console already has the ERROR lines.
     */
    public static int notifyAdmins() {
        return notifyAdmins(null);
    }

    /**
     * After (re)loading, on the main thread. {@code sender} (the one who ran the reload) gets every
     * standing problem in detail ({@link #send}) - except the server console, which already has the
     * ERROR lines. Online admins receive or resume reminders within the server-run limit and
     * interval; an admin sender's detailed reply counts toward that limit. Also starts the join
     * and quit handling. Returns how many
     * problems stand (0: nothing was sent).
     */
    public static int notifyAdmins(CommandSender sender) {
        listen();
        List<LoadProblem> problems = current();
        if (problems.isEmpty()) {
            return 0;
        }
        UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
        if (sender != null && !(sender instanceof ConsoleCommandSender)) {
            for (LoadProblem problem : problems) {
                send(sender, problem);
            }
            if (sender instanceof Player player && isAdmin(player)) {
                countDetailedReply(player);
            }
        }
        for (Player player : online()) {
            if (!player.getUniqueId().equals(senderId) && isAdmin(player) && behind(player)) {
                remind(player);
            }
        }
        return problems.size();
    }

    /** A transaction already sent the short cancellation reply; do not notify its sender twice. */
    static void notifyReloadCancelled(CommandSender sender) {
        listen();
        UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
        if (sender instanceof Player player && isAdmin(player)) {
            countDetailedReply(player);
        }
        for (Player player : online()) {
            if (!player.getUniqueId().equals(senderId) && isAdmin(player) && behind(player)) {
                remind(player);
            }
        }
    }

    /** Sends {@code problem} to {@code sender} in chat in detail ({@link #lines}). */
    public static void send(CommandSender sender, LoadProblem problem) {
        for (String line : lines(sender, problem)) {
            sender.sendMessage(Lang.forConsole(sender, line));
        }
    }

    /**
     * The detailed chat lines for {@code problem}, in {@code sender}'s language - the reload
     * reply: a header with the file and position, the cause, what was done and how to recover, the
     * backup.
     */
    public static List<String> lines(CommandSender sender, LoadProblem problem) {
        String name = Log.nameColor() + (Keystone.isInitialized() ? Keystone.name() : "Keystone");
        String position = problem.position().isEmpty() ? "" : "（" + problem.position() + "）";
        String backup;
        if (problem.backup() != null) {
            backup =
                    text(
                            sender,
                            problem.backupCreated() ? BACKUP_CREATED : BACKUP_EXISTING,
                            problem.backupDisplay());
        } else if (problem.kind() == LoadProblem.Kind.READ) {
            backup = text(sender, BACKUP_UNREADABLE);
        } else {
            String why = problem.backupError() == null ? "原因不明" : problem.backupError();
            backup = text(sender, BACKUP_FAILED, why);
        }
        List<String> lines = new ArrayList<>();
        add(lines, text(sender, HEADER, name, problem.path(), position));
        add(lines, text(sender, CAUSE, problem.cause()));
        add(lines, text(sender, EFFECT, problem.note()));
        add(lines, text(sender, BACKUP, backup));
        return lines;
    }

    /**
     * The short reminder admins get (user decision 2026-10-01): one line naming the broken files
     * and pointing to the console, e.g. {@code [Friend] 配置文件出错（config.yml、lang/zh_CN.yml），
     * 详情请查看服务器后台。}, or "" when nothing stands (or the lang entry is empty).
     */
    public static String reminderLine(CommandSender sender) {
        List<LoadProblem> problems = current();
        if (problems.isEmpty()) {
            return "";
        }
        List<String> paths = new ArrayList<>();
        for (LoadProblem problem : problems) {
            paths.add(problem.path());
        }
        String name = Keystone.isInitialized() ? Keystone.name() : "Keystone";
        return text(sender, REMINDER, name, String.join("、", paths));
    }

    /** Every key with its built-in (Chinese) template. */
    public static Map<String, String> defaults() {
        return Collections.unmodifiableMap(DEFAULTS);
    }

    // ---- the reminder schedule ------------------------------------------------------------

    /**
     * How many short reminders an admin gets per server run and how far apart (default 3, 120 s;
     * user decision 2026-10-01). The first comes on joining (or at once when online as a problem
     * starts), the others while the admin stays online; one who leaves gets the rest on the next
     * join. New problems never reset the count or shorten the interval; pending reminders stop
     * once nothing stands. {@code times} 0 switches reminders
     * off (the reload reply stays).
     */
    public static void reminders(int times, Duration interval) {
        if (times < 0) {
            throw new IllegalArgumentException("times < 0: " + times);
        }
        if (interval == null || interval.toMillis() < 50) {
            throw new IllegalArgumentException("interval under one tick: " + interval);
        }
        reminderTimes = times;
        reminderInterval = interval;
    }

    public static int reminderTimes() {
        return reminderTimes;
    }

    public static Duration reminderInterval() {
        return reminderInterval;
    }

    /** One admin in this server run: reminders sent, last send time and the next task. */
    private static final class Reminder {
        int sent;
        long lastSent;
        BukkitTask pending;
    }

    /** Whether {@code player} has budget left and no pending reminder. */
    private static synchronized boolean behind(Player player) {
        Reminder reminder = REMINDERS.get(player.getUniqueId());
        return reminder == null
                || (reminder.sent < reminderTimes
                        && (reminder.pending == null || reminder.pending.isCancelled()));
    }

    /**
     * Sends the next short reminder to {@code player} and schedules the one after it. Nothing
     * when the player is offline, not an admin, nothing stands, or the server-run limit is used
     * up. If the interval has not elapsed, only schedules the remaining delay.
     */
    static synchronized void remind(Player player) {
        Reminder reminder = REMINDERS.computeIfAbsent(player.getUniqueId(), id -> new Reminder());
        cancel(reminder);
        if (!player.isOnline() || !isAdmin(player) || CURRENT.isEmpty()) {
            return;
        }
        if (reminder.sent >= reminderTimes) {
            return;
        }
        long delay = remainingTicks(reminder);
        if (delay > 0) {
            reminder.pending = later(delay, () -> remind(player));
            return;
        }
        String line = reminderLine(player);
        if (!line.isEmpty()) {
            player.sendMessage(Lang.forConsole(player, line));
        }
        reminder.sent++;
        reminder.lastSent = reminderClock.getAsLong();
        scheduleNext(player, reminder);
    }

    /** An explicit reload reply stays available and consumes remaining automatic reminders. */
    private static synchronized void countDetailedReply(Player player) {
        Reminder reminder = REMINDERS.computeIfAbsent(player.getUniqueId(), id -> new Reminder());
        cancel(reminder);
        if (reminder.sent < reminderTimes) {
            reminder.sent++;
        }
        reminder.lastSent = reminderClock.getAsLong();
        scheduleNext(player, reminder);
    }

    private static long remainingTicks(Reminder reminder) {
        if (reminder.sent == 0) {
            return 0;
        }
        long remaining =
                reminderInterval.toNanos() - (reminderClock.getAsLong() - reminder.lastSent);
        return remaining <= 0 ? 0 : 1 + (remaining - 1) / 50_000_000L;
    }

    private static void scheduleNext(Player player, Reminder reminder) {
        if (reminder.sent < reminderTimes) {
            long ticks = Math.max(1L, remainingTicks(reminder));
            reminder.pending = later(ticks, () -> remind(player));
        }
    }

    /** A join: resumes after the join delay and the remaining interval, if an admin by then. */
    static synchronized void joined(Player player) {
        if (CURRENT.isEmpty()) {
            return;
        }
        Reminder reminder = REMINDERS.computeIfAbsent(player.getUniqueId(), id -> new Reminder());
        cancel(reminder);
        if (reminder.sent < reminderTimes) {
            reminder.pending =
                    later(Math.max(joinDelayTicks, remainingTicks(reminder)), () -> remind(player));
        }
    }

    /** A quit: the pending reminder goes; what was sent still counts on the next join. */
    static synchronized void left(Player player) {
        Reminder reminder = REMINDERS.get(player.getUniqueId());
        if (reminder != null) {
            cancel(reminder);
        }
    }

    /** The plugin is being disabled, or nothing stands any more: every pending reminder goes. */
    static synchronized void cancelAll() {
        for (Reminder reminder : REMINDERS.values()) {
            cancel(reminder);
        }
    }

    /** Test hook: reminders waiting to be sent. */
    static synchronized int pendingReminders() {
        int pending = 0;
        for (Reminder reminder : REMINDERS.values()) {
            if (reminder.pending != null && !reminder.pending.isCancelled()) {
                pending++;
            }
        }
        return pending;
    }

    private static void cancel(Reminder reminder) {
        if (reminder.pending != null) {
            reminder.pending.cancel();
            reminder.pending = null;
        }
    }

    /** A main-thread task of this plugin, or null when it is not enabled (never run inline). */
    private static BukkitTask later(long ticks, Runnable body) {
        if (!Keystone.isInitialized() || !Keystone.plugin().isEnabled()) {
            return null;
        }
        Plugin plugin = Keystone.plugin();
        return Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            if (plugin.isEnabled()) {
                                body.run();
                            }
                        },
                        ticks);
    }

    /** Registers join, quit and disable handling once per enabled plugin instance. */
    private static synchronized void listen() {
        if (!Keystone.isInitialized()) {
            return;
        }
        Plugin plugin = Keystone.plugin();
        if (!plugin.isEnabled() || listeningFor == plugin) {
            return;
        }
        Events.listen(
                PlayerJoinEvent.class,
                EventPriority.MONITOR,
                false,
                event -> joined(event.getPlayer()));
        Events.listen(
                PlayerQuitEvent.class,
                EventPriority.MONITOR,
                false,
                event -> left(event.getPlayer()));
        Events.listen(
                PluginDisableEvent.class,
                EventPriority.MONITOR,
                false,
                event -> {
                    if (event.getPlugin() == plugin) {
                        cancelAll();
                    }
                });
        listeningFor = plugin;
    }

    private static Collection<? extends Player> online() {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        return players == null ? List.of() : players;
    }

    private static void add(List<String> lines, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
    }

    /** The plugin's own lang entry for {@code key} when it has one, else the built-in default. */
    private static String text(CommandSender sender, String key, Object... args) {
        LangNode node = Lang.nodesFor(sender).get(key);
        if (node instanceof LangNode.Text entry) {
            return entry.text() == null ? "" : Lang.render(sender, entry.text(), args);
        }
        if (node instanceof LangNode.Lines lines) {
            List<String> rendered = new ArrayList<>();
            for (LangNode.Text line : lines.lines()) {
                if (line.text() != null) {
                    rendered.add(Lang.render(sender, line.text(), args));
                }
            }
            return String.join("\n", rendered);
        }
        String template = DEFAULTS.get(key);
        return template == null ? "{" + key + "}" : Lang.render(sender, template, args);
    }

    /** Test hook: forgets every problem, reminder and setting. */
    static synchronized void reset() {
        cancelAll();
        CURRENT.clear();
        REMINDERS.clear();
        reminderClock = System::nanoTime;
        permissions = List.of();
        reloadCommand = null;
        reminderTimes = 3;
        reminderInterval = Duration.ofSeconds(120);
        listeningFor = null;
    }
}
