package dev.itemloom.paper.display;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.ItemStack;

/** Direct 26.2 packet mapping. Neither packet lists nor their original item stacks are edited. */
public final class ItemPackets {
    private ItemPackets() {}

    public static Object map(Object packet, BiFunction<ItemStack, Boolean, ItemStack> item) {
        if (packet instanceof ClientboundContainerSetSlotPacket p)
            return new ClientboundContainerSetSlotPacket(
                    p.getContainerId(), p.getStateId(), p.getSlot(), item.apply(p.getItem(), true));
        if (packet instanceof ClientboundContainerSetContentPacket p)
            return new ClientboundContainerSetContentPacket(
                    p.containerId(),
                    p.stateId(),
                    p.items().stream().map(s -> item.apply(s, true)).toList(),
                    item.apply(p.carriedItem(), true));
        if (packet instanceof ClientboundSetCursorItemPacket p)
            return new ClientboundSetCursorItemPacket(item.apply(p.contents(), true));
        if (packet instanceof ClientboundSetPlayerInventoryPacket p)
            return new ClientboundSetPlayerInventoryPacket(
                    p.slot(), item.apply(p.contents(), true));
        if (packet instanceof ClientboundSetEquipmentPacket p)
            return new ClientboundSetEquipmentPacket(
                    p.getEntity(),
                    p.getSlots().stream()
                            .map(
                                    pair ->
                                            Pair.of(
                                                    pair.getFirst(),
                                                    item.apply(pair.getSecond(), false)))
                            .toList());
        if (packet instanceof ClientboundSetEntityDataPacket p) {
            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(p.packedItems().size());
            for (var value : p.packedItems()) values.add(mapData(value, item));
            return new ClientboundSetEntityDataPacket(p.id(), values);
        }
        if (packet instanceof ClientboundBundlePacket p) {
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            for (var child : p.subPackets()) packets.add(cast(map(child, item)));
            return new ClientboundBundlePacket(packets);
        }
        return packet;
    }

    @SuppressWarnings("unchecked")
    private static Packet<? super ClientGamePacketListener> cast(Object packet) {
        return (Packet<? super ClientGamePacketListener>) packet;
    }

    private static <T> SynchedEntityData.DataValue<T> mapData(
            SynchedEntityData.DataValue<T> data, BiFunction<ItemStack, Boolean, ItemStack> item) {
        if (!(data.value() instanceof ItemStack stack)) return data;
        @SuppressWarnings("unchecked")
        T replacement = (T) item.apply(stack, false);
        return new SynchedEntityData.DataValue<>(data.id(), data.serializer(), replacement);
    }

    /** No allocation on the common pass-through path. */
    public static boolean any(
            Object packet, java.util.function.BiPredicate<ItemStack, Boolean> test) {
        if (packet instanceof ClientboundContainerSetSlotPacket p)
            return test.test(p.getItem(), true);
        if (packet instanceof ClientboundContainerSetContentPacket p) {
            for (ItemStack item : p.items()) if (test.test(item, true)) return true;
            return test.test(p.carriedItem(), true);
        }
        if (packet instanceof ClientboundSetCursorItemPacket p)
            return test.test(p.contents(), true);
        if (packet instanceof ClientboundSetPlayerInventoryPacket p)
            return test.test(p.contents(), true);
        if (packet instanceof ClientboundSetEquipmentPacket p) {
            for (var pair : p.getSlots()) if (test.test(pair.getSecond(), false)) return true;
        }
        if (packet instanceof ClientboundSetEntityDataPacket p) {
            for (var data : p.packedItems())
                if (data.value() instanceof ItemStack item && test.test(item, false)) return true;
        }
        if (packet instanceof ClientboundBundlePacket p)
            for (var child : p.subPackets()) if (any(child, test)) return true;
        return false;
    }
}
