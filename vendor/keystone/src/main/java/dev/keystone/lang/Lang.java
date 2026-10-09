package dev.keystone.lang;

import dev.keystone.Keystone;
import dev.keystone.config.ConfigFile;
import dev.keystone.config.ConfigProblems;
import dev.keystone.config.LoadProblem;
import dev.keystone.config.ReloadTransaction;
import dev.keystone.config.UpgradePolicy;
import dev.keystone.util.Texts;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * The plugin's lang files, compatible with the files TabooLib wrote and read.
 *
 * <p>Format (unchanged): {@code lang/<code>.yml} with flat root keys whose values are strings or
 * string lists, {@code &} colour codes, positional {@code {0}} arguments and CraftEngine {@code
 * <shift:N>} / {@code <image:..>} tags that CraftEngine's packet layer draws.
 *
 * <p>Behaviour:
 *
 * <ul>
 *   <li>Every {@code .yml} under the jar's {@code lang} folder is released on first run. Later
 *       runs merge newly added jar keys into the server copy (CraftEngine-style version header,
 *       user text never touched), and any key still missing falls back to the jar text in memory.
 *   <li>Locale: the player's client locale (console: the JVM default), mapped like TabooLib ({@code
 *       zh_hans_cn -> zh_CN}, {@code en_gb -> en_US} ...), matched case-insensitively, else the
 *       default language.
 *   <li>Templates pass through the transfers - colour codes first, then CraftEngine tag removal
 *       when CraftEngine is absent, then any registered by the plugin - and only then get their
 *       arguments, so argument values are never colourised.
 *   <li>An unknown key renders as {@code {key}}; an empty string sends nothing.
 * </ul>
 */
public final class Lang {

    private static final Map<String, String> CODE_TRANSFER =
            Map.of(
                    "zh_hans_cn", "zh_CN",
                    "zh_hant_cn", "zh_TW",
                    "en_ca", "en_US",
                    "en_au", "en_US",
                    "en_gb", "en_US",
                    "en_nz", "en_US");

    private static final Pattern CE_TAGS = Pattern.compile("(?:<(?:shift|image):[^>]*>)+ {0,2}");

    private static volatile String defaultLanguage = "zh_CN";
    private static volatile String folder = "lang";
    private static volatile Map<String, Map<String, LangNode>> files = Map.of();
    private static volatile boolean ceAbsent;
    private static final List<LangTransfer> transfers = new CopyOnWriteArrayList<>();
    private static final List<Overlay> overlays = new CopyOnWriteArrayList<>();

    /** A folder read into every language as {@code prefix + key}; see {@link #overlay}. */
    private record Overlay(String prefix, File dir, boolean replace) {}

    private Lang() {}

    /** Fallback language code (default {@code zh_CN}). */
    public static void setDefault(String code) {
        defaultLanguage = code;
    }

    public static String defaultLanguage() {
        return defaultLanguage;
    }

    /** Folder inside the jar and the data folder (default {@code lang}). */
    public static void setFolder(String path) {
        folder = path;
    }

    /** Codes of the loaded languages. */
    public static List<String> languages() {
        return List.copyOf(files.keySet());
    }

    /** Adds a transfer that runs after the built-in ones. */
    public static void addTransfer(LangTransfer transfer) {
        if (!transfers.contains(transfer)) {
            transfers.add(transfer);
        }
    }

    /**
     * Also reads {@code <dir>/<code>.yml} and adds its keys as {@code <prefix><key>} (used by
     * Ambience to keep a merged module's old lang folder working). Takes effect on the next load.
     * Only fills keys the plugin's own lang lacks; {@link #overlay(String, File, boolean)} with
     * {@code replace} lets the folder's texts win.
     */
    public static void overlay(String prefix, File dir) {
        overlay(prefix, dir, false);
    }

    /**
     * Like {@link #overlay(String, File)}; with {@code replace} a key the folder has replaces the
     * plugin's own text for {@code <prefix><key>} (the old LootBeam files, whose operator-edited
     * texts took precedence), without it the plugin's own text is kept. The folder is only read.
     */
    public static void overlay(String prefix, File dir, boolean replace) {
        overlays.add(new Overlay(prefix, dir, replace));
    }

    /**
     * (Re)loads every language. Call in onEnable (after CraftEngine enabled) and on reload. A
     * language file that cannot be used is reported like any {@link ConfigFile} (0.3.5: {@link
     * ConfigProblems}, three ERROR lines, admin notice); problems of language files that are no
     * longer loaded are dropped.
     */
    public static void load() {
        boolean nextCeAbsent = !Bukkit.getPluginManager().isPluginEnabled("CraftEngine");
        Map<String, Map<String, LangNode>> loaded = new LinkedHashMap<>();
        Set<String> readFiles = new HashSet<>();
        for (String resource : jarLangFiles()) {
            String name = resource.substring(resource.lastIndexOf('/') + 1);
            String code = name.substring(0, name.lastIndexOf('.'));
            String jarText = Keystone.resourceText(resource);
            Map<String, LangNode> nodes = new LinkedHashMap<>(readNodes(parse(jarText)));
            ConfigFile user = new ConfigFile(resource, resource, UpgradePolicy.LANG).load();
            readFiles.add(key(user.file()));
            nodes.putAll(readNodes(user));
            loaded.put(code, nodes);
        }
        // Server-only languages (a translation the jar never shipped).
        File dir = new File(Keystone.dataFolder(), folder);
        File[] extra = dir.listFiles(f -> f.isFile() && f.getName().endsWith(".yml"));
        if (extra != null) {
            for (File file : extra) {
                String code = file.getName().substring(0, file.getName().length() - 4);
                if (loaded.keySet().stream().noneMatch(k -> k.equalsIgnoreCase(code))) {
                    ConfigFile own = new ConfigFile(folder + "/" + file.getName(), null).load();
                    readFiles.add(key(own.file()));
                    loaded.put(code, readNodes(own));
                }
            }
        }
        for (Overlay overlay : overlays) {
            for (Map.Entry<String, Map<String, LangNode>> language : loaded.entrySet()) {
                File file = new File(overlay.dir(), language.getKey() + ".yml");
                if (!file.isFile()) {
                    continue;
                }
                Map<String, LangNode> merged = new LinkedHashMap<>(language.getValue());
                ReloadTransaction transaction = ReloadTransaction.current();
                YamlConfiguration config =
                        transaction == null
                                ? YamlConfiguration.loadConfiguration(file)
                                : transaction.checkYaml(file, file.getPath());
                if (config == null) {
                    continue;
                }
                Map<String, LangNode> read = readNodes(config);
                for (Map.Entry<String, LangNode> entry : read.entrySet()) {
                    String key = overlay.prefix() + entry.getKey();
                    if (overlay.replace()) {
                        merged.put(key, entry.getValue());
                    } else {
                        merged.putIfAbsent(key, entry.getValue());
                    }
                }
                language.setValue(merged);
            }
        }
        Map<String, Map<String, LangNode>> frozen = new LinkedHashMap<>();
        loaded.forEach((code, nodes) -> frozen.put(code, Collections.unmodifiableMap(nodes)));
        Runnable install =
                () -> {
                    files = Collections.unmodifiableMap(frozen);
                    ceAbsent = nextCeAbsent;
                    forgetUnloaded(new File(Keystone.dataFolder(), folder), readFiles);
                };
        ReloadTransaction transaction = ReloadTransaction.current();
        if (transaction == null) {
            install.run();
        } else {
            transaction.afterCommit(install);
        }
    }

    /**
     * Drops the problems of language files directly in {@code dir} ({@code <code>.yml}) that this
     * load did not read (a server-only translation that was deleted).
     */
    private static void forgetUnloaded(File dir, Set<String> read) {
        String folderKey = key(dir);
        for (LoadProblem problem : ConfigProblems.current()) {
            File file = problem.file().getAbsoluteFile();
            File parent = file.getParentFile();
            if (parent != null
                    && key(parent).equals(folderKey)
                    && file.getName().endsWith(".yml")
                    && !read.contains(key(file))) {
                ConfigProblems.resolve(problem.file());
            }
        }
    }

    private static String key(File file) {
        return file.getAbsoluteFile().toPath().normalize().toString();
    }

    public static void reload() {
        load();
    }

    /** The nodes of {@code code} (case-insensitive), falling back to the default, then any. */
    public static Map<String, LangNode> nodes(String code) {
        Map<String, Map<String, LangNode>> all = files;
        for (Map.Entry<String, Map<String, LangNode>> entry : all.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(code)) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, Map<String, LangNode>> entry : all.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(defaultLanguage)) {
                return entry.getValue();
            }
        }
        return all.isEmpty() ? Map.of() : all.values().iterator().next();
    }

    /** The language code used for {@code sender}. */
    @SuppressWarnings("deprecation")
    public static String localeOf(CommandSender sender) {
        if (sender instanceof Player player) {
            String locale = player.getLocale();
            return CODE_TRANSFER.getOrDefault(locale.toLowerCase(Locale.ROOT), locale);
        }
        String code =
                Locale.getDefault().toLanguageTag().replace("-", "_").toLowerCase(Locale.ROOT);
        return CODE_TRANSFER.getOrDefault(code, code);
    }

    public static Map<String, LangNode> nodesFor(CommandSender sender) {
        return nodes(localeOf(sender));
    }

    /** Runs the transfers over a template, then fills the arguments. */
    public static String render(CommandSender sender, String template, Object... args) {
        String text = Texts.colored(template);
        if (ceAbsent && text.indexOf('<') >= 0) {
            text = removeCeTags(text);
        }
        for (LangTransfer transfer : transfers) {
            text = transfer.transfer(sender, text);
        }
        return Texts.replaceWithOrder(text, args);
    }

    /** Removes CraftEngine tags when CraftEngine is absent, for text outside the lang system. */
    public static String stripCeTags(String text) {
        return ceAbsent ? removeCeTags(text) : text;
    }

    /**
     * Whether {@code sender} is a plain-text console: the server console or RCON. CraftEngine draws
     * {@code <shift:N>} / {@code <image:..>} only in packets to a Minecraft client, so there the
     * tags would print as raw text whether CraftEngine is installed or not. Players, command
     * blocks, entities and other senders are not consoles.
     */
    public static boolean isConsole(CommandSender sender) {
        return sender instanceof ConsoleCommandSender
                || sender instanceof RemoteConsoleCommandSender;
    }

    /**
     * {@code text} as a console should get it: CraftEngine tags (and the padding after them, the
     * pattern {@link #stripCeTags} uses) removed for {@linkplain #isConsole consoles}, CraftEngine
     * installed or not; anyone else gets {@code text} itself, unchanged. For plugin text that goes
     * to a sender outside {@link #send} and {@code Log.lang}, which already apply it.
     */
    public static String forConsole(CommandSender sender, String text) {
        return text != null && isConsole(sender) && text.indexOf('<') >= 0
                ? removeCeTags(text)
                : text;
    }

    private static String removeCeTags(String text) {
        return CE_TAGS.matcher(text).replaceAll("");
    }

    // ---- sending ------------------------------------------------------------

    /**
     * Sends lang {@code node}: every line of a list, nothing for an empty string, {@code {key}} when
     * missing. A console gets the lines through {@link #forConsole} (no CraftEngine tags); players
     * get the rendered text unchanged.
     */
    public static void send(CommandSender sender, String node, Object... args) {
        LangNode entry = nodesFor(sender).get(node);
        if (entry == null) {
            sender.sendMessage("{" + node + "}");
        } else if (entry instanceof LangNode.Text text) {
            if (text.text() != null) {
                sender.sendMessage(forConsole(sender, render(sender, text.text(), args)));
            }
        } else if (entry instanceof LangNode.Lines lines) {
            for (LangNode.Text line : lines.lines()) {
                if (line.text() != null) {
                    sender.sendMessage(forConsole(sender, render(sender, line.text(), args)));
                }
            }
        }
    }

    /** The rendered text of {@code node}; {@code {node}} when missing or a list, "" when empty. */
    public static String text(CommandSender sender, String node, Object... args) {
        LangNode entry = nodesFor(sender).get(node);
        if (entry instanceof LangNode.Text text) {
            return text.text() == null ? "" : render(sender, text.text(), args);
        }
        return "{" + node + "}";
    }

    /** The rendered text of {@code node}, or null when missing, empty or a list. */
    public static String textOrNull(CommandSender sender, String node, Object... args) {
        LangNode entry = nodesFor(sender).get(node);
        if (entry instanceof LangNode.Text text && text.text() != null) {
            return render(sender, text.text(), args);
        }
        return null;
    }

    /** The rendered lines of {@code node}; a single string gives one line. */
    public static List<String> lines(CommandSender sender, String node, Object... args) {
        LangNode entry = nodesFor(sender).get(node);
        List<String> result = new ArrayList<>();
        if (entry == null) {
            result.add("{" + node + "}");
        } else if (entry instanceof LangNode.Text text) {
            if (text.text() != null) {
                result.add(render(sender, text.text(), args));
            }
        } else if (entry instanceof LangNode.Lines lines) {
            for (LangNode.Text line : lines.lines()) {
                if (line.text() != null) {
                    result.add(render(sender, line.text(), args));
                }
            }
        }
        return result;
    }

    // ---- internals ----------------------------------------------------------

    private static YamlConfiguration parse(String text) {
        YamlConfiguration conf = new YamlConfiguration();
        if (text == null) {
            return conf;
        }
        try {
            conf.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException("插件内置的语言文件无法解析", e);
        }
        return conf;
    }

    private static List<String> jarLangFiles() {
        List<String> result = new ArrayList<>();
        String prefix = folder + "/";
        try (JarFile jar = new JarFile(Keystone.jarFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(prefix)
                        && name.endsWith(".yml")
                        && name.indexOf('/', prefix.length()) < 0) {
                    result.add(name);
                }
            }
        } catch (IOException e) {
            return result;
        }
        Collections.sort(result);
        return result;
    }

    private static Map<String, LangNode> readNodes(ConfigurationSection section) {
        Map<String, LangNode> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (key.equals("___version___")) {
                continue;
            }
            Object value = section.get(key);
            if (value instanceof String text) {
                result.put(key, new LangNode.Text(text.isEmpty() ? null : text));
            } else if (value instanceof List<?> list) {
                List<LangNode.Text> lines = new ArrayList<>();
                for (Object item : list) {
                    String line = item == null ? null : item.toString();
                    lines.add(new LangNode.Text(line == null || line.isEmpty() ? null : line));
                }
                result.put(key, new LangNode.Lines(lines));
            } else if (value instanceof Number || value instanceof Boolean) {
                result.put(key, new LangNode.Text(value.toString()));
            }
        }
        return result;
    }
}
