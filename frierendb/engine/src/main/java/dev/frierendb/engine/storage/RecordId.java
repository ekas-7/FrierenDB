package dev.frierendb.engine.storage;

public record RecordId(int pageId, int slot) {

    @Override
    public String toString() {
        return pageId + ":" + slot;
    }
}
