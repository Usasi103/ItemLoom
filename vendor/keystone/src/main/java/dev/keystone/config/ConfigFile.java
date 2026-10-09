package dev.keystone.config;

import dev.keystone.Keystone;
import dev.keystone.log.Log;
import dev.keystone.storage.FileBackup;
import dev.keystone.storage.StorageWriter;
import dev.keystone.storage.WriteGuard;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * A YAML file in the plugin's data folder, read through Bukkit's {@link YamlConfiguration} API.
 *
 * <p>With a jar default ({@code resource}) the file follows CraftEngine's lifecycle:
 *
 * <ol>
 *   <li>missing: the default is released with a {@code ___version___} header;
 *   <li>present with another version: the default is merged in under the {@link UpgradePolicy}
 *       (user values and comments kept, new keys added with their comments) and the file is
 *       rewritten;
 *   <li>unreadable YAML (Bukkit cannot parse it): the file is copied to {@code
 *       <name>_yyyyMMddHHmmss.bak}, a fresh default copy (with the version header) is written in its
 *       place, the plugin runs on the defaults, and the error is reported (0.3.4, user decision
 *       2026-09-29; 0.3.5: three ERROR lines with line and column, a {@link LoadProblem}, see
 *       below). The next load reads the fresh file without an error.
 *       {@link #replaceBroken(boolean) replaceBroken(false)} restores the 0.3.3 behaviour: the file
 *       is left as it is (backed up) and the defaults are used in memory only.
 * </ol>
 *
 * <p>Data files (no jar default: {@link #data}, {@code new ConfigFile(path, null)}) are never
 * replaced: an unreadable one is backed up, left as it is and read as empty, and <b>writes to it
 * are blocked</b> ({@link #isWriteBlocked}, {@link WriteGuard}) until a later load reads it cleanly
 * (0.3.4, user rule: a broken data file is never overwritten or lost). The same block applies to
 * any file whose bytes could not be read at all (I/O error, a OneDrive lock) or copied to a
 * verified backup. Bytes that are not UTF-8 (a GBK re-save) count as unreadable YAML: backed up,
 * then handled like a parse error. None of these throw from {@link #load} any more. A readable file whose
 * upgrade fails is not replaced either (backed up, defaults in memory, as in 0.3.3). A file is only
 * replaced once its bytes are safe in a {@code .bak}; when no backup can be made it stays.
 *
 * <p>Every load that cannot use its file records a {@link LoadProblem} - file, line and column,
 * cause, what was done, the verified backup - in {@link #loadProblem()} and {@link
 * ConfigProblems}, and prints it as three ERROR lines (0.3.5; 0.3.4 printed "警告" lines). A clean
 * load clears it. {@link ConfigProblems#notifyAdmins} tells admins.
 *
 * <p>The version key is hidden from the in-memory view, so iterating root keys never sees it, and
 * restored by {@link #saveToFile}.
 *
 * <p>The typed readers ({@link #readInt}, {@link #readDouble}, {@link #readString} ...) report
 * wrong types and out-of-range values with file, line and path, then fall back, where the plain
 * Bukkit getters stay silent.
 */
public class ConfigFile extends YamlConfiguration {

    private final String path;
    private final String resource;
    private final UpgradePolicy policy;
    private final List<Runnable> reloadCallbacks = new ArrayList<>();

    /** The {@code ___version___} of the loaded text (null for unversioned data files). */
    private String version;

    /** The text the current values came from, for line lookups. */
    private String sourceText = "";

    private Map<String, Integer> lineIndex;
    private boolean usingFallback;

    /**
     * Whether a new ConfigFile replaces an unreadable file that has a jar default. The user chose
     * true on 2026-09-29; one line to change.
     */
    public static final boolean REPLACE_BROKEN_DEFAULT = true;

    /** Whether an unreadable file with a jar default is replaced by a fresh copy. */
    private boolean replaceBroken = REPLACE_BROKEN_DEFAULT;

    private boolean brokenFileReplaced;
    private File brokenFileBackup;

    /** The verified backup of the bytes the last load could not use, or null. */
    private File unreadableBackup;

    /** What the last load could not use, or null after a clean load. */
    private LoadProblem problem;

    /** Line two of the console report and chat notice for this file, or null for the generic one. */
    private String brokenNote;

    /** The jar resource a file without a version header is repaired from, or null (0.3.5). */
    private String repairResource;

    /** {@link #contentDefaults(boolean)}, or null for the automatic choice (0.3.5). */
    private Boolean contentDefaults;

    /** A settings file with a jar default of the same path. */
    public ConfigFile(String path) {
        this(path, path, UpgradePolicy.CONFIG);
    }

    /** A file with the given jar default; {@code resource == null} means a plain data file. */
    public ConfigFile(String path, String resource) {
        this(path, resource, UpgradePolicy.CONFIG);
    }

    public ConfigFile(String path, String resource, UpgradePolicy policy) {
        this.path = path;
        this.resource = resource;
        this.policy = policy;
    }

    /** Loads a data file without a jar default (no versioning). Missing file = empty. */
    public static ConfigFile data(String path) {
        return new ConfigFile(path, null).load();
    }

    /**
     * Loads a content file (a menu, a category, a description): no version header, never merged,
     * and repaired from the jar resource of the same path when it cannot be used ({@link
     * #repairFrom}). Without that resource in the jar it is a plain {@link #data} file. Missing
     * file = empty, as for data files (0.3.5).
     */
    public static ConfigFile content(String path) {
        return new ConfigFile(path, null).repairFrom(path).load();
    }

    public String path() {
        return path;
    }

    public File file() {
        return new File(Keystone.dataFolder(), path);
    }

    /**
     * Whether the last load fell back to the jar default because the file was unreadable - also when
     * the file was then replaced by a fresh default copy ({@link #brokenFileReplaced()}).
     */
    public boolean usingFallback() {
        return usingFallback;
    }

    /**
     * Whether an unreadable file is replaced by a fresh default copy after its backup (default
     * {@link #REPLACE_BROKEN_DEFAULT}). {@code false} = 0.3.3: left as it is, defaults in memory
     * only (for a caller that rejects a broken reload and keeps its running settings). No effect on
     * data files, which are never replaced (a file with {@link #repairFrom} is a content file).
     */
    public ConfigFile replaceBroken(boolean replace) {
        this.replaceBroken = replace;
        return this;
    }

    public boolean replacesBroken() {
        return replaceBroken;
    }

    /** Whether the last load found the file unreadable and wrote a fresh default copy over it. */
    public boolean brokenFileReplaced() {
        return brokenFileReplaced;
    }

    /** The {@code .bak} holding the unreadable original after {@link #brokenFileReplaced}, or null. */
    public File brokenFileBackup() {
        return brokenFileBackup;
    }

    /**
     * What the last load could not use - which file, line and column, cause, whether it was
     * replaced, left as it is or write-blocked, and the backup - or null when it read the file
     * cleanly (0.3.5). The same record stands in {@link ConfigProblems} until a clean load.
     */
    public LoadProblem loadProblem() {
        return problem;
    }

    /**
     * Sets the second line of this file's error report (console and chat): what the plugin turns
     * off when the file cannot be used and how to recover, e.g. {@code 挖掘点已停用，本次一个也不载入；在修好
     * sites.yml 并执行 /tarch admin reload 之前，插件不会写入这个文件。}. Without it a generic text for
     * the outcome is used (0.3.5).
     */
    public ConfigFile brokenNote(String note) {
        this.brokenNote = note == null || note.isBlank() ? null : note;
        return this;
    }

    public String brokenNote() {
        return brokenNote;
    }

    /**
     * For a file without a jar default of its own ({@code new ConfigFile(path, null)}): when it
     * cannot be used (Bukkit cannot parse it, the bytes are not UTF-8) and {@code resource} is in
     * the plugin jar, the original is kept as a verified {@code <name>_yyyyMMddHHmmss.bak}, the
     * resource text is written in its place as it is (no version header, nothing merged) and
     * loaded - the 2026-09-29 rule for broken content files, reported once as {@link
     * LoadProblem.Outcome#REPLACED} (three ERROR lines). Without the resource, or when no backup
     * can be made or the bytes cannot be read at all, the file stays as it is and writes are
     * blocked, as for any data file. {@link #replaceBroken replaceBroken(false)} keeps the file and
     * uses the resource in memory only. No effect on a file that has a jar default of its own.
     * Takes effect on the next {@link #load}; null switches it off (0.3.5: Handbook's {@code
     * ContentFiles} and DialogMenu's {@code BrokenFiles} did this by hand, after Keystone had
     * already reported the file as left as it is).
     *
     * @param resource the jar resource path, which may differ from {@link #path} (DialogMenu's
     *     {@code catalog/menus/main.yml} for {@code menus/main.yml})
     */
    public ConfigFile repairFrom(String resource) {
        this.repairResource = resource == null || resource.isBlank() ? null : resource;
        return this;
    }

    /** The resource set by {@link #repairFrom}, or null. */
    public String repairResource() {
        return repairResource;
    }

    /** Loads (or reloads) from disk. Safe to call repeatedly. */
    public ConfigFile load() {
        ReloadTransaction transaction = ReloadTransaction.current();
        if (transaction != null) {
            return transaction.load(this);
        }
        applyText(prepareText());
        if (problem == null) {
            ConfigProblems.resolve(file());
        }
        return this;
    }

    /** {@link #load}, then runs the {@link #onReload} callbacks. */
    public void reload() {
        load();
        ReloadTransaction transaction = ReloadTransaction.current();
        if (transaction != null) {
            for (Runnable callback : reloadCallbacks) {
                transaction.afterCommit(callback);
            }
            return;
        }
        for (Runnable callback : reloadCallbacks) {
            try {
                callback.run();
            } catch (Throwable t) {
                Log.warn(path + " 重载回调失败: " + t.getMessage());
            }
        }
    }

    public void onReload(Runnable callback) {
        reloadCallbacks.add(callback);
    }

    /** Writes the current values back to {@link #file}, keeping the version header. */
    public void saveToFile() {
        trySave();
    }

    /**
     * {@link #saveToFile}, telling whether it wrote. A {@linkplain #isWriteBlocked write-blocked}
     * file is not written: a warning says why, the file on disk stays as it is, and false is
     * returned (0.3.4). A failed write still throws {@link UncheckedIOException}, as before.
     */
    public boolean trySave() {
        ReloadTransaction transaction = ReloadTransaction.current();
        if (transaction != null) {
            String text = saveToString();
            transaction.stage(
                    file(),
                    path,
                    version == null ? text : YamlUpgrade.withVersion(text, version),
                    false);
            return true;
        }
        String reason = WriteGuard.reason(file());
        if (reason != null) {
            LoadProblem found = ConfigProblems.get(file());
            String position =
                    found == null || found.position().isEmpty() ? "" : "（" + found.position() + "）";
            String backup =
                    found == null || found.backup() == null
                            ? ""
                            : "；原文件的备份在 " + found.backupDisplay();
            Log.error(
                    path
                            + " 没有保存："
                            + reason
                            + position
                            + "；这次的修改没有写入，磁盘上的文件保持不动，修好它并"
                            + ConfigProblems.reloadHint()
                            + "后才会恢复写入"
                            + backup
                            + "。");
            return false;
        }
        String body = saveToString();
        try {
            StorageWriter.writeAtomic(
                    file(), version == null ? body : YamlUpgrade.withVersion(body, version));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    /**
     * Whether writes to this file are blocked: the last load could not read it (data file that does
     * not parse, bytes that could not be read, or a failed backup). {@link #saveToFile} then skips
     * it and {@link StorageWriter#writeAtomic} refuses it. A load that reads the file cleanly lifts
     * the block.
     */
    public boolean isWriteBlocked() {
        return WriteGuard.isBlocked(file());
    }

    /** Why writes are blocked, or null. */
    public String writeBlockReason() {
        return WriteGuard.reason(file());
    }

    /**
     * Lifts the write block on purpose - for a plugin that deliberately starts this file over -
     * but only while the unreadable bytes are safe in a verified backup ({@link
     * #unreadableBackup}). Returns whether the block is gone.
     */
    public boolean allowWrites() {
        if (!isWriteBlocked()) {
            return true;
        }
        if (unreadableBackup == null || !unreadableBackup.isFile()) {
            Log.warn(path + " 没有经过核对的备份，不能解除写入保护");
            return false;
        }
        WriteGuard.unblock(file());
        Log.warn(path + " 已解除写入保护，原内容在 " + unreadableBackup.getPath());
        return true;
    }

    /** The verified backup of the bytes the last load could not use, or null. */
    public File unreadableBackup() {
        return unreadableBackup;
    }

    // ---- typed readers ------------------------------------------------------

    public int readInt(String path, int def) {
        return readInt(path, def, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public int readInt(String path, int def, int min, int max) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        Integer value = null;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw instanceof String text) {
            value = parseInt(text);
        }
        if (value == null) {
            return invalid(path, raw, "整数", def);
        }
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(max, value));
            issue(path, "数值 " + value + " 超出范围 " + min + ".." + max + "，已按 " + fixed + " 处理");
            return fixed;
        }
        return value;
    }

    public long readLong(String path, long def) {
        return readLong(path, def, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public long readLong(String path, long def, long min, long max) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        Long value = null;
        if (raw instanceof Number number) {
            value = number.longValue();
        } else if (raw instanceof String text) {
            try {
                value = Long.parseLong(text.trim().replace("_", ""));
            } catch (NumberFormatException e) {
                value = null;
            }
        }
        if (value == null) {
            return invalid(path, raw, "整数", def);
        }
        if (value < min || value > max) {
            long fixed = Math.max(min, Math.min(max, value));
            issue(path, "数值 " + value + " 超出范围 " + min + ".." + max + "，已按 " + fixed + " 处理");
            return fixed;
        }
        return value;
    }

    public double readDouble(String path, double def) {
        return readDouble(path, def, -Double.MAX_VALUE, Double.MAX_VALUE);
    }

    public double readDouble(String path, double def, double min, double max) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        Double value = null;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else if (raw instanceof String text) {
            try {
                value = Double.parseDouble(text.trim().replace("_", ""));
            } catch (NumberFormatException e) {
                value = null;
            }
        }
        if (value == null) {
            return invalid(path, raw, "数字", def);
        }
        if (value < min || value > max) {
            double fixed = Math.max(min, Math.min(max, value));
            issue(path, "数值 " + value + " 超出范围 " + min + ".." + max + "，已按 " + fixed + " 处理");
            return fixed;
        }
        return value;
    }

    public boolean readBool(String path, boolean def) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        if (raw instanceof String text) {
            switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on", "1":
                    return true;
                case "false", "no", "off", "0":
                    return false;
                default:
                    break;
            }
        }
        return invalid(path, raw, "true/false", def);
    }

    public String readString(String path, String def) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
            return raw.toString();
        }
        return invalid(path, raw, "文本", def);
    }

    /** A string list; a single scalar counts as a one-element list. Missing = empty list. */
    public List<String> readStrings(String path) {
        return readStrings(path, List.of());
    }

    public List<String> readStrings(String path, List<String> def) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result;
        }
        if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
            return List.of(raw.toString());
        }
        return invalid(path, raw, "文本列表", def);
    }

    /**
     * An integer list; a single integer counts as a one-element list. Missing = empty list.
     * Elements that are not integers (text, fractions, values outside the int range, empty items)
     * are reported with file, line and index and skipped.
     */
    public List<Integer> readIntegers(String path) {
        return readIntegers(path, List.of());
    }

    public List<Integer> readIntegers(String path, List<Integer> def) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        if (raw instanceof List<?> list) {
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                Integer value = integerOf(item);
                if (value == null) {
                    issue(path + "[" + i + "]", "期望整数，实际为 \"" + item + "\"，已跳过这一项");
                } else {
                    result.add(value);
                }
            }
            return result;
        }
        Integer single = integerOf(raw);
        if (single != null) {
            return List.of(single);
        }
        return invalid(path, raw, "整数列表", def);
    }

    /** An exact int: a whole number in range, or text that parses as one; otherwise null. */
    private static Integer integerOf(Object raw) {
        if (raw instanceof String text) {
            return parseInt(text);
        }
        if (!(raw instanceof Number number)) {
            return null;
        }
        if (raw instanceof Double || raw instanceof Float || raw instanceof BigDecimal) {
            double value = number.doubleValue();
            return value == Math.rint(value)
                            && value >= Integer.MIN_VALUE
                            && value <= Integer.MAX_VALUE
                    ? (int) value
                    : null;
        }
        BigInteger value =
                raw instanceof BigInteger big ? big : BigInteger.valueOf(number.longValue());
        return value.bitLength() < Integer.SIZE ? value.intValue() : null;
    }

    public <E extends Enum<E>> E readEnum(String path, Class<E> type, E def) {
        Object raw = get(path);
        if (raw == null) {
            return def;
        }
        String name =
                raw.toString().trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        String options =
                Arrays.stream(type.getEnumConstants())
                        .map(e -> e.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining(", "));
        issue(
                path,
                "无效的值 \""
                        + raw
                        + "\"，可选: "
                        + options
                        + "，已使用 "
                        + def.name().toLowerCase(Locale.ROOT));
        return def;
    }

    /** Child sections of {@code path} as (key, section) pairs; non-section children are skipped. */
    public static List<Map.Entry<String, ConfigurationSection>> sections(
            ConfigurationSection section, String path) {
        ConfigurationSection parent = section.getConfigurationSection(path);
        List<Map.Entry<String, ConfigurationSection>> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        for (String key : parent.getKeys(false)) {
            ConfigurationSection child = parent.getConfigurationSection(key);
            if (child != null) {
                result.add(new AbstractMap.SimpleImmutableEntry<>(key, child));
            }
        }
        return result;
    }

    /**
     * This file read with TabooLib's lenient getter rules ({@code "5"} is 5, unparseable text is
     * 0, {@code "~"} is missing ...); see {@link LenientSection}. The view is live: it reads the
     * values of the latest {@link #load}/{@link #reload}, and never reports issues.
     */
    public LenientSection lenient() {
        return LenientSection.of(this);
    }

    // ---- issue reporting ----------------------------------------------------

    /** 1-based line of a dotted key path in the loaded text, or -1. */
    public int lineOf(String path) {
        if (lineIndex == null) {
            lineIndex = buildLineIndex(sourceText);
        }
        return lineIndex.getOrDefault(path, -1);
    }

    /** Logs a problem with {@code file:line path} context, the way CraftEngine reports them. */
    public void issue(String path, String message) {
        int line = lineOf(path);
        ReloadTransaction transaction = ReloadTransaction.current();
        if (transaction != null) {
            transaction.problem(file(), this.path, path + " - " + message, line, -1);
            return;
        }
        String where = line > 0 ? this.path + ":" + line : this.path;
        Log.warn(where + " " + path + " - " + message);
    }

    private <T> T invalid(String path, Object raw, String expected, T def) {
        issue(path, "期望" + expected + "，实际为 \"" + raw + "\"，已使用默认值 " + def);
        return def;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim().replace("_", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- lifecycle internals ------------------------------------------------

    record ReloadState(
            String text,
            String source,
            String version,
            boolean fallback,
            boolean replaced,
            File backup,
            File unreadable,
            LoadProblem problem) {}

    ReloadState reloadState() {
        return new ReloadState(
                saveToString(),
                sourceText,
                version,
                usingFallback,
                brokenFileReplaced,
                brokenFileBackup,
                unreadableBackup,
                problem);
    }

    void restoreReloadState(ReloadState state) {
        try {
            loadFromString(state.text());
        } catch (InvalidConfigurationException error) {
            throw new IllegalStateException("Cannot restore the validated in-memory config", error);
        }
        sourceText = state.source();
        version = state.version();
        usingFallback = state.fallback();
        brokenFileReplaced = state.replaced();
        brokenFileBackup = state.backup();
        unreadableBackup = state.unreadable();
        problem = state.problem();
        lineIndex = null;
    }

    void loadForReload(ReloadTransaction transaction) {
        File target = file();
        String text = transaction.readText(target, path);
        if (text == null) {
            return;
        }
        String defaultText =
                resource == null ? null : YamlUpgrade.stripBom(Keystone.resourceText(resource));
        String nextVersion = defaultText == null ? null : YamlUpgrade.versionOf(defaultText);
        try {
            if (!target.exists() && defaultText != null) {
                text = YamlUpgrade.withVersion(defaultText, nextVersion);
                transaction.stage(target, path, text, false);
            }
            Exception parseProblem = parseError(text);
            if (parseProblem != null) {
                transaction.problem(
                        LoadProblem.builder(path, target).error(parseProblem).source(text).build());
                return;
            }
            if (defaultText != null && !nextVersion.equals(YamlUpgrade.storedVersion(text))) {
                YamlUpgrade.Result upgraded = YamlUpgrade.upgrade(text, defaultText, policy);
                text = YamlUpgrade.withVersion(upgraded.text(), nextVersion);
                transaction.stage(target, path, text, false);
            }
            loadFromString(text);
            set(YamlUpgrade.VERSION_KEY, null);
            sourceText = text;
            version = nextVersion;
            lineIndex = null;
            usingFallback = false;
            brokenFileReplaced = false;
            brokenFileBackup = null;
            unreadableBackup = null;
            problem = null;
            transaction.resolve(target);
        } catch (Exception error) {
            transaction.problem(
                    LoadProblem.builder(path, target).error(error).source(text).build());
        }
    }

    private String prepareText() {
        usingFallback = false;
        brokenFileReplaced = false;
        brokenFileBackup = null;
        unreadableBackup = null;
        problem = null;
        File target = file();
        // This load decides the block afresh; a clean read keeps it lifted.
        WriteGuard.unblock(target);
        StorageWriter.recover(target);
        String defaultText =
                resource == null ? null : YamlUpgrade.stripBom(Keystone.resourceText(resource));
        try {
            if (defaultText == null) {
                version = null;
                if (!target.isFile()) {
                    return "";
                }
                try {
                    return readStrict(target);
                } catch (Unreadable e) {
                    String repair = repairText();
                    if (repair != null && e.bytes != null) {
                        usingFallback = true;
                        return recover(target, repair, problemFor(target).encoding(e.bytes));
                    }
                    return unreadableData(target, e);
                }
            }
            String targetVersion = YamlUpgrade.versionOf(defaultText);
            version = targetVersion;
            if (!target.isFile()) {
                String text = YamlUpgrade.withVersion(defaultText, targetVersion);
                StorageWriter.writeAtomic(target, text);
                return text;
            }
            String fallback = YamlUpgrade.withVersion(defaultText, targetVersion);
            String localText;
            try {
                localText = readStrict(target);
            } catch (Unreadable e) {
                usingFallback = true;
                if (e.bytes == null) {
                    blockWrites(target, "无法读取（" + e.getMessage() + "）");
                    report(
                            problemFor(target)
                                    .error(e.getCause() != null ? e.getCause() : e)
                                    .kind(LoadProblem.Kind.READ)
                                    .outcome(LoadProblem.Outcome.WRITE_BLOCKED));
                    return fallback;
                }
                return recover(target, fallback, problemFor(target).encoding(e.bytes));
            }
            if (targetVersion.equals(YamlUpgrade.storedVersion(localText))) {
                if (localText.indexOf('﻿') >= 0) {
                    // Written by 0.3.1 in front of a byte-order mark: move the mark out of the way.
                    String repaired = YamlUpgrade.withVersion(localText, targetVersion);
                    if (!repaired.equals(localText)) {
                        StorageWriter.writeAtomic(target, repaired);
                        return repaired;
                    }
                }
                return localText;
            }
            try {
                YamlUpgrade.Result result = YamlUpgrade.upgrade(localText, defaultText, policy);
                String text = YamlUpgrade.withVersion(result.text(), targetVersion);
                StorageWriter.writeAtomic(target, text);
                logUpgrade(result.report());
                return text;
            } catch (Exception e) {
                usingFallback = true;
                String fresh = YamlUpgrade.withVersion(defaultText, targetVersion);
                Exception unparsable = parseError(localText);
                if (unparsable != null) {
                    // Position and cause from Bukkit's own parser, the one the plugin reads with.
                    return recover(
                            target, fresh, problemFor(target).error(unparsable).source(localText));
                }
                // Bukkit reads it and only the upgrade failed: a readable file is never replaced.
                Saved saved = backupBroken(target);
                if (saved.file() == null) {
                    blockWrites(target, "升级失败且无法备份");
                }
                report(
                        saved.into(
                                        problemFor(target)
                                                .error(e)
                                                .source(localText)
                                                .kind(LoadProblem.Kind.UPGRADE))
                                .outcome(
                                        saved.file() == null
                                                ? LoadProblem.Outcome.WRITE_BLOCKED
                                                : LoadProblem.Outcome.NOT_LOADED));
                return fresh;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void logUpgrade(UpgradeReport report) {
        if (!report.added.isEmpty()) {
            Log.info(path + " 已升级，新增 " + report.added.size() + " 项: " + preview(report.added));
        }
        if (!report.removed.isEmpty()) {
            Log.info(path + " 已移除 " + report.removed.size() + " 个废弃项: " + preview(report.removed));
        }
        for (String key : report.keptMismatched) {
            Log.warn(path + " " + key + " 的类型与默认配置不同，已保留你的写法，请确认是否有效");
        }
    }

    private static String preview(List<String> keys) {
        String shown = String.join(", ", keys.subList(0, Math.min(8, keys.size())));
        return keys.size() > 8 ? shown + " ..." : shown;
    }

    private void applyText(String text) {
        sourceText = text;
        lineIndex = null;
        try {
            loadFromString(text);
        } catch (InvalidConfigurationException | RuntimeException e) {
            String defaultText =
                    resource == null ? null : YamlUpgrade.stripBom(Keystone.resourceText(resource));
            usingFallback = true;
            String repair = defaultText == null ? repairText() : null;
            if (repair != null) {
                if (text.equals(repair)) {
                    throw new IllegalStateException("插件内置的 " + repairResource + " 无法解析", e);
                }
                sourceText = recover(file(), repair, problemFor(file()).error(e).source(text));
            } else if (defaultText == null) {
                Saved saved = backupBroken(file());
                unreadableBackup = saved.file();
                blockWrites(file(), "上次加载无法解析");
                report(
                        saved.into(problemFor(file()).error(e).source(text))
                                .outcome(LoadProblem.Outcome.WRITE_BLOCKED));
                sourceText = "";
            } else {
                String fresh =
                        YamlUpgrade.withVersion(
                                defaultText,
                                version != null ? version : YamlUpgrade.versionOf(defaultText));
                if (text.equals(fresh) || text.equals(defaultText)) {
                    throw new IllegalStateException("插件内置的 " + path + " 无法解析", e);
                }
                sourceText = recover(file(), fresh, problemFor(file()).error(e).source(text));
            }
            try {
                loadFromString(sourceText);
            } catch (InvalidConfigurationException invalid) {
                throw new IllegalStateException("插件内置的 " + path + " 无法解析", invalid);
            }
        }
        if (contains(YamlUpgrade.VERSION_KEY)) {
            set(YamlUpgrade.VERSION_KEY, null);
        }
    }

    /** Whether Bukkit's parser, the one the plugin reads with, accepts {@code text}. */
    static boolean readable(String text) {
        return parseError(text) == null;
    }

    /** What Bukkit's parser says about {@code text}, or null when it accepts it. */
    static Exception parseError(String text) {
        try {
            new YamlConfiguration().loadFromString(text);
            return null;
        } catch (InvalidConfigurationException | RuntimeException e) {
            return e;
        }
    }

    /**
     * An unreadable file with a jar default: backs it up, then (unless {@link #replaceBroken} is
     * off, or no backup holds its bytes) writes {@code fresh} in its place, and reports what
     * happened ({@link LoadProblem}: three ERROR lines). Returns the text to run on: {@code fresh}
     * either way.
     */
    private String recover(File target, String fresh, LoadProblem.Builder found) {
        Saved saved = backupBroken(target);
        if (!replaceBroken) {
            unreadableBackup = saved.file();
            if (saved.file() == null) {
                blockWrites(target, "无法备份");
            }
            report(
                    saved.into(found)
                            .outcome(
                                    saved.file() == null
                                            ? LoadProblem.Outcome.WRITE_BLOCKED
                                            : LoadProblem.Outcome.NOT_LOADED));
            return fresh;
        }
        if (saved.file() == null) {
            blockWrites(target, "无法备份");
            report(saved.into(found).outcome(LoadProblem.Outcome.WRITE_BLOCKED));
            return fresh;
        }
        unreadableBackup = saved.file();
        try {
            StorageWriter.writeAtomic(target, fresh);
        } catch (IOException | RuntimeException e) {
            String noun = LoadProblem.defaultsNoun(contentDefaults());
            String note =
                    brokenNote != null
                            ? brokenNote
                            : "写入插件内置的"
                                    + noun
                                    + "失败（"
                                    + e.getClass().getSimpleName()
                                    + ": "
                                    + e.getMessage()
                                    + "），"
                                    + path
                                    + " 保持原样，本次改用插件内置的"
                                    + noun
                                    + "；修好 "
                                    + path
                                    + " 后"
                                    + ConfigProblems.reloadHint()
                                    + "即可用回它。";
            report(saved.into(found).outcome(LoadProblem.Outcome.NOT_LOADED).note(note));
            return fresh;
        }
        brokenFileReplaced = true;
        brokenFileBackup = saved.file();
        report(saved.into(found).outcome(LoadProblem.Outcome.REPLACED));
        return fresh;
    }

    /** A problem with this file, with its note and whether the plugin runs on jar defaults. */
    private LoadProblem.Builder problemFor(File target) {
        return LoadProblem.builder(path, target)
                .defaults(resource != null || repairText() != null)
                .contentDefaults(contentDefaults())
                .note(brokenNote);
    }

    /**
     * Whether this file's jar default is content (a lang, menu or page file) rather than settings,
     * which decides the word in the generic second line of its error report: 默认内容 or 默认配置
     * (0.3.5). Unless set by {@link #contentDefaults(boolean)}: true for a lang file ({@link
     * UpgradePolicy#LANG}) and a file repaired from the jar ({@link #repairFrom}), false otherwise.
     */
    public boolean contentDefaults() {
        if (contentDefaults != null) {
            return contentDefaults;
        }
        return resource != null ? UpgradePolicy.LANG.equals(policy) : repairText() != null;
    }

    /**
     * Says the jar default of this file is content - a versioned menu file, {@code new
     * ConfigFile("menus/main.yml")} - so its error report says 默认内容 instead of 默认配置
     * (0.3.5). Takes effect on the next {@link #load}.
     */
    public ConfigFile contentDefaults(boolean content) {
        this.contentDefaults = content;
        return this;
    }

    /** The text of {@link #repairFrom} (BOM removed), or null when unset or not in the jar. */
    private String repairText() {
        if (resource != null || repairResource == null) {
            return null;
        }
        String text = Keystone.resourceText(repairResource);
        return text == null ? null : YamlUpgrade.stripBom(text);
    }

    /** Records the problem of this load and prints it ({@link ConfigProblems#report}). */
    private void report(LoadProblem.Builder found) {
        problem = found.build();
        ConfigProblems.report(problem);
    }

    /** Makes the backup of an unreadable file; replaced in tests to make the copy fail. */
    @FunctionalInterface
    interface BackupCopier {
        File copy(File file, Date now) throws IOException;
    }

    static BackupCopier copier = ConfigFile::backupCopy;

    /**
     * The verified backup of a file, or why there is none.
     *
     * @param file the backup, or null
     * @param created whether this load made it (false: an identical backup already existed)
     * @param error why there is no backup, when {@code file} is null
     */
    private record Saved(File file, boolean created, String error) {

        LoadProblem.Builder into(LoadProblem.Builder builder) {
            return file != null ? builder.backup(file, created) : builder.backupFailed(error);
        }
    }

    /**
     * Copies a file that cannot be used to {@code <name>_yyyyMMddHHmmss.bak} next to it ({@link
     * FileBackup}: read back and compared), as TabooLib's {@code ConfigFile.loadFromFile} did, so
     * a later {@link #saveToFile} never destroys the only copy. A file that already has a
     * byte-identical backup is not copied again (the existing one is named), so a file that stays
     * broken across reloads is backed up once; {@code .bak} files are never copied.
     */
    private static Saved backupBroken(File file) {
        try {
            File copy = copier.copy(file, new Date());
            if (copy != null) {
                return new Saved(copy, true, null);
            }
            File existing = identicalBackup(file);
            if (existing != null) {
                return new Saved(existing, false, null);
            }
            boolean isBackup = file.getName().toLowerCase(Locale.ROOT).endsWith(".bak");
            return new Saved(null, false, isBackup ? "文件本身就是 .bak 备份" : "文件不存在");
        } catch (IOException | RuntimeException e) {
            return new Saved(null, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void blockWrites(File file, String reason) {
        WriteGuard.block(file, path + " " + reason);
    }

    /** Bytes that could not be read ({@code bytes == null}) or are not valid UTF-8. */
    private static final class Unreadable extends IOException {
        final byte[] bytes;

        Unreadable(String message, byte[] bytes, Throwable cause) {
            super(message, cause);
            this.bytes = bytes;
        }
    }

    /** The file as strict UTF-8 without a byte-order mark; malformed bytes are an error. */
    private static String readStrict(File file) throws Unreadable {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            throw new Unreadable(e.getClass().getSimpleName() + ": " + e.getMessage(), null, e);
        }
        try {
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            text = YamlUpgrade.stripBom(text);
            String repaired = ReloadTransaction.repairIndentation(text);
            if (!repaired.equals(text) && readable(repaired)) {
                FileBackup.beside(file, bytes);
                StorageWriter.writeAtomic(file, repaired);
                return repaired;
            }
            return text;
        } catch (CharacterCodingException e) {
            throw new Unreadable("不是 UTF-8 编码（可能被另存为 GBK）", bytes, e);
        } catch (IOException e) {
            throw new Unreadable("缩进修复失败（" + e.getMessage() + "）", null, e);
        }
    }

    /** A data file that could not be read or decoded: backed up when possible, blocked, empty. */
    private String unreadableData(File target, Unreadable e) {
        usingFallback = true;
        Saved saved = e.bytes != null ? backupBroken(target) : new Saved(null, false, null);
        if (e.bytes != null) {
            unreadableBackup = saved.file();
        }
        blockWrites(target, e.bytes == null ? "无法读取（" + e.getMessage() + "）" : e.getMessage());
        LoadProblem.Builder found = problemFor(target);
        if (e.bytes == null) {
            found.error(e.getCause() != null ? e.getCause() : e).kind(LoadProblem.Kind.READ);
        } else {
            found.encoding(e.bytes);
        }
        report(saved.into(found).outcome(LoadProblem.Outcome.WRITE_BLOCKED));
        return "";
    }

    /**
     * The copy made by {@link #backupBroken}, or null when nothing was copied: no file, a {@code
     * .bak} itself, or a {@code <name>_*.bak} next to it with the same bytes. A second copy within
     * the same second gets a {@code _2}, {@code _3} ... suffix instead of overwriting the first.
     * {@link FileBackup#beside} is the public form.
     */
    static File backupCopy(File file, Date now) throws IOException {
        if (!file.isFile() || file.getName().toLowerCase(Locale.ROOT).endsWith(".bak")) {
            return null;
        }
        FileBackup.Backup backup = FileBackup.beside(file, Files.readAllBytes(file.toPath()), now);
        return backup.created() ? backup.file() : null;
    }

    /** A {@code <name>_*.bak} next to {@code file} with its exact bytes, or null. */
    static File identicalBackup(File file) throws IOException {
        if (!file.isFile()) {
            return null;
        }
        // Same form as the file (0.3.4 returned an absolute path here and a relative one for a
        // new copy, so plugins printing getPath() showed both forms).
        return FileBackup.identical(file, Files.readAllBytes(file.toPath()));
    }

    /**
     * The parser's source name before a position: {@code in reader, } (snakeyaml-engine) and {@code
     * in 'reader', } (SnakeYAML 2, which Bukkit uses), and the same for string input.
     */
    private static final Pattern SOURCE_NAME =
            Pattern.compile("\\bin (?:'reader'|\"reader\"|reader|'string'|\"string\"|string), ");

    /** A YAML error as one line: every message line, with the parser's "in reader" dropped. */
    static String describe(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.lines()
                .map(line -> SOURCE_NAME.matcher(line.trim()).replaceAll(""))
                .filter(line -> !line.isEmpty() && !line.equals("^"))
                .collect(Collectors.joining(" / "));
    }

    /**
     * Maps every mapping key path in {@code text} to its 1-based line, and every list item to the
     * line it starts on ({@code path[0]}, {@code path[1]} ...).
     */
    static Map<String, Integer> buildLineIndex(String text) {
        Map<String, Integer> result = new HashMap<>();
        Node root;
        try {
            root = new Yaml(new LoaderOptions()).compose(new StringReader(text));
        } catch (Exception e) {
            return result;
        }
        if (root != null) {
            walk(root, "", result);
        }
        return result;
    }

    private static void walk(Node node, String prefix, Map<String, Integer> result) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                    continue;
                }
                String path = prefix.isEmpty() ? key.getValue() : prefix + "." + key.getValue();
                result.putIfAbsent(path, key.getStartMark().getLine() + 1);
                walk(tuple.getValueNode(), path, result);
            }
        } else if (node instanceof SequenceNode sequence) {
            List<Node> items = sequence.getValue();
            for (int i = 0; i < items.size(); i++) {
                String path = prefix + "[" + i + "]";
                result.putIfAbsent(path, items.get(i).getStartMark().getLine() + 1);
                walk(items.get(i), path, result);
            }
        }
    }
}
