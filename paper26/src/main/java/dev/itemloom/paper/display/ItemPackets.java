package dev.itemloom.paper.display;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.ItemStack;

/** The item-bearing packet surface of the configured Paper version. */
public final class ItemPackets {
    private ItemPackets() {}

    public static Object map(Object packet, BiFunction<ItemStack, Boolean, ItemStack> mapper) {
        return switch (packet) {
            case ClientboundContainerSetSlotPacket p ->
                    new ClientboundContainerSetSlotPacket(
                            p.getContainerId(),
                            p.getStateId(),
                            p.getSlot(),
                            mapper.apply(p.getItem(), true));
            case ClientboundContainerSetContentPacket p ->
                    new ClientboundContainerSetContentPacket(
                            p.containerId(),
                            p.stateId(),
                            p.items().stream().map(item -> mapper.apply(item, true)).toList(),
                            mapper.apply(p.carriedItem(), true));
            case ClientboundSetCursorItemPacket p ->
                    new ClientboundSetCursorItemPacket(mapper.apply(p.contents(), true));
            case ClientboundSetPlayerInventoryPacket p ->
                    new ClientboundSetPlayerInventoryPacket(
                            p.slot(), mapper.apply(p.contents(), true));
            case ClientboundSetEquipmentPacket p ->
                    new ClientboundSetEquipmentPacket(
                            p.getEntity(),
                            p.getSlots().stream()
                                    .map(
                                            slot ->
                                                    Pair.of(
                                                            slot.getFirst(),
                                                            mapper.apply(slot.getSecond(), false)))
                                    .toList());
            case ClientboundSetEntityDataPacket p ->
                    new ClientboundSetEntityDataPacket(
                            p.id(),
                            p.packedItems().stream()
                                    .<SynchedEntityData.DataValue<?>>map(
                                            value -> mappedValue(value, mapper))
                                    .toList());
            case ClientboundBundlePacket p -> {
                var children = new ArrayList<Packet<? super ClientGamePacketListener>>();
                for (var child : p.subPackets()) children.add(mappedPacket(child, mapper));
                yield new ClientboundBundlePacket(children);
            }
            default -> packet;
        };
    }

    @SuppressWarnings("unchecked")
    private static Packet<? super ClientGamePacketListener> mappedPacket(
            Object packet, BiFunction<ItemStack, Boolean, ItemStack> mapper) {
        return (Packet<? super ClientGamePacketListener>) map(packet, mapper);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SynchedEntityData.DataValue<?> mappedValue(
            SynchedEntityData.DataValue<?> value,
            BiFunction<ItemStack, Boolean, ItemStack> mapper) {
        if (!(value.value() instanceof ItemStack item)) return value;
        return new SynchedEntityData.DataValue(
                value.id(), value.serializer(), mapper.apply(item, false));
    }

    public static boolean any(Object packet, BiPredicate<ItemStack, Boolean> test) {
        switch (packet) {
            case ClientboundContainerSetSlotPacket p -> {
                return test.test(p.getItem(), true);
            }
            case ClientboundContainerSetContentPacket p -> {
                for (var item : p.items()) if (test.test(item, true)) return true;
                return test.test(p.carriedItem(), true);
            }
            case ClientboundSetCursorItemPacket p -> {
                return test.test(p.contents(), true);
            }
            case ClientboundSetPlayerInventoryPacket p -> {
                return test.test(p.contents(), true);
            }
            case ClientboundSetEquipmentPacket p -> {
                for (var slot : p.getSlots()) if (test.test(slot.getSecond(), false)) return true;
            }
            case ClientboundSetEntityDataPacket p -> {
                for (var value : p.packedItems())
                    if (value.value() instanceof ItemStack item && test.test(item, false))
                        return true;
            }
            case ClientboundBundlePacket p -> {
                for (var child : p.subPackets()) if (any(child, test)) return true;
            }
            default -> {}
        }
        return false;
    }
}
