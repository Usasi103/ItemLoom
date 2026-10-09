package dev.itemloom.probe;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import dev.itemloom.api.ItemDisplayEvent;
import dev.itemloom.compat.ni.NiConfig;
import dev.itemloom.compat.ni.NiRepository;
import dev.itemloom.compat.ni.NiYaml;
import dev.itemloom.paper.action.PlayerActionState;
import dev.itemloom.paper.compat.NiCatalog;
import dev.itemloom.paper.compat.NiDisplayTemplate;
import dev.itemloom.paper.display.DisplayLedger;
import dev.itemloom.paper.display.ItemDisplayService;
import dev.itemloom.paper.display.ItemPackets;
import dev.itemloom.paper.display.OrderedDisplayHandler;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Real NMS codecs/packets and EmbeddedChannel; no connected client or Internet throughput claim. */
final class ItemDisplayProbe {
    static CompletionStage<Map<String, Object>> run(JavaPlugin plugin) {
        Checks c = new Checks(plugin);
        c.group("templates", () -> templates(c, plugin));
        c.group("ledger", () -> ledger(c));
        c.group("packets", () -> packets(c));
        c.group("queue", () -> queue(c));
        c.group("failure", () -> failures(c));
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        try {
            integration(c, plugin, result);
        } catch (Throwable error) {
            c.failures.put("integration-setup", error.toString());
            result.complete(c.report());
        }
        return result;
    }

    private static void templates(Checks c, JavaPlugin plugin) {
        try (Fixture f = new Fixture(plugin)) {
            Player alice = player("Alice", new EmbeddedChannel()),
                    bob = player("Bob", new EmbeddedChannel());
            var original = f.catalog.generate("shown", alice, Map.of("roll", "saved"), false);
            var canonical = CraftItemStack.unwrap(original).copy();
            var copy = CraftItemStack.asCraftMirror(canonical.copy());
            f.catalog.display(f.catalog.displayTemplate("shown"), alice, copy);
            var shown = CraftItemStack.unwrap(copy);
            c.that(
                    shown.get(DataComponents.CUSTOM_NAME).getString().equals("Alice:saved:7"),
                    "viewer, saved roll and item placeholders resolve together");
            c.that(
                    shown.get(DataComponents.LORE).lines().getFirst().getString().equals("fixed"),
                    "constant lore line compiled with dynamic neighbors");
            c.that(
                    shown.get(DataComponents.LORE).lines().get(1).getString().equals("Alice"),
                    "dynamic lore line uses recipient");
            c.that(
                    ItemStack.matches(canonical, CraftItemStack.unwrap(original))
                            && canonical
                                    .get(DataComponents.CUSTOM_NAME)
                                    .getString()
                                    .equals("server"),
                    "display leaves canonical name and components intact");
            var other = CraftItemStack.asCraftMirror(canonical.copy());
            f.catalog.display(f.catalog.displayTemplate("shown"), bob, other);
            c.that(
                    CraftItemStack.unwrap(other)
                            .get(DataComponents.CUSTOM_NAME)
                            .getString()
                            .startsWith("Bob:"),
                    "same original renders differently for another viewer");
            c.that(f.parses.get() == 0, "display never evaluates missing generation sections");
            var empty = new NiDisplayTemplate(NiYaml.read("lore: []", "probe"));
            empty.apply(shown, text -> text);
            c.that(
                    shown.get(DataComponents.LORE) == null
                            || shown.get(DataComponents.LORE).lines().isEmpty(),
                    "empty display lore clears previous lore");
            for (String bad :
                    List.of(
                            "nbt: {x: 1}",
                            "components: {damage: 2}",
                            "name: a\ncomponents: {custom_name: b}",
                            "lore: [1]")) {
                c.reject(
                        () -> new NiDisplayTemplate(NiYaml.read(bad, "probe")),
                        "reject invalid template " + bad.replace('\n', ' '));
            }
            c.that(
                    NiYaml.read("components: {lore: null}", "probe")
                            .section("components")
                            .keys()
                            .isEmpty(),
                    "YAML null keys are absent before template compilation, matching Bukkit section semantics");
            var nullComponent = new LinkedHashMap<String, Object>();
            nullComponent.put("lore", null);
            c.reject(
                    () -> new NiDisplayTemplate(new NiConfig(Map.of("components", nullComponent))),
                    "explicit null supplied by script map is rejected");
            var p = f.catalog.itemPlaceholders();
            p.addExpansion("test", (item, params) -> params.equals("null") ? null : params);
            c.that(
                    p.parse(copy, "%TEST_a b%/%missing_x%/%test_null%")
                            .getText()
                            .equals("a b/%missing_x%/%test_null%"),
                    "percent parser preserves missing and null results, accepts spaces after underscore");
            c.that(
                    p.parse(copy, "%§ateSt_ok%").getText().equals("ok"),
                    "percent parser strips identifier color codes");
            p.addExpansion("same", (item, params) -> "%same_%");
            c.that(
                    p.parse(copy, "%same_%").getChanged(),
                    "changed means replacement occurred even when text stays equal");
            shown.set(
                    DataComponents.CUSTOM_NAME,
                    Component.translatable(
                                    "%test_translation%", Component.literal("%test_argument%"))
                            .withStyle(
                                    style -> style.withInsertion("%test_insert%").withBold(true)));
            shown.set(
                    DataComponents.LORE,
                    new ItemLore(
                            List.of(Component.literal("%test_raw%")),
                            List.of(Component.literal("%test_styled%"))));
            p.itemParse(copy);
            String json =
                    org.bukkit.craftbukkit.util.CraftChatMessage.toJSON(
                            shown.get(DataComponents.CUSTOM_NAME));
            c.that(
                    !json.contains("%test")
                            && json.contains("translation")
                            && json.contains("argument")
                            && json.contains("insert")
                            && json.contains("bold"),
                    "whole JSON replacement preserves translation, argument and style fields");
            c.that(
                    shown.get(DataComponents.LORE).lines().getFirst().getString().equals("raw")
                            && shown.get(DataComponents.LORE)
                                    .styledLines()
                                    .getFirst()
                                    .getString()
                                    .equals("styled"),
                    "both raw and styled lore channels are parsed");
            var context = f.catalog.actionContext(alice, Map.of());
            Object aliases =
                    context.invoke(
                            () ->
                                    context.evaluation()
                                            .scripts()
                                            .invoke(
                                                    "display.js",
                                                    "register",
                                                    Map.of(),
                                                    alice,
                                                    copy));
            c.that(
                    Boolean.TRUE.equals(aliases),
                    "legacy Java.type and Packages event constructors and ParseResult remain callable");
            c.that(
                    p.parse(copy, "%fromjs_value%").getText().equals("js:value:STONE"),
                    "old script BiFunction expansion executes after its registration scope ends");
            var published = f.catalog.registry().displays();
            var generator = f.catalog.registry().generators().remove("shown");
            c.that(
                    f.catalog.registry().displays().isEmpty() && published.containsKey("shown"),
                    "local registry publication atomically replaces display lookup without mutating old snapshots");
            f.catalog.registry().generators().put("shown", generator);
            c.that(
                    f.catalog.displayTemplate("shown") == published.get("shown"),
                    "legacy generator map insertion republishes display template");
            f.catalog.close();
            c.reject(
                    () -> p.parse(copy, "plain"),
                    "closed catalog rejects retained placeholder adapter");
            ((EmbeddedChannel)
                            ((org.bukkit.craftbukkit.entity.CraftPlayer) alice)
                                    .getHandle()
                                    .connection
                                    .connection
                                    .channel)
                    .finishAndReleaseAll();
            ((EmbeddedChannel)
                            ((org.bukkit.craftbukkit.entity.CraftPlayer) bob)
                                    .getHandle()
                                    .connection
                                    .connection
                                    .channel)
                    .finishAndReleaseAll();
        }
    }

    private static void ledger(Checks c) {
        var ledger = new DisplayLedger();
        ItemStack original = named("server"), display = named("viewer");
        var proof = ledger.prepare(original, display);
        c.that(
                ledger.restore(proof.display()) == null,
                "prepared but unsent display cannot authorize creative return");
        var pending = ledger.prepare(original.copyWithCount(2), display.copyWithCount(9));
        c.that(
                pending.token().equals(proof.token()) && pending.display().getCount() == 9,
                "identical uncommitted display shares token across counts and preparations");
        c.that(
                ledger.restore(pending.display()) == null && !ledger.knownOriginal(original),
                "reservation reuse does not authorize an unsent original or display");
        pending.display().set(DataComponents.CUSTOM_NAME, Component.literal("mutated copy"));
        pending.original().set(DataComponents.CUSTOM_NAME, Component.literal("mutated original"));
        var isolated = ledger.prepare(original, display);
        c.that(
                ItemStack.matches(isolated.display(), proof.display())
                        && ItemStack.matches(isolated.original(), original),
                "returned proof mutation cannot modify the shared reservation");
        ledger.commit(proof);
        ItemStack returned = proof.display().copyWithCount(13);
        c.that(
                ItemStack.isSameItemSameComponents(ledger.restore(returned), original)
                        && ledger.restore(returned).getCount() == 13,
                "successful display return restores canonical components and retains count");
        c.that(
                !DisplayLedger.marked(original) && !DisplayLedger.marked(display),
                "proof preparation never marks supplied original or rendered object");
        c.that(
                new DisplayLedger().restore(returned) == null,
                "another connection cannot use this connection's token");
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(
                DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(
                        List.of(proof.display())));
        c.that(
                DisplayLedger.hasNestedMarker(box),
                "display token cannot be hidden inside a shulker container");
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(
                DataComponents.BUNDLE_CONTENTS,
                new net.minecraft.world.item.component.BundleContents(
                        List.of(
                                net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(
                                        box))));
        c.that(
                DisplayLedger.hasNestedMarker(bundle),
                "nested container traversal finds a display copy inside bundle contents");
        ItemStack remainder = new ItemStack(Items.MILK_BUCKET);
        remainder.set(
                DataComponents.USE_REMAINDER,
                new net.minecraft.world.item.component.UseRemainder(
                        net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(
                                proof.display())));
        c.that(
                DisplayLedger.hasNestedMarker(remainder),
                "use remainder cannot preserve a displayed copy as a future server item");
        box.set(
                DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.fromItems(
                        List.of(original)));
        c.that(
                !DisplayLedger.hasNestedMarker(box),
                "ordinary nested canonical item remains accepted");
        returned.set(DataComponents.CUSTOM_NAME, Component.literal("forged"));
        c.that(ledger.restore(returned) == null, "known token with edited components is rejected");
        HashedPatchMap.HashGenerator hashes = Object::hashCode;
        HashedStack received = HashedStack.create(proof.display().copyWithCount(5), hashes);
        c.that(
                !received.matches(original.copyWithCount(5), hashes)
                        && ledger.acceptSent(received).matches(original.copyWithCount(5), hashes),
                "click hash accepts exact display corresponding to canonical stack");
        c.that(
                !ledger.acceptSent(received).matches(original.copyWithCount(4), hashes)
                        && !ledger.acceptSent(received).matches(named("different"), hashes),
                "click hash cannot authorize different amount or canonical data");
        var reused = ledger.prepare(original, display);
        c.that(
                reused.token().equals(proof.token()),
                "identical display reuses token instead of changing stacking every send");
        for (int i = 0; i < 5; i++) ledger.commit(ledger.prepare(original, named("variant-" + i)));
        c.that(
                ledger.restore(proof.display()) == null,
                "oldest variant expires after bounded per-original history");
        for (int i = 0; i < 513; i++)
            ledger.commit(ledger.prepare(named("original-" + i), named("variant")));
        c.that(
                ledger.size() == 512 && !ledger.knownOriginal(original),
                "connection ledger bounds original count and evicts old originals");
        ledger.clear();
        c.that(ledger.size() == 0, "disconnect clears proofs");
        var bounded = new DisplayLedger();
        var first = bounded.prepare(original, display);
        for (int i = 0; i < 2048; i++) bounded.prepare(named("pending-" + i), display);
        c.that(
                !bounded.prepare(original, display).token().equals(first.token())
                        && bounded.restore(first.display()) == null,
                "abandoned preparations have a bounded cache and never become authorized");
        var beforeClear = bounded.prepare(original, display);
        bounded.clear();
        c.that(
                !bounded.prepare(original, display).token().equals(beforeClear.token()),
                "disconnect also releases pending reservations");
        var detached = new DisplayLedger();
        var committed = detached.prepare(original, display);
        detached.commit(committed);
        committed
                .display()
                .set(DataComponents.CUSTOM_NAME, Component.literal("edited after commit"));
        committed
                .original()
                .set(DataComponents.CUSTOM_NAME, Component.literal("edited after commit"));
        var again = detached.prepare(original, display);
        c.that(
                again.token().equals(committed.token())
                        && ItemStack.matches(detached.restore(again.display()), original),
                "committed comparison and return state remain detached from public proof edits");
        for (boolean emptyData : List.of(false, true)) {
            var plain = named("same-components");
            if (emptyData)
                plain.set(
                        DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(new CompoundTag()));
            var local = new DisplayLedger();
            var initial = local.prepare(original, plain);
            local.commit(initial);
            var expected = initial.display().copy();
            net.minecraft.world.item.component.CustomData.update(
                    DataComponents.CUSTOM_DATA, expected, tag -> tag.remove(DisplayLedger.MARKER));
            boolean shouldReuse = ItemStack.isSameItemSameComponents(expected, plain);
            c.that(
                    local.prepare(original, plain).token().equals(initial.token()),
                    "matching reservation preserves the legacy token even when marker removal drops empty custom_data: "
                            + emptyData);
            // The original implementation can retain a reservation when removing the marker
            // also removes an empty component. Evict pending reservations before testing only
            // the committed comparison; an unsent preparation never authorizes a return.
            for (int i = 0; i < 2048; i++)
                local.prepare(named("empty-component-pending-" + i), named("pending"));
            c.that(
                    local.prepare(original, plain).token().equals(initial.token()) == shouldReuse,
                    "cached comparison preserves removal of an empty custom_data component: "
                            + emptyData);
        }
    }

    private static void packets(Checks c) {
        var item = named("server");
        var key = new SynchedEntityData.DataValue<>(9, EntityDataSerializers.ITEM_STACK, item);
        var integer = new SynchedEntityData.DataValue<>(8, EntityDataSerializers.INT, 10);
        List<Object> packets =
                List.of(
                        new ClientboundContainerSetSlotPacket(2, 3, 4, item),
                        new ClientboundContainerSetContentPacket(2, 3, List.of(item), item),
                        new ClientboundSetCursorItemPacket(item),
                        new ClientboundSetPlayerInventoryPacket(5, item),
                        new ClientboundSetEquipmentPacket(
                                6,
                                List.of(
                                        Pair.of(
                                                net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                                                item))),
                        new ClientboundSetEntityDataPacket(7, List.of(integer, key)));
        for (Object packet : packets) {
            Object mapped =
                    ItemPackets.map(
                            packet,
                            (stack, inventory) -> named(inventory ? "inventory" : "entity"));
            c.that(
                    mapped != packet
                            && ItemPackets.any(
                                    mapped,
                                    (stack, inventory) ->
                                            stack.get(DataComponents.CUSTOM_NAME)
                                                    .getString()
                                                    .equals(inventory ? "inventory" : "entity")),
                    "direct item mapping " + packet.getClass().getSimpleName());
            c.that(
                    ItemPackets.any(
                            packet,
                            (stack, inventory) ->
                                    stack.get(DataComponents.CUSTOM_NAME)
                                            .getString()
                                            .equals("server")),
                    "original packet untouched " + packet.getClass().getSimpleName());
        }
        var bundle =
                new ClientboundBundlePacket(
                        List.of(
                                (ClientboundContainerSetSlotPacket) packets.getFirst(),
                                (ClientboundSetEntityDataPacket) packets.getLast()));
        AtomicInteger count = new AtomicInteger();
        ItemPackets.map(
                bundle,
                (stack, inventory) -> {
                    count.incrementAndGet();
                    return stack.copy();
                });
        c.that(
                count.get() == 2 && ItemPackets.any(bundle, (stack, inventory) -> true),
                "bundle visits nested slot and metadata packets");
        var buffer =
                new RegistryFriendlyByteBuf(
                        Unpooled.buffer(),
                        ((CraftServer) Bukkit.getServer()).getServer().registryAccess());
        try {
            ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(
                    buffer, (ClientboundContainerSetSlotPacket) packets.getFirst());
            var decoded = ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buffer);
            c.that(
                    decoded.getContainerId() == 2
                            && decoded.getStateId() == 3
                            && decoded.getSlot() == 4
                            && ItemStack.matches(decoded.getItem(), item),
                    "actual 26.2 slot codec retains item and synchronization fields");
            buffer.clear();
            var ledger = new DisplayLedger();
            var proof = ledger.prepare(item, named("client"));
            ledger.commit(proof);
            ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(buffer, slot(proof.display()));
            var wire = ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buffer).getItem();
            c.that(
                    DisplayLedger.marked(wire) && ItemStack.matches(wire, proof.display()),
                    "actual outgoing codec preserves display components and connection marker");
            buffer.clear();
            ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.encode(
                    buffer, new ServerboundSetCreativeModeSlotPacket(3, wire));
            var inbound = ServerboundSetCreativeModeSlotPacket.STREAM_CODEC.decode(buffer);
            c.that(
                    ledger.restore(inbound.itemStack()) != null,
                    "actual creative codec round trip remains restorable");
        } finally {
            buffer.release();
        }
    }

    private static void queue(Checks c) {
        try (Harness h = new Harness()) {
            ItemStack original = named("server");
            var promise = h.channel.newPromise();
            h.channel.write(slot(original), promise);
            h.channel.flush();
            h.channel.runPendingTasks();
            h.channel.write("later");
            h.channel.flush();
            c.that(
                    h.output.isEmpty() && !promise.isDone() && h.tasks.size() == 1,
                    "pending display does not block thread or let later packets overtake");
            h.tasks.removeFirst().run();
            h.channel.runPendingTasks();
            c.that(
                    h.output.equals(List.of("display", "flush", "later", "flush"))
                            && promise.isSuccess(),
                    "packet and flush order preserved across asynchronous render");
            c.that(
                    original.get(DataComponents.CUSTOM_NAME).getString().equals("server")
                            && h.ledger.size() == 1,
                    "canonical stack isolated and proof committed only after successful write");
            var sent = ((ClientboundContainerSetSlotPacket) h.channel.readOutbound()).getItem();
            h.channel.readOutbound();
            h.channel.writeInbound(
                    new ServerboundSetCreativeModeSlotPacket(3, sent.copyWithCount(8)));
            var creative = (ServerboundSetCreativeModeSlotPacket) h.channel.readInbound();
            c.that(
                    creative.itemStack().getCount() == 8
                            && creative.itemStack()
                                    .get(DataComponents.CUSTOM_NAME)
                                    .getString()
                                    .equals("server")
                            && !DisplayLedger.marked(creative.itemStack()),
                    "channel restores creative packet before server listener");
            HashedPatchMap.HashGenerator hashes = Object::hashCode;
            var click =
                    new ServerboundContainerClickPacket(
                            1,
                            2,
                            (short) 3,
                            (byte) 0,
                            ContainerInput.PICKUP,
                            new Int2ObjectOpenHashMap<>(
                                    Map.of(3, HashedStack.create(sent, hashes))),
                            HashedStack.EMPTY);
            h.channel.writeInbound(click);
            var received = (ServerboundContainerClickPacket) h.channel.readInbound();
            c.that(
                    received.changedSlots().get(3).matches(original, hashes)
                            && received.stateId() == 2
                            && received.slotNum() == 3,
                    "channel adapts click hash without changing click intent");
            h.handler.stopRendering(h.channel.pipeline().context(h.handler));
            h.channel.writeInbound(new ServerboundSetCreativeModeSlotPacket(3, sent));
            c.that(
                    ((ServerboundSetCreativeModeSlotPacket) h.channel.readInbound())
                            .itemStack()
                            .get(DataComponents.CUSTOM_NAME)
                            .getString()
                            .equals("server"),
                    "late creative packet is still restored after rendering stops");
        }
        try (Harness h = new Harness()) {
            ItemStack original = named("server");
            h.channel.write(
                    new ClientboundContainerSetContentPacket(
                            1, 2, java.util.Collections.nCopies(8, original), ItemStack.EMPTY));
            h.channel.write(slot(original.copyWithCount(3)));
            h.channel.flush();
            h.channel.runPendingTasks();
            while (!h.tasks.isEmpty()) {
                h.tasks.removeFirst().run();
                h.channel.runPendingTasks();
            }
            var content = (ClientboundContainerSetContentPacket) h.channel.readOutbound();
            var following = (ClientboundContainerSetSlotPacket) h.channel.readOutbound();
            c.that(
                    content.items().stream()
                            .allMatch(
                                    stack ->
                                            ItemStack.isSameItemSameComponents(
                                                    stack, following.getItem())),
                    "duplicate slots and following packet share display components before the batch is committed");
            c.that(
                    content.items().stream()
                            .allMatch(
                                    stack ->
                                            ItemStack.isSameItemSameComponents(
                                                    h.ledger.restore(stack), original)),
                    "more than four identical slots remain restorable after one batch");
        }
        try (Harness h = new Harness()) {
            for (int i = 0; i < 2100; i++) {
                h.channel.write(slot(named("server")));
                h.channel.flush();
                h.channel.runPendingTasks();
                h.tasks.removeFirst().run();
                h.channel.runPendingTasks();
                h.channel.readOutbound();
            }
            c.that(
                    h.errors.isEmpty() && h.renders.get() == 2100 && h.ledger.size() == 1,
                    "completed batches release queue capacity across sustained repeated sends");
        }
        try (Harness h = new Harness()) {
            h.onWrite =
                    () -> {
                        h.channel.write("reentrant");
                        h.channel.flush();
                    };
            h.channel.write(slot(named("first")));
            h.channel.write(slot(named("second")));
            h.channel.flush();
            h.channel.runPendingTasks();
            while (!h.tasks.isEmpty()) {
                h.tasks.removeFirst().run();
                h.channel.runPendingTasks();
            }
            c.that(
                    h.output.equals(List.of("display", "display", "flush", "reentrant", "flush")),
                    "downstream reentrant sends cannot overtake remaining batch packets: "
                            + h.output);
        }
        try (Harness h = new Harness()) {
            h.onWrite = () -> h.channel.close();
            h.channel.write(slot(named("first")));
            var buffer = Unpooled.buffer().writeByte(1);
            var pending = h.channel.newPromise();
            h.channel.write(buffer, pending);
            h.channel.flush();
            h.channel.runPendingTasks();
            h.tasks.removeFirst().run();
            h.channel.runPendingTasks();
            c.that(
                    buffer.refCnt() == 0 && pending.isDone() && !pending.isSuccess(),
                    "reentrant close releases undelivered batch buffers exactly once and fails promises");
        }
    }

    private static void failures(Checks c) {
        try (Harness h = new Harness()) {
            h.channel.write(slot(named("server")));
            h.channel.flush();
            h.channel.runPendingTasks();
            for (int i = 0; i < 257; i++) h.channel.write("tail-" + i);
            h.channel.flush();
            c.that(
                    h.errors.size() == 1
                            && h.output.getFirst().equals("server")
                            && h.output.indexOf("tail-0") < h.output.indexOf("tail-256"),
                    "queue overflow drains original packets in order");
            h.tasks.forEach(Runnable::run);
            h.channel.runPendingTasks();
            c.that(
                    h.renders.get() == 0 && h.output.stream().noneMatch("display"::equals),
                    "cancelled batch never invokes delayed renderer");
        }
        try (Harness h = new Harness()) {
            var promise = h.channel.newPromise();
            h.channel.write(slot(named("server")), promise);
            h.channel.runPendingTasks();
            var buffer = Unpooled.buffer().writeByte(1);
            var second = h.channel.newPromise();
            h.channel.write(buffer, second);
            h.channel.close();
            h.tasks.forEach(Runnable::run);
            h.channel.runPendingTasks();
            c.that(
                    promise.isDone()
                            && !promise.isSuccess()
                            && second.isDone()
                            && buffer.refCnt() == 0
                            && h.renders.get() == 0,
                    "disconnect fails pending promises, releases queued buffers and cancels renderer");
        }
        try (Harness h = new Harness()) {
            h.failRender = true;
            h.channel.write(slot(named("server")));
            h.channel.flush();
            h.channel.runPendingTasks();
            h.tasks.removeFirst().run();
            h.channel.runPendingTasks();
            c.that(
                    h.errors.size() == 1
                            && h.output.getFirst().equals("server")
                            && h.renders.get() == 1,
                    "render exception falls back once without replaying user code");
        }
        try (Harness h = new Harness()) {
            h.failWrite = true;
            var promise = h.channel.newPromise();
            h.channel.write(slot(named("server")), promise);
            h.channel.flush();
            h.channel.runPendingTasks();
            h.tasks.removeFirst().run();
            h.channel.runPendingTasks();
            c.that(
                    !promise.isSuccess() && h.ledger.size() == 0,
                    "failed network write never authorizes the rendered display");
        }
        try (Harness h = new Harness()) {
            h.channel.write(slot(named("server")));
            h.channel.flush();
            h.channel.runPendingTasks();
            h.channel.advanceTimeBy(251, java.util.concurrent.TimeUnit.MILLISECONDS);
            h.channel.runScheduledPendingTasks();
            c.that(
                    h.errors.size() == 1 && h.output.getFirst().equals("server"),
                    "stalled main-thread task has bounded queue residence");
            h.tasks.forEach(Runnable::run);
            c.that(h.renders.get() == 0, "timed-out task cannot later render");
        }
        try (Harness h = new Harness()) {
            var packet =
                    new ClientboundContainerSetContentPacket(
                            1,
                            2,
                            java.util.Collections.nCopies(2049, named("server")),
                            ItemStack.EMPTY);
            h.channel.write(packet);
            h.channel.flush();
            h.channel.runPendingTasks();
            c.that(
                    h.errors.size() == 1 && h.tasks.isEmpty() && h.channel.readOutbound() == packet,
                    "oversized item count falls back before scheduling script work");
        }
        var errors = new ArrayList<Throwable>();
        var ledger = new DisplayLedger();
        var rejected =
                new OrderedDisplayHandler(
                        packet -> true,
                        (packet, live) -> {
                            throw new AssertionError("must not execute");
                        },
                        task -> {
                            throw new IllegalStateException("injected scheduler rejection");
                        },
                        errors::add,
                        () -> {},
                        stack -> true,
                        ledger);
        var channel = new EmbeddedChannel(rejected);
        try {
            channel.write(slot(named("server")));
            channel.flush();
            channel.runPendingTasks();
            var sent = (ClientboundContainerSetSlotPacket) channel.readOutbound();
            c.that(
                    errors.size() == 1
                            && sent.getItem()
                                    .get(DataComponents.CUSTOM_NAME)
                                    .getString()
                                    .equals("server"),
                    "scheduler rejection immediately sends canonical item");
            channel.writeInbound(new ServerboundSetCreativeModeSlotPacket(3, sent.getItem()));
            c.that(
                    channel.readInbound() != null,
                    "successful canonical fallback remains a legitimate creative return");
            channel.writeInbound(new ServerboundSetCreativeModeSlotPacket(3, named("forged")));
            c.that(
                    channel.readInbound() == null,
                    "unmarked managed item not sent to connection is rejected");
            var foreign = new DisplayLedger().prepare(named("server"), named("display"));
            channel.writeInbound(new ServerboundSetCreativeModeSlotPacket(3, foreign.display()));
            c.that(channel.readInbound() == null, "foreign token is rejected by inbound channel");
            var box = new ItemStack(Items.SHULKER_BOX);
            box.set(
                    DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(
                            List.of(foreign.display())));
            channel.writeInbound(new ServerboundSetCreativeModeSlotPacket(3, box));
            c.that(
                    channel.readInbound() == null,
                    "nested foreign token is rejected before server listener");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void integration(
            Checks c, JavaPlugin plugin, CompletableFuture<Map<String, Object>> result) {
        Fixture f = new Fixture(plugin);
        AtomicReference<NiCatalog> current = new AtomicReference<>(f.catalog);
        var original =
                CraftItemStack.unwrap(
                        f.catalog.generate("shown", null, Map.of("roll", "saved"), false));
        var generator = f.catalog.registry().generators().remove("shown");
        ItemDisplayService service = new ItemDisplayService(plugin, current::get);
        service.start();
        var channel = new EmbeddedChannel();
        channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
        Player viewer = player("Integrated", channel);
        var serverPlayer = ((org.bukkit.craftbukkit.entity.CraftPlayer) viewer).getHandle();
        serverPlayer.getInventory().setItem(0, original.copy());
        serverPlayer.initInventoryMenu();
        channel.runPendingTasks();
        boolean initialCanonical = false;
        for (Object sent; (sent = channel.readOutbound()) != null; ) {
            if (sent instanceof ClientboundContainerSetContentPacket content)
                initialCanonical |=
                        content.items().stream()
                                .anyMatch(stack -> ItemStack.matches(stack, original));
        }
        c.that(
                initialCanonical,
                "actual inventory initialization sends canonical contents before the join handler exists");
        service.join(new PlayerJoinEvent(viewer, (net.kyori.adventure.text.Component) null));
        channel.runPendingTasks();
        channel.writeInbound(new ServerboundSetCreativeModeSlotPacket(3, original));
        c.that(
                channel.readInbound() != null,
                "no display publication leaves ordinary creative behavior intact");
        f.catalog.registry().generators().put("shown", generator);
        channel.writeInbound(new ServerboundSetCreativeModeSlotPacket(3, original));
        c.that(
                channel.readInbound() == null,
                "local generator publication protects unsent originals immediately without a tick");
        Listener listener = new Listener() {};
        AtomicInteger events = new AtomicInteger();
        Bukkit.getPluginManager()
                .registerEvent(
                        ItemDisplayEvent.class,
                        listener,
                        EventPriority.NORMAL,
                        (ignored, raw) -> {
                            var event = (ItemDisplayEvent) raw;
                            if (event.getViewer() != viewer) return;
                            c.that(
                                    Bukkit.isPrimaryThread() && !event.isAsynchronous(),
                                    "network event dispatches on main thread");
                            events.incrementAndGet();
                        },
                        plugin);
        // EmbeddedChannel has no autonomous event loop. Pump scheduled writes while Bukkit
        // performs the initial inventory correction and its subsequent main-thread render.
        var network = Bukkit.getScheduler().runTaskTimer(plugin, channel::runPendingTasks, 1, 1);
        CompletableFuture.runAsync(
                        () -> {
                            channel.writeAndFlush(slot(original));
                            channel.runPendingTasks();
                        })
                .whenComplete(
                        (ignored, failure) -> {
                            if (failure != null)
                                c.failures.put("integration-send", failure.toString());
                        });
        Bukkit.getScheduler()
                .runTaskLater(
                        plugin,
                        () -> {
                            try {
                                channel.runPendingTasks();
                                ClientboundContainerSetSlotPacket packet = null;
                                boolean correctedInitial = false;
                                for (Object sent; (sent = channel.readOutbound()) != null; ) {
                                    if (sent instanceof ClientboundContainerSetSlotPacket slot
                                            && slot.getContainerId() == 1
                                            && slot.getSlot() == 3) packet = slot;
                                    if (sent
                                            instanceof ClientboundContainerSetContentPacket content)
                                        correctedInitial |=
                                                content.items().stream()
                                                        .anyMatch(
                                                                stack ->
                                                                        DisplayLedger.marked(stack)
                                                                                && stack.get(
                                                                                                DataComponents
                                                                                                        .CUSTOM_NAME)
                                                                                        .getString()
                                                                                        .equals(
                                                                                                "Integrated:saved:7"));
                                }
                                c.that(
                                        correctedInitial,
                                        "attach resynchronizes inventory items that were first sent before join");
                                c.that(
                                        packet != null
                                                && packet.getItem()
                                                        .get(DataComponents.CUSTOM_NAME)
                                                        .getString()
                                                        .equals("Integrated:saved:7")
                                                && events.get() >= 2,
                                        "real service injection schedules worker-originated packet through catalog rendering");
                                c.that(
                                        original.get(DataComponents.CUSTOM_NAME)
                                                        .getString()
                                                        .equals("server")
                                                && !DisplayLedger.marked(original),
                                        "integrated send leaves server item unmodified");
                                var display = packet.getItem();
                                try (Fixture replacement = new Fixture(plugin, "reloaded")) {
                                    current.set(replacement.catalog);
                                    f.catalog.close();
                                    var unsent =
                                            CraftItemStack.unwrap(
                                                    replacement.catalog.generate(
                                                            "reloaded",
                                                            viewer,
                                                            Map.of("roll", "saved"),
                                                            false));
                                    channel.writeInbound(
                                            new ServerboundSetCreativeModeSlotPacket(3, unsent));
                                    c.that(
                                            channel.readInbound() == null,
                                            "full catalog replacement protects newly managed originals immediately without a tick");
                                    channel.writeInbound(
                                            new ServerboundSetCreativeModeSlotPacket(3, display));
                                    var late =
                                            (ServerboundSetCreativeModeSlotPacket)
                                                    channel.readInbound();
                                    c.that(
                                            late != null
                                                    && late.itemStack()
                                                            .get(DataComponents.CUSTOM_NAME)
                                                            .getString()
                                                            .equals("server"),
                                            "catalog replacement preserves proofs for already-sent displays");
                                    service.close();
                                    channel.runPendingTasks();
                                    current.set(null);
                                    channel.writeInbound(
                                            new ServerboundSetCreativeModeSlotPacket(3, unsent));
                                    c.that(
                                            channel.readInbound() == null,
                                            "closed service detaches live catalog but retains the final return policy");
                                }
                                channel.writeInbound(
                                        new ServerboundSetCreativeModeSlotPacket(3, display));
                                var returned =
                                        (ServerboundSetCreativeModeSlotPacket)
                                                channel.readInbound();
                                c.that(
                                        returned != null
                                                && returned.itemStack()
                                                        .get(DataComponents.CUSTOM_NAME)
                                                        .getString()
                                                        .equals("server"),
                                        "service close retains guard for outstanding display return");
                            } catch (Throwable error) {
                                c.failures.put("integration", error.toString());
                                plugin.getLogger()
                                        .log(
                                                java.util.logging.Level.WARNING,
                                                "Display integration probe",
                                                error);
                            } finally {
                                network.cancel();
                                service.close();
                                HandlerList.unregisterAll(listener);
                                f.close();
                                channel.finishAndReleaseAll();
                                result.complete(c.report());
                            }
                        },
                        5);
    }

    private static ItemStack named(String text) {
        var item = new ItemStack(Items.STONE);
        item.set(DataComponents.CUSTOM_NAME, Component.literal(text));
        return item;
    }

    private static ClientboundContainerSetSlotPacket slot(ItemStack item) {
        return new ClientboundContainerSetSlotPacket(1, 2, 3, item);
    }

    private static Player player(String name, EmbeddedChannel channel) {
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        var profile = new GameProfile(UUID.randomUUID(), name);
        var player =
                new ServerPlayer(
                        server,
                        ((CraftWorld) Bukkit.getWorlds().getFirst()).getHandle(),
                        profile,
                        ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        connection.channel = channel;
        player.connection =
                new ServerGamePacketListenerImpl(
                        server,
                        connection,
                        player,
                        CommonListenerCookie.createInitial(profile, false));
        return player.getBukkitEntity();
    }

    private static final class Harness implements AutoCloseable {
        final List<Runnable> tasks = new ArrayList<>();
        final List<String> output = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final AtomicInteger renders = new AtomicInteger();
        final DisplayLedger ledger = new DisplayLedger();
        boolean failRender, failWrite;
        Runnable onWrite;
        final OrderedDisplayHandler handler =
                new OrderedDisplayHandler(
                        packet -> ItemPackets.any(packet, (stack, ordinary) -> true),
                        (packet, live) -> {
                            renders.incrementAndGet();
                            if (failRender)
                                throw new IllegalStateException("injected render failure");
                            var sent = new ArrayList<DisplayLedger.Sent>();
                            Object mapped =
                                    ItemPackets.map(
                                            packet,
                                            (stack, ordinary) -> {
                                                if (stack.isEmpty()) return stack;
                                                var proof =
                                                        ledger.prepare(
                                                                stack,
                                                                named("display")
                                                                        .copyWithCount(
                                                                                stack.getCount()));
                                                sent.add(proof);
                                                return proof.display();
                                            });
                            return new OrderedDisplayHandler.Rendered(mapped, List.copyOf(sent));
                        },
                        tasks::add,
                        errors::add,
                        () -> {},
                        stack -> false,
                        ledger);
        final EmbeddedChannel channel =
                new EmbeddedChannel(
                        new ChannelDuplexHandler() {
                            @Override
                            public void write(
                                    ChannelHandlerContext ctx,
                                    Object message,
                                    ChannelPromise promise) {
                                if (failWrite) {
                                    promise.tryFailure(
                                            new IllegalStateException("injected write failure"));
                                    return;
                                }
                                output.add(
                                        message instanceof ClientboundContainerSetSlotPacket p
                                                ? p.getItem()
                                                        .get(DataComponents.CUSTOM_NAME)
                                                        .getString()
                                                : String.valueOf(message));
                                ctx.write(message, promise);
                                Runnable reenter = onWrite;
                                onWrite = null;
                                if (reenter != null) reenter.run();
                            }

                            @Override
                            public void flush(ChannelHandlerContext ctx) {
                                output.add("flush");
                                ctx.flush();
                            }
                        },
                        handler);

        @Override
        public void close() {
            channel.finishAndReleaseAll();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final PlayerActionState players = new PlayerActionState();
        final NiCatalog catalog;
        final AtomicInteger parses = new AtomicInteger();

        Fixture(JavaPlugin plugin) {
            this(plugin, "shown");
        }

        Fixture(JavaPlugin plugin, String id) {
            NiConfig definition =
                    NiYaml.read(
                            """
                    material: STONE
                    name: server
                    options: {charge: 7}
                    sections: {unused: '<papi::must-not-run>'}
                    client_bound_data:
                      name: '<viewer_name>:<roll>:%neigeitems_charge%'
                      lore: ['fixed', '<viewer_name>']
                    """,
                            "probe");
            var input =
                    new NiRepository.Input(
                            new NiConfig(Map.of("ItemPlaceholder", Map.of("enable", true))),
                            Map.of(
                                    id,
                                    new NiRepository.Definition(id, "Items/probe.yml", definition)),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Map.of(
                                    "display.js",
                                    """
                    function register(player, stack) {
                      var P = Java.type('pers.neige.neigeitems.item.ItemPlaceholder');
                      var R = Java.type('pers.neige.neigeitems.item.ItemPlaceholder$ParseResult');
                      var E = Java.type('pers.neige.neigeitems.event.ItemPacketEvent');
                      P.INSTANCE.addExpansion('fromjs', function(item, params) { return 'js:' + params + ':' + item.getType(); });
                      var a = new E(player, stack), b = new Packages.pers.neige.neigeitems.event.ItemPacketEvent(player, stack);
                      var r = new R('text', true);
                      return a instanceof E && b instanceof E && a.getPlayer() == player && a.getItemStack() == stack
                        && a.getHandlers() == b.getHandlers() && a.getEventName() == 'ItemPacketEvent' && r.getText() == 'text' && r.getChanged();
                    }
                    """),
                            Map.of(),
                            Map.of());
            catalog =
                    new NiCatalog(
                            1,
                            input,
                            plugin,
                            (viewer, text) -> {
                                parses.incrementAndGet();
                                return text;
                            },
                            players);
        }

        @Override
        public void close() {
            catalog.close();
            players.close();
        }
    }

    private static final class Checks {
        final JavaPlugin plugin;
        final List<String> assertions = new ArrayList<>();
        final Map<String, String> failures = new LinkedHashMap<>();

        Checks(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        void that(boolean okay, String text) {
            if (!okay) throw new AssertionError(text);
            assertions.add(text);
        }

        void reject(Runnable action, String text) {
            boolean rejected = false;
            try {
                action.run();
            } catch (RuntimeException expected) {
                rejected = true;
            }
            that(rejected, text);
        }

        void group(String name, Runnable action) {
            try {
                action.run();
            } catch (Throwable error) {
                failures.put(name, error.toString());
                plugin.getLogger()
                        .log(java.util.logging.Level.WARNING, "Display probe " + name, error);
            }
        }

        Map<String, Object> report() {
            return Map.of(
                    "passed",
                    failures.isEmpty(),
                    "checks",
                    assertions.size(),
                    "assertions",
                    assertions,
                    "failures",
                    failures,
                    "realClient",
                    false,
                    "boundary",
                    "Actual Paper 26.2 NMS packets, item codecs and EmbeddedChannel; no real client rendering or bandwidth benchmark");
        }
    }
}
