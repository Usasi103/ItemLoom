package dev.itemloom.paper.compat.script;

import static org.junit.jupiter.api.Assertions.*;

import dev.itemloom.paper.compat.nbt.LegacyNbt;

import org.junit.jupiter.api.Test;

class LegacyItemEditorManagerNbtTest {
    private static LegacyNbt.IntValue integer(int value) {
        return LegacyNbt.IntValue.valueOf(value);
    }

    @Test
    void zeroIntermediateCreatesListAndSizeIntermediateAppendsCompound() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        LegacyItemEditorManagerNbt.put(root, "rows.0.value", integer(4));
        LegacyItemEditorManagerNbt.put(root, "rows.1.value", integer(5));
        LegacyNbt.ListValue rows = assertInstanceOf(LegacyNbt.ListValue.class, root.get("rows"));
        assertEquals(2, rows.size());
        assertEquals(4, rows.getCompound(0).getInt("value"));
        assertEquals(5, rows.getCompound(1).getInt("value"));
    }

    @Test
    void nonzeroIntermediateUsesNumericCompoundKey() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        LegacyItemEditorManagerNbt.put(root, "rows.2.value", integer(4));
        assertEquals(4, root.getCompound("rows").getCompound("2").getInt("value"));
        LegacyItemEditorManagerNbt.put(root, "rows.-1.value", integer(8));
        assertEquals(8, root.getCompound("rows").getCompound("-1").getInt("value"));
        assertEquals(4, root.getCompound("rows").getCompound("2").getInt("value"));
    }

    @Test
    void intermediateZeroDiscardsExistingCompoundAndOutOfRangeDiscardsList() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        LegacyItemEditorManagerNbt.put(root, "rows.keep", integer(9));
        LegacyItemEditorManagerNbt.put(root, "rows.0.value", integer(4));
        assertInstanceOf(LegacyNbt.ListValue.class, root.get("rows"));
        assertEquals(4, root.getList("rows").getCompound(0).getInt("value"));
        LegacyItemEditorManagerNbt.put(root, "rows.3.value", integer(5));
        LegacyNbt.Compound rows = assertInstanceOf(LegacyNbt.Compound.class, root.get("rows"));
        assertEquals(1, rows.size());
        assertEquals(5, rows.getCompound("3").getInt("value"));
    }

    @Test
    void numericLeafInCompoundPreservesEmptyOrExistingContainer() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        LegacyItemEditorManagerNbt.put(root, "rows.2", integer(4));
        assertTrue(assertInstanceOf(LegacyNbt.Compound.class, root.get("rows")).isEmpty());
        LegacyItemEditorManagerNbt.put(root, "rows.keep", integer(9));
        LegacyItemEditorManagerNbt.put(root, "rows.0", integer(4));
        assertEquals(1, root.getCompound("rows").size());
        assertEquals(9, root.getCompound("rows").getInt("keep"));
    }

    @Test
    void numericLeafLeavesAnExistingScalarUnchanged() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        root.put("scalar", integer(7));
        LegacyItemEditorManagerNbt.put(root, "scalar.0", integer(4));
        LegacyItemEditorManagerNbt.put(root, "scalar.-2", integer(5));
        assertInstanceOf(LegacyNbt.IntValue.class, root.get("scalar"));
        assertEquals(7, root.getInt("scalar"));
    }

    @Test
    void listLeavesReplaceAppendAndConvertOutOfRangeToNumericCompound() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        root.put("rows", new LegacyNbt.ListValue());
        LegacyItemEditorManagerNbt.put(root, "rows.0", integer(4));
        LegacyItemEditorManagerNbt.put(root, "rows.1", integer(5));
        LegacyItemEditorManagerNbt.put(root, "rows.0", integer(6));
        assertEquals(2, root.getList("rows").size());
        assertEquals(6, root.getList("rows").getInt(0));
        assertEquals(5, root.getList("rows").getInt(1));
        LegacyItemEditorManagerNbt.put(root, "rows.-1", integer(7));
        assertEquals(1, root.getCompound("rows").size());
        assertEquals(7, root.getCompound("rows").getInt("-1"));
    }

    @Test
    void namedLeavesAndScalarIntermediatesBecomeCompounds() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        root.put("scalar", integer(1));
        LegacyItemEditorManagerNbt.put(root, "scalar.deep.value", integer(4));
        assertEquals(4, root.getCompound("scalar").getCompound("deep").getInt("value"));
        root.put("rows", new LegacyNbt.ListValue());
        LegacyItemEditorManagerNbt.put(root, "rows.name", integer(5));
        assertEquals(5, root.getCompound("rows").getInt("name"));
    }

    @Test
    void typedArrayLeavesRequireExactScalarTypesBeforeIndexFallback() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        root.put("bytes", new LegacyNbt.ByteArray(new byte[] {1}));
        LegacyItemEditorManagerNbt.put(root, "bytes.0", LegacyNbt.ByteValue.valueOf((byte) 2));
        LegacyItemEditorManagerNbt.put(root, "bytes.1", LegacyNbt.ByteValue.valueOf((byte) 3));
        LegacyItemEditorManagerNbt.put(root, "bytes.0", integer(7));
        LegacyItemEditorManagerNbt.put(root, "bytes.9", integer(7));
        assertArrayEquals(new byte[] {2, 3}, root.getByteArray("bytes"));
        LegacyItemEditorManagerNbt.put(root, "bytes.9", LegacyNbt.ByteValue.valueOf((byte) 7));
        assertEquals(1, root.getCompound("bytes").size());
        assertEquals(7, root.getCompound("bytes").getByte("9"));

        root.put("ints", new LegacyNbt.IntArray(new int[] {1}));
        LegacyItemEditorManagerNbt.put(root, "ints.1", integer(2));
        LegacyItemEditorManagerNbt.put(root, "ints.0", LegacyNbt.LongValue.valueOf(99));
        assertArrayEquals(new int[] {1, 2}, root.getIntArray("ints"));

        root.put("longs", new LegacyNbt.LongArray(new long[] {1}));
        LegacyItemEditorManagerNbt.put(root, "longs.1", LegacyNbt.LongValue.valueOf(2));
        LegacyItemEditorManagerNbt.put(root, "longs.0", integer(99));
        assertArrayEquals(new long[] {1, 2}, root.getLongArray("longs"));
    }

    @Test
    void escapedDotsSlashesAndUnknownEscapesRemainLiteralKeys() {
        LegacyNbt.Compound root = new LegacyNbt.Compound();
        LegacyItemEditorManagerNbt.put(root, "dot\\.name.slash\\\\name", integer(4));
        LegacyItemEditorManagerNbt.put(root, "unknown\\q.value", integer(5));
        assertEquals(4, root.getCompound("dot.name").getInt("slash\\name"));
        assertEquals(5, root.getCompound("unknown\\q").getInt("value"));
    }
}
