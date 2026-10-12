package dev.itemloom.paper.compat.script;

import java.util.Locale;

public enum LegacyItemActionType {
    LEFT,
    SHIFT_LEFT,
    RIGHT,
    SHIFT_RIGHT,
    ALL,
    SHIFT_ALL,
    EAT,
    DROP,
    PICK,
    CLICK,
    BECLICKED,
    SHOOT_BOW,
    SHOOT_ARROW,
    BLOCKING,
    DAMAGE,
    DAMAGE_OFFHAND,
    DAMAGE_HEAD,
    DAMAGE_CHEST,
    DAMAGE_LEGS,
    DAMAGE_FEET,
    DAMAGED_HAND,
    DAMAGED_OFFHAND,
    DAMAGED_HEAD,
    DAMAGED_CHEST,
    DAMAGED_LEGS,
    DAMAGED_FEET,
    KILL_HAND,
    KILL_OFFHAND,
    KILL_HEAD,
    KILL_CHEST,
    KILL_LEGS,
    KILL_FEET,
    BREAK_BLOCK,
    TICK_HAND,
    TICK_OFFHAND,
    TICK_HEAD,
    TICK_CHEST,
    TICK_LEGS,
    TICK_FEET,
    TICK_0,
    TICK_1,
    TICK_2,
    TICK_3,
    TICK_4,
    TICK_5,
    TICK_6,
    TICK_7,
    TICK_8,
    TICK_9,
    TICK_10,
    TICK_11,
    TICK_12,
    TICK_13,
    TICK_14,
    TICK_15,
    TICK_16,
    TICK_17,
    TICK_18,
    TICK_19,
    TICK_20,
    TICK_21,
    TICK_22,
    TICK_23,
    TICK_24,
    TICK_25,
    TICK_26,
    TICK_27,
    TICK_28,
    TICK_29,
    TICK_30,
    TICK_31,
    TICK_32,
    TICK_33,
    TICK_34,
    TICK_35,
    TICK_36,
    TICK_37,
    TICK_38,
    TICK_39,
    TICK_40;

    private final String type = name().toLowerCase(Locale.ROOT);

    public String getType() {
        return type;
    }

    public static LegacyItemActionType matchType(String type) {
        for (LegacyItemActionType value : values()) {
            if (value.type.equals(type)) return value;
        }
        return null;
    }
}
