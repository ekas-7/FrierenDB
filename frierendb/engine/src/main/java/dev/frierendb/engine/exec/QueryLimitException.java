package dev.frierendb.engine.exec;

import dev.frierendb.engine.FrierenException;

/** A query was stopped because it exceeded its time or memory budget. */
public class QueryLimitException extends FrierenException {

    private static final long serialVersionUID = 1L;

    public QueryLimitException(String message) {
        super(message);
    }
}
