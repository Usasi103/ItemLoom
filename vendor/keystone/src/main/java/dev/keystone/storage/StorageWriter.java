package dev.keystone.storage;

import dev.keystone.Keystone;
import dev.keystone.log.Log;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The single thread this plugin does its blocking storage work on.
 *
 * <p>Bukkit's shared async pool promises nothing about order: a player's quit-save and an autosave
 * that started a moment earlier land on two threads, and a delete-then-insert writer interleaves
 * them into duplicate or missing rows. One thread makes every queued operation strictly FIFO - a
 * load queued after a save reads what the save wrote - while the JDBC and file work still stays
 * off the server thread.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>the main thread snapshots (copies a few fields, clones a few items); the writer serialises
 *       and writes. Nothing the main thread keeps mutating is ever read from here;
 *   <li>{@code onDisable} calls {@link #drain} before its final synchronous save, then {@link
 *       #shutdown} last;
 *   <li>after {@link #shutdown} a task handed to {@link #execute} runs inline on the caller, so a
 *       save issued late in disable still happens instead of being dropped.
 * </ul>
 *
 * <p>Never call {@link #drain} from the writer thread itself; it would wait on itself.
 *
 * <p>Ported from the per-plugin {@code StorageWriter.kt} template.
 */
public final class StorageWriter {

    /** A task that returns a value and may throw (JDBC, IO). */
    @FunctionalInterface
    public interface Work<T> {
        T run() throws Exception;
    }

    /** A task that may throw (JDBC, IO). */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    private static final Object lock = new Object();
    private static ExecutorService executor;
    private static volatile boolean closed;
    private static volatile String threadName;

    private StorageWriter() {}

    private static String threadName() {
        String name = threadName;
        if (name == null) {
            String owner = Keystone.isInitialized() ? Keystone.name() : "Storage";
            name = owner + "-Storage";
            threadName = name;
        }
        return name;
    }

    /** Whether the calling thread is this writer. */
    public static boolean isWriterThread() {
        return Thread.currentThread().getName().equals(threadName());
    }

    private static ExecutorService executor() {
        if (closed) {
            return null;
        }
        synchronized (lock) {
            if (closed) {
                return null;
            }
            if (executor == null) {
                String name = threadName();
                executor =
                        Executors.newSingleThreadExecutor(
                                r -> {
                                    Thread thread = new Thread(r, name);
                                    thread.setDaemon(true);
                                    return thread;
                                });
            }
            return executor;
        }
    }

    /**
     * Queues {@code task} behind everything queued before it.
     *
     * <p>Failures are logged, never thrown, so one bad row cannot take the thread down with it.
     * Once the writer is closed the task runs on the calling thread.
     */
    public static void execute(Action task) {
        ExecutorService pool = executor();
        if (pool == null) {
            runGuarded(task);
            return;
        }
        try {
            pool.execute(() -> runGuarded(task));
        } catch (RejectedExecutionException e) {
            runGuarded(task);
        }
    }

    /** {@link #execute} for work that hands a value (or its failure) back. */
    public static <T> CompletableFuture<T> supply(Work<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        execute(
                () -> {
                    try {
                        future.complete(task.run());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
        return future;
    }

    /** {@link #drain(long)} with a 10 second limit. */
    public static void drain() {
        drain(10);
    }

    /**
     * Blocks until everything queued so far has run, or {@code timeoutSeconds} passed. Leaves the
     * writer open, so several subsystems can each drain before their own final save.
     */
    public static void drain(long timeoutSeconds) {
        ExecutorService pool;
        synchronized (lock) {
            pool = executor;
        }
        if (pool == null || closed || isWriterThread()) {
            return;
        }
        CompletableFuture<Void> marker = new CompletableFuture<>();
        try {
            pool.execute(() -> marker.complete(null));
        } catch (RejectedExecutionException e) {
            return;
        }
        try {
            marker.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Log.warn("存储线程 " + timeoutSeconds + "s 内没有排空, 最后几次写入可能丢失.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // The marker failed: nothing left to wait for.
        }
    }

    /**
     * Drains, then closes the thread. Call last in {@code onDisable}; a save issued after this
     * point runs inline and is not lost.
     */
    public static void shutdown() {
        drain();
        ExecutorService pool;
        synchronized (lock) {
            closed = true;
            pool = executor;
            executor = null;
        }
        if (pool != null) {
            pool.shutdown();
        }
    }

    private static void runGuarded(Action task) {
        try {
            task.run();
        } catch (Throwable t) {
            Log.warn("存储任务失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ---- files --------------------------------------------------------------

    /** Moves a file; replaced in tests to refuse renames the way a OneDrive lock does. */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to, boolean atomic) throws IOException;
    }

    /** Writes bytes into a file in place; replaced in tests to cut a write short. */
    @FunctionalInterface
    interface InPlace {
        void write(Path target, byte[] bytes) throws IOException;
    }

    static Mover mover =
            (from, to, atomic) -> {
                if (atomic) {
                    Files.move(
                            from,
                            to,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            };

    static InPlace inPlace = StorageWriter::writeSynced;

    /** How often a refused rename is tried again, and the pause before the first retry. */
    static int moveAttempts = 5;

    static long retryMillis = 25;

    /**
     * Writes {@code text} to {@code file} so that a hard kill at any moment leaves a complete file
     * - the old one or the new one - never a truncated one.
     *
     * <ol>
     *   <li>A file {@linkplain WriteGuard blocked} for this plugin (a data file that could not be
     *       read or backed up) is refused with an {@link IOException} and left as it is (0.3.4).
     *   <li>The text goes to {@code <name>.tmp}, synced to disk, then is renamed over the file
     *       (atomic move, else a plain move). A refused rename - OneDrive holds handles on files it
     *       is syncing - is retried a few times.
     *   <li>When every rename is refused, the file is rewritten in place, guarded (0.3.4; 0.3.3
     *       wrote in place unguarded): the old bytes are first copied to {@code <name>.previous}
     *       and verified, the new bytes stay in the synced {@code .tmp}, and the file is read back
     *       after the write. A kill in the middle leaves both copies behind, and {@link #recover}
     *       - run by {@code ConfigFile} before every read - completes the file from them. A
     *       read-back that does not match puts the old bytes back and throws.
     * </ol>
     */
    public static void writeAtomic(File file, String text) throws IOException {
        WriteGuard.check(file);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Path tmp = tmpOf(file);
        writeSynced(tmp, bytes);
        if (moveWithRetries(tmp, file.toPath())) {
            return;
        }
        copyOver(file, tmp, bytes);
    }

    private static boolean moveWithRetries(Path from, Path to) {
        for (int attempt = 0; attempt < moveAttempts; attempt++) {
            try {
                mover.move(from, to, true);
                return true;
            } catch (IOException atomic) {
                // Try the plain move below.
            }
            try {
                mover.move(from, to, false);
                return true;
            } catch (IOException plain) {
                // Refused again: wait a little and retry.
            }
            if (attempt + 1 < moveAttempts) {
                try {
                    Thread.sleep(retryMillis * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static void copyOver(File file, Path tmp, byte[] bytes) throws IOException {
        Path target = file.toPath();
        Path previous = previousOf(file);
        byte[] old = Files.isRegularFile(target) ? Files.readAllBytes(target) : new byte[0];
        writeSynced(previous, old);
        if (!Arrays.equals(old, Files.readAllBytes(previous))) {
            Files.deleteIfExists(previous);
            throw new IOException("无法为 " + file.getPath() + " 建立写入前的副本，文件保持原样");
        }
        try {
            inPlace.write(target, bytes);
            if (!Arrays.equals(bytes, Files.readAllBytes(target))) {
                throw new IOException("写入后读回的内容不一致");
            }
        } catch (IOException e) {
            try {
                writeSynced(target, old);
                if (Arrays.equals(old, Files.readAllBytes(target))) {
                    Files.deleteIfExists(previous);
                }
            } catch (IOException restore) {
                // Both copies stay next to the file; recover() completes it on the next read.
            }
            throw new IOException("写入 " + file.getPath() + " 失败，原内容已保留: " + e.getMessage(), e);
        }
        Files.deleteIfExists(previous);
        Files.deleteIfExists(tmp);
    }

    /**
     * Completes a file whose guarded in-place rewrite ({@link #writeAtomic}) was cut short: while
     * {@code <name>.previous} exists, the file is set to the synced new bytes of {@code
     * <name>.tmp} (or, without them, back to the old bytes of {@code .previous}) and both copies
     * are removed. Returns whether anything was repaired. Plugins reading a file themselves call it
     * before the read; {@code ConfigFile} does already.
     */
    public static boolean recover(File file) {
        Path previous = previousOf(file);
        if (!Files.isRegularFile(previous)) {
            return false;
        }
        Path tmp = tmpOf(file);
        Path target = file.toPath();
        try {
            boolean fromTmp = Files.isRegularFile(tmp);
            byte[] source = Files.readAllBytes(fromTmp ? tmp : previous);
            byte[] current = Files.isRegularFile(target) ? Files.readAllBytes(target) : null;
            if (!Arrays.equals(source, current)) {
                writeSynced(target, source);
                if (!Arrays.equals(source, Files.readAllBytes(target))) {
                    Log.warn(file.getPath() + " 上次写入被打断，恢复失败，完整的副本仍在 " + previous + " / " + tmp);
                    return false;
                }
                Log.warn(file.getPath() + " 上次写入被打断，已按完整的" + (fromTmp ? "新内容" : "旧内容") + "恢复");
            }
            Files.deleteIfExists(previous);
            Files.deleteIfExists(tmp);
            return true;
        } catch (IOException e) {
            Log.warn(file.getPath() + " 上次写入被打断，恢复失败: " + e.getMessage());
            return false;
        }
    }

    static Path tmpOf(File file) {
        return new File(file.getAbsoluteFile().getParentFile(), file.getName() + ".tmp").toPath();
    }

    static Path previousOf(File file) {
        return new File(file.getAbsoluteFile().getParentFile(), file.getName() + ".previous")
                .toPath();
    }

    /** Writes {@code bytes} to {@code path} (created or truncated) and syncs it to disk. */
    static void writeSynced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }
}
