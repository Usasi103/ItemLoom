package dev.itemloom.paper.action;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.itemloom.paper.ItemsService;
import dev.keystone.storage.FileBackup;
import dev.keystone.storage.StorageWriter;
import dev.keystone.storage.WriteGuard;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class ReturnLedger implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final JavaPlugin plugin;
    private final PlayerActionState players;
    private final Path path;
    private final UUID process = UUID.randomUUID();
    private final LinkedHashMap<UUID, Entry> live = new LinkedHashMap<>();
    private final List<Diagnostic> recovered = new ArrayList<>();
    private final Map<UUID, Integer> leases = new HashMap<>();
    private boolean closing;
    private BukkitTask flushTask;
    private final Object writerLock = new Object();
    private Document queued;
    private Document persisted;
    private boolean writing;
    private CompletableFuture<Void> saving = CompletableFuture.completedFuture(null);

    ReturnLedger(JavaPlugin plugin, PlayerActionState players, Path path) {
        ItemsService.requireThread();
        this.plugin = plugin;
        this.players = players;
        this.path = path;
        load();
    }

    void begin(UUID owner) {
        ItemsService.requireThread();
        if (closing) throw new IllegalStateException("Return ledger is closing");
        leases.merge(owner, 1, Integer::sum);
    }

    void end(UUID owner) {
        ItemsService.requireThread();
        Integer count = leases.get(owner);
        if (count == null) throw new IllegalStateException("Unbalanced return lease");
        if (count == 1) leases.remove(owner);
        else leases.put(owner, count - 1);
        if (closing && leases.isEmpty()) await(save());
    }

    Entry register(
            UUID owner,
            Player player,
            ItemStack item,
            boolean ready,
            boolean authorized,
            String source,
            ItemStack before) {
        return registerEncoded(
                owner, player, item, ready, authorized, source, encode(before), null);
    }

    Entry registerPrepared(
            UUID owner, Player player, ItemStack item, String source, SourceSnapshot snapshots) {
        return registerEncoded(
                owner, player, item, false, true, source, snapshots.before(), snapshots.prepared());
    }

    private Entry registerEncoded(
            UUID owner,
            Player player,
            ItemStack item,
            boolean ready,
            boolean authorized,
            String source,
            String before,
            String prepared) {
        ItemsService.requireThread();
        if (closing && !leases.containsKey(owner))
            throw new IllegalStateException("Return ledger is closing");
        players.observe(player);
        Entry entry =
                new Entry(
                        owner,
                        player.getUniqueId(),
                        players.session(player.getUniqueId()),
                        item,
                        ready,
                        authorized,
                        source,
                        before,
                        prepared);
        live.put(entry.id, entry);
        changed(false);
        return entry;
    }

    void preparing(Entry entry, ItemStack prepared) {
        ItemsService.requireThread();
        if (!contains(entry)) return;
        entry.prepared = encode(prepared);
        entry.phase = Phase.SOURCE_COMMIT;
        changed(false);
    }

    void ready(Entry entry) {
        ItemsService.requireThread();
        if (!contains(entry) || entry.outcomeUnknown) return;
        entry.ready = true;
        changed(false);
    }

    void sourceUnknown(Entry entry, ItemStack prepared, Throwable failure) {
        ItemsService.requireThread();
        if (!contains(entry)) return;
        entry.outcomeUnknown = true;
        if (prepared != null) {
            try {
                entry.prepared = encode(prepared);
            } catch (RuntimeException encoding) {
                if (failure == null) failure = encoding;
                else failure.addSuppressed(encoding);
            }
        }
        sourceUnknown(entry, failure);
    }

    void sourceUnknown(Entry entry, Throwable failure) {
        ItemsService.requireThread();
        if (!contains(entry)) return;
        entry.phase = Phase.SOURCE_COMMIT;
        quarantine(entry, failure);
    }

    void abort(Entry entry) {
        ItemsService.requireThread();
        if (contains(entry) && !entry.outcomeUnknown) remove(entry);
    }

    List<Entry> entries(UUID owner) {
        ItemsService.requireThread();
        return live.values().stream().filter(entry -> entry.owner.equals(owner)).toList();
    }

    public List<Diagnostic> diagnostics() {
        ItemsService.requireThread();
        List<Diagnostic> result = new ArrayList<>(recovered);
        for (Entry entry : live.values()) result.add(entry.diagnostic());
        return List.copyOf(result);
    }

    void schedule(Entry entry) {
        ItemsService.requireThread();
        if (closing
                || !contains(entry)
                || !entry.ready
                || entry.outcomeUnknown
                || entry.task != null) return;
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
            entry.failure = failure.toString();
            plugin.getLogger()
                    .log(Level.WARNING, "Could not schedule item return " + entry.id, failure);
            changed(false);
        }
    }

    void joined(Player player) {
        ItemsService.requireThread();
        if (closing) return;
        for (Entry entry : new ArrayList<>(live.values())) {
            if (entry.player.equals(player.getUniqueId()) && entry.ready && !entry.outcomeUnknown) {
                entry.session = players.session(entry.player);
                changed(false);
                schedule(entry);
            }
        }
    }

    public void flush(Player player) {
        ItemsService.requireThread();
        for (Entry entry : new ArrayList<>(live.values())) {
            if (entry.player.equals(player.getUniqueId())) deliver(entry);
        }
    }

    public void flushAll() {
        ItemsService.requireThread();
        for (Entry entry : new ArrayList<>(live.values())) deliver(entry);
    }

    void deliver(Entry entry) {
        ItemsService.requireThread();
        if (closing
                || !contains(entry)
                || !entry.ready
                || entry.outcomeUnknown
                || !entry.authorizedReturn
                || leases.containsKey(entry.owner)) return;
        Player player = players.resolve(entry.player, entry.session);
        if (player == null) return;
        try {
            if (entry.inventoryPending) {
                ItemStack supplied = entry.item.clone();
                entry.phase = Phase.INVENTORY_ADD;
                entry.outcomeUnknown = true;
                changed(false);
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(supplied.clone());
                if (leftovers.isEmpty()) {
                    remove(entry);
                    return;
                }
                if (leftovers.size() != 1 || !leftovers.containsKey(0)) {
                    throw new IllegalStateException("Inventory returned unrecognized leftovers");
                }
                ItemStack remainder = leftovers.get(0);
                if (remainder == null || remainder.isEmpty()) {
                    remove(entry);
                    return;
                }
                if (!remainder.isSimilar(supplied)
                        || remainder.getAmount() > supplied.getAmount()) {
                    throw new IllegalStateException("Inventory returned an invalid leftover stack");
                }
                entry.item = remainder.clone();
                entry.encoded = encode(entry.item);
                entry.inventoryPending = false;
                entry.outcomeUnknown = false;
                entry.failure = null;
                changed(false);
            }
            if (entry.item.isEmpty()) {
                remove(entry);
                return;
            }
            player = players.resolve(entry.player, entry.session);
            if (player == null || closing) return;
            entry.phase = Phase.WORLD_DROP;
            entry.outcomeUnknown = true;
            changed(false);
            Item dropped = player.getWorld().dropItem(player.getLocation(), entry.item.clone());
            if (dropped == null || !dropped.isValid())
                throw new IllegalStateException("World did not confirm an item return");
            remove(entry);
        } catch (RuntimeException failure) {
            quarantine(entry, failure);
        }
    }

    private boolean contains(Entry entry) {
        return entry != null && live.get(entry.id) == entry;
    }

    private void remove(Entry entry) {
        if (!contains(entry)) return;
        live.remove(entry.id);
        if (entry.task != null) entry.task.cancel();
        entry.task = null;
        changed(false);
    }

    private void quarantine(Entry entry, Throwable failure) {
        if (!contains(entry)) return;
        entry.outcomeUnknown = true;
        entry.failure = failure == null ? "Unknown item delivery result" : failure.toString();
        if (entry.task != null) entry.task.cancel();
        entry.task = null;
        plugin.getLogger()
                .log(
                        Level.SEVERE,
                        "Item return " + entry.id + " requires review: " + entry.failure,
                        failure);
        changed(true);
    }

    private void changed(boolean urgent) {
        if (path == null) return;
        if (closing || urgent) {
            save();
            return;
        }
        if (flushTask != null) return;
        try {
            flushTask =
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    plugin,
                                    () -> {
                                        flushTask = null;
                                        save();
                                    },
                                    1);
        } catch (RuntimeException failure) {
            save();
        }
    }

    public CompletableFuture<Void> save() {
        ItemsService.requireThread();
        if (flushTask != null) flushTask.cancel();
        flushTask = null;
        if (path == null) return CompletableFuture.completedFuture(null);
        if (WriteGuard.isBlocked(path.toFile())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Return archive is write-blocked"));
        }
        Document snapshot = new Document(1, process, diagnostics());
        synchronized (writerLock) {
            if (!writing && snapshot.equals(persisted)) return saving;
            queued = snapshot;
            if (writing) return saving;
            writing = true;
            CompletableFuture<Void> completion = new CompletableFuture<>();
            saving = completion;
            StorageWriter.supply(
                            () -> {
                                try {
                                    writeQueued();
                                    completion.complete(null);
                                } catch (Throwable failure) {
                                    synchronized (writerLock) {
                                        writing = false;
                                    }
                                    plugin.getLogger()
                                            .log(
                                                    Level.SEVERE,
                                                    "Could not save item return archive",
                                                    failure);
                                    completion.completeExceptionally(failure);
                                }
                                return null;
                            })
                    .whenComplete(
                            (ignored, failure) -> {
                                if (failure != null) {
                                    synchronized (writerLock) {
                                        writing = false;
                                    }
                                    plugin.getLogger()
                                            .log(
                                                    Level.SEVERE,
                                                    "Could not dispatch item return archive save",
                                                    failure);
                                    completion.completeExceptionally(failure);
                                }
                            });
            return completion;
        }
    }

    private void writeQueued() throws Exception {
        while (true) {
            Document next;
            synchronized (writerLock) {
                next = queued;
                queued = null;
                if (next == null) {
                    writing = false;
                    return;
                }
            }
            if (!next.equals(persisted)) {
                if (WriteGuard.isBlocked(path.toFile()))
                    throw new IllegalStateException("Return archive is write-blocked");
                StorageWriter.writeAtomic(path.toFile(), JSON.toJson(next));
                synchronized (writerLock) {
                    persisted = next;
                }
            }
        }
    }

    private void load() {
        if (path == null) return;
        byte[] original = null;
        try {
            StorageWriter.recover(path.toFile());
            if (Files.exists(path.resolveSibling(path.getFileName() + ".previous"))) {
                throw new IllegalStateException("Unresolved previous return archive remains");
            }
            if (!Files.exists(path)) {
                WriteGuard.unblock(path.toFile());
                return;
            }
            original = Files.readAllBytes(path);
            String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(original))
                            .toString();
            JsonObject archive = JSON.fromJson(text, JsonObject.class);
            validateFields(archive);
            Document document = JSON.fromJson(archive, Document.class);
            if (document == null
                    || document.schema != 1
                    || document.process == null
                    || document.records == null) {
                throw new IllegalArgumentException("Invalid return archive header");
            }
            Set<UUID> transactions = new HashSet<>();
            for (Diagnostic record : document.records) {
                validate(record, transactions);
                String failure =
                        record.status == Status.RECOVERY_REVIEW
                                ? record.failure
                                : "Previous process "
                                        + document.process
                                        + " status "
                                        + record.status
                                        + ": "
                                        + Objects.toString(record.failure, "");
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
                                failure,
                                record.createdAt));
            }
            WriteGuard.unblock(path.toFile());
            if (!recovered.isEmpty())
                plugin.getLogger()
                        .warning(
                                "Recovered "
                                        + recovered.size()
                                        + " item return records for review; automatic delivery is disabled");
        } catch (Exception failure) {
            WriteGuard.block(
                    path.toFile(), "Cannot read item return archive: " + failure.getMessage());
            if (original != null) {
                try {
                    FileBackup.beside(path.toFile(), original);
                } catch (Exception backup) {
                    failure.addSuppressed(backup);
                }
            }
            throw new IllegalStateException("Cannot load item return archive " + path, failure);
        }
    }

    private static void validateFields(JsonObject archive) {
        if (archive == null) throw new IllegalArgumentException("Missing return archive");
        if (integer(archive, "schema") != 1)
            throw new IllegalArgumentException("Invalid return archive schema");
        string(archive, "process");
        JsonElement records = archive.get("records");
        if (records == null || !records.isJsonArray())
            throw new IllegalArgumentException("Invalid return records");
        for (JsonElement element : records.getAsJsonArray()) {
            if (!element.isJsonObject())
                throw new IllegalArgumentException("Invalid return record");
            JsonObject record = element.getAsJsonObject();
            for (String key :
                    List.of(
                            "transaction",
                            "player",
                            "owner",
                            "status",
                            "phase",
                            "source",
                            "item")) {
                string(record, key);
            }
            integer(record, "session");
            integer(record, "createdAt");
            for (String key : List.of("inventoryPending", "authorizedReturn")) {
                if (!primitive(record, key).isBoolean())
                    throw new IllegalArgumentException("Invalid return field " + key);
            }
            for (String key : List.of("before", "prepared", "failure")) {
                JsonElement value = record.get(key);
                if (value != null && !value.isJsonNull()) string(record, key);
            }
        }
    }

    private static JsonPrimitive primitive(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive())
            throw new IllegalArgumentException("Invalid return field " + key);
        return value.getAsJsonPrimitive();
    }

    private static void string(JsonObject object, String key) {
        if (!primitive(object, key).isString())
            throw new IllegalArgumentException("Invalid return field " + key);
    }

    private static long integer(JsonObject object, String key) {
        JsonPrimitive value = primitive(object, key);
        if (!value.isNumber()) throw new IllegalArgumentException("Invalid return field " + key);
        return value.getAsBigDecimal().longValueExact();
    }

    private static void validate(Diagnostic record, Set<UUID> transactions) {
        if (record == null
                || record.transaction == null
                || record.player == null
                || record.owner == null
                || record.status == null
                || record.phase == null
                || record.source == null
                || record.item == null
                || !transactions.add(record.transaction))
            throw new IllegalArgumentException("Invalid return record");
        Base64.getDecoder().decode(record.item);
        if (record.before != null) Base64.getDecoder().decode(record.before);
        if (record.prepared != null) Base64.getDecoder().decode(record.prepared);
    }

    @Override
    public void close() {
        ItemsService.requireThread();
        if (closing) return;
        closing = true;
        for (Entry entry : live.values()) {
            if (entry.task != null) entry.task.cancel();
            entry.task = null;
        }
        await(save());
    }

    private void await(CompletableFuture<Void> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.SEVERE, "Item return archive save not confirmed", failure);
        } catch (Exception failure) {
            plugin.getLogger().log(Level.SEVERE, "Item return archive save not confirmed", failure);
        }
    }

    private static String encode(ItemStack item) {
        return item == null || item.isEmpty()
                ? ""
                : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

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

    static final class Entry {
        final UUID id = UUID.randomUUID();
        final UUID player;
        final UUID owner;
        final String source;
        final String before;
        final long createdAt = System.currentTimeMillis();
        long session;
        ItemStack item;
        String encoded;
        String prepared;
        String failure;
        Phase phase;
        boolean ready;
        boolean outcomeUnknown;
        boolean inventoryPending = true;
        final boolean authorizedReturn;
        BukkitTask task;

        Entry(
                UUID owner,
                UUID player,
                long session,
                ItemStack item,
                boolean ready,
                boolean authorized,
                String source,
                String before,
                String prepared) {
            this.owner = owner;
            this.player = player;
            this.session = session;
            this.item = item.clone();
            this.encoded = encode(this.item);
            this.ready = ready;
            this.authorizedReturn = authorized;
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

    record SourceSnapshot(String before, String prepared) {
        static SourceSnapshot capture(ItemStack before, ItemStack prepared) {
            ItemsService.requireThread();
            return new SourceSnapshot(encode(before), encode(prepared));
        }
    }
}
