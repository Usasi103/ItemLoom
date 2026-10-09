package dev.keystone.config;

import dev.keystone.storage.FileBackup;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * What went wrong the last time a file could not be used (0.3.5): which file, exactly where, why,
 * what the plugin did about it and where the untouched original is. Immutable.
 *
 * <p>{@link ConfigFile} makes one for every load that cannot use its file - settings, lang and
 * menu files (replaced by the jar default, or left as they are) and data files (left as they are,
 * writes blocked) - and hands it to {@link ConfigProblems}, which prints it to the console and
 * tells admins. Plugins that parse a file themselves build one with {@link #builder} and {@link
 * ConfigProblems#report report} it the same way.
 *
 * <p>Positions are 1-based and come from the parser's own marks ({@link MarkedYAMLException}), not
 * from the message text: {@link #problemLine}/{@link #problemColumn} where the parser gave up,
 * {@link #contextLine}/{@link #contextColumn} where the construct it was reading started (an
 * unclosed quote or bracket is only noticed further down, so both matter). {@link #line}/{@link
 * #column} - {@link #position()}, the chat title - is where an admin should look: the problem mark,
 * or the context mark when the problem mark is at the very end of the file (only blank text after
 * it: an unclosed quote or bracket runs to the end of the stream). -1 when unknown. For {@link
 * Kind#ENCODING} the column counts bytes within the line.
 *
 * @param path the file as the plugin names it, relative to its data folder ({@code config.yml},
 *     {@code lang/zh_CN.yml})
 * @param file the file on disk
 * @param kind why it could not be used
 * @param line where to look, 1-based, or -1: {@link #problemLine}, or {@link #contextLine} when the
 *     problem mark is at the end of the file
 * @param column column of {@link #line}, 1-based, or -1
 * @param contextLine where the construct being read started, 1-based, or -1
 * @param contextColumn column of {@link #contextLine}, 1-based, or -1
 * @param problemLine where the parser gave up, 1-based, or -1
 * @param problemColumn column of {@link #problemLine}, 1-based, or -1
 * @param cause one line, with the positions written in: {@code while parsing a flow sequence（第 11
 *     行，第 8 列）：expected ',' or ']', but got :（第 12 行，第 6 列）}
 * @param outcome what was done with the file
 * @param backup the verified {@code <name>_yyyyMMddHHmmss.bak} holding the original bytes, or null
 * @param backupCreated whether this load wrote {@link #backup}; false when an identical backup
 *     already existed (or there is none)
 * @param backupError why there is no backup, or null
 * @param note what is disabled and how to recover: the second console line and the chat notice
 * @param time when the problem was found
 */
public record LoadProblem(
        String path,
        File file,
        Kind kind,
        int line,
        int column,
        int contextLine,
        int contextColumn,
        int problemLine,
        int problemColumn,
        String cause,
        Outcome outcome,
        File backup,
        boolean backupCreated,
        String backupError,
        String note,
        Instant time) {

    /** Why a file could not be used. */
    public enum Kind {
        /** Not valid YAML (Bukkit's parser rejects it). */
        SYNTAX,
        /** Bytes that are not UTF-8 (a GBK re-save). */
        ENCODING,
        /** The bytes could not be read at all (an I/O error, a lock). */
        READ,
        /** Readable, but merging the new jar default into it failed. */
        UPGRADE,
        /** Valid YAML whose content the plugin cannot use (reported by the plugin). */
        CONTENT
    }

    /** What was done with the file. */
    public enum Outcome {
        /** Kept as {@link #backup()} and replaced by a fresh copy of the jar default. */
        REPLACED,
        /** Left as it is and not loaded (the plugin runs on its defaults or without it). */
        NOT_LOADED,
        /** Left as it is, not loaded, and the plugin will not write it until it loads cleanly. */
        WRITE_BLOCKED
    }

    public LoadProblem {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outcome, "outcome");
        cause = cause == null ? "" : cause;
        note = note == null ? "" : note;
        time = time == null ? Instant.now() : time;
    }

    public boolean replaced() {
        return outcome == Outcome.REPLACED;
    }

    public boolean writeBlocked() {
        return outcome == Outcome.WRITE_BLOCKED;
    }

    /** {@code 第 12 行，第 6 列} ({@code 第 3 行，第 5 字节} for encoding errors), or "". */
    public String position() {
        if (line <= 0) {
            return "";
        }
        if (column <= 0) {
            return "第 " + line + " 行";
        }
        return "第 " + line + " 行，第 " + column + (kind == Kind.ENCODING ? " 字节" : " 列");
    }

    /** {@link #backup} as an admin reads it ({@code plugins\Friend\config.yml_...bak}), or "". */
    public String backupDisplay() {
        return backup == null ? "" : FileBackup.display(backup);
    }

    /**
     * The three ERROR lines of the user-approved style (09-29): the cause with line and column,
     * what is disabled and how to recover, the verified backup.
     */
    public List<String> consoleLines() {
        return List.of(headline(), note, backupLine());
    }

    /** Line one: {@code config.yml 无法解析：<cause>}. */
    public String headline() {
        return path + " " + verb() + "：" + cause;
    }

    /** Line three: where the original bytes are, read back and compared, or why there is no copy. */
    public String backupLine() {
        if (backup != null) {
            return backupCreated
                    ? "原文件已备份为 " + backupDisplay() + "（已读回核对，与原文件逐字节相同）。"
                    : "原文件与已有的备份 " + backupDisplay() + " 逐字节相同，不再重复备份。";
        }
        if (kind == Kind.READ) {
            return "原文件读不出来，没能备份，原文件保持不动。";
        }
        return "原文件没能备份（" + (backupError == null ? "原因不明" : backupError) + "），原文件保持不动。";
    }

    /** A short phrase for the outcome: {@code 已换成插件内置的默认内容} ... */
    public String outcomeText() {
        return switch (outcome) {
            case REPLACED -> "已换成插件内置的默认内容";
            case NOT_LOADED -> "文件保持原样，本次没有载入";
            case WRITE_BLOCKED -> "文件保持原样，本次没有载入，暂停写入";
        };
    }

    private String verb() {
        return switch (kind) {
            case SYNTAX, ENCODING -> "无法解析";
            case READ -> "无法读取";
            case UPGRADE -> "升级失败（文件本身可以读取）";
            case CONTENT -> "内容有误";
        };
    }

    /**
     * Whether {@code other} is the same problem found again: same file, kind, outcome, cause and
     * backup. The time does not count.
     */
    public boolean sameAs(LoadProblem other) {
        return other != null
                && key(file).equals(key(other.file))
                && kind == other.kind
                && outcome == other.outcome
                && cause.equals(other.cause)
                && Objects.equals(
                        backup == null ? null : key(backup),
                        other.backup == null ? null : key(other.backup));
    }

    static String key(File file) {
        return file.getAbsoluteFile().toPath().normalize().toString();
    }

    // ---- building -------------------------------------------------------------------------

    /** A problem with {@code path} ({@code file} is the file on disk), for a plugin's own files. */
    public static Builder builder(String path, File file) {
        return new Builder(path, file);
    }

    /** Builds a {@link LoadProblem}; {@link #error}, {@link #encoding} or {@link #cause} first. */
    public static final class Builder {

        private final String path;
        private final File file;
        private Kind kind = Kind.SYNTAX;
        private int line = -1;
        private int column = -1;
        private int contextLine = -1;
        private int contextColumn = -1;
        private int problemIndex = -1;
        private String source;
        private String cause = "";
        private Outcome outcome = Outcome.NOT_LOADED;
        private File backup;
        private boolean backupCreated;
        private String backupError;
        private String note;
        private boolean defaults;
        private boolean contentDefaults;

        private Builder(String path, File file) {
            this.path = Objects.requireNonNull(path, "path");
            this.file = Objects.requireNonNull(file, "file");
        }

        /**
         * Kind, position and cause from a parse or read failure: a {@link MarkedYAMLException}
         * anywhere in the cause chain gives {@link Kind#SYNTAX} with its marks; a {@link
         * CharacterCodingException} gives {@link Kind#ENCODING}; any other {@link IOException}
         * gives {@link Kind#READ}; anything else {@link Kind#SYNTAX} without a position.
         */
        public Builder error(Throwable error) {
            MarkedYAMLException marked = marked(error);
            if (marked != null) {
                kind = Kind.SYNTAX;
                Mark problem = marked.getProblemMark();
                Mark context = marked.getContextMark();
                line = problem == null ? -1 : problem.getLine() + 1;
                column = problem == null ? -1 : problem.getColumn() + 1;
                problemIndex = problem == null ? -1 : problem.getIndex();
                contextLine = context == null ? -1 : context.getLine() + 1;
                contextColumn = context == null ? -1 : context.getColumn() + 1;
                cause = markedText(marked);
                return this;
            }
            Throwable io = find(error, IOException.class);
            if (find(error, CharacterCodingException.class) != null) {
                kind = Kind.ENCODING;
            } else if (io != null) {
                kind = Kind.READ;
            } else {
                kind = Kind.SYNTAX;
            }
            Throwable shown = io != null ? io : error;
            cause =
                    kind == Kind.SYNTAX
                            ? ConfigFile.describe(asException(error))
                            : shown.getClass().getSimpleName() + ": " + oneLine(shown.getMessage());
            return this;
        }

        /**
         * {@link Kind#ENCODING} with the line and byte column of the first byte of {@code bytes}
         * that is not UTF-8: {@code 不是 UTF-8 编码，可能被另存为 GBK（第 3 行，第 5 字节）}.
         */
        public Builder encoding(byte[] bytes) {
            kind = Kind.ENCODING;
            problemIndex = -1;
            int offset = firstMalformed(bytes);
            if (offset < 0) {
                cause = "不是 UTF-8 编码，可能被另存为 GBK";
                return this;
            }
            int lineNumber = 1;
            int lineStart = 0;
            for (int i = 0; i < offset; i++) {
                if (bytes[i] == '\n') {
                    lineNumber++;
                    lineStart = i + 1;
                }
            }
            line = lineNumber;
            column = offset - lineStart + 1;
            cause = "不是 UTF-8 编码，可能被另存为 GBK（第 " + line + " 行，第 " + column + " 字节）";
            return this;
        }

        /** {@link Kind#CONTENT}: valid YAML the plugin cannot use, at an optional position. */
        public Builder content(String text, int line, int column) {
            this.kind = Kind.CONTENT;
            this.problemIndex = -1;
            this.line = line;
            this.column = column;
            this.cause = text + (line > 0 ? at(line, column) : "");
            return this;
        }

        public Builder kind(Kind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
            return this;
        }

        /** Replaces the cause text (positions are not added). */
        public Builder cause(String cause) {
            this.cause = cause == null ? "" : cause;
            return this;
        }

        /** Sets the position shown, as given (it is not moved, see {@link #source}). */
        public Builder position(int line, int column) {
            this.line = line;
            this.column = column;
            this.problemIndex = -1;
            return this;
        }

        /**
         * The text the parser read. With it, a problem mark at the very end of the file (nothing
         * but blank text after it) is shown at the context mark instead - the opening quote or
         * bracket of what was never closed - in {@link LoadProblem#position()} and the chat title;
         * the cause keeps both marks as the parser gave them. {@link ConfigFile} passes it.
         */
        public Builder source(String text) {
            this.source = text;
            return this;
        }

        public Builder outcome(Outcome outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            return this;
        }

        public Builder backup(FileBackup.Backup backup) {
            this.backup = backup == null ? null : backup.file();
            this.backupCreated = backup != null && backup.created();
            return this;
        }

        public Builder backup(File backup, boolean created) {
            this.backup = backup;
            this.backupCreated = backup != null && created;
            return this;
        }

        /** No backup, and why ({@code IOException: disk full}). */
        public Builder backupFailed(String why) {
            this.backup = null;
            this.backupCreated = false;
            this.backupError = why;
            return this;
        }

        /** {@link #backupFailed(String)} with the exception's type and message. */
        public Builder backupFailed(Throwable why) {
            return backupFailed(why.getClass().getSimpleName() + ": " + oneLine(why.getMessage()));
        }

        /**
         * Line two (what is disabled, how to recover). Without one, a generic text for the outcome
         * is used, naming the reload hint of {@link ConfigProblems#reloadHint()}.
         */
        public Builder note(String note) {
            this.note = note;
            return this;
        }

        /**
         * Whether the plugin runs on built-in defaults in place of the file (a settings, lang or
         * menu file); the generic note says so. Default false (a data or content file the plugin
         * runs without).
         */
        public Builder defaults(boolean defaults) {
            this.defaults = defaults;
            return this;
        }

        /**
         * Whether the jar default is content - a lang, menu or page file ({@code Lang}, {@link
         * ConfigFile#content}, {@link ConfigFile#repairFrom}) - rather than settings: the generic
         * note then says 默认内容 instead of 默认配置. True also sets {@link #defaults(boolean)}.
         */
        public Builder contentDefaults(boolean content) {
            this.contentDefaults = content;
            if (content) {
                this.defaults = true;
            }
            return this;
        }

        public LoadProblem build() {
            String text =
                    note != null && !note.isBlank()
                            ? note
                            : defaultNote(
                                    path, file.getName(), kind, outcome, defaults, contentDefaults);
            boolean moved =
                    problemIndex >= 0
                            && contextLine > 0
                            && source != null
                            && atEnd(source, problemIndex);
            return new LoadProblem(
                    path,
                    file,
                    kind,
                    moved ? contextLine : line,
                    moved ? contextColumn : column,
                    contextLine,
                    contextColumn,
                    line,
                    column,
                    cause,
                    outcome,
                    backup,
                    backupCreated,
                    backup == null ? backupError : null,
                    text,
                    Instant.now());
        }
    }

    /**
     * Whether nothing but blank text follows code point {@code index} of {@code source}: the parser
     * ran to the end of the file (a leading byte-order mark does not count).
     */
    static boolean atEnd(String source, int index) {
        String text =
                !source.isEmpty() && source.charAt(0) == '\uFEFF' ? source.substring(1) : source;
        if (index >= text.codePointCount(0, text.length())) {
            return true;
        }
        return text.substring(text.offsetByCodePoints(0, index)).isBlank();
    }

    /** {@code 默认内容} for lang, menu and page files, {@code 默认配置} for settings. */
    static String defaultsNoun(boolean content) {
        return content ? "默认内容" : "默认配置";
    }

    /** The generic line two for an outcome. */
    static String defaultNote(
            String path,
            String name,
            Kind kind,
            Outcome outcome,
            boolean defaults,
            boolean content) {
        String hint = ConfigProblems.reloadHint();
        String noun = defaultsNoun(content);
        String running = defaults ? "本次改用插件内置的" + noun : "本次没有载入其中的内容";
        return switch (outcome) {
            case REPLACED ->
                    path
                            + " 已换成插件内置的"
                            + noun
                            + "，本次和以后都按默认运行；要用回原来的内容，修好备份中的错误后改回原名 "
                            + name
                            + "，再"
                            + hint
                            + "。";
            case NOT_LOADED ->
                    kind == Kind.UPGRADE
                            ? path
                                    + " 保持原样，"
                                    + running
                                    + "；对照插件内置的"
                                    + noun
                                    + "检查它，改好后"
                                    + hint
                                    + "即可用回它。"
                            : path + " 保持原样，" + running + "；修好 " + path + " 后" + hint + "即可用回它。";
            case WRITE_BLOCKED ->
                    path
                            + " 保持原样，"
                            + running
                            + "；在"
                            + (kind == Kind.READ ? " " + path + " 能正常读取" : "修好 " + path + " ")
                            + "并"
                            + hint
                            + "之前，插件不会写入这个文件。";
        };
    }

    // ---- parser marks ---------------------------------------------------------------------

    private static MarkedYAMLException marked(Throwable error) {
        return find(error, MarkedYAMLException.class);
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * {@code <context>（第 n 行，第 m 列）：<problem>（第 n 行，第 m 列）}, the context part only when the
     * parser gave one: the shape of the approved Archeology sample.
     */
    static String markedText(MarkedYAMLException error) {
        String problem = error.getProblem() == null ? "" : oneLine(error.getProblem());
        String problemText = problem + at(error.getProblemMark());
        String context = error.getContext() == null ? "" : oneLine(error.getContext());
        if (context.isEmpty()) {
            return problemText.isEmpty() ? ConfigFile.describe(error) : problemText;
        }
        String contextText = context + at(error.getContextMark());
        return problemText.isEmpty() ? contextText : contextText + "：" + problemText;
    }

    private static String at(Mark mark) {
        return mark == null ? "" : at(mark.getLine() + 1, mark.getColumn() + 1);
    }

    private static String at(int line, int column) {
        return column > 0 ? "（第 " + line + " 行，第 " + column + " 列）" : "（第 " + line + " 行）";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s*\\R\\s*", " / ");
    }

    private static Exception asException(Throwable error) {
        return error instanceof Exception exception ? exception : new Exception(error.toString());
    }

    /** Offset of the first byte that is not UTF-8, or -1 when all of them are. */
    static int firstMalformed(byte[] bytes) {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(bytes.length + 1);
        CoderResult result = decoder.decode(input, output, true);
        if (!result.isError()) {
            result = decoder.flush(output);
        }
        return result.isError() ? input.position() : -1;
    }
}
