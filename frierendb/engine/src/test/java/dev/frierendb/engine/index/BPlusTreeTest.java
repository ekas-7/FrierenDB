package dev.frierendb.engine.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.storage.PageFile;
import dev.frierendb.engine.storage.RecordId;
import dev.frierendb.engine.storage.StorageException;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BPlusTreeTest {

    @TempDir
    Path dir;

    private static RecordId rid(int n) {
        return new RecordId(n / 100, n % 100);
    }

    private static List<RecordId> drain(Iterator<RecordId> it) {
        List<RecordId> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    @Test
    void emptyTreeHasNothing() {
        try (PageFile file = PageFile.open(dir.resolve("t.idx"))) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(8), DataType.INT);
            assertEquals(0, tree.size());
            assertEquals(1, tree.height());
            assertTrue(tree.find(Value.of(5)).isEmpty());
            assertTrue(drain(tree.range(null, true, null, true)).isEmpty());
            tree.checkInvariants();
        }
    }

    @Test
    void insertsInAnyOrderComeBackSortedAndTheTreeGrows() {
        for (int order = 0; order < 3; order++) {
            try (PageFile file = PageFile.open(dir.resolve("t" + order + ".idx"))) {
                BPlusTree tree = BPlusTree.open(file, new BufferPool(16), DataType.INT);
                int n = 5000;
                List<Integer> keys = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    keys.add(i);
                }
                if (order == 1) {
                    Collections.reverse(keys);
                } else if (order == 2) {
                    Collections.shuffle(keys, new Random(42));
                }
                for (int k : keys) {
                    assertTrue(tree.insert(Value.of(k), rid(k)));
                }
                assertEquals(n, tree.size());
                assertTrue(tree.height() >= 2, "5000 keys cannot fit in one leaf");
                tree.checkInvariants();
                List<RecordId> all = drain(tree.range(null, true, null, true));
                assertEquals(n, all.size());
                for (int i = 0; i < n; i++) {
                    assertEquals(rid(i), all.get(i));
                }
            }
        }
    }

    @Test
    void duplicateKeysAreSeparateEntriesButIdenticalPairsAreNot() {
        try (PageFile file = PageFile.open(dir.resolve("t.idx"))) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(8), DataType.INT);
            assertTrue(tree.insert(Value.of(7), rid(3)));
            assertTrue(tree.insert(Value.of(7), rid(1)));
            assertTrue(tree.insert(Value.of(7), rid(2)));
            assertFalse(tree.insert(Value.of(7), rid(2)));
            assertEquals(List.of(rid(1), rid(2), rid(3)), tree.find(Value.of(7)));
            assertEquals(3, tree.size());
            assertTrue(tree.delete(Value.of(7), rid(2)));
            assertFalse(tree.delete(Value.of(7), rid(2)));
            assertEquals(List.of(rid(1), rid(3)), tree.find(Value.of(7)));
        }
    }

    @Test
    void manyDuplicatesOfOneKeySpanLeavesAndStayFindable() {
        try (PageFile file = PageFile.open(dir.resolve("t.idx"))) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(16), DataType.INT);
            for (int i = 0; i < 3000; i++) {
                tree.insert(Value.of(1), rid(i));
                tree.insert(Value.of(2), rid(i));
                tree.insert(Value.of(3), rid(i));
            }
            tree.checkInvariants();
            assertEquals(3000, tree.find(Value.of(2)).size());
            assertEquals(3000, tree.find(Value.of(1)).size());
            assertEquals(9000, tree.size());
            assertEquals(rid(0), tree.find(Value.of(3)).get(0));
            assertEquals(rid(2999), tree.find(Value.of(3)).get(2999));
        }
    }

    @Test
    void rangeBoundsAreInclusiveOrExclusiveAsRequested() {
        try (PageFile file = PageFile.open(dir.resolve("t.idx"))) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(8), DataType.INT);
            for (int k = 0; k < 100; k += 10) {
                tree.insert(Value.of(k), rid(k));
            }
            assertEquals(List.of(rid(20), rid(30), rid(40)),
                    drain(tree.range(Value.of(20), true, Value.of(40), true)));
            assertEquals(List.of(rid(30)), drain(tree.range(Value.of(20), false, Value.of(40), false)));
            assertEquals(List.of(rid(80), rid(90)), drain(tree.range(Value.of(75), true, null, true)));
            assertEquals(List.of(rid(0), rid(10)), drain(tree.range(null, true, Value.of(10), true)));
            assertEquals(List.of(rid(0)), drain(tree.range(null, true, Value.of(10), false)));
            assertTrue(drain(tree.range(Value.of(41), true, Value.of(49), true)).isEmpty());
            assertTrue(drain(tree.range(Value.of(50), true, Value.of(40), true)).isEmpty());
            assertTrue(drain(tree.range(Value.of(1000), true, null, true)).isEmpty());
        }
    }

    @Test
    void deletingRemovesEntriesAndLeavesAValidTree() {
        try (PageFile file = PageFile.open(dir.resolve("t.idx"))) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(16), DataType.INT);
            int n = 4000;
            for (int i = 0; i < n; i++) {
                tree.insert(Value.of(i), rid(i));
            }
            Random random = new Random(7);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            Collections.shuffle(order, random);
            List<Integer> removed = order.subList(0, n * 3 / 4);
            for (int k : removed) {
                assertTrue(tree.delete(Value.of(k), rid(k)));
            }
            assertEquals(n / 4, tree.size());
            tree.checkInvariants();
            java.util.Set<Integer> gone = new java.util.HashSet<>(removed);
            List<RecordId> expected = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!gone.contains(i)) {
                    expected.add(rid(i));
                }
            }
            assertEquals(expected, drain(tree.range(null, true, null, true)));
            assertTrue(tree.find(Value.of(removed.get(0))).isEmpty());
            for (int k : removed.subList(0, 100)) {
                assertTrue(tree.insert(Value.of(k), rid(k)));
            }
            tree.checkInvariants();
            assertEquals(n / 4 + 100, tree.size());
        }
    }

    @Test
    void matchesAnOrderedMapModelUnderRandomOperations() {
        try (PageFile file = PageFile.open(dir.resolve("t.idx"))) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(16), DataType.INT);
            TreeMap<Long, TreeMap<Integer, RecordId>> model = new TreeMap<>();
            Random random = new Random(2024);
            for (int step = 0; step < 20000; step++) {
                long key = random.nextInt(300);
                int id = random.nextInt(50);
                if (random.nextInt(3) < 2) {
                    boolean added = model.computeIfAbsent(key, k -> new TreeMap<>()).putIfAbsent(id, rid(id)) == null;
                    assertEquals(added, tree.insert(Value.of(key), rid(id)));
                } else {
                    TreeMap<Integer, RecordId> bucket = model.get(key);
                    boolean removed = bucket != null && bucket.remove(id) != null;
                    assertEquals(removed, tree.delete(Value.of(key), rid(id)));
                }
            }
            tree.checkInvariants();
            for (int trial = 0; trial < 200; trial++) {
                long a = random.nextInt(320) - 10;
                long b = a + random.nextInt(80);
                boolean aInc = random.nextBoolean();
                boolean bInc = random.nextBoolean();
                List<RecordId> expected = new ArrayList<>();
                for (var entry : model.entrySet()) {
                    long k = entry.getKey();
                    boolean aboveLow = aInc ? k >= a : k > a;
                    boolean belowHigh = bInc ? k <= b : k < b;
                    if (aboveLow && belowHigh) {
                        expected.addAll(entry.getValue().values());
                    }
                }
                assertEquals(expected, drain(tree.range(Value.of(a), aInc, Value.of(b), bInc)),
                        "range " + a + (aInc ? " [" : " (") + b);
            }
        }
    }

    @Test
    void longTextKeysSplitPagesCorrectly() {
        try (PageFile file = PageFile.open(dir.resolve("t.idx"))) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(16), DataType.TEXT);
            String pad = "x".repeat(200);
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                keys.add(String.format("%04d", i) + pad);
            }
            List<String> shuffled = new ArrayList<>(keys);
            Collections.shuffle(shuffled, new Random(5));
            for (int i = 0; i < shuffled.size(); i++) {
                tree.insert(Value.of(shuffled.get(i)), rid(i));
            }
            tree.checkInvariants();
            assertTrue(tree.height() >= 3, "600 keys of ~200 bytes need at least three levels");
            Iterator<RecordId> all = tree.range(Value.of(keys.get(100)), true, Value.of(keys.get(104)), true);
            assertEquals(5, drain(all).size());
            assertEquals(1, tree.find(Value.of(keys.get(599))).size());
        }
    }

    @Test
    void allKeyTypesRoundTrip() {
        try (PageFile f1 = PageFile.open(dir.resolve("d.idx")); PageFile f2 = PageFile.open(dir.resolve("b.idx"))) {
            BPlusTree doubles = BPlusTree.open(f1, new BufferPool(8), DataType.DOUBLE);
            doubles.insert(Value.of(-1.5), rid(1));
            doubles.insert(Value.of(0.0), rid(2));
            doubles.insert(Value.of(3.25), rid(3));
            assertEquals(List.of(rid(1), rid(2)), drain(doubles.range(null, true, Value.of(0.0), true)));
            BPlusTree bools = BPlusTree.open(f2, new BufferPool(8), DataType.BOOLEAN);
            bools.insert(Value.of(true), rid(1));
            bools.insert(Value.of(false), rid(2));
            assertEquals(List.of(rid(2), rid(1)), drain(bools.range(null, true, null, true)));
            assertEquals(List.of(rid(1)), bools.find(Value.of(true)));
        }
    }

    @Test
    void rejectsNullWrongTypeAndOversizedKeys() {
        try (PageFile file = PageFile.open(dir.resolve("t.idx"))) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(8), DataType.TEXT);
            assertThrows(StorageException.class, () -> tree.insert(Value.NULL, rid(1)));
            assertThrows(StorageException.class, () -> tree.insert(Value.of(5), rid(1)));
            StorageException tooLong = assertThrows(StorageException.class,
                    () -> tree.insert(Value.of("y".repeat(256)), rid(1)));
            assertTrue(tooLong.getMessage().contains("limited to 255 bytes"));
            assertTrue(tree.insert(Value.of("y".repeat(255)), rid(1)));
            assertEquals(1, tree.size());
        }
    }

    @Test
    void survivesCloseAndReopen() {
        Path path = dir.resolve("t.idx");
        try (PageFile file = PageFile.open(path)) {
            BufferPool pool = new BufferPool(8);
            BPlusTree tree = BPlusTree.open(file, pool, DataType.INT);
            for (int i = 0; i < 2000; i++) {
                tree.insert(Value.of(i), rid(i));
            }
            pool.flush(file);
        }
        try (PageFile file = PageFile.open(path)) {
            BPlusTree tree = BPlusTree.open(file, new BufferPool(8), DataType.INT);
            assertEquals(2000, tree.size());
            tree.checkInvariants();
            assertEquals(List.of(rid(1234)), tree.find(Value.of(1234)));
        }
    }

    @Test
    void refusesAFileBuiltForAnotherKeyType() {
        Path path = dir.resolve("t.idx");
        try (PageFile file = PageFile.open(path)) {
            BufferPool pool = new BufferPool(8);
            BPlusTree.open(file, pool, DataType.INT);
            pool.flush(file);
        }
        try (PageFile file = PageFile.open(path)) {
            assertThrows(StorageException.class, () -> BPlusTree.open(file, new BufferPool(8), DataType.TEXT));
        }
    }
}
