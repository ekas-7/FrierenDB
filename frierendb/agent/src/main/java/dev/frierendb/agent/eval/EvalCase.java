package dev.frierendb.agent.eval;

/** A question paired with a reference SQL query whose result defines the correct answer. */
public record EvalCase(String question, String goldSql) {}
