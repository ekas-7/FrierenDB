package dev.frierendb.agent.tools;

import dev.frierendb.engine.FrierenException;

/** A tool call failed in a way the model can act on. The message is shown to the model. */
public class ToolException extends FrierenException {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
