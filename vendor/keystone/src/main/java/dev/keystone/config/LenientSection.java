package dev.keystone.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/**
 * A read-only view of a Bukkit {@link ConfigurationSection} that reads values the way TabooLib
 * 6.3's {@code Configuration} ({@code ConfigSection} + {@code Coerce}) did, for plugins whose
 * files were written against those rules.
 *
 * <p>The tree is Bukkit's own: TabooLib's YAML loader is a copy of Bukkit's (the same SnakeYAML
 * constructor and the same walk with {@code .} as the path separator), so keys, sections and
 * scalar types are the same - {@code 5} is an Integer, {@code 5.0} a Double, unquoted {@code yes}
 * / {@code on} are already Booleans, an unquoted {@code ~} or an empty value leaves no key at all.
 * What differs is how the getters read those values:
 *
 * <ul>
 *   <li><b>Unwrapping</b> (every read, TabooLib's {@code ConfigSection.unwrap}): the strings {@code
 *       "~"} and {@code "null"} (that is, quoted in the file) read as null, so the key counts as
 *       missing for every getter with a default; the strings {@code "''"} and {@code "\"\""} read
 *       as an empty string; every {@code \}{@code uXXXX} in a string is decoded into that
 *       character, at read time, in list items and map values too. The Bukkit tree keeps the text
 *       as written ({@link #raw()} still returns it). A {@code \}{@code u} not followed by four hex
 *       digits is kept as written, where TabooLib threw.
 *   <li><b>Numbers</b> ({@code Coerce.toInteger/toLong/toDouble}): a number is converted ({@code
 *       intValue()} etc., so a Long out of int range wraps and 3.7 reads as 3); anything else is
 *       parsed from its text after TabooLib's clean-up: trimmed; one pair of matching brackets
 *       {@code ()}, {@code []} or {@code {}} stripped; with a {@code .} before a {@code ,}, only
 *       the part before that comma is read; a {@code -} after the first character reads as 0
 *       ({@code "1-2"}, and also {@code "1e-5"}); commas removed; only the part before the first
 *       space read ({@code "12 apples"} is 12, {@code "1,000"} is 1000, a list {@code [4, 5]} is
 *       4). {@code getInt} parses an int ({@code -} and ASCII digits), else a double and truncates
 *       it (saturating, so {@code "99999999999"} is {@link Integer#MAX_VALUE}); {@code getLong}
 *       uses {@link Long#parseLong} only, so {@code "3.7"} reads as 0; {@code getDouble} accepts
 *       Java's floating-point syntax ({@code "1e3"}, {@code "+5"}, {@code "NaN"}). Text that does
 *       not parse, and a section, read as 0 - <b>even when a default is given</b>: the default is
 *       only used for a missing (or {@code "~"}) value.
 *   <li><b>Booleans</b> ({@code Coerce.toBoolean}): a Boolean as is; anything else is true when
 *       its trimmed text is {@code 1}, {@code true} or {@code yes} (case-insensitive) and false
 *       otherwise - {@code 'on'} quoted, {@code 2} and {@code 1.0} are false. A present value never
 *       falls back to the default.
 *   <li><b>Strings</b>: any value's text ({@code 5} reads as {@code "5"}); a list reads as its
 *       items joined with {@code \n} (a null item as {@code "null"}).
 *   <li><b>String lists</b>: a list reads item by item ({@code String.valueOf}, so a null or
 *       {@code "~"} item reads as {@code "null"} and a map item as its {@code toString}); any other
 *       value reads as its text split into lines at {@code \r\n}, {@code \n} or {@code \r}
 *       (Kotlin's {@code lines()}, which keeps a trailing empty line). Missing reads as an empty
 *       list. As in TabooLib's {@code getList}, the items of {@link #getList(String)}, {@link
 *       #getStringList} and {@link #getMapList} are unwrapped a second time, which only matters for
 *       text that decodes into another escape or into {@code "~"}.
 *   <li><b>Keys</b>: {@link #getKeys getKeys(true)} lists leaf paths only; sections themselves,
 *       and empty sections, are not listed.
 * </ul>
 *
 * <p>Where the plain Bukkit getters differ: {@code getInt/getLong/getDouble} only accept numbers
 * and return the default for {@code "5"}; {@code getBoolean} only accepts Booleans; {@code
 * getString} returns {@code "[a, b]"} for a list and does not treat {@code "~"} as missing nor
 * decode escapes; {@code getStringList} skips null and map items and returns an empty list for a
 * single value; {@code getKeys(true)} includes section paths.
 *
 * <p>Deliberate differences from TabooLib, for values that only a mistake produces: a section read
 * with {@link #getString} (and {@link #getStringList}) is treated as missing, where TabooLib
 * returned the section re-serialised as YAML text; a malformed {@code \}{@code u} escape is kept,
 * where TabooLib threw.
 *
 * <p>The view reads the wrapped section live: wrapping a {@link ConfigFile} sees the values of the
 * latest (re)load, while a view of a sub-section obtained before a reload keeps the old values.
 * Configuration defaults set through Bukkit's {@code addDefault} are seen as Bukkit's own {@code
 * get} sees them. Values returned (lists, maps) are copies.
 */
public final class LenientSection {

    /** {@code Coerce.listPattern}: an optional opening and closing bracket around the text. */
    private static final Pattern LIST_PATTERN = Pattern.compile("^([(\\[{]?)(.+?)([)\\]}]?)$");

    private static final String OPENERS = "([{";
    private static final String CLOSERS = ")]}";

    /** Guava's {@code Ints.tryParse}: an optional minus sign and ASCII digits. */
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");

    /** Guava's {@code Doubles.tryParse} pattern: Java's floating-point literal syntax. */
    private static final Pattern FLOATING_POINT = floatingPoint();

    /** Kotlin's {@code lines()} separators. */
    private static final Pattern LINES = Pattern.compile("\r\n|\n|\r");

    private final ConfigurationSection section;

    private LenientSection(ConfigurationSection section) {
        this.section = section;
    }

    /** A view of {@code section}; null for null, so a missing section can be passed as is. */
    public static LenientSection of(ConfigurationSection section) {
        return section == null ? null : new LenientSection(section);
    }

    /**
     * A view of a map, such as an item of {@link #getMapList} ({@code Configuration.fromMap}):
     * nested maps become sections, keys are read with {@link String#valueOf}. A null map gives an
     * empty view.
     */
    public static LenientSection fromMap(Map<?, ?> map) {
        MemoryConfiguration config = new MemoryConfiguration();
        if (map != null) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (entry.getValue() instanceof Map<?, ?> nested) {
                    config.createSection(key, nested);
                } else {
                    config.set(key, entry.getValue());
                }
            }
        }
        return new LenientSection(config);
    }

    /** The wrapped Bukkit section, for anything this view does not cover. */
    public ConfigurationSection raw() {
        return section;
    }

    /**
     * The keys of this section; with {@code deep}, the paths of every non-section value below it
     * (TabooLib: sections are descended into, not listed).
     */
    public Set<String> getKeys(boolean deep) {
        if (!deep) {
            return section.getKeys(false);
        }
        Set<String> keys = new LinkedHashSet<>();
        collectLeaves(section, "", separator(), keys);
        return keys;
    }

    private static void collectLeaves(
            ConfigurationSection from, String prefix, char separator, Set<String> keys) {
        for (String key : from.getKeys(false)) {
            if (from.get(key) instanceof ConfigurationSection child) {
                collectLeaves(child, prefix + key + separator, separator, keys);
            } else {
                keys.add(prefix + key);
            }
        }
    }

    private char separator() {
        Configuration root = section.getRoot();
        return root == null ? '.' : root.options().pathSeparator();
    }

    /** Whether the path holds a value, {@code "~"} included (Bukkit's {@code contains}). */
    public boolean contains(String path) {
        return section.contains(path);
    }

    /** Bukkit's {@code isSet}; TabooLib made no difference between this and {@link #contains}. */
    public boolean isSet(String path) {
        return section.isSet(path);
    }

    /**
     * The unwrapped value: null when missing or {@code "~"}, a {@link LenientSection} for a
     * section, a copy for a list or map, a decoded string, otherwise the value itself.
     */
    public Object get(String path) {
        return get(path, null);
    }

    /** {@link #get(String)}, with {@code def} (unwrapped the same way) when the key is missing. */
    public Object get(String path, Object def) {
        Object value = section.get(path);
        if (value == null) {
            value = def;
        }
        if (value instanceof ConfigurationSection child) {
            return new LenientSection(child);
        }
        return unwrap(value);
    }

    /** The section at {@code path}, or null when it is missing or not a section. */
    public LenientSection getSection(String path) {
        return section.get(path) instanceof ConfigurationSection child
                ? new LenientSection(child)
                : null;
    }

    /** The value's text, a list's items joined with {@code \n}; null when missing or a section. */
    public String getString(String path) {
        Object value = get(path);
        if (value == null || value instanceof LenientSection) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<String> lines = new ArrayList<>(list.size());
            for (Object item : list) {
                lines.add(String.valueOf(item));
            }
            return String.join("\n", lines);
        }
        return value.toString();
    }

    /** {@link #getString(String)}, or {@code def} when that is null. */
    public String getString(String path, String def) {
        String value = getString(path);
        return value == null ? def : value;
    }

    /** The value as an int; 0 when missing or unparseable. */
    public int getInt(String path) {
        return toInt(get(path));
    }

    /** The value as an int; {@code def} only when missing, 0 when present but unparseable. */
    public int getInt(String path, int def) {
        Object value = get(path);
        return value == null ? def : toInt(value);
    }

    /** The value as a long; 0 when missing or unparseable. */
    public long getLong(String path) {
        return toLong(get(path));
    }

    /** The value as a long; {@code def} only when missing, 0 when present but unparseable. */
    public long getLong(String path, long def) {
        Object value = get(path);
        return value == null ? def : toLong(value);
    }

    /** The value as a double; 0 when missing or unparseable. */
    public double getDouble(String path) {
        return toDouble(get(path));
    }

    /** The value as a double; {@code def} only when missing, 0 when present but unparseable. */
    public double getDouble(String path, double def) {
        Object value = get(path);
        return value == null ? def : toDouble(value);
    }

    /** The value as a boolean; false when missing. */
    public boolean getBoolean(String path) {
        return toBoolean(get(path));
    }

    /** The value as a boolean; {@code def} only when missing. */
    public boolean getBoolean(String path, boolean def) {
        Object value = get(path);
        return value == null ? def : toBoolean(value);
    }

    /** The list at {@code path} with its items unwrapped (again), or null when not a list. */
    public List<?> getList(String path) {
        if (!(get(path) instanceof List<?> list)) {
            return null;
        }
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(unwrap(item));
        }
        return result;
    }

    /** The list at {@code path} (unwrapped once, as TabooLib does here), or {@code def}. */
    public List<?> getList(String path, List<?> def) {
        return get(path) instanceof List<?> list ? list : def;
    }

    /**
     * A list's items as strings, or another value's lines; empty when missing or a section. See
     * the class notes for null and map items.
     */
    public List<String> getStringList(String path) {
        return getStringList(path, new ArrayList<>());
    }

    /** {@link #getStringList(String)}, or {@code def} when the value is missing or a section. */
    public List<String> getStringList(String path, List<String> def) {
        Object value = get(path);
        if (value == null || value instanceof LenientSection) {
            return def;
        }
        List<String> result = new ArrayList<>();
        if (value instanceof List<?>) {
            for (Object item : getList(path)) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        result.addAll(Arrays.asList(LINES.split(value.toString(), -1)));
        return result;
    }

    /** The map items of the list at {@code path}; see {@link #fromMap} to read one. */
    public List<Map<?, ?>> getMapList(String path) {
        List<Map<?, ?>> result = new ArrayList<>();
        List<?> list = getList(path);
        if (list != null) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(map);
                }
            }
        }
        return result;
    }

    // ---- TabooLib ConfigSection.unwrap -----------------------------------------------

    static Object unwrap(Object value) {
        if ("~".equals(value) || "null".equals(value)) {
            return null;
        }
        if ("''".equals(value) || "\"\"".equals(value)) {
            return "";
        }
        if (value instanceof ConfigurationSection child) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : child.getValues(false).entrySet()) {
                map.put(entry.getKey(), unwrap(entry.getValue()));
            }
            return map;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> list = new ArrayList<>(collection.size());
            for (Object item : collection) {
                list.add(unwrap(item));
            }
            return list;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(entry.getKey(), unwrap(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof String text) {
            return decodeUnicode(text);
        }
        return value;
    }

    /**
     * TabooLib's {@code String.decodeUnicode()}: each {@code \}{@code u} followed by four hex
     * digits becomes that char; a backslash does not escape another. A malformed escape is kept.
     */
    static String decodeUnicode(String text) {
        int first = text.indexOf("\\u");
        if (first < 0) {
            return text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        builder.append(text, 0, first);
        int i = first;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\'
                    && i + 1 < text.length()
                    && text.charAt(i + 1) == 'u'
                    && i + 6 <= text.length()
                    && isHex(text, i + 2, i + 6)) {
                builder.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                i += 6;
            } else {
                builder.append(c);
                i++;
            }
        }
        return builder.toString();
    }

    private static boolean isHex(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    // ---- TabooLib Coerce ---------------------------------------------------------------

    static int toInt(Object value) {
        if (value == null || value instanceof LenientSection) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = sanitiseNumber(value);
        Integer parsed = tryParseInt(text);
        if (parsed != null) {
            return parsed;
        }
        Double decimal = tryParseDouble(text);
        return decimal == null ? 0 : decimal.intValue();
    }

    static long toLong(Object value) {
        if (value == null || value instanceof LenientSection) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(sanitiseNumber(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    static double toDouble(Object value) {
        if (value == null || value instanceof LenientSection) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        Double parsed = tryParseDouble(sanitiseNumber(value));
        return parsed == null ? 0.0 : parsed;
    }

    static boolean toBoolean(Object value) {
        if (value == null || value instanceof LenientSection) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim();
        return text.equals("1") || text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes");
    }

    /** {@code Coerce.sanitiseNumber}, step for step. */
    static String sanitiseNumber(Object value) {
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return "0";
        }
        Matcher candidate = LIST_PATTERN.matcher(text);
        if (candidate.matches()
                && OPENERS.indexOf(candidate.group(1)) == CLOSERS.indexOf(candidate.group(3))) {
            text = candidate.group(2).trim();
        }
        int decimal = text.indexOf('.');
        int comma = text.indexOf(',', decimal);
        if (decimal > -1 && comma > -1) {
            return sanitiseNumber(text.substring(0, comma));
        }
        if (text.indexOf('-', 1) != -1) {
            return "0";
        }
        return text.replace(",", "").split(" ")[0];
    }

    /** Guava's {@code Ints.tryParse(String)}. */
    static Integer tryParseInt(String text) {
        if (!INTEGER.matcher(text).matches()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Guava's {@code Doubles.tryParse(String)}. */
    static Double tryParseDouble(String text) {
        if (!FLOATING_POINT.matcher(text).matches()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Pattern floatingPoint() {
        String decimal = "(?:\\d++(?:\\.\\d*+)?|\\.\\d++)";
        String completeDec = decimal + "(?:[eE][+-]?\\d++)?[fFdD]?";
        String hex = "(?:[0-9a-fA-F]++(?:\\.[0-9a-fA-F]*+)?|\\.[0-9a-fA-F]++)";
        String completeHex = "0[xX]" + hex + "[pP][+-]?\\d++[fFdD]?";
        return Pattern.compile("[+-]?(?:NaN|Infinity|" + completeDec + "|" + completeHex + ")");
    }
}
