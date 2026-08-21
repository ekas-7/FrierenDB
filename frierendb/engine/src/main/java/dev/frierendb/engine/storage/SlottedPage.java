package dev.frierendb.engine.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Variable-length record page. Layout: a 4-byte header (slot count, start of the record area),
 * then a slot directory growing upward (offset, length per slot) while record bytes grow downward
 * from the end of the page. A slot with offset 0 is free and may be reused.
 */
public final class SlottedPage {

    public static final int HEADER_SIZE = 4;
    public static final int SLOT_SIZE = 4;
    public static final int MAX_RECORD_SIZE = PageFile.PAGE_SIZE - HEADER_SIZE - SLOT_SIZE;

    private final byte[] data;
    private final ByteBuffer buf;

    public SlottedPage(byte[] data) {
        if (data.length != PageFile.PAGE_SIZE) {
            throw new StorageException("A page must be " + PageFile.PAGE_SIZE + " bytes");
        }
        this.data = data;
        this.buf = ByteBuffer.wrap(data);
    }

    public static SlottedPage format(byte[] data) {
        SlottedPage page = new SlottedPage(data);
        page.setSlotCount(0);
        page.setRecordStart(PageFile.PAGE_SIZE);
        return page;
    }

    public int slotCount() {
        return buf.getShort(0) & 0xFFFF;
    }

    /** Bytes available for new records and slots, including space reclaimable by compaction. */
    public int freeBytes() {
        int live = 0;
        for (int i = 0; i < slotCount(); i++) {
            live += length(i);
        }
        return PageFile.PAGE_SIZE - HEADER_SIZE - slotCount() * SLOT_SIZE - live;
    }

    public boolean isLive(int slot) {
        checkSlot(slot);
        return offset(slot) != 0;
    }

    /** Returns the slot used, or -1 if the record does not fit. */
    public int insert(byte[] record) {
        if (record.length == 0 || record.length > MAX_RECORD_SIZE) {
            throw new StorageException("Record size " + record.length + " is not storable");
        }
        int slot = firstFreeSlot();
        int slotCost = slot < 0 ? SLOT_SIZE : 0;
        if (freeBytes() < record.length + slotCost) {
            return -1;
        }
        if (slot < 0) {
            slot = slotCount();
            setSlotCount(slot + 1);
            setSlot(slot, 0, 0);
        }
        place(slot, record);
        return slot;
    }

    public byte[] get(int slot) {
        checkSlot(slot);
        if (offset(slot) == 0) {
            throw new StorageException("Slot " + slot + " is empty");
        }
        byte[] out = new byte[length(slot)];
        System.arraycopy(data, offset(slot), out, 0, out.length);
        return out;
    }

    public void delete(int slot) {
        checkSlot(slot);
        if (offset(slot) == 0) {
            throw new StorageException("Slot " + slot + " is already empty");
        }
        setSlot(slot, 0, 0);
    }

    /** Replaces a record in place, keeping its slot. Returns false if the page cannot hold it. */
    public boolean update(int slot, byte[] record) {
        checkSlot(slot);
        if (offset(slot) == 0) {
            throw new StorageException("Slot " + slot + " is empty");
        }
        if (record.length == 0 || record.length > MAX_RECORD_SIZE) {
            throw new StorageException("Record size " + record.length + " is not storable");
        }
        int oldLength = length(slot);
        if (record.length <= oldLength) {
            System.arraycopy(record, 0, data, offset(slot), record.length);
            setSlot(slot, offset(slot), record.length);
            return true;
        }
        if (freeBytes() + oldLength < record.length) {
            return false;
        }
        setSlot(slot, 0, 0);
        place(slot, record);
        return true;
    }

    private void place(int slot, byte[] record) {
        if (contiguousFree() < record.length) {
            compact();
        }
        int start = recordStart() - record.length;
        System.arraycopy(record, 0, data, start, record.length);
        setSlot(slot, start, record.length);
        setRecordStart(start);
    }

    private void compact() {
        List<byte[]> records = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < slotCount(); i++) {
            if (offset(i) != 0) {
                records.add(get(i));
                slots.add(i);
            }
        }
        int start = PageFile.PAGE_SIZE;
        for (int i = 0; i < records.size(); i++) {
            byte[] record = records.get(i);
            start -= record.length;
            System.arraycopy(record, 0, data, start, record.length);
            setSlot(slots.get(i), start, record.length);
        }
        setRecordStart(start);
    }

    private int contiguousFree() {
        return recordStart() - (HEADER_SIZE + slotCount() * SLOT_SIZE);
    }

    private int firstFreeSlot() {
        for (int i = 0; i < slotCount(); i++) {
            if (offset(i) == 0) {
                return i;
            }
        }
        return -1;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= slotCount()) {
            throw new StorageException("Slot " + slot + " out of range (" + slotCount() + " slots)");
        }
    }

    private int recordStart() {
        return buf.getShort(2) & 0xFFFF;
    }

    private void setRecordStart(int value) {
        buf.putShort(2, (short) value);
    }

    private void setSlotCount(int value) {
        buf.putShort(0, (short) value);
    }

    private int offset(int slot) {
        return buf.getShort(HEADER_SIZE + slot * SLOT_SIZE) & 0xFFFF;
    }

    private int length(int slot) {
        return buf.getShort(HEADER_SIZE + slot * SLOT_SIZE + 2) & 0xFFFF;
    }

    private void setSlot(int slot, int offset, int length) {
        buf.putShort(HEADER_SIZE + slot * SLOT_SIZE, (short) offset);
        buf.putShort(HEADER_SIZE + slot * SLOT_SIZE + 2, (short) length);
    }
}
