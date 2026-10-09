package dev.keystone.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.node.ScalarNode;
import net.momirealms.sparrow.yaml.node.SectionNode;
import net.momirealms.sparrow.yaml.node.SequenceNode;
import net.momirealms.sparrow.yaml.node.YamlNode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The CraftEngine-style version header and merge. The merge is our own (see {@link UpgradePolicy}
 * for how it differs from CraftEngine's pipeline) and is applied as edits to the user's text
 * ({@link YamlPatch}): a file that needs nothing but the header keeps every other byte, and a real
 * merge changes only the added keys, replaced comments and removed keys. sparrow-yaml's
 * comment-preserving document model is the fallback for text that cannot be patched in place, and
 * the check that a patch means the same as the document merge.
 */
final class YamlUpgrade {

    /** CraftEngine's version key, placed first in every managed file. */
    static final String VERSION_KEY = "___version___";

    private static final String VERSION_COMMENT = "# 此值由插件维护，用于配置自动升级，请勿修改";

    private static final Pattern VERSION_LINE =
            Pattern.compile(
                    "^" + Pattern.quote(VERSION_KEY) + ":.*(?:\\r\\n|\\r|\\n|$)",
                    Pattern.MULTILINE);
    private static final Pattern VERSION_COMMENT_LINE =
            Pattern.compile(
                    "^" + Pattern.quote(VERSION_COMMENT) + "(?:\\r\\n|\\r|\\n)", Pattern.MULTILINE);

    private static final Pattern BLANK_WITH_INDENT = Pattern.compile("(?m)^[ \\t]+$");

    private static volatile SparrowYaml yaml;

    private YamlUpgrade() {}

    static SparrowYaml yaml() {
        SparrowYaml current = yaml;
        if (current == null) {
            current =
                    SparrowYaml.builder()
                            .setAllowDuplicateKeys(true)
                            .setSplitLines(false)
                            .setWidth(4096)
                            .build();
            yaml = current;
        }
        return current;
    }

    /** Version of a default file: a short hash of its exact text. Any edit changes it. */
    static String versionOf(String defaultText) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(stripBom(defaultText).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The version stored in a user file, or null when it has none. */
    static String storedVersion(String text) {
        Matcher matcher = VERSION_LINE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group().substring(matcher.group().indexOf(':') + 1).trim();
        value = stripQuotes(value);
        return value.isEmpty() ? null : value;
    }

    private static String stripQuotes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == '\'' || value.charAt(start) == '"')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == '\'' || value.charAt(end - 1) == '"')) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * {@code text} without a leading byte-order mark. Windows editors save UTF-8 with one; the
     * version header must be the first line, and a mark after it breaks the YAML parser.
     */
    static String stripBom(String text) {
        return text != null && !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
    }

    /**
     * {@code text} with the version header replaced by one for {@code version}. The two header
     * lines end with the break most lines of the rest of the file end with ({@link
     * LineBreaks#dominant}: by line count, a tie goes to LF, then CRLF; a file without any line
     * break gets LF), so a CRLF file stays CRLF from its first line.
     */
    static String withVersion(String text, String version) {
        String body = withoutVersion(text);
        String eol = LineBreaks.dominant(body);
        return VERSION_COMMENT + eol + VERSION_KEY + ": '" + version + "'" + eol + body;
    }

    /** {@code text} without the version header. */
    static String withoutVersion(String text) {
        String body = VERSION_LINE.matcher(stripBom(text)).replaceAll("");
        // 0.3.1 wrote the header in front of a mark; removing the header must not leave it first.
        return stripBom(VERSION_COMMENT_LINE.matcher(body).replaceAll(""));
    }

    /** The merged text (without a version header) and what changed. */
    record Result(String text, UpgradeReport report) {}

    /**
     * Merges {@code defaultText} into {@code localText} under {@code policy}. Throws when either
     * text is not valid YAML.
     */
    static Result upgrade(String localText, String defaultText, UpgradePolicy policy)
            throws IOException {
        String body = withoutVersion(localText);
        UpgradeReport report = new UpgradeReport();
        String patched;
        try {
            patched = YamlPatch.patch(body, defaultText, policy, report);
        } catch (YamlPatch.Unsupported e) {
            return rewrite(body, defaultText, policy);
        }
        if (patched.equals(body)) {
            // Nothing to add, remove or re-comment: only the version header changes.
            return new Result(body, report);
        }
        Result rewritten;
        try {
            rewritten = rewrite(body, defaultText, policy);
        } catch (RuntimeException e) {
            // sparrow cannot load a file of nothing but comments ("tag:yaml.org,2002:comment").
            // With no keys of its own, the merge is exactly the defaults: check the patch by that.
            if (!hasKeys(body) && sameValues(patched, defaultText)) {
                return new Result(patched, report);
            }
            throw e;
        }
        if (!sameValues(patched, rewritten.text())) {
            // The in-place edit disagrees with the document merge: trust the document.
            return rewritten;
        }
        return new Result(patched, report);
    }

    /**
     * The document merge: load, merge, dump. Loses the user's list indentation and comment
     * padding, so it is only used when the text cannot be patched in place.
     */
    private static Result rewrite(String body, String defaultText, UpgradePolicy policy)
            throws IOException {
        YamlDocument local = yaml().load(body);
        YamlDocument defaults = yaml().load(defaultText);
        UpgradeReport report = new UpgradeReport();
        merge(local, defaults, "", policy, report);
        if (policy.deleteRemovedNodes()) {
            clean(local, defaults, "", policy, report);
        }
        return new Result(emptyBlankLines(local.dumpToString()), report);
    }

    /** sparrow writes blank lines inside sections as indentation; keep them truly empty. */
    static String emptyBlankLines(String text) {
        return BLANK_WITH_INDENT.matcher(text).replaceAll("");
    }

    /** Whether {@code text} holds any key at all (a file of comments or blank lines does not). */
    private static boolean hasKeys(String text) {
        try {
            YamlConfiguration conf = new YamlConfiguration();
            conf.loadFromString(text);
            return !conf.getKeys(false).isEmpty();
        } catch (InvalidConfigurationException e) {
            return true;
        }
    }

    /** Whether two texts hold the same leaf values (order and comments aside). */
    private static boolean sameValues(String a, String b) {
        try {
            return leaves(a).equals(leaves(b));
        } catch (InvalidConfigurationException e) {
            return false;
        }
    }

    private static Map<String, Object> leaves(String text) throws InvalidConfigurationException {
        YamlConfiguration conf = new YamlConfiguration();
        conf.loadFromString(text);
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : conf.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static boolean isDynamic(String path, UpgradePolicy policy) {
        for (String route : policy.dynamicRoutes()) {
            if (path.equals(route) || path.startsWith(route + ".")) {
                return true;
            }
        }
        return false;
    }

    private static String child(String path, Object key) {
        return path.isEmpty() ? String.valueOf(key) : path + "." + key;
    }

    private static void merge(
            SectionNode local,
            SectionNode defaults,
            String path,
            UpgradePolicy policy,
            UpgradeReport report) {
        List<Object> defaultKeys = new ArrayList<>(defaults.value().keySet());
        for (Object key : defaultKeys) {
            String keyPath = child(path, key);
            YamlNode<?> defaultNode = defaults.value().get(key);
            if (defaultNode == null) {
                continue;
            }
            YamlNode<?> localNode = local.value().get(key);
            if (localNode == null) {
                local.setSubNode(key, defaultNode);
                report.added.add(keyPath);
                continue;
            }
            if (isDynamic(keyPath, policy)) {
                continue;
            }
            if (defaultNode instanceof SectionNode defaultSection
                    && localNode instanceof SectionNode localSection) {
                merge(localSection, defaultSection, keyPath, policy, report);
            } else if (!sameShape(defaultNode, localNode)) {
                report.keptMismatched.add(keyPath);
            }
            if (policy.updateComments()) {
                defaultNode.copyNonEmptyCommentsTo(localNode);
            }
        }
        if (policy.reorder()) {
            List<Object> order = new ArrayList<>();
            for (Object key : defaultKeys) {
                if (local.value().containsKey(key)) {
                    order.add(key);
                }
            }
            for (Object key : local.value().keySet()) {
                if (!order.contains(key)) {
                    order.add(key);
                }
            }
            if (!order.equals(new ArrayList<>(local.value().keySet()))) {
                local.reorderKeys(order);
            }
        }
    }

    private static boolean sameShape(YamlNode<?> a, YamlNode<?> b) {
        return (a instanceof SequenceNode && b instanceof SequenceNode)
                || (a instanceof ScalarNode && b instanceof ScalarNode);
    }

    private static void clean(
            SectionNode local,
            SectionNode defaults,
            String path,
            UpgradePolicy policy,
            UpgradeReport report) {
        List<Object> remove = new ArrayList<>();
        for (Map.Entry<Object, YamlNode<?>> entry : local.value().entrySet()) {
            String keyPath = child(path, entry.getKey());
            if (isDynamic(keyPath, policy)) {
                continue;
            }
            YamlNode<?> defaultNode = defaults.value().get(entry.getKey());
            if (defaultNode == null) {
                remove.add(entry.getKey());
                report.removed.add(keyPath);
            } else if (entry.getValue() instanceof SectionNode localSection
                    && defaultNode instanceof SectionNode defaultSection) {
                clean(localSection, defaultSection, keyPath, policy, report);
            }
        }
        if (!remove.isEmpty()) {
            local.removeSubNodes(remove);
        }
    }
}
