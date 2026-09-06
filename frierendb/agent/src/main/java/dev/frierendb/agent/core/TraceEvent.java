package dev.frierendb.agent.core;

/**
 * One entry of an execution trace. For TOOL events {@code detail} is the JSON arguments the model
 * sent; for MODEL events it describes the outcome of the call.
 */
public record TraceEvent(int step, Kind kind, String name, String detail, boolean ok, long millis) {

    public enum Kind { MODEL, TOOL }
}
