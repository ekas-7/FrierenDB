package dev.frierendb.engine.exec;

import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.types.Value;

@FunctionalInterface
interface RowFunction {
    Value apply(Row row);
}
