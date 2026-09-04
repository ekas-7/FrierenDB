package dev.frierendb.agent.model;

import dev.frierendb.engine.FrierenException;

/** A model call failed. {@code retryable} is true for transient problems such as rate limits and timeouts. */
public class ModelException extends FrierenException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;

    public ModelException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ModelException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
