package dev.frierendb.engine.catalog;

import dev.frierendb.engine.FrierenException;

public class CatalogException extends FrierenException {

    private static final long serialVersionUID = 1L;

    public CatalogException(String message) {
        super(message);
    }

    public CatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
