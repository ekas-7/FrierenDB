package dev.frierendb.engine.index;

import dev.frierendb.engine.storage.BufferPool;
import dev.frierendb.engine.storage.PageFile;
import dev.frierendb.engine.storage.RecordId;
import dev.frierendb.engine.storage.StorageException;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A persistent, non-unique B+ tree that maps one column's values to {@link RecordId}s.
 *
 * <p>Entries are ordered by (key, record id), so duplicate keys are distinct entries and deletion
 * of one row never disturbs another. Page 0 holds metadata; every other page is a leaf or an
 * internal node. Leaves are chained left to right for range scans. NULL keys are not indexed.
 *
 * <p>Deletion removes the entry from its leaf but does not merge or rebalance nodes, so a tree
 * that has lost many entries may be taller or sparser than necessary. All structure lives in
 * pages managed by the {@link BufferPool}, so it is covered by whatever durability the pool provides.
 */
public final class BPlusTree {

    private static final int MAGIC = 0x46425054; // "FBPT"
    private static final short VERSION = 1;
    private static final byte LEAF = 1;
    private static final byte INTERNAL = 2;
    private static final int NODE_HEADER = 7; // kind(1) count(2) next-leaf or first child(4)
    private static final int RID_BYTES = 8;
    private static final int CHILD_BYTES = 4;
    private static final RecordId LOWEST = new RecordId(Integer.MIN_VALUE, Integer.MIN_VALUE);

    /** One index entry. Ordered by key, then by record id. */
    record Entry(Value key, RecordId rid) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            int byKey = key.compareTo(other.key);
            if (byKey != 0) {
                return byKey;
            }
            int byPage = Integer.compare(rid.pageId(), other.rid.pageId());
            return byPage != 0 ? byPage : Integer.compare(rid.slot(), other.rid.slot());
        }
    }

    private static final class Node {
        final boolean leaf;
        final List<Entry> entries = new ArrayList<>();
        final List<Integer> children = new ArrayList<>(); // internal nodes: entries.size() + 1 pages
        int next = -1; // leaves only

        Node(boolean leaf) {
            this.leaf = leaf;
        }
    }

    private record Split(Entry separator, int rightPage) {}

    private record InsertResult(boolean inserted, Split split) {}

    private final PageFile file;
    private final BufferPool pool;
    private final DataType keyType;

    private BPlusTree(PageFile file, BufferPool pool, DataType keyType) {
        this.file = file;
        this.pool = pool;
        this.keyType = keyType;
    }

    /** Opens the tree stored in {@code file}, or initialises it if the file is empty. */
    public static BPlusTree open(PageFile file, BufferPool pool, DataType keyType) {
        BPlusTree tree = new BPlusTree(file, pool, keyType);
        if (file.pageCount() == 0) {
            try (BufferPool.Frame meta = pool.allocate(file)) {
                try (BufferPool.Frame root = pool.allocate(file)) {
                    tree.encode(root.data(), new Node(true));
                    root.markDirty();
                    tree.writeMeta(meta, root.pageId(), 0);
                }
            }
        } else {
            tree.verifyMeta();
        }
        return tree;
    }

    public DataType keyType() {
        return keyType;
    }

    public synchronized long size() {
        try (BufferPool.Frame meta = pool.fetch(file, 0)) {
            return ByteBuffer.wrap(meta.data()).getLong(11);
        }
    }

    /** Number of levels; a tree with a single leaf has height 1. */
    public synchronized int height() {
        int height = 1;
        Node node = read(root());
        while (!node.leaf) {
            node = read(node.children.get(0));
            height++;
        }
        return height;
    }

    /** Adds an entry. Returns false if the identical (key, record id) pair is already present. */
    public synchronized boolean insert(Value key, RecordId rid) {
        KeyCodec.check(keyType, key);
        int rootPage = root();
        InsertResult result = insertInto(rootPage, new Entry(key, rid));
        if (result.split() != null) {
            Node newRoot = new Node(false);
            newRoot.entries.add(result.split().separator());
            newRoot.children.add(rootPage);
            newRoot.children.add(result.split().rightPage());
            setRoot(allocate(newRoot));
        }
        if (result.inserted()) {
            setSize(size() + 1);
        }
        return result.inserted();
    }

    /** Removes an entry. Returns false if it was not present. */
    public synchronized boolean delete(Value key, RecordId rid) {
        if (key.isNull()) {
            return false;
        }
        KeyCodec.check(keyType, key);
        Entry target = new Entry(key, rid);
        int pageId = findLeaf(target);
        Node leaf = read(pageId);
        int position = Collections.binarySearch(leaf.entries, target);
        if (position < 0) {
            return false;
        }
        leaf.entries.remove(position);
        write(pageId, leaf);
        setSize(size() - 1);
        return true;
    }

    /** Record ids of all entries with exactly this key, in record-id order. */
    public List<RecordId> find(Value key) {
        List<RecordId> out = new ArrayList<>();
        range(key, true, key, true).forEachRemaining(out::add);
        return out;
    }

    /**
     * Record ids whose key lies between the bounds, in ascending key order. A null bound means
     * unbounded on that side. Bounds must be comparable with the key type. The iterator reads one
     * leaf at a time and must not be used while the tree is being modified.
     */
    public Iterator<RecordId> range(Value lo, boolean loInclusive, Value hi, boolean hiInclusive) {
        return new RangeIterator(lo, loInclusive, hi, hiInclusive);
    }

    /** Verifies ordering, balance, leaf chaining and the entry count. Intended for tests. */
    public synchronized void checkInvariants() {
        int[] leafDepth = {-1};
        List<Integer> leavesInOrder = new ArrayList<>();
        long counted = checkNode(root(), null, null, 1, leafDepth, leavesInOrder);
        for (int i = 0; i < leavesInOrder.size(); i++) {
            int expectedNext = i + 1 < leavesInOrder.size() ? leavesInOrder.get(i + 1) : -1;
            if (read(leavesInOrder.get(i)).next != expectedNext) {
                throw new StorageException("Leaf " + leavesInOrder.get(i) + " has a wrong next pointer");
            }
        }
        if (counted != size()) {
            throw new StorageException("Tree holds " + counted + " entries but metadata says " + size());
        }
    }

    // ---- insertion ----

    private InsertResult insertInto(int pageId, Entry entry) {
        Node node = read(pageId);
        if (node.leaf) {
            int position = Collections.binarySearch(node.entries, entry);
            if (position >= 0) {
                return new InsertResult(false, null);
            }
            node.entries.add(-position - 1, entry);
            if (encodedSize(node) <= PageFile.PAGE_SIZE) {
                write(pageId, node);
                return new InsertResult(true, null);
            }
            int mid = splitPoint(node);
            Node right = new Node(true);
            right.entries.addAll(node.entries.subList(mid, node.entries.size()));
            right.next = node.next;
            node.entries.subList(mid, node.entries.size()).clear();
            int rightPage = allocate(right);
            node.next = rightPage;
            write(pageId, node);
            return new InsertResult(true, new Split(right.entries.get(0), rightPage));
        }

        int childIndex = childIndex(node, entry);
        InsertResult below = insertInto(node.children.get(childIndex), entry);
        if (below.split() == null) {
            return below;
        }
        node.entries.add(childIndex, below.split().separator());
        node.children.add(childIndex + 1, below.split().rightPage());
        if (encodedSize(node) <= PageFile.PAGE_SIZE) {
            write(pageId, node);
            return new InsertResult(below.inserted(), null);
        }
        int mid = splitPoint(node);
        Entry promoted = node.entries.get(mid);
        Node right = new Node(false);
        right.entries.addAll(node.entries.subList(mid + 1, node.entries.size()));
        right.children.addAll(node.children.subList(mid + 1, node.children.size()));
        node.entries.subList(mid, node.entries.size()).clear();
        node.children.subList(mid + 1, node.children.size()).clear();
        int rightPage = allocate(right);
        write(pageId, node);
        return new InsertResult(below.inserted(), new Split(promoted, rightPage));
    }

    /** Index at which the node's entries are cut so both halves hold roughly equal bytes. */
    private int splitPoint(Node node) {
        int total = encodedSize(node) - NODE_HEADER;
        int running = 0;
        for (int i = 0; i < node.entries.size(); i++) {
            running += entrySize(node, node.entries.get(i));
            if (running * 2 >= total) {
                return Math.min(Math.max(i, 1), node.entries.size() - 2);
            }
        }
        return node.entries.size() / 2;
    }

    /** The child to descend into: the number of separators that are less than or equal to the target. */
    private static int childIndex(Node node, Entry target) {
        int low = 0;
        int high = node.entries.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (node.entries.get(mid).compareTo(target) <= 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private int findLeaf(Entry target) {
        int pageId = root();
        while (true) {
            Node node = read(pageId);
            if (node.leaf) {
                return pageId;
            }
            pageId = node.children.get(childIndex(node, target));
        }
    }

    private int leftmostLeaf() {
        int pageId = root();
        while (true) {
            Node node = read(pageId);
            if (node.leaf) {
                return pageId;
            }
            pageId = node.children.get(0);
        }
    }

    // ---- invariants ----

    private long checkNode(int pageId, Entry low, Entry high, int depth, int[] leafDepth, List<Integer> leaves) {
        Node node = read(pageId);
        for (int i = 0; i < node.entries.size(); i++) {
            Entry e = node.entries.get(i);
            if (i > 0 && node.entries.get(i - 1).compareTo(e) >= 0) {
                throw new StorageException("Page " + pageId + " is not strictly ordered");
            }
            if (low != null && e.compareTo(low) < 0 || high != null && e.compareTo(high) >= 0) {
                throw new StorageException("Page " + pageId + " holds an entry outside its separator range");
            }
        }
        if (node.leaf) {
            if (leafDepth[0] == -1) {
                leafDepth[0] = depth;
            } else if (leafDepth[0] != depth) {
                throw new StorageException("Leaves are at different depths");
            }
            leaves.add(pageId);
            return node.entries.size();
        }
        if (node.children.size() != node.entries.size() + 1) {
            throw new StorageException("Internal page " + pageId + " has the wrong number of children");
        }
        long total = 0;
        for (int i = 0; i < node.children.size(); i++) {
            Entry childLow = i == 0 ? low : node.entries.get(i - 1);
            Entry childHigh = i == node.entries.size() ? high : node.entries.get(i);
            total += checkNode(node.children.get(i), childLow, childHigh, depth + 1, leafDepth, leaves);
        }
        return total;
    }

    // ---- page encoding ----

    private int entrySize(Node node, Entry entry) {
        return KeyCodec.size(keyType, entry.key()) + RID_BYTES + (node.leaf ? 0 : CHILD_BYTES);
    }

    private int encodedSize(Node node) {
        int size = NODE_HEADER;
        for (Entry entry : node.entries) {
            size += entrySize(node, entry);
        }
        return size;
    }

    private void encode(byte[] page, Node node) {
        java.util.Arrays.fill(page, (byte) 0);
        ByteBuffer out = ByteBuffer.wrap(page);
        out.put(node.leaf ? LEAF : INTERNAL);
        out.putShort((short) node.entries.size());
        out.putInt(node.leaf ? node.next : node.children.get(0));
        for (int i = 0; i < node.entries.size(); i++) {
            Entry entry = node.entries.get(i);
            KeyCodec.write(out, keyType, entry.key());
            out.putInt(entry.rid().pageId());
            out.putInt(entry.rid().slot());
            if (!node.leaf) {
                out.putInt(node.children.get(i + 1));
            }
        }
    }

    private Node decode(byte[] page) {
        ByteBuffer in = ByteBuffer.wrap(page);
        byte kind = in.get();
        if (kind != LEAF && kind != INTERNAL) {
            throw new StorageException("Corrupt index page in " + file.path());
        }
        Node node = new Node(kind == LEAF);
        int count = in.getShort() & 0xFFFF;
        int link = in.getInt();
        if (node.leaf) {
            node.next = link;
        } else {
            node.children.add(link);
        }
        for (int i = 0; i < count; i++) {
            Value key = KeyCodec.read(in, keyType);
            node.entries.add(new Entry(key, new RecordId(in.getInt(), in.getInt())));
            if (!node.leaf) {
                node.children.add(in.getInt());
            }
        }
        return node;
    }

    private Node read(int pageId) {
        try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
            return decode(frame.data());
        }
    }

    private void write(int pageId, Node node) {
        try (BufferPool.Frame frame = pool.fetch(file, pageId)) {
            encode(frame.data(), node);
            frame.markDirty();
        }
    }

    private int allocate(Node node) {
        try (BufferPool.Frame frame = pool.allocate(file)) {
            encode(frame.data(), node);
            frame.markDirty();
            return frame.pageId();
        }
    }

    // ---- metadata (page 0): magic(4) version(2) keyType(1) root(4) size(8) ----

    private void writeMeta(BufferPool.Frame frame, int root, long size) {
        ByteBuffer out = ByteBuffer.wrap(frame.data());
        out.putInt(MAGIC);
        out.putShort(VERSION);
        out.put((byte) keyType.ordinal());
        out.putInt(root);
        out.putLong(size);
        frame.markDirty();
    }

    private void verifyMeta() {
        try (BufferPool.Frame meta = pool.fetch(file, 0)) {
            ByteBuffer in = ByteBuffer.wrap(meta.data());
            if (in.getInt() != MAGIC) {
                throw new StorageException(file.path() + " is not a FrierenDB index file");
            }
            short version = in.getShort();
            if (version != VERSION) {
                throw new StorageException("Unsupported index version " + version + " in " + file.path());
            }
            if (in.get() != keyType.ordinal()) {
                throw new StorageException("Index " + file.path() + " was built for a different key type");
            }
        }
    }

    private int root() {
        try (BufferPool.Frame meta = pool.fetch(file, 0)) {
            return ByteBuffer.wrap(meta.data()).getInt(7);
        }
    }

    private void setRoot(int root) {
        try (BufferPool.Frame meta = pool.fetch(file, 0)) {
            ByteBuffer.wrap(meta.data()).putInt(7, root);
            meta.markDirty();
        }
    }

    private void setSize(long size) {
        try (BufferPool.Frame meta = pool.fetch(file, 0)) {
            ByteBuffer.wrap(meta.data()).putLong(11, size);
            meta.markDirty();
        }
    }

    // ---- range scan ----

    private final class RangeIterator implements Iterator<RecordId> {
        private final Value lo;
        private final boolean loInclusive;
        private final Value hi;
        private final boolean hiInclusive;
        private List<Entry> entries = List.of();
        private int position;
        private int nextLeaf;
        private RecordId pending;
        private boolean finished;

        RangeIterator(Value lo, boolean loInclusive, Value hi, boolean hiInclusive) {
            this.lo = lo;
            this.loInclusive = loInclusive;
            this.hi = hi;
            this.hiInclusive = hiInclusive;
            int start;
            synchronized (BPlusTree.this) {
                start = lo == null ? leftmostLeaf() : findLeaf(new Entry(lo, LOWEST));
                load(start);
            }
            pending = advance();
        }

        private void load(int pageId) {
            Node leaf = read(pageId);
            entries = leaf.entries;
            nextLeaf = leaf.next;
            position = 0;
        }

        private RecordId advance() {
            while (!finished) {
                if (position >= entries.size()) {
                    if (nextLeaf < 0) {
                        finished = true;
                        return null;
                    }
                    synchronized (BPlusTree.this) {
                        load(nextLeaf);
                    }
                    continue;
                }
                Entry entry = entries.get(position++);
                if (lo != null) {
                    int c = entry.key().compareTo(lo);
                    if (c < 0 || c == 0 && !loInclusive) {
                        continue;
                    }
                }
                if (hi != null) {
                    int c = entry.key().compareTo(hi);
                    if (c > 0 || c == 0 && !hiInclusive) {
                        finished = true;
                        return null;
                    }
                }
                return entry.rid();
            }
            return null;
        }

        @Override
        public boolean hasNext() {
            return pending != null;
        }

        @Override
        public RecordId next() {
            if (pending == null) {
                throw new NoSuchElementException();
            }
            RecordId current = pending;
            pending = advance();
            return current;
        }
    }
}
