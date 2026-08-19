package dev.frierendb.engine.storage;

import dev.frierendb.engine.FrierenException;

public class StorageException extends FrierenException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
