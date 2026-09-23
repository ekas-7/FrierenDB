package dev.frierendb.engine.exec;

import dev.frierendb.engine.catalog.Catalog;
import dev.frierendb.engine.catalog.Table;
import dev.frierendb.engine.schema.Column;
import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.schema.Schema;
import dev.frierendb.engine.sql.Expr;
import dev.frierendb.engine.sql.SqlException;
import dev.frierendb.engine.sql.Statement;
import dev.frierendb.engine.storage.RecordId;
import dev.frierendb.engine.types.Value;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Runs parsed statements against a catalog. Not thread-safe; callers serialise access. */
public final class Executor {

    private final Catalog catalog;
    private final Planner planner;

    public Executor(Catalog catalog) {
        this.catalog = catalog;
        this.planner = new Planner(catalog);
    }

    public QueryResult execute(Statement statement, QueryOptions options) {
        ExecutionContext context = options.newContext();
        QueryResult result = switch (statement) {
            case Statement.Select s -> select(s, options, context);
            case Statement.Explain e -> explain(e);
            case Statement.Insert i -> insert(i);
            case Statement.Update u -> update(u, context);
            case Statement.Delete d -> delete(d, context);
            case Statement.CreateTable c -> createTable(c);
            case Statement.DropTable d -> dropTable(d);
            case Statement.CreateIndex c -> createIndex(c);
            case Statement.DropIndex d -> dropIndex(d);
            case Statement.Begin b -> throw transactionControl();
            case Statement.Commit c -> throw transactionControl();
            case Statement.Rollback r -> throw transactionControl();
        };
        if (!statement.isReadOnly()) {
            catalog.flush();
        }
        return result;
    }

    private static SqlException transactionControl() {
        return new SqlException("BEGIN, COMMIT and ROLLBACK must be run through Database.execute");
    }

    private QueryResult select(Statement.Select select, QueryOptions options, ExecutionContext context) {
        PlanNode plan = planner.plan(select);
        Iterator<Row> rows = plan.execute(context);
        List<Row> collected = new ArrayList<>();
        while (rows.hasNext() && collected.size() < options.maxRows()) {
            collected.add(rows.next());
        }
        boolean truncated = rows.hasNext();
        return QueryResult.rows(plan.schema().names(), collected, truncated);
    }

    private QueryResult explain(Statement.Explain explain) {
        List<Row> lines = planner.plan(explain.select()).explain().stream()
                .map(line -> Row.of(Value.of(line)))
                .toList();
        return QueryResult.rows(List.of("plan"), lines, false);
    }

    private QueryResult insert(Statement.Insert insert) {
        Table table = catalog.table(insert.table());
        Schema schema = table.schema();
        int[] targets = insertTargets(insert, schema);
        ExpressionCompiler constants = new ExpressionCompiler(new RowSchema(List.of()), null);
        Row empty = new Row(List.of());
        List<Row> prepared = new ArrayList<>();
        for (List<Expr> exprs : insert.rows()) {
            if (exprs.size() != targets.length) {
                throw new SqlException("INSERT has " + exprs.size() + " values but " + targets.length + " target columns");
            }
            Value[] values = new Value[schema.size()];
            java.util.Arrays.fill(values, Value.NULL);
            for (int i = 0; i < targets.length; i++) {
                values[targets[i]] = constants.compile(exprs.get(i)).apply(empty);
            }
            prepared.add(schema.conform(List.of(values)));
        }
        prepared.forEach(table::checkIndexable);
        prepared.forEach(table::insert);
        return QueryResult.affected(prepared.size());
    }

    private static int[] insertTargets(Statement.Insert insert, Schema schema) {
        if (insert.columns().isEmpty()) {
            int[] all = new int[schema.size()];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            return all;
        }
        int[] targets = new int[insert.columns().size()];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < targets.length; i++) {
            targets[i] = schema.requireIndexOf(insert.columns().get(i));
            if (!seen.add(targets[i])) {
                throw new SqlException("Column listed twice in INSERT: " + insert.columns().get(i));
            }
        }
        return targets;
    }

    private QueryResult update(Statement.Update update, ExecutionContext context) {
        Table table = catalog.table(update.table());
        Schema schema = table.schema();
        ExpressionCompiler compiler = new ExpressionCompiler(tableSchema(table), null);
        int[] targets = new int[update.assignments().size()];
        List<RowFunction> values = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < targets.length; i++) {
            Statement.Assignment a = update.assignments().get(i);
            targets[i] = schema.requireIndexOf(a.column());
            if (!seen.add(targets[i])) {
                throw new SqlException("Column assigned twice in UPDATE: " + a.column());
            }
            values.add(compiler.compile(a.value()));
        }
        List<Table.RowEntry> matches = matching(table, update.where(), compiler, context);
        List<Row> replacements = new ArrayList<>(matches.size());
        for (Table.RowEntry entry : matches) {
            List<Value> updated = new ArrayList<>(entry.row().values());
            for (int i = 0; i < targets.length; i++) {
                updated.set(targets[i], values.get(i).apply(entry.row()));
            }
            replacements.add(schema.conform(updated));
        }
        replacements.forEach(table::checkIndexable);
        for (int i = 0; i < matches.size(); i++) {
            table.update(matches.get(i).id(), replacements.get(i));
        }
        return QueryResult.affected(matches.size());
    }

    private QueryResult delete(Statement.Delete delete, ExecutionContext context) {
        Table table = catalog.table(delete.table());
        ExpressionCompiler compiler = new ExpressionCompiler(tableSchema(table), null);
        List<Table.RowEntry> matches = matching(table, delete.where(), compiler, context);
        for (Table.RowEntry entry : matches) {
            table.delete(entry.id());
        }
        return QueryResult.affected(matches.size());
    }

    /** Collects matches before any modification so a statement never sees its own writes. */
    private static List<Table.RowEntry> matching(Table table, Expr where, ExpressionCompiler compiler,
            ExecutionContext context) {
        RowFunction predicate = where == null ? null : compiler.compile(where);
        List<Table.RowEntry> matches = new ArrayList<>();
        Iterator<Table.RowEntry> source = IndexSelection.choose(table, table.name(), where)
                .map(c -> table.indexScan(c.index().name(), c.lo(), c.loInclusive(), c.hi(), c.hiInclusive()))
                .orElseGet(table::scan);
        for (Iterator<Table.RowEntry> it = source; it.hasNext(); ) {
            Table.RowEntry entry = it.next();
            context.tick();
            if (predicate == null || ExpressionCompiler.matches(predicate, entry.row(), "WHERE condition")) {
                context.materialize(1);
                matches.add(entry);
            }
        }
        return matches;
    }

    private static RowSchema tableSchema(Table table) {
        return new RowSchema(table.schema().columns().stream()
                .map(c -> new RowSchema.Binding(table.name(), c.name()))
                .toList());
    }

    private QueryResult createTable(Statement.CreateTable create) {
        if (create.ifNotExists() && catalog.exists(create.name())) {
            return QueryResult.affected(0);
        }
        List<Column> columns = new ArrayList<>();
        for (Statement.ColumnDef def : create.columns()) {
            columns.add(new Column(def.name(), def.type(), def.nullable()));
        }
        catalog.createTable(create.name(), new Schema(columns));
        return QueryResult.affected(0);
    }

    private QueryResult dropTable(Statement.DropTable drop) {
        if (drop.ifExists() && !catalog.exists(drop.name())) {
            return QueryResult.affected(0);
        }
        catalog.dropTable(drop.name());
        return QueryResult.affected(0);
    }

    private QueryResult createIndex(Statement.CreateIndex create) {
        if (create.ifNotExists() && catalog.indexExists(create.name())) {
            return QueryResult.affected(0);
        }
        catalog.createIndex(create.name(), create.table(), create.column());
        return QueryResult.affected(0);
    }

    private QueryResult dropIndex(Statement.DropIndex drop) {
        if (drop.ifExists() && !catalog.indexExists(drop.name())) {
            return QueryResult.affected(0);
        }
        catalog.dropIndex(drop.name());
        return QueryResult.affected(0);
    }
}
