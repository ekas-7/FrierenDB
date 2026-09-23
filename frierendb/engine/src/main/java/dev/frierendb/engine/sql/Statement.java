package dev.frierendb.engine.sql;

import dev.frierendb.engine.types.DataType;
import java.util.List;

public sealed interface Statement {

    /** True for statements that cannot modify data or schema. */
    default boolean isReadOnly() {
        return false;
    }

    sealed interface SelectItem {}

    /** {@code table} is null for a bare {@code *}. */
    record Star(String table) implements SelectItem {}

    record Projection(Expr expr, String alias) implements SelectItem {}

    record TableRef(String name, String alias) {
        public String effectiveName() {
            return alias != null ? alias : name;
        }
    }

    enum JoinType { INNER, LEFT }

    record Join(JoinType type, TableRef table, Expr on) {}

    record OrderItem(Expr expr, boolean ascending) {}

    record Assignment(String column, Expr value) {}

    record ColumnDef(String name, DataType type, boolean nullable) {}

    record Select(
            List<SelectItem> items,
            boolean distinct,
            TableRef from,
            List<Join> joins,
            Expr where,
            List<Expr> groupBy,
            Expr having,
            List<OrderItem> orderBy,
            Long limit) implements Statement {

        public Select {
            items = List.copyOf(items);
            joins = List.copyOf(joins);
            groupBy = List.copyOf(groupBy);
            orderBy = List.copyOf(orderBy);
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }
    }

    /** {@code columns} is empty when the statement does not list columns. */
    record Insert(String table, List<String> columns, List<List<Expr>> rows) implements Statement {
        public Insert {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    record Update(String table, List<Assignment> assignments, Expr where) implements Statement {
        public Update {
            assignments = List.copyOf(assignments);
        }
    }

    record Delete(String table, Expr where) implements Statement {}

    record CreateTable(String name, List<ColumnDef> columns, boolean ifNotExists) implements Statement {
        public CreateTable {
            columns = List.copyOf(columns);
        }
    }

    record DropTable(String name, boolean ifExists) implements Statement {}

    record CreateIndex(String name, String table, String column, boolean ifNotExists) implements Statement {}

    record DropIndex(String name, boolean ifExists) implements Statement {}

    /** Transaction control. These change no data themselves, but they are not read-only. */
    record Begin() implements Statement {}

    record Commit() implements Statement {}

    record Rollback() implements Statement {}

    record Explain(Select select) implements Statement {
        @Override
        public boolean isReadOnly() {
            return true;
        }
    }
}
