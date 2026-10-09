package dev.itemloom.paper.action;

import com.google.gson.Gson;
import dev.keystone.storage.FileBackup;
import dev.keystone.storage.StorageWriter;
import dev.keystone.storage.WriteGuard;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Service-owned returns. Restart records are evidence, never authorization to repeat delivery. */
public final class ReturnLedger implements AutoCloseable {
    public enum Status {
        STAGED,
        READY,
        UNKNOWN,
        RECOVERY_REVIEW
    }

    public enum Phase {
        SOURCE_COMMIT,
        INVENTORY_ADD,
        WORLD_DROP,
        OWNED_RETURN
    }

    public record Diagnostic(
            UUID transaction,
            UUID player,
            UUID owner,
            long session,
            Status status,
            Phase phase,
            boolean inventoryPending,
            boolean authorizedReturn,
            String source,
            String item,
            String before,
            String prepared,
            String failure,
            long createdAt) {}

    private record Document(int schema, UUID process, List<Diagnostic> records) {}

    record SourceSnapshot(String before, String prepared) {
        static SourceSnapshot capture(ItemStack before, ItemStack prepared) {
            thread();
            return new SourceSnapshot(encode(before), encode(prepared));
        }
    }

    static final class Entry {
        final UUID id = UUID.randomUUID();
        final UUID player, owner;
        final String source, before;
        final long createdAt = System.currentTimeMillis();
        long session;
        ItemStack item;
        String encoded, prepared = "", failure = "";
        Phase phase;
        boolean ready, outcomeUnknown, inventoryPending = true;
        final boolean authorizedReturn;
        BukkitTask task;

        Entry(
                UUID player,
                UUID owner,
                long session,
                ItemStack item,
                boolean ready,
                boolean authorizedReturn,
                String source,
                String before,
                String prepared) {
            this.player = player;
            this.owner = owner;
            this.session = session;
            this.item = item.clone();
            encoded = encode(item);
            this.ready = ready;
            this.authorizedReturn = authorizedReturn;
            this.source = source;
            this.before = before;
            this.prepared = prepared;
            phase = ready ? Phase.OWNED_RETURN : Phase.SOURCE_COMMIT;
        }

        Diagnostic diagnostic() {
            return new Diagnostic(
                    id,
                    player,
                    owner,
                    session,
                    outcomeUnknown ? Status.UNKNOWN : ready ? Status.READY : Status.STAGED,
                    phase,
                    inventoryPending,
                    authorizedReturn,
                    source,
                    encoded,
                    before,
                    prepared,
                    failure,
                    createdAt);
        }
    }

    private static final Gson JSON = new Gson();
    private final JavaPlugin plugin;
    private final PlayerActionState players;
    private final Path file;
    private final UUID process = UUID.randomUUID();
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final List<Diagnostic> recovered = new ArrayList<>();
    private final Map<UUID, Integer> leases = new LinkedHashMap<>();
    private final Object writerLock = new Object();
    private Document queued;
    private volatile Document persisted;
    private CompletableFuture<Void> saving = CompletableFuture.completedFuture(null);
    private BukkitTask flushTask;
    private boolean writing, closing;

    ReturnLedger(JavaPlugin plugin, PlayerActionState players, Path file) {
        this.plugin = Objects.requireNonNull(plugin);
        this.players = Objects.requireNonNull(players);
        this.file = file;
        if (file != null) load();
    }

    void begin(UUID owner) {
        thread();
        if (closing) throw new IllegalStateException("Return ledger is closing");
        leases.merge(owner, 1, Integer::sum);
    }

    void end(UUID owner) {
        thread();
        int count = leases.getOrDefault(owner, 0);
        if (count <= 0) throw new IllegalStateException("Unbalanced return transaction");
        if (count == 1) leases.remove(owner);
        else leases.put(owner, count - 1);
        // A plugin can disable inside a synchronous body. Its finally still owns a lease;
        // late terminal changes remain writable after close and are saved without a next tick.
        if (closing && leases.isEmpty()) awaitSave();
    }

    Entry register(
            UUID owner,
            Player player,
            ItemStack item,
            boolean ready,
            boolean authorizedReturn,
            String source,
            ItemStack before) {
        thread();
        return registerEncoded(
                owner, player, item, ready, authorizedReturn, source, encode(before), "");
    }

    Entry registerPrepared(
            UUID owner, Player player, ItemStack item, String source, SourceSnapshot snapshot) {
        return registerEncoded(
                owner, player, item, false, true, source, snapshot.before(), snapshot.prepared());
    }

    private Entry registerEncoded(
            UUID owner,
            Player player,
            ItemStack item,
            boolean ready,
            boolean authorizedReturn,
            String source,
            String before,
            String prepared) {
        thread();
        if (closing && !leases.containsKey(owner))
            throw new IllegalStateException("Return ledger is closed");
        players.observe(player);
        Entry entry =
                new Entry(
                        player.getUniqueId(),
                        owner,
                        players.session(player.getUniqueId()),
                        item,
                        ready,
                        authorizedReturn,
                        source,
                        before,
                        prepared);
        entries.put(entry.id, entry);
        changed();
        return entry;
    }

    void preparing(Entry entry, ItemStack candidate) {
        if (!contains(entry)) return;
        entry.prepared = encode(candidate);
        entry.phase = Phase.SOURCE_COMMIT;
        changed();
    }

    void ready(Entry entry) {
        if (!contains(entry)) return;
        entry.ready = true;
        changed();
    }

    void sourceUnknown(Entry entry, ItemStack candidate, Throwable failure) {
        if (!contains(entry)) return;
        entry.prepared = encode(candidate);
        sourceUnknown(entry, failure);
    }

    void sourceUnknown(Entry entry, Throwable failure) {
        if (!contains(entry)) return;
        entry.phase = Phase.SOURCE_COMMIT;
        unknown(entry, failure);
    }

    void abort(Entry entry) {
        if (contains(entry) && !entry.outcomeUnknown) remove(entry);
    }

    List<Entry> entries(UUID owner) {
        return entries.values().stream().filter(entry -> entry.owner.equals(owner)).toList();
    }

    /** Includes quarantined records restored from a previous process without decoding their items. */
    public List<Diagnostic> diagnostics() {
        thread();
        List<Diagnostic> result = new ArrayList<>(recovered);
        entries.values().forEach(entry -> result.add(entry.diagnostic()));
        return List.copyOf(result);
    }

    void schedule(Entry entry) {
        thread();
        if (!contains(entry)
                || closing
                || entry.task != null
                || !entry.ready
                || entry.outcomeUnknown) return;
        try {
            entry.task =
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () -> {
                                        entry.task = null;
                                        deliver(entry);
                                    },
                                    1);
        } catch (RuntimeException failure) {
            entry.failure = "Scheduling failed before delivery: " + failure;
            changed();
            plugin.getLogger()
                    .log(
                            Level.WARNING,
                            "Return " + entry.id + " remains ready after scheduling failed",
                            failure);
        }
    }

    void joined(Player player) {
        thread();
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.player.equals(player.getUniqueId()) && entry.ready && !entry.outcomeUnknown) {
                entry.session = players.session(entry.player);
                schedule(entry);
            }
        }
        if (!entries.isEmpty()) changed();
    }

    public void flush(Player player) {
        thread();
        for (Entry entry : List.copyOf(entries.values()))
            if (entry.player.equals(player.getUniqueId())) deliver(entry);
    }

    public void flushAll() {
        thread();
        for (Entry entry : List.copyOf(entries.values())) deliver(entry);
    }

    void deliver(Entry entry) {
        thread();
        if (!contains(entry)
                || !entry.ready
                || entry.outcomeUnknown
                || !entry.authorizedReturn
                || leases.containsKey(entry.owner)) return;
        Player target = players.resolve(entry.player, entry.session);
        if (target == null) return;
        try {
            if (entry.inventoryPending) {
                attempting(entry, Phase.INVENTORY_ADD);
                Map<Integer, ItemStack> left = target.getInventory().addItem(entry.item.clone());
                if (left.isEmpty()) {
                    remove(entry);
                    return;
                }
                if (left.size() != 1 || !left.containsKey(0))
                    throw new IllegalStateException("Invalid leftovers for one supplied stack");
                ItemStack remaining = Objects.requireNonNull(left.get(0), "leftover");
                if (!remaining.isEmpty()
                        && (!entry.item.isSimilar(remaining)
                                || remaining.getAmount() > entry.item.getAmount()))
                    throw new IllegalStateException(
                            "Returned leftover differs from the supplied stack");
                entry.item = remaining.clone();
                entry.encoded = encode(entry.item);
                entry.inventoryPending = false;
                entry.outcomeUnknown = false;
                entry.failure = "";
                changed();
            }
            if (entry.item.isEmpty()) {
                remove(entry);
                return;
            }
            // addItem may synchronously end the session. Its known leftover belongs to the
            // ledger until a later live session; never drop using an obsolete Player object.
            target = players.resolve(entry.player, entry.session);
            if (target == null) return;
            attempting(entry, Phase.WORLD_DROP);
            var entity = target.getWorld().dropItem(target.getLocation(), entry.item.clone());
            if (entity.isValid()) {
                remove(entry);
                return;
            }
            unknown(
                    entry,
                    new IllegalStateException(
                            "Drop returned no live entity; another listener may have redirected the item"));
        } catch (RuntimeException failure) {
            unknown(entry, failure);
        }
    }

    private void attempting(Entry entry, Phase phase) {
        entry.phase = phase;
        entry.outcomeUnknown = true;
        entry.failure = "Delivery attempt started; completion not yet confirmed";
        changed();
    }

    private void unknown(Entry entry, Throwable failure) {
        entry.outcomeUnknown = true;
        entry.failure = failure.toString();
        changed();
        save();
        plugin.getLogger()
                .log(
                        Level.SEVERE,
                        "Return "
                                + entry.id
                                + " for player "
                                + entry.player
                                + " has unknown outcome at "
                                + entry.phase
                                + "; automatic retry disabled",
                        failure);
    }

    private boolean contains(Entry entry) {
        return entry != null && entries.get(entry.id) == entry;
    }

    private void remove(Entry entry) {
        entries.remove(entry.id);
        if (entry.task != null) {
            entry.task.cancel();
            entry.task = null;
        }
        changed();
    }

    /** Flushes diagnostics only; a saved READY record is not a crash-safe delivery receipt. */
    public CompletableFuture<Void> save() {
        thread();
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        queueSave();
        synchronized (writerLock) {
            return saving;
        }
    }

    private void changed() {
        thread();
        if (file == null) return;
        // Intermediate states of a successful synchronous return need no separate file I/O.
        // Uncertain outcomes and shutdown force a snapshot; ordinary changes share one tick.
        if (closing) {
            save();
            return;
        }
        if (flushTask != null) return;
        try {
            flushTask =
                    Bukkit.getScheduler()
                            .runTask(
                                    plugin,
                                    () -> {
                                        flushTask = null;
                                        queueSave();
                                    });
        } catch (RuntimeException failure) {
            flushTask = null;
            queueSave();
        }
    }

    private void queueSave() {
        if (file == null) return;
        Document snapshot = new Document(1, process, diagnostics());
        synchronized (writerLock) {
            queued = snapshot;
            if (writing) return;
            writing = true;
            saving =
                    StorageWriter.supply(
                            () -> {
                                try {
                                    while (true) {
                                        Document next;
                                        synchronized (writerLock) {
                                            next = queued;
                                            queued = null;
                                            if (next == null) {
                                                writing = false;
                                                return null;
                                            }
                                        }
                                        if (!next.equals(persisted)) {
                                            StorageWriter.writeAtomic(
                                                    file.toFile(), JSON.toJson(next));
                                            persisted = next;
                                        }
                                    }
                                } catch (Exception failure) {
                                    synchronized (writerLock) {
                                        writing = false;
                                    }
                                    plugin.getLogger()
                                            .log(
                                                    Level.SEVERE,
                                                    "Cannot persist return diagnostics to " + file,
                                                    failure);
                                    throw failure;
                                }
                            });
        }
    }

    private void load() {
        byte[] original = null;
        try {
            StorageWriter.recover(file.toFile());
            if (Files.exists(file.resolveSibling(file.getFileName() + ".previous")))
                throw new IOException(
                        "Previous return-ledger write has not recovered; preserve recovery files");
            if (!Files.exists(file)) return;
            original = Files.readAllBytes(file);
            Document document =
                    JSON.fromJson(
                            StandardCharsets.UTF_8
                                    .newDecoder()
                                    .decode(ByteBuffer.wrap(original))
                                    .toString(),
                            Document.class);
            if (document == null
                    || document.schema != 1
                    || document.process == null
                    || document.records == null)
                throw new IOException("Invalid return ledger schema");
            var ids = new java.util.HashSet<UUID>();
            for (Diagnostic record : document.records) {
                if (record == null
                        || record.transaction == null
                        || record.player == null
                        || record.owner == null
                        || record.status == null
                        || record.phase == null
                        || record.item == null
                        || record.before == null
                        || record.prepared == null
                        || record.failure == null
                        || record.source == null
                        || !ids.add(record.transaction))
                    throw new IOException("Invalid or duplicate return diagnostic record");
                Base64.getDecoder().decode(record.item);
                Base64.getDecoder().decode(record.before);
                Base64.getDecoder().decode(record.prepared);
                recovered.add(
                        new Diagnostic(
                                record.transaction,
                                record.player,
                                record.owner,
                                record.session,
                                Status.RECOVERY_REVIEW,
                                record.phase,
                                record.inventoryPending,
                                record.authorizedReturn,
                                record.source,
                                record.item,
                                record.before,
                                record.prepared,
                                record.status == Status.RECOVERY_REVIEW
                                        ? record.failure
                                        : "Previous process "
                                                + document.process
                                                + " / "
                                                + record.status
                                                + ": "
                                                + record.failure,
                                record.createdAt));
            }
            WriteGuard.unblock(file.toFile());
            if (!recovered.isEmpty())
                plugin.getLogger()
                        .warning(
                                "Loaded "
                                        + recovered.size()
                                        + " return diagnostics for review; no previous-process item will be automatically delivered");
        } catch (Exception failure) {
            WriteGuard.block(file.toFile(), failure.toString());
            if (original != null) {
                try {
                    FileBackup.beside(file.toFile(), original);
                } catch (IOException backup) {
                    failure.addSuppressed(backup);
                }
            }
            throw new IllegalStateException(
                    "Cannot read return ledger; existing data preserved: " + file, failure);
        }
    }

    private void awaitSave() {
        if (file == null) return;
        try {
            save().get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            plugin.getLogger()
                    .log(
                            Level.SEVERE,
                            "Return diagnostics are not confirmed saved: " + file,
                            failure);
        }
    }

    @Override
    public void close() {
        thread();
        if (closing) return;
        closing = true;
        for (Entry entry : entries.values())
            if (entry.task != null) {
                entry.task.cancel();
                entry.task = null;
            }
        awaitSave();
    }

    private static String encode(ItemStack item) {
        return item == null || item.isEmpty()
                ? ""
                : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static void thread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Return ledger requires the server thread");
    }
}
