package dev.frierendb.engine.catalog;

/** Definition of a secondary index: its name, the table it belongs to and the indexed column. */
public record IndexInfo(String name, String table, String column) {}
