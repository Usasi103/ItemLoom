package dev.keystone.config;

import dev.keystone.log.Log;
import dev.keystone.storage.FileBackup;
import dev.keystone.storage.StorageWriter;
import dev.keystone.storage.WriteGuard;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A synchronous reload's read/validate/commit boundary. ConfigFile loads preview disk content
 * without replacing bad files, making backups or changing write guards. Callers prepare their
 * business models before commit, and install them only after it returns true. Closing without
 * committing restores existing ConfigFile objects and discards every pending write and callback.
 * Each shaded Keystone has its own thread-local transaction; nested transactions are rejected.
 */
public final class ReloadTransaction implements AutoCloseable {

    private static final ThreadLocal<ReloadTransaction> CURRENT = new ThreadLocal<>();
    private static final String CANCELLED = "重载已取消，仍按重载前的设置运行；文件保持原样。建议使用 VS Code 修改后重新执行重载。";

    private record Read(File file, String path, byte[] original) {}

    private record Write(Read read, byte[] content, boolean tabs) {}

    private final Map<String, Read> reads = new LinkedHashMap<>();
    private final Map<String, Write> writes = new LinkedHashMap<>();
    private final Map<String, LoadProblem> problems = new LinkedHashMap<>();
    private final Map<ConfigFile, ConfigFile.ReloadState> snapshots = new IdentityHashMap<>();
    private final List<Runnable> committedCallbacks = new ArrayList<>();
    private final List<Runnable> rollbackCallbacks = new ArrayList<>();
    private final Map<String, File> resolved = new LinkedHashMap<>();
    private boolean finished;

    private ReloadTransaction() {}

    public static ReloadTransaction begin() {
        if (CURRENT.get() != null) {
            throw new IllegalStateException(
                    "A reload transaction is already active on this thread");
        }
        ReloadTransaction transaction = new ReloadTransaction();
        CURRENT.set(transaction);
        return transaction;
    }

    /** Null outside a reload. Startup continues to use ConfigFile's normal repair policy. */
    public static ReloadTransaction current() {
        return CURRENT.get();
    }

    public boolean valid() {
        return problems.isEmpty();
    }

    public List<LoadProblem> problems() {
        return List.copyOf(problems.values());
    }

    public void afterCommit(Runnable callback) {
        requireActive();
        committedCallbacks.add(callback);
    }

    public void onRollback(Runnable callback) {
        requireActive();
        rollbackCallbacks.add(callback);
    }

    /** Validate a self-parsed YAML file; missing files are empty, as for ConfigFile.data. */
    public YamlConfiguration checkYaml(File file, String path) {
        String text = readText(file, path);
        if (text == null) {
            return null;
        }
        YamlConfiguration result = new YamlConfiguration();
        try {
            result.loadFromString(text);
            resolve(file);
            return result;
        } catch (InvalidConfigurationException | RuntimeException error) {
            problem(LoadProblem.builder(path, file).error(error).source(text).build());
            return null;
        }
    }

    public void problem(File file, String path, Throwable error) {
        problem(LoadProblem.builder(path, file).error(error).build());
    }

    public void problem(File file, String path, String cause, int line, int column) {
        problem(LoadProblem.builder(path, file).content(cause, line, column).build());
    }

    /** A plugin using its own parser may report through ConfigProblems.report instead. */
    public void problem(LoadProblem found) {
        requireActive();
        LoadProblem cancelled =
                new LoadProblem(
                        found.path(),
                        found.file(),
                        found.kind(),
                        found.line(),
                        found.column(),
                        found.contextLine(),
                        found.contextColumn(),
                        found.problemLine(),
                        found.problemColumn(),
                        found.cause(),
                        LoadProblem.Outcome.NOT_LOADED,
                        null,
                        false,
                        null,
                        CANCELLED,
                        found.time());
        String key = LoadProblem.key(found.file()) + ":" + found.line() + ":" + found.cause();
        problems.putIfAbsent(key, cancelled);
    }

    ConfigFile load(ConfigFile file) {
        requireActive();
        snapshots.computeIfAbsent(file, ConfigFile::reloadState);
        file.loadForReload(this);
        return file;
    }

    void resolve(File file) {
        resolved.put(LoadProblem.key(file), file);
    }

    /** Strict UTF-8, no journal recovery or repair. Leading indentation tabs become four spaces. */
    String readText(File file, String path) {
        requireActive();
        String key = LoadProblem.key(file);
        Read read = reads.get(key);
        if (read == null) {
            try {
                byte[] original =
                        Files.exists(file.toPath()) ? Files.readAllBytes(file.toPath()) : null;
                read = new Read(file, path, original);
                reads.put(key, read);
            } catch (IOException | RuntimeException error) {
                problem(
                        LoadProblem.builder(path, file)
                                .error(error)
                                .kind(LoadProblem.Kind.READ)
                                .build());
                return null;
            }
        }
        if (read.original() == null) {
            return "";
        }
        String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(read.original()))
                            .toString();
        } catch (CharacterCodingException error) {
            problem(LoadProblem.builder(path, file).encoding(read.original()).build());
            return null;
        }
        text = YamlUpgrade.stripBom(text);
        String repaired = repairIndentation(text);
        if (!repaired.equals(text)) {
            stage(file, path, repaired, true);
        }
        return repaired;
    }

    static String repairIndentation(String text) {
        StringBuilder result = new StringBuilder(text.length());
        boolean indentation = true;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (indentation && character == '\t') {
                result.append("    ");
            } else {
                result.append(character);
            }
            if (character == '\n' || character == '\r') {
                indentation = true;
            } else if (character != ' ' && character != '\t') {
                indentation = false;
            }
        }
        return result.toString();
    }

    void stage(File file, String path, String text, boolean tabs) {
        requireActive();
        String key = LoadProblem.key(file);
        if (!reads.containsKey(key) && readText(file, path) == null) {
            return;
        }
        Write previous = writes.get(key);
        writes.put(
                key,
                new Write(
                        reads.get(key),
                        text.getBytes(StandardCharsets.UTF_8),
                        tabs || previous != null && previous.tabs()));
    }

    /**
     * Commit file upgrades/tab repairs and pure memory callbacks after every candidate has been
     * validated. False restores previews, prints detailed console errors and sends one short
     * cancellation reply. Callers must not publish their business models until true is returned.
     */
    public boolean commit(CommandSender sender) {
        requireActive();
        if (!valid()) {
            return cancel(sender);
        }
        for (Read read : reads.values()) {
            try {
                byte[] now =
                        Files.exists(read.file().toPath())
                                ? Files.readAllBytes(read.file().toPath())
                                : null;
                if (!Arrays.equals(now, read.original())) {
                    problem(read.file(), read.path(), "验证期间文件被修改，请重新执行重载", -1, -1);
                }
            } catch (IOException | RuntimeException error) {
                problem(read.file(), read.path(), error);
            }
        }
        if (!valid()) {
            return cancel(sender);
        }
        List<Write> applied = new ArrayList<>();
        List<File> createdBackups = new ArrayList<>();
        Map<File, String> guards = new LinkedHashMap<>();
        try {
            for (File file : resolved.values()) {
                guards.put(file, WriteGuard.reason(file));
                WriteGuard.unblock(file);
            }
            // No backup until all file contents and business values have passed validation.
            for (Write write : writes.values()) {
                if (write.tabs() && write.read().original() != null) {
                    FileBackup.Backup backup =
                            FileBackup.beside(write.read().file(), write.read().original());
                    if (backup.created()) {
                        createdBackups.add(backup.file());
                    }
                }
            }
            for (Write write : writes.values()) {
                applied.add(write);
                StorageWriter.writeAtomic(
                        write.read().file(), new String(write.content(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException error) {
            guards.forEach(
                    (file, reason) -> {
                        if (reason != null) {
                            WriteGuard.block(file, reason);
                        }
                    });
            for (int index = applied.size() - 1; index >= 0; index--) {
                Read read = applied.get(index).read();
                try {
                    if (read.original() == null) {
                        Files.deleteIfExists(read.file().toPath());
                    } else {
                        Files.write(read.file().toPath(), read.original());
                    }
                } catch (IOException rollbackError) {
                    error.addSuppressed(rollbackError);
                    Log.error(read.path() + " 恢复原文件失败：" + rollbackError.getMessage());
                }
            }
            for (File backup : createdBackups) {
                try {
                    Files.deleteIfExists(backup.toPath());
                } catch (IOException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            Write failed =
                    applied.isEmpty()
                            ? writes.values().iterator().next()
                            : applied.get(applied.size() - 1);
            problem(failed.read().file(), failed.read().path(), error);
            return cancel(sender);
        }
        finished = true;
        CURRENT.remove();
        for (File file : resolved.values()) {
            WriteGuard.unblock(file);
            ConfigProblems.resolve(file);
        }
        for (Runnable callback : committedCallbacks) {
            callback.run();
        }
        return true;
    }

    private boolean cancel(CommandSender sender) {
        List<LoadProblem> found = problems();
        close();
        for (LoadProblem problem : found) {
            ConfigProblems.record(problem);
            String position = problem.position();
            Log.error(
                    problem.headline()
                            + (position.isEmpty() || problem.cause().contains(position)
                                    ? ""
                                    : "（" + position + "）"));
            Log.error(CANCELLED);
        }
        if (sender != null) {
            sender.sendMessage("§e⚠ 重载已取消：发现 " + found.size() + " 个问题，仍按重载前的设置运行。（请检查后台以获取详细信息）");
        }
        ConfigProblems.notifyReloadCancelled(sender);
        return false;
    }

    private void requireActive() {
        if (finished || CURRENT.get() != this) {
            throw new IllegalStateException("The reload transaction has already finished");
        }
    }

    @Override
    public void close() {
        if (finished) {
            return;
        }
        finished = true;
        CURRENT.remove();
        snapshots.forEach(ConfigFile::restoreReloadState);
        for (int index = rollbackCallbacks.size() - 1; index >= 0; index--) {
            rollbackCallbacks.get(index).run();
        }
    }
}
