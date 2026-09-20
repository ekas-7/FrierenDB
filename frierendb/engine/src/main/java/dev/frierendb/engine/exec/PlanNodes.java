package dev.frierendb.engine.exec;

import dev.frierendb.engine.catalog.Table;
import dev.frierendb.engine.schema.Row;
import dev.frierendb.engine.sql.Statement.JoinType;
import dev.frierendb.engine.types.Value;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

final class PlanNodes {

    private PlanNodes() {}

    private static List<Row> drain(Iterator<Row> rows, ExecutionContext context) {
        List<Row> all = new ArrayList<>();
        while (rows.hasNext()) {
            context.materialize(1);
            all.add(rows.next());
        }
        return all;
    }

    private static Iterator<Row> filtered(Iterator<Row> source, Predicate<Row> keep) {
        return new Iterator<>() {
            private Row next = advance();

            private Row advance() {
                while (source.hasNext()) {
                    Row candidate = source.next();
                    if (keep.test(candidate)) {
                        return candidate;
                    }
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Row next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                Row current = next;
                next = advance();
                return current;
            }
        };
    }

    private static <A, B> Iterator<B> mapped(Iterator<A> source, Function<A, B> f) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return source.hasNext();
            }

            @Override
            public B next() {
                return f.apply(source.next());
            }
        };
    }

    private static List<Value> key(Row row, int[] indexes) {
        List<Value> key = new ArrayList<>(indexes.length);
        for (int index : indexes) {
            key.add(SqlValues.canonical(row.get(index)));
        }
        return key;
    }

    static final class Scan implements PlanNode {
        private final Table table;
        private final String alias;
        private final RowSchema schema;

        Scan(Table table, String alias) {
            this.table = table;
            this.alias = alias;
            this.schema = new RowSchema(table.schema().columns().stream()
                    .map(c -> new RowSchema.Binding(alias, c.name()))
                    .toList());
        }

        Table table() {
            return table;
        }

        String alias() {
            return alias;
        }

        @Override
        public RowSchema schema() {
            return schema;
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            return mapped(table.scan(), entry -> {
                context.tick();
                return entry.row();
            });
        }

        @Override
        public void describe(int depth, List<String> out) {
            String suffix = alias.equalsIgnoreCase(table.name()) ? "" : " AS " + alias;
            out.add(PlanNode.indent(depth) + "Scan " + table.name() + suffix + " (full table scan)");
        }
    }

    /** Reads only the rows in a key range of one index, in key order. The filter above still rechecks them. */
    static final class IndexScan implements PlanNode {
        private final Table table;
        private final String alias;
        private final IndexSelection.Choice choice;
        private final RowSchema schema;

        IndexScan(Table table, String alias, IndexSelection.Choice choice) {
            this.table = table;
            this.alias = alias;
            this.choice = choice;
            this.schema = new RowSchema(table.schema().columns().stream()
                    .map(c -> new RowSchema.Binding(alias, c.name()))
                    .toList());
        }

        @Override
        public RowSchema schema() {
            return schema;
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            return mapped(table.indexScan(choice.index().name(), choice.lo(), choice.loInclusive(),
                    choice.hi(), choice.hiInclusive()), entry -> {
                context.tick();
                return entry.row();
            });
        }

        @Override
        public void describe(int depth, List<String> out) {
            String suffix = alias.equalsIgnoreCase(table.name()) ? "" : " AS " + alias;
            out.add(PlanNode.indent(depth) + "IndexScan " + table.name() + suffix + " using "
                    + choice.index().name() + " (" + choice.condition() + ")");
        }
    }

    static final class Filter implements PlanNode {
        private final PlanNode child;
        private final RowFunction predicate;
        private final String text;
        private final String context;

        Filter(PlanNode child, RowFunction predicate, String text, String context) {
            this.child = child;
            this.predicate = predicate;
            this.text = text;
            this.context = context;
        }

        @Override
        public RowSchema schema() {
            return child.schema();
        }

        @Override
        public Iterator<Row> execute(ExecutionContext ctx) {
            return filtered(child.execute(ctx), row -> ExpressionCompiler.matches(predicate, row, context));
        }

        @Override
        public void describe(int depth, List<String> out) {
            out.add(PlanNode.indent(depth) + "Filter " + text);
            child.describe(depth + 1, out);
        }
    }

    static final class Join implements PlanNode {
        private final PlanNode left;
        private final PlanNode right;
        private final JoinType type;
        private final RowFunction condition;
        private final int[] leftKeys;
        private final int[] rightKeys;
        private final String conditionText;
        private final RowSchema schema;

        Join(PlanNode left, PlanNode right, JoinType type, RowFunction condition,
                int[] leftKeys, int[] rightKeys, String conditionText) {
            this.left = left;
            this.right = right;
            this.type = type;
            this.condition = condition;
            this.leftKeys = leftKeys;
            this.rightKeys = rightKeys;
            this.conditionText = conditionText;
            this.schema = left.schema().concat(right.schema());
        }

        @Override
        public RowSchema schema() {
            return schema;
        }

        private boolean isHashJoin() {
            return leftKeys.length > 0;
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            List<Row> rightRows = drain(right.execute(context), context);
            Map<List<Value>, List<Row>> hashed = new HashMap<>();
            if (isHashJoin()) {
                for (Row row : rightRows) {
                    List<Value> k = key(row, rightKeys);
                    if (!k.contains(Value.NULL)) {
                        hashed.computeIfAbsent(k, ignored -> new ArrayList<>()).add(row);
                    }
                }
            }
            int rightWidth = right.schema().size();
            Iterator<Row> leftRows = left.execute(context);
            return new Iterator<>() {
                private final ArrayDeque<Row> pending = new ArrayDeque<>();

                private void fill() {
                    while (pending.isEmpty() && leftRows.hasNext()) {
                        Row l = leftRows.next();
                        List<Row> candidates = rightRows;
                        if (isHashJoin()) {
                            List<Value> k = key(l, leftKeys);
                            candidates = k.contains(Value.NULL) ? List.of() : hashed.getOrDefault(k, List.of());
                        }
                        boolean matched = false;
                        for (Row r : candidates) {
                            context.tick();
                            Row combined = concat(l, r);
                            if (ExpressionCompiler.matches(condition, combined, "JOIN condition")) {
                                pending.add(combined);
                                matched = true;
                            }
                        }
                        if (!matched && type == JoinType.LEFT) {
                            List<Value> padded = new ArrayList<>(l.values());
                            for (int i = 0; i < rightWidth; i++) {
                                padded.add(Value.NULL);
                            }
                            pending.add(new Row(padded));
                        }
                    }
                }

                @Override
                public boolean hasNext() {
                    fill();
                    return !pending.isEmpty();
                }

                @Override
                public Row next() {
                    fill();
                    if (pending.isEmpty()) {
                        throw new NoSuchElementException();
                    }
                    return pending.poll();
                }
            };
        }

        private static Row concat(Row l, Row r) {
            List<Value> values = new ArrayList<>(l.size() + r.size());
            values.addAll(l.values());
            values.addAll(r.values());
            return new Row(values);
        }

        @Override
        public void describe(int depth, List<String> out) {
            String algorithm = isHashJoin() ? "HashJoin" : "NestedLoopJoin";
            out.add(PlanNode.indent(depth) + algorithm + " " + type + " ON " + conditionText);
            left.describe(depth + 1, out);
            right.describe(depth + 1, out);
        }
    }

    static final class Aggregate implements PlanNode {
        private final PlanNode child;
        private final List<RowFunction> groupKeys;
        private final List<GroupScope.AggregateSpec> specs;
        private final RowSchema schema;
        private final String text;

        Aggregate(PlanNode child, List<RowFunction> groupKeys, List<GroupScope.AggregateSpec> specs,
                RowSchema schema, String text) {
            this.child = child;
            this.groupKeys = groupKeys;
            this.specs = specs;
            this.schema = schema;
            this.text = text;
        }

        @Override
        public RowSchema schema() {
            return schema;
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            record Group(List<Value> keyValues, Accumulator[] accumulators) {}
            Map<List<Value>, Group> groups = new LinkedHashMap<>();
            Iterator<Row> input = child.execute(context);
            while (input.hasNext()) {
                Row row = input.next();
                List<Value> keyValues = new ArrayList<>(groupKeys.size());
                for (RowFunction f : groupKeys) {
                    keyValues.add(f.apply(row));
                }
                List<Value> canonicalKey = keyValues.stream().map(SqlValues::canonical).toList();
                Group group = groups.get(canonicalKey);
                if (group == null) {
                    context.materialize(1);
                    group = new Group(keyValues, newAccumulators());
                    groups.put(canonicalKey, group);
                }
                for (int i = 0; i < specs.size(); i++) {
                    RowFunction argument = specs.get(i).argument();
                    group.accumulators()[i].add(argument == null ? Value.of(true) : argument.apply(row));
                }
            }
            if (groups.isEmpty() && groupKeys.isEmpty()) {
                groups.put(List.of(), new Group(List.of(), newAccumulators()));
            }
            List<Row> output = new ArrayList<>(groups.size());
            for (Group group : groups.values()) {
                List<Value> values = new ArrayList<>(group.keyValues());
                for (Accumulator accumulator : group.accumulators()) {
                    values.add(accumulator.result());
                }
                output.add(new Row(values));
            }
            return output.iterator();
        }

        private Accumulator[] newAccumulators() {
            Accumulator[] accumulators = new Accumulator[specs.size()];
            for (int i = 0; i < accumulators.length; i++) {
                var expression = specs.get(i).expression();
                accumulators[i] = Accumulator.create(
                        expression.function(), expression.argument() == null, expression.distinct());
            }
            return accumulators;
        }

        @Override
        public void describe(int depth, List<String> out) {
            out.add(PlanNode.indent(depth) + "Aggregate " + text);
            child.describe(depth + 1, out);
        }
    }

    static final class Project implements PlanNode {
        private final PlanNode child;
        private final List<RowFunction> expressions;
        private final RowSchema schema;
        private final String text;

        Project(PlanNode child, List<RowFunction> expressions, RowSchema schema, String text) {
            this.child = child;
            this.expressions = expressions;
            this.schema = schema;
            this.text = text;
        }

        @Override
        public RowSchema schema() {
            return schema;
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            return mapped(child.execute(context), row -> {
                List<Value> values = new ArrayList<>(expressions.size());
                for (RowFunction f : expressions) {
                    values.add(f.apply(row));
                }
                return new Row(values);
            });
        }

        @Override
        public void describe(int depth, List<String> out) {
            out.add(PlanNode.indent(depth) + "Project " + text);
            child.describe(depth + 1, out);
        }
    }

    static final class Sort implements PlanNode {
        private final PlanNode child;
        private final int[] columns;
        private final boolean[] ascending;
        private final String text;

        Sort(PlanNode child, int[] columns, boolean[] ascending, String text) {
            this.child = child;
            this.columns = columns;
            this.ascending = ascending;
            this.text = text;
        }

        @Override
        public RowSchema schema() {
            return child.schema();
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            List<Row> rows = drain(child.execute(context), context);
            Comparator<Row> comparator = (a, b) -> {
                for (int i = 0; i < columns.length; i++) {
                    int c = a.get(columns[i]).compareTo(b.get(columns[i]));
                    if (c != 0) {
                        return ascending[i] ? c : Integer.compare(0, c);
                    }
                }
                return 0;
            };
            context.checkDeadline();
            rows.sort(comparator);
            return rows.iterator();
        }

        @Override
        public void describe(int depth, List<String> out) {
            out.add(PlanNode.indent(depth) + "Sort " + text + " (NULLs first when ascending)");
            child.describe(depth + 1, out);
        }
    }

    /** Drops helper columns that were only needed for sorting. */
    static final class Strip implements PlanNode {
        private final PlanNode child;
        private final int keep;
        private final RowSchema schema;

        Strip(PlanNode child, int keep) {
            this.child = child;
            this.keep = keep;
            this.schema = new RowSchema(child.schema().bindings().subList(0, keep));
        }

        @Override
        public RowSchema schema() {
            return schema;
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            return mapped(child.execute(context), row -> new Row(row.values().subList(0, keep)));
        }

        @Override
        public void describe(int depth, List<String> out) {
            child.describe(depth, out);
        }
    }

    static final class Distinct implements PlanNode {
        private final PlanNode child;

        Distinct(PlanNode child) {
            this.child = child;
        }

        @Override
        public RowSchema schema() {
            return child.schema();
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            Set<List<Value>> seen = new HashSet<>();
            return filtered(child.execute(context), row -> {
                boolean added = seen.add(row.values().stream().map(SqlValues::canonical).toList());
                if (added) {
                    context.materialize(1);
                }
                return added;
            });
        }

        @Override
        public void describe(int depth, List<String> out) {
            out.add(PlanNode.indent(depth) + "Distinct");
            child.describe(depth + 1, out);
        }
    }

    static final class Limit implements PlanNode {
        private final PlanNode child;
        private final long limit;

        Limit(PlanNode child, long limit) {
            this.child = child;
            this.limit = limit;
        }

        @Override
        public RowSchema schema() {
            return child.schema();
        }

        @Override
        public Iterator<Row> execute(ExecutionContext context) {
            Iterator<Row> source = child.execute(context);
            return new Iterator<>() {
                private long returned;

                @Override
                public boolean hasNext() {
                    return returned < limit && source.hasNext();
                }

                @Override
                public Row next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    returned++;
                    return source.next();
                }
            };
        }

        @Override
        public void describe(int depth, List<String> out) {
            out.add(PlanNode.indent(depth) + "Limit " + limit);
            child.describe(depth + 1, out);
        }
    }
}
