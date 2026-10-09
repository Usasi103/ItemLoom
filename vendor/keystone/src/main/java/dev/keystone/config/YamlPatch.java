package dev.keystone.config;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.nodes.CollectionNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * The upgrade merge done as edits to the user's text, so everything the merge does not change
 * stays byte for byte: list indentation, inline comment padding, quoting, blank lines, line
 * endings.
 *
 * <p>The only edits are the ones the policy asks for: missing keys are inserted (copied from the
 * jar default, re-indented to the surrounding section, placed after their default predecessor with
 * {@code reorder} or at the end of the section without), the jar's non-empty comments replace the
 * user's with {@code updateComments} (the comment lines above a key and the comment on its line,
 * keeping the user's padding before {@code #}), and removed keys are cut with {@code
 * deleteRemovedNodes}. Existing keys are never moved. With no edit the text is returned unchanged.
 *
 * <p>An inserted key brings what belongs to it in the jar: the comment lines above it, the comment
 * on its line together with the continuation lines SnakeYAML (and sparrow) attach to it (comment
 * lines whose {@code #} is in the same column), and the blank lines that separate it from what is
 * above (topped up to the jar's count, the user's own blank lines counted in). A key that follows a
 * section goes below the blank lines ending that section when they are not also the end of the
 * enclosing section, so the user's section breaks stay where they were; what it then lands on gets
 * the jar's separation back.
 *
 * <p>Line breaks: every line the patch does not touch keeps its own break, so a file that mixes
 * CRLF and LF keeps both. An inserted line takes the break of the line above the insertion point
 * (the line below when it goes first in the file); a replaced line keeps the break of the line it
 * replaces, and lines a replacement adds take the break of the one above them. A file that ended
 * without a line break still does. See {@link LineBreaks}.
 *
 * <p>Positions come from SnakeYAML's composer (the parser Bukkit itself uses). What this cannot
 * edit in place only matters once there is something to edit: the merge still reads such a text, a
 * text that needs no edit comes back unchanged, and one that does throws {@link Unsupported} so the
 * caller rewrites the document. That is a file with a repeated key, a flow-style file, a new key
 * for a flow-style section, and an edit that would reach into what an anchor shares: a key added
 * to, re-commented in or removed from a mapping that is anchored ({@code &x}, which every {@code
 * *x} shows too), lies inside an anchored node or holds a merge key ({@code <<}); removing an entry
 * that holds an anchor, an alias or a merge key; adding a key whose jar text holds one. Anchors,
 * aliases, merge keys and flow mappings anywhere else stay as written. Complex (non-scalar) keys
 * throw right away.
 */
final class YamlPatch {

    /** The text cannot be patched in place; rewrite it instead. */
    static final class Unsupported extends Exception {
        Unsupported(String reason) {
            super(reason, null, false, false);
        }
    }

    /** One block-mapping entry: its key and value nodes and the lines it covers. */
    private record Entry(String key, ScalarNode keyNode, Node value, int keyLine, int keyColumn) {}

    /** Replace lines [from, to) with {@code lines}; an insertion has from == to. */
    private record Edit(int from, int to, List<String> lines, int order) {}

    /**
     * A new key's jar lines, to go before line {@code at}. The blank lines around it are settled
     * in {@link #apply}, where every insertion at the same line is known.
     *
     * @param blanksBefore blank lines above the key's first line in the jar
     * @param blanksAfter blank lines below its last line in the jar (0 at the end of the jar)
     * @param belowBlankRun {@code at} was moved below the user's blank lines after a section
     */
    private record Insert(
            int at,
            List<String> lines,
            int blanksBefore,
            int blanksAfter,
            boolean belowBlankRun,
            int order) {}

    /** Where an insertion goes; see {@link #insertionPoint}. */
    private record Point(int line, boolean belowBlankRun) {}

    private final List<String> local;

    /** The break after each line of {@link #local}, kept in step with it; see {@link LineBreaks}. */
    private final List<String> breaks;

    private final List<String> defaults;
    private final UpgradePolicy policy;
    private final UpgradeReport report;
    private final List<Edit> edits = new ArrayList<>();
    private final List<Insert> inserts = new ArrayList<>();
    private int order;

    /** Why the texts cannot be edited in place at all, or null; see {@link #editable}. */
    private final String problem;

    /** Local nodes no edit may reach into; see {@link #shared(Node)}. */
    private final Set<Node> shared;

    /** (local, default) mapping pairs already merged / cleaned, so aliases cannot loop. */
    private final Map<Node, Set<Node>> merged = new IdentityHashMap<>();

    private final Map<Node, Set<Node>> cleaned = new IdentityHashMap<>();

    private YamlPatch(
            List<String> local,
            List<String> breaks,
            List<String> defaults,
            UpgradePolicy policy,
            UpgradeReport report,
            String problem,
            Set<Node> shared) {
        this.local = local;
        this.breaks = breaks;
        this.defaults = defaults;
        this.policy = policy;
        this.report = report;
        this.problem = problem;
        this.shared = shared;
    }

    /**
     * {@code localText} (no version header) merged with {@code defaultText}; fills {@code report}.
     */
    static String patch(
            String localText, String defaultText, UpgradePolicy policy, UpgradeReport report)
            throws Unsupported {
        String bom = localText.startsWith("﻿") ? "﻿" : "";
        String body = localText.substring(bom.length());
        LineBreaks.Split split = LineBreaks.split(body);
        Node localRoot = compose(body);
        Node defaultRoot = compose(defaultText);
        if (!(defaultRoot instanceof MappingNode defaultMapping)) {
            throw new Unsupported("default is not a mapping");
        }
        String defaultProblem = repeatedKey(defaultRoot, identitySet());
        List<String> defaultLines = defaultText.lines().toList();
        if (localRoot == null) {
            String copyProblem = problem(defaultRoot, identitySet());
            if (copyProblem != null) {
                throw new Unsupported(copyProblem);
            }
            // Only comments or nothing: the whole default goes below them.
            for (Entry entry : entries(defaultMapping)) {
                report.added.add(entry.key());
            }
            String eol = LineBreaks.neighbour(split.breaks(), split.lines().size());
            String separator = body.isEmpty() || split.endsWithBreak() ? "" : eol;
            return bom + body + separator + String.join(eol, defaultLines) + eol;
        }
        if (!(localRoot instanceof MappingNode localMapping)) {
            throw new Unsupported("file is not a mapping");
        }
        String localProblem = repeatedKey(localRoot, identitySet());
        if (localMapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            localProblem = "file is a flow mapping";
        }
        List<String> lines = new ArrayList<>(split.lines());
        List<String> breaks = new ArrayList<>(split.breaks());
        YamlPatch patch =
                new YamlPatch(
                        lines,
                        breaks,
                        defaultLines,
                        policy,
                        report,
                        first(localProblem, defaultProblem),
                        shared(localRoot));
        patch.merge(localMapping, defaultMapping, "");
        if (policy.deleteRemovedNodes()) {
            patch.clean(localMapping, defaultMapping, "");
        }
        if (patch.edits.isEmpty() && patch.inserts.isEmpty()) {
            return localText;
        }
        patch.apply();
        patch.settleFinalBreak(split.endsWithBreak());
        StringBuilder text = new StringBuilder(bom);
        for (int i = 0; i < lines.size(); i++) {
            text.append(lines.get(i)).append(breaks.get(i));
        }
        return text.toString();
    }

    private static Node compose(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        options.setAllowDuplicateKeys(true);
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        options.setCodePointLimit(Integer.MAX_VALUE);
        return new Yaml(options).compose(new StringReader(text));
    }

    /**
     * The first reason {@code node} cannot be edited in place - an anchor, an alias, a merge key or
     * a repeated key - or null. Throws for a non-scalar key, which the merge cannot even read.
     */
    private static String problem(Node node, Set<Node> seen) throws Unsupported {
        int line = node.getStartMark().getLine() + 1;
        if (!seen.add(node)) {
            return "alias at line " + line;
        }
        String problem = node.getAnchor() == null ? null : "anchor at line " + line;
        if (node instanceof MappingNode mapping) {
            Set<String> keys = new HashSet<>();
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                    throw new Unsupported(
                            "complex key at line "
                                    + (tuple.getKeyNode().getStartMark().getLine() + 1));
                }
                if (problem == null && (key.getValue().equals("<<") || !keys.add(key.getValue()))) {
                    problem = "merge or repeated key at line " + (key.getStartMark().getLine() + 1);
                }
                problem = first(problem, problem(key, seen));
                problem = first(problem, problem(tuple.getValueNode(), seen));
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                problem = first(problem, problem(item, seen));
            }
        }
        return problem;
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
    }

    private static Set<Node> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * The first repeated key in {@code node}'s tree, or null; a merge key {@code <<} does not
     * count. Throws for a non-scalar key, which the merge cannot even read.
     */
    private static String repeatedKey(Node node, Set<Node> seen) throws Unsupported {
        if (!seen.add(node)) {
            return null;
        }
        String found = null;
        if (node instanceof MappingNode mapping) {
            Set<String> keys = new HashSet<>();
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                    throw new Unsupported(
                            "complex key at line "
                                    + (tuple.getKeyNode().getStartMark().getLine() + 1));
                }
                if (found == null && !key.getValue().equals("<<") && !keys.add(key.getValue())) {
                    found = "repeated key at line " + (key.getStartMark().getLine() + 1);
                }
                found = first(found, repeatedKey(tuple.getValueNode(), seen));
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                found = first(found, repeatedKey(item, seen));
            }
        }
        return found;
    }

    /**
     * The nodes of {@code root} that anchors share: every anchored node (an alias hands back that
     * same node) and every mapping with a merge key, together with everything below them.
     */
    private static Set<Node> shared(Node root) {
        Set<Node> result = identitySet();
        markShared(root, false, result, identitySet());
        return result;
    }

    private static void markShared(Node node, boolean inside, Set<Node> shared, Set<Node> seen) {
        boolean here = inside || node.getAnchor() != null || hasMergeKey(node);
        if (!(here ? shared.add(node) : seen.add(node))) {
            return;
        }
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                markShared(tuple.getKeyNode(), here, shared, seen);
                markShared(tuple.getValueNode(), here, shared, seen);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                markShared(item, here, shared, seen);
            }
        }
    }

    private static boolean hasMergeKey(Node node) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals("<<")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Called before recording an edit to {@code mapping}'s entries: a text with a {@link #problem},
     * or an edit to a mapping anchors share, is rewritten instead.
     */
    private void editable(MappingNode mapping) throws Unsupported {
        if (problem != null) {
            throw new Unsupported(problem);
        }
        if (shared.contains(mapping)) {
            throw new Unsupported(
                    "edit inside an anchored or merged mapping at line "
                            + (mapping.getStartMark().getLine() + 1));
        }
    }

    /** A new key is copied as jar text: that text must not define or use an anchor. */
    private static void copyable(Entry defaultEntry, String keyPath) throws Unsupported {
        Set<Node> seen = identitySet();
        String reason =
                first(problem(defaultEntry.keyNode(), seen), problem(defaultEntry.value(), seen));
        if (reason != null) {
            throw new Unsupported("new key " + keyPath + ": " + reason);
        }
    }

    /** A removed entry must not take an anchor, an alias or a merge key with it. */
    private static void removable(Entry localEntry, String keyPath) throws Unsupported {
        Set<Node> seen = identitySet();
        String reason =
                first(problem(localEntry.keyNode(), seen), problem(localEntry.value(), seen));
        if (reason != null) {
            throw new Unsupported("removed key " + keyPath + ": " + reason);
        }
    }

    /**
     * Whether the entry's value is an alias ({@code key: *x}): SnakeYAML hands back the anchored
     * node itself, which starts before the key.
     */
    private static boolean isAlias(Entry entry) {
        return entry.value().getStartMark().getIndex() < entry.keyNode().getStartMark().getIndex();
    }

    /** False when this pair of mappings was seen before (reached again through an alias). */
    private static boolean firstVisit(Map<Node, Set<Node>> seen, Node localNode, Node defaultNode) {
        return seen.computeIfAbsent(
                        localNode, node -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(defaultNode);
    }

    private static List<Entry> entries(MappingNode mapping) {
        List<Entry> result = new ArrayList<>();
        for (NodeTuple tuple : mapping.getValue()) {
            ScalarNode key = (ScalarNode) tuple.getKeyNode();
            result.add(
                    new Entry(
                            key.getValue(),
                            key,
                            tuple.getValueNode(),
                            key.getStartMark().getLine(),
                            key.getStartMark().getColumn()));
        }
        return result;
    }

    private static Map<String, Entry> byKey(List<Entry> entries) {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : entries) {
            result.put(entry.key(), entry);
        }
        return result;
    }

    private static String child(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private boolean isDynamic(String path) {
        for (String route : policy.dynamicRoutes()) {
            if (path.equals(route) || path.startsWith(route + ".")) {
                return true;
            }
        }
        return false;
    }

    // ---- merge ---------------------------------------------------------------------

    /** Same decisions as {@link YamlUpgrade}'s document merge, recorded as text edits. */
    private void merge(MappingNode localMapping, MappingNode defaultMapping, String path)
            throws Unsupported {
        if (!firstVisit(merged, localMapping, defaultMapping)) {
            return;
        }
        List<Entry> localEntries = entries(localMapping);
        Map<String, Entry> localByKey = byKey(localEntries);
        List<Entry> defaultEntries = entries(defaultMapping);
        int childColumn = localEntries.isEmpty() ? 0 : localEntries.get(0).keyColumn();
        Entry previous = null;
        for (Entry defaultEntry : defaultEntries) {
            String keyPath = child(path, defaultEntry.key());
            Entry localEntry = localByKey.get(defaultEntry.key());
            if (localEntry == null) {
                if (localMapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
                    throw new Unsupported("new key " + keyPath + " inside a flow mapping");
                }
                editable(localMapping);
                copyable(defaultEntry, keyPath);
                insert(
                        defaultEntry,
                        childColumn,
                        insertionPoint(localEntries, previous, path.isEmpty()));
                report.added.add(keyPath);
                continue;
            }
            previous = localEntry;
            if (isDynamic(keyPath)) {
                continue;
            }
            Node defaultValue = defaultEntry.value();
            Node localValue = localEntry.value();
            if (defaultValue instanceof MappingNode defaultSection
                    && localValue instanceof MappingNode localSection) {
                merge(localSection, defaultSection, keyPath);
            } else if (!sameShape(defaultValue, localValue)) {
                report.keptMismatched.add(keyPath);
            }
            if (policy.updateComments()) {
                replaceComments(defaultEntry, localEntry, localMapping);
            }
        }
    }

    private static boolean sameShape(Node a, Node b) {
        return (a instanceof SequenceNode && b instanceof SequenceNode)
                || (a instanceof ScalarNode && b instanceof ScalarNode);
    }

    /**
     * Where a new key goes: after its default predecessor with {@code reorder} (at the top of the
     * section when it has none), otherwise after the section's last key.
     *
     * <p>When that key holds a block section or list followed by blank lines, those lines end it.
     * Unless they also end the enclosing section (nothing but the parent's next key or the end of
     * the file follows), the new key goes below them: the user's break after the section stays in
     * place and the new key does not split it from the section. At the root the file's closing
     * comments count as a sibling, so a new root section goes between the break and them.
     */
    private Point insertionPoint(List<Entry> localEntries, Entry previous, boolean root) {
        if (localEntries.isEmpty()) {
            return new Point(local.size(), false);
        }
        Entry after = previous;
        if (!policy.reorder()) {
            after = localEntries.get(0);
            for (Entry entry : localEntries) {
                if (entry.keyLine() > after.keyLine()) {
                    after = entry;
                }
            }
        } else if (previous == null) {
            return new Point(leadStart(localEntries.get(0)), false);
        }
        int line = contentEnd(after) + 1;
        if (!isBlockCollection(after.value()) || isAlias(after)) {
            return new Point(line, false);
        }
        int next = line;
        while (next < local.size() && local.get(next).isBlank()) {
            next++;
        }
        if (next == line || next >= local.size()) {
            return new Point(line, false);
        }
        boolean sibling = false;
        for (Entry entry : localEntries) {
            sibling |= entry.keyLine() > after.keyLine();
        }
        return sibling || root ? new Point(next, true) : new Point(line, false);
    }

    private static boolean isBlockCollection(Node node) {
        return node instanceof CollectionNode<?> collection
                && collection.getFlowStyle() != DumperOptions.FlowStyle.FLOW;
    }

    /**
     * The default entry's lines (comments above, its trailing comment and continuation lines
     * included), re-indented to {@code column}; the jar's blank lines around it are recorded.
     */
    private void insert(Entry defaultEntry, int column, Point at) {
        int from = leadStartIn(defaultEntry);
        int to = contentEndIn(defaultEntry, defaults);
        List<String> block = new ArrayList<>();
        for (int line = from; line <= to; line++) {
            block.add(shift(defaults.get(line), column - defaultEntry.keyColumn()));
        }
        inserts.add(
                new Insert(
                        at.line(),
                        block,
                        blanksAbove(defaults, from),
                        blanksBelow(defaults, to + 1),
                        at.belowBlankRun(),
                        order++));
    }

    /** Blank lines directly above {@code line}. */
    private static int blanksAbove(List<String> lines, int line) {
        int n = 0;
        while (line - n - 1 >= 0 && lines.get(line - n - 1).isBlank()) {
            n++;
        }
        return n;
    }

    /** Blank lines from {@code line} down to the next content; 0 when they run to the end. */
    private static int blanksBelow(List<String> lines, int line) {
        int n = 0;
        while (line + n < lines.size() && lines.get(line + n).isBlank()) {
            n++;
        }
        return line + n >= lines.size() ? 0 : n;
    }

    private static String shift(String line, int by) {
        if (line.isBlank()) {
            return "";
        }
        if (by >= 0) {
            return " ".repeat(by) + line;
        }
        int spaces = 0;
        while (spaces < line.length() && spaces < -by && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return line.substring(spaces);
    }

    // ---- comments ------------------------------------------------------------------

    /** The jar's non-empty comments win (sparrow's {@code copyNonEmptyCommentsTo}). */
    private void replaceComments(Entry defaultEntry, Entry localEntry, MappingNode localMapping)
            throws Unsupported {
        List<CommentLine> defaultBlock = textComments(defaultEntry.keyNode().getBlockComments());
        if (!defaultBlock.isEmpty()) {
            List<CommentLine> localBlock = textComments(localEntry.keyNode().getBlockComments());
            if (!values(defaultBlock).equals(values(localBlock))) {
                int defaultFrom = defaultBlock.get(0).getStartMark().getLine();
                int defaultTo = defaultBlock.get(defaultBlock.size() - 1).getStartMark().getLine();
                List<String> replacement = new ArrayList<>();
                for (int line = defaultFrom; line <= defaultTo; line++) {
                    replacement.add(
                            shift(
                                    defaults.get(line),
                                    localEntry.keyColumn() - defaultEntry.keyColumn()));
                }
                int from =
                        localBlock.isEmpty()
                                ? localEntry.keyLine()
                                : localBlock.get(0).getStartMark().getLine();
                int to =
                        localBlock.isEmpty()
                                ? localEntry.keyLine()
                                : localBlock.get(localBlock.size() - 1).getStartMark().getLine()
                                        + 1;
                editable(localMapping);
                edits.add(new Edit(from, to, replacement, order++));
            }
        }
        CommentLine defaultInline = inline(defaultEntry);
        if (defaultInline == null) {
            return;
        }
        int line = inlineLine(localEntry);
        if (line < 0) {
            return;
        }
        CommentLine localInline = inline(localEntry);
        if (localInline != null && localInline.getValue().equals(defaultInline.getValue())) {
            return;
        }
        String text = local.get(line);
        String replaced;
        if (localInline != null && localInline.getStartMark().getLine() == line) {
            int at = charIndex(text, localInline.getStartMark().getColumn());
            replaced = text.substring(0, at) + "#" + defaultInline.getValue();
        } else {
            String defaultText = defaults.get(defaultInline.getStartMark().getLine());
            int hash = charIndex(defaultText, defaultInline.getStartMark().getColumn());
            int padding = 0;
            while (hash - padding - 1 >= 0 && defaultText.charAt(hash - padding - 1) == ' ') {
                padding++;
            }
            replaced =
                    text.stripTrailing()
                            + " ".repeat(Math.max(1, padding))
                            + "#"
                            + defaultInline.getValue();
        }
        editable(localMapping);
        edits.add(new Edit(line, line + 1, List.of(replaced), order++));
    }

    private static List<CommentLine> textComments(List<CommentLine> comments) {
        List<CommentLine> result = new ArrayList<>();
        if (comments != null) {
            for (CommentLine comment : comments) {
                if (comment.getCommentType() == CommentType.BLOCK) {
                    result.add(comment);
                }
            }
        }
        return result;
    }

    private static List<String> values(List<CommentLine> comments) {
        List<String> result = new ArrayList<>();
        for (CommentLine comment : comments) {
            result.add(comment.getValue());
        }
        return result;
    }

    /** The comment on the key's line: after a one-line scalar value, else after the key. */
    private static CommentLine inline(Entry entry) {
        List<CommentLine> comments =
                entry.value() instanceof ScalarNode scalar && oneLine(scalar, entry.keyLine())
                        ? scalar.getInLineComments()
                        : entry.keyNode().getInLineComments();
        if (comments == null) {
            return null;
        }
        for (CommentLine comment : comments) {
            if (comment.getCommentType() == CommentType.IN_LINE
                    && comment.getStartMark().getLine() == entry.keyLine()) {
                return comment;
            }
        }
        return null;
    }

    /** The line an inline comment may be edited on, or -1 for values that span lines. */
    private static int inlineLine(Entry entry) {
        if (entry.value() instanceof ScalarNode scalar && !oneLine(scalar, entry.keyLine())) {
            return -1;
        }
        return entry.keyLine();
    }

    private static boolean oneLine(ScalarNode scalar, int line) {
        return scalar.getStartMark().getLine() == line
                && scalar.getEndMark().getLine() == line
                && scalar.getScalarStyle() != DumperOptions.ScalarStyle.LITERAL
                && scalar.getScalarStyle() != DumperOptions.ScalarStyle.FOLDED;
    }

    /** SnakeYAML columns count code points. */
    private static int charIndex(String text, int codePoints) {
        return text.offsetByCodePoints(
                0, Math.min(codePoints, text.codePointCount(0, text.length())));
    }

    // ---- removing ------------------------------------------------------------------

    private void clean(MappingNode localMapping, MappingNode defaultMapping, String path)
            throws Unsupported {
        if (!firstVisit(cleaned, localMapping, defaultMapping)) {
            return;
        }
        Map<String, Entry> defaultByKey = byKey(entries(defaultMapping));
        for (Entry localEntry : entries(localMapping)) {
            String keyPath = child(path, localEntry.key());
            if (isDynamic(keyPath)) {
                continue;
            }
            Entry defaultEntry = defaultByKey.get(localEntry.key());
            if (defaultEntry == null) {
                editable(localMapping);
                removable(localEntry, keyPath);
                List<CommentLine> comments = textComments(localEntry.keyNode().getBlockComments());
                int from =
                        comments.isEmpty()
                                ? localEntry.keyLine()
                                : comments.get(0).getStartMark().getLine();
                edits.add(new Edit(from, contentEnd(localEntry) + 1, List.of(), order++));
                report.removed.add(keyPath);
            } else if (localEntry.value() instanceof MappingNode localSection
                    && defaultEntry.value() instanceof MappingNode defaultSection) {
                clean(localSection, defaultSection, keyPath);
            }
        }
    }

    // ---- line ranges ---------------------------------------------------------------

    private static List<CommentLine> blockComments(Entry entry) {
        List<CommentLine> comments = entry.keyNode().getBlockComments();
        return comments == null ? List.of() : comments;
    }

    /** First line of the comments and blank lines SnakeYAML attaches above the key. */
    private int leadStart(Entry entry) {
        List<CommentLine> comments = blockComments(entry);
        return comments.isEmpty() ? entry.keyLine() : comments.get(0).getStartMark().getLine();
    }

    /** Like {@link #leadStart}, in the default text, from its first text comment. */
    private int leadStartIn(Entry entry) {
        List<CommentLine> comments = textComments(entry.keyNode().getBlockComments());
        return comments.isEmpty() ? entry.keyLine() : comments.get(0).getStartMark().getLine();
    }

    private int contentEnd(Entry entry) {
        return contentEndIn(entry, local);
    }

    /**
     * Last line holding the entry's own content: its value, the comment on its line and the
     * continuation lines of that comment. Comment lines SnakeYAML gives to the next key (those
     * whose {@code #} is not in the trailing comment's column) are not part of it.
     */
    private static int contentEndIn(Entry entry, List<String> lines) {
        int last = Math.max(entry.keyLine(), lastLine(entry.value(), entry.keyLine(), lines));
        return Math.max(last, Math.min(commentEnd(entry.keyNode()), lines.size() - 1));
    }

    /**
     * Last line of the trailing comments SnakeYAML attached to {@code node}: the comment on its
     * line and the lines below that continue it, or -1.
     */
    private static int commentEnd(Node node) {
        int last = -1;
        List<CommentLine> comments = node.getInLineComments();
        if (comments != null) {
            for (CommentLine comment : comments) {
                if (comment.getCommentType() == CommentType.IN_LINE) {
                    last = Math.max(last, comment.getStartMark().getLine());
                }
            }
        }
        return last;
    }

    /** Last line of {@code node}, trailing comments and their continuation lines included. */
    private static int lastLine(Node node, int floor, List<String> lines) {
        int last = Math.max(floor, Math.min(commentEnd(node), lines.size() - 1));
        if (node instanceof MappingNode mapping
                && mapping.getFlowStyle() != DumperOptions.FlowStyle.FLOW) {
            for (NodeTuple tuple : mapping.getValue()) {
                last = Math.max(last, tuple.getKeyNode().getStartMark().getLine());
                last = Math.max(last, Math.min(commentEnd(tuple.getKeyNode()), lines.size() - 1));
                last = Math.max(last, lastLine(tuple.getValueNode(), last, lines));
            }
            return last;
        }
        if (node instanceof SequenceNode sequence
                && sequence.getFlowStyle() != DumperOptions.FlowStyle.FLOW) {
            for (Node item : sequence.getValue()) {
                last = Math.max(last, lastLine(item, floor, lines));
            }
            return last;
        }
        if (node instanceof ScalarNode scalar
                && scalar.getValue().isEmpty()
                && scalar.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN) {
            // "key:" with nothing after it; its marks can point at the next line.
            return last;
        }
        int start = node.getStartMark().getLine();
        int end = node.getEndMark().getLine();
        if (node.getEndMark().getColumn() == 0 && end > start) {
            end--;
        }
        while (end > Math.max(start, floor) && end < lines.size() && lines.get(end).isBlank()) {
            end--;
        }
        return Math.max(last, Math.min(end, lines.size() - 1));
    }

    // ---- applying ------------------------------------------------------------------

    /**
     * Turns the insertions at each line into one block, then applies every edit bottom-up, so
     * earlier line numbers stay valid; at one line, replacements go first.
     */
    private void apply() {
        Map<Integer, List<Insert>> byLine = new LinkedHashMap<>();
        for (Insert insert : inserts) {
            byLine.computeIfAbsent(insert.at(), line -> new ArrayList<>()).add(insert);
        }
        byLine = withoutRepeatedComments(byLine);
        for (Map.Entry<Integer, List<Insert>> group : byLine.entrySet()) {
            edits.add(block(group.getKey(), group.getValue()));
        }
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(
                (a, b) -> {
                    if (a.from() != b.from()) {
                        return Integer.compare(b.from(), a.from());
                    }
                    boolean aInsert = a.from() == a.to();
                    boolean bInsert = b.from() == b.to();
                    if (aInsert != bInsert) {
                        return aInsert ? 1 : -1;
                    }
                    return Integer.compare(b.order(), a.order());
                });
        for (Edit edit : ordered) {
            List<String> lineBreaks = breaksFor(edit);
            List<String> range = local.subList(edit.from(), edit.to());
            range.clear();
            range.addAll(edit.lines());
            List<String> breakRange = breaks.subList(edit.from(), edit.to());
            breakRange.clear();
            breakRange.addAll(lineBreaks);
        }
    }

    /**
     * A new key's jar comment that the user's file already has right next to the insertion point
     * is not inserted a second time (Attribute: the user deleted {@code gui.size} but kept its
     * comment, which then sat on top of the next key's comments). Lines are compared trimmed.
     *
     * <ul>
     *   <li>The same lines directly below the insertion point are the removed key's own comment:
     *       the last key inserted there goes below them instead, without its jar comment and
     *       without blank lines between the comment and the key.
     *   <li>The same lines directly above it: the first key inserted there drops its jar comment
     *       and the blank lines that would separate it from them.
     * </ul>
     *
     * Only lines no other edit touches are reused; a comment that is being replaced anyway (the
     * next key's comments updated from the jar) keeps the plain insertion.
     */
    private Map<Integer, List<Insert>> withoutRepeatedComments(Map<Integer, List<Insert>> byLine) {
        // In line order: a key moved down joins the next group ahead of that group's own keys.
        Map<Integer, List<Insert>> sorted = new java.util.TreeMap<>(byLine);
        Map<Integer, List<Insert>> result = new java.util.TreeMap<>();
        for (Map.Entry<Integer, List<Insert>> group : sorted.entrySet()) {
            int at = group.getKey();
            List<Insert> list = new ArrayList<>(group.getValue());
            Insert last = list.get(list.size() - 1);
            int below = leadingComments(last.lines());
            if (below > 0
                    && at + below <= local.size()
                    && sameTrimmed(local.subList(at, at + below), last.lines().subList(0, below))
                    && untouched(at, at + below)) {
                list.remove(list.size() - 1);
                Insert moved =
                        new Insert(
                                at + below,
                                last.lines().subList(below, last.lines().size()),
                                0,
                                last.blanksAfter(),
                                last.belowBlankRun(),
                                last.order());
                result.computeIfAbsent(at + below, line -> new ArrayList<>()).add(moved);
            }
            if (!list.isEmpty() && !result.containsKey(at)) {
                Insert first = list.get(0);
                int above = leadingComments(first.lines());
                if (above > 0
                        && at - above >= 0
                        && sameTrimmed(
                                local.subList(at - above, at), first.lines().subList(0, above))
                        && untouched(at - above, at)) {
                    list.set(
                            0,
                            new Insert(
                                    at,
                                    first.lines().subList(above, first.lines().size()),
                                    0,
                                    first.blanksAfter(),
                                    first.belowBlankRun(),
                                    first.order()));
                }
            }
            if (!list.isEmpty()) {
                result.computeIfAbsent(at, line -> new ArrayList<>()).addAll(list);
            }
        }
        return result;
    }

    /** How many of {@code lines} are comment lines before the first other line. */
    private static int leadingComments(List<String> lines) {
        int n = 0;
        while (n < lines.size() - 1 && lines.get(n).trim().startsWith("#")) {
            n++;
        }
        return n;
    }

    private static boolean sameTrimmed(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).trim().equals(b.get(i).trim())) {
                return false;
            }
        }
        return true;
    }

    /** Whether no collected edit replaces any of the lines {@code from..to-1}. */
    private boolean untouched(int from, int to) {
        for (Edit edit : edits) {
            if (edit.from() < to && edit.to() > from && edit.from() != edit.to()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The breaks for an edit's lines, taken before it is applied. Edits go bottom-up, so the lines
     * above {@code from} are still the user's. A replaced line keeps the break of the line it
     * replaces; any further line takes the break of the one above it; an insertion takes {@link
     * LineBreaks#neighbour}'s.
     */
    private List<String> breaksFor(Edit edit) {
        int replaced = edit.to() - edit.from();
        List<String> result = new ArrayList<>(edit.lines().size());
        for (int i = 0; i < edit.lines().size(); i++) {
            if (i < replaced) {
                result.add(breaks.get(edit.from() + i));
            } else if (i > 0) {
                result.add(result.get(i - 1));
            } else {
                result.add(LineBreaks.neighbour(breaks, edit.from()));
            }
        }
        return result;
    }

    /**
     * After the edits: the last line ends with a break exactly when the file did, and a line that
     * had none (the old last line, now followed by inserted lines) gets the break of the nearest
     * line above it.
     */
    private void settleFinalBreak(boolean finalBreak) {
        for (int i = 0; i < breaks.size(); i++) {
            if (i == breaks.size() - 1 && !finalBreak) {
                breaks.set(i, "");
            } else if (breaks.get(i).isEmpty()) {
                breaks.set(i, LineBreaks.neighbour(breaks, i));
            }
        }
    }

    /**
     * The insertions at one line, in merge order, each topped up to the jar's blank lines above it
     * (the blank lines already there count). A block that went below the user's blank lines gets
     * the jar's separation from what follows it, which lost its own.
     */
    private Edit block(int at, List<Insert> group) {
        List<String> lines = new ArrayList<>();
        int blanks = blanksAbove(local, at);
        for (Insert insert : group) {
            for (int i = blanks; i < insert.blanksBefore(); i++) {
                lines.add("");
            }
            lines.addAll(insert.lines());
            blanks = 0;
        }
        Insert last = group.get(group.size() - 1);
        if (last.belowBlankRun() && at < local.size()) {
            for (int i = 0; i < last.blanksAfter(); i++) {
                lines.add("");
            }
        }
        return new Edit(at, at, lines, group.get(0).order());
    }
}
