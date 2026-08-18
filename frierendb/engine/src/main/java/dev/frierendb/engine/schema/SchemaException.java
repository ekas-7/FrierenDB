package dev.frierendb.engine.schema;

import dev.frierendb.engine.FrierenException;

public class SchemaException extends FrierenException {

    private static final long serialVersionUID = 1L;

    public SchemaException(String message) {
        super(message);
    }

    public SchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
