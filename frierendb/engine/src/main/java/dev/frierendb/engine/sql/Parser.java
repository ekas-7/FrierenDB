package dev.frierendb.engine.sql;

import dev.frierendb.engine.sql.Expr.AggregateFunction;
import dev.frierendb.engine.sql.Expr.BinaryOp;
import dev.frierendb.engine.sql.Expr.ScalarFunction;
import dev.frierendb.engine.sql.Expr.UnaryOp;
import dev.frierendb.engine.sql.Lexer.Kind;
import dev.frierendb.engine.sql.Lexer.Token;
import dev.frierendb.engine.sql.Statement.Assignment;
import dev.frierendb.engine.sql.Statement.ColumnDef;
import dev.frierendb.engine.sql.Statement.Join;
import dev.frierendb.engine.sql.Statement.JoinType;
import dev.frierendb.engine.sql.Statement.OrderItem;
import dev.frierendb.engine.sql.Statement.Projection;
import dev.frierendb.engine.sql.Statement.Select;
import dev.frierendb.engine.sql.Statement.SelectItem;
import dev.frierendb.engine.sql.Statement.Star;
import dev.frierendb.engine.sql.Statement.TableRef;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Parser {

    private static final int MAX_NESTING = 64;

    private static final Set<String> RESERVED = Set.of(
            "select", "distinct", "from", "where", "group", "by", "having", "order", "limit",
            "join", "inner", "left", "outer", "on", "as", "and", "or", "not", "is", "null", "in",
            "like", "between", "asc", "desc", "insert", "into", "values", "update", "set", "delete",
            "create", "table", "drop", "if", "exists", "explain", "true", "false");

    private final List<Token> tokens;
    private int index;
    private int depth;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Parses exactly one statement, optionally ended by a semicolon. */
    public static Statement parse(String sql) {
        Parser parser = new Parser(Lexer.tokenize(sql));
        Statement statement = parser.statement();
        parser.acceptSymbol(";");
        parser.expectEnd();
        return statement;
    }

    /** Parses semicolon-separated statements, for seed scripts. */
    public static List<Statement> parseScript(String sql) {
        Parser parser = new Parser(Lexer.tokenize(sql));
        List<Statement> statements = new ArrayList<>();
        while (parser.peek().kind() != Kind.EOF) {
            if (parser.acceptSymbol(";")) {
                continue;
            }
            statements.add(parser.statement());
            if (parser.peek().kind() != Kind.EOF) {
                parser.expectSymbol(";");
            }
        }
        return statements;
    }

    private Statement statement() {
        if (acceptKeyword("select")) {
            return select();
        }
        if (acceptKeyword("explain")) {
            expectKeyword("select");
            return new Statement.Explain(select());
        }
        if (acceptKeyword("insert")) {
            return insert();
        }
        if (acceptKeyword("update")) {
            return update();
        }
        if (acceptKeyword("delete")) {
            return delete();
        }
        if (acceptKeyword("create")) {
            return acceptKeyword("index") ? createIndex() : createTable();
        }
        if (acceptKeyword("drop")) {
            return acceptKeyword("index") ? dropIndex() : dropTable();
        }
        throw error(peek(), "Expected SELECT, INSERT, UPDATE, DELETE, CREATE, DROP or EXPLAIN");
    }

    private Select select() {
        boolean distinct = acceptKeyword("distinct");
        List<SelectItem> items = new ArrayList<>();
        do {
            items.add(selectItem());
        } while (acceptSymbol(","));
        expectKeyword("from");
        TableRef from = tableRef();
        if (peek().kind() == Kind.SYMBOL && peek().text().equals(",")) {
            throw error(peek(), "Comma joins are not supported; use JOIN ... ON");
        }
        List<Join> joins = new ArrayList<>();
        while (true) {
            JoinType type = joinType();
            if (type == null) {
                break;
            }
            TableRef table = tableRef();
            expectKeyword("on");
            joins.add(new Join(type, table, expression()));
        }
        Expr where = acceptKeyword("where") ? expression() : null;
        List<Expr> groupBy = new ArrayList<>();
        if (acceptKeyword("group")) {
            expectKeyword("by");
            do {
                groupBy.add(expression());
            } while (acceptSymbol(","));
        }
        Expr having = acceptKeyword("having") ? expression() : null;
        List<OrderItem> orderBy = new ArrayList<>();
        if (acceptKeyword("order")) {
            expectKeyword("by");
            do {
                Expr expr = expression();
                boolean ascending = !acceptKeyword("desc");
                if (ascending) {
                    acceptKeyword("asc");
                }
                orderBy.add(new OrderItem(expr, ascending));
            } while (acceptSymbol(","));
        }
        Long limit = null;
        if (acceptKeyword("limit")) {
            Token t = next();
            if (t.kind() != Kind.NUMBER || t.text().matches(".*[.eE].*")) {
                throw error(t, "LIMIT expects a non-negative integer");
            }
            limit = parseLong(t, t.text());
        }
        return new Select(items, distinct, from, joins, where, groupBy, having, orderBy, limit);
    }

    private JoinType joinType() {
        if (acceptKeyword("join")) {
            return JoinType.INNER;
        }
        if (acceptKeyword("inner")) {
            expectKeyword("join");
            return JoinType.INNER;
        }
        if (acceptKeyword("left")) {
            acceptKeyword("outer");
            expectKeyword("join");
            return JoinType.LEFT;
        }
        return null;
    }

    private SelectItem selectItem() {
        if (acceptSymbol("*")) {
            return new Star(null);
        }
        if (isIdentifierToken(peek()) && isSymbol(peek(1), ".") && isSymbol(peek(2), "*")) {
            String table = identifier();
            next();
            next();
            return new Star(table);
        }
        Expr expr = expression();
        String alias = null;
        if (acceptKeyword("as")) {
            alias = identifier();
        } else if (isImplicitAlias(peek())) {
            alias = identifier();
        }
        return new Projection(expr, alias);
    }

    private TableRef tableRef() {
        String name = identifier();
        String alias = null;
        if (acceptKeyword("as")) {
            alias = identifier();
        } else if (isImplicitAlias(peek())) {
            alias = identifier();
        }
        return new TableRef(name, alias);
    }

    private Statement insert() {
        expectKeyword("into");
        String table = identifier();
        List<String> columns = new ArrayList<>();
        if (acceptSymbol("(")) {
            do {
                columns.add(identifier());
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        expectKeyword("values");
        List<List<Expr>> rows = new ArrayList<>();
        do {
            expectSymbol("(");
            List<Expr> row = new ArrayList<>();
            do {
                row.add(expression());
            } while (acceptSymbol(","));
            expectSymbol(")");
            rows.add(row);
        } while (acceptSymbol(","));
        return new Statement.Insert(table, columns, rows);
    }

    private Statement update() {
        String table = identifier();
        expectKeyword("set");
        List<Assignment> assignments = new ArrayList<>();
        do {
            String column = identifier();
            expectSymbol("=");
            assignments.add(new Assignment(column, expression()));
        } while (acceptSymbol(","));
        Expr where = acceptKeyword("where") ? expression() : null;
        return new Statement.Update(table, assignments, where);
    }

    private Statement delete() {
        expectKeyword("from");
        String table = identifier();
        Expr where = acceptKeyword("where") ? expression() : null;
        return new Statement.Delete(table, where);
    }

    private Statement createTable() {
        expectKeyword("table");
        boolean ifNotExists = false;
        if (acceptKeyword("if")) {
            expectKeyword("not");
            expectKeyword("exists");
            ifNotExists = true;
        }
        String name = identifier();
        expectSymbol("(");
        List<ColumnDef> columns = new ArrayList<>();
        do {
            columns.add(columnDef());
        } while (acceptSymbol(","));
        expectSymbol(")");
        return new Statement.CreateTable(name, columns, ifNotExists);
    }

    private ColumnDef columnDef() {
        String name = identifier();
        Token typeToken = next();
        if (typeToken.kind() != Kind.WORD) {
            throw error(typeToken, "Expected a column type");
        }
        DataType type = DataType.fromSqlName(typeToken.text())
                .orElseThrow(() -> error(typeToken, "Unknown column type '" + typeToken.text() + "'"));
        if (acceptSymbol("(")) {
            do {
                Token size = next();
                if (size.kind() != Kind.NUMBER) {
                    throw error(size, "Expected a type size");
                }
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        boolean nullable = true;
        if (acceptKeyword("not")) {
            expectKeyword("null");
            nullable = false;
        } else {
            acceptKeyword("null");
        }
        return new ColumnDef(name, type, nullable);
    }

    private Statement createIndex() {
        boolean ifNotExists = false;
        if (acceptKeyword("if")) {
            expectKeyword("not");
            expectKeyword("exists");
            ifNotExists = true;
        }
        String name = identifier();
        expectKeyword("on");
        String table = identifier();
        expectSymbol("(");
        String column = identifier();
        if (peek().kind() == Kind.SYMBOL && peek().text().equals(",")) {
            throw error(peek(), "An index covers exactly one column");
        }
        expectSymbol(")");
        return new Statement.CreateIndex(name, table, column, ifNotExists);
    }

    private Statement dropIndex() {
        boolean ifExists = false;
        if (acceptKeyword("if")) {
            expectKeyword("exists");
            ifExists = true;
        }
        return new Statement.DropIndex(identifier(), ifExists);
    }

    private Statement dropTable() {
        expectKeyword("table");
        boolean ifExists = false;
        if (acceptKeyword("if")) {
            expectKeyword("exists");
            ifExists = true;
        }
        return new Statement.DropTable(identifier(), ifExists);
    }

    // ---- expressions, lowest to highest precedence ----

    private Expr expression() {
        enter();
        try {
            Expr left = and();
            while (acceptKeyword("or")) {
                left = new Expr.Binary(BinaryOp.OR, left, and());
            }
            return left;
        } finally {
            depth--;
        }
    }

    private Expr and() {
        Expr left = not();
        while (acceptKeyword("and")) {
            left = new Expr.Binary(BinaryOp.AND, left, not());
        }
        return left;
    }

    private Expr not() {
        if (acceptKeyword("not")) {
            enter();
            try {
                return new Expr.Unary(UnaryOp.NOT, not());
            } finally {
                depth--;
            }
        }
        return comparison();
    }

    private Expr comparison() {
        Expr left = additive();
        Token t = peek();
        if (t.kind() == Kind.SYMBOL) {
            BinaryOp op = switch (t.text()) {
                case "=" -> BinaryOp.EQ;
                case "!=", "<>" -> BinaryOp.NE;
                case "<" -> BinaryOp.LT;
                case "<=" -> BinaryOp.LE;
                case ">" -> BinaryOp.GT;
                case ">=" -> BinaryOp.GE;
                default -> null;
            };
            if (op != null) {
                next();
                return new Expr.Binary(op, left, additive());
            }
            return left;
        }
        if (acceptKeyword("is")) {
            boolean negated = acceptKeyword("not");
            expectKeyword("null");
            return new Expr.IsNull(left, negated);
        }
        boolean negated = false;
        if (isKeyword(peek(), "not")
                && (isKeyword(peek(1), "in") || isKeyword(peek(1), "like") || isKeyword(peek(1), "between"))) {
            next();
            negated = true;
        }
        Expr result;
        if (acceptKeyword("like")) {
            result = new Expr.Binary(BinaryOp.LIKE, left, additive());
        } else if (acceptKeyword("in")) {
            expectSymbol("(");
            List<Expr> items = new ArrayList<>();
            do {
                items.add(expression());
            } while (acceptSymbol(","));
            expectSymbol(")");
            return new Expr.InList(left, items, negated);
        } else if (acceptKeyword("between")) {
            Expr low = additive();
            expectKeyword("and");
            Expr high = additive();
            result = new Expr.Binary(BinaryOp.AND,
                    new Expr.Binary(BinaryOp.GE, left, low),
                    new Expr.Binary(BinaryOp.LE, left, high));
        } else {
            return left;
        }
        return negated ? new Expr.Unary(UnaryOp.NOT, result) : result;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            if (acceptSymbol("+")) {
                left = new Expr.Binary(BinaryOp.ADD, left, multiplicative());
            } else if (acceptSymbol("-")) {
                left = new Expr.Binary(BinaryOp.SUBTRACT, left, multiplicative());
            } else {
                return left;
            }
        }
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            if (acceptSymbol("*")) {
                left = new Expr.Binary(BinaryOp.MULTIPLY, left, unary());
            } else if (acceptSymbol("/")) {
                left = new Expr.Binary(BinaryOp.DIVIDE, left, unary());
            } else if (acceptSymbol("%")) {
                left = new Expr.Binary(BinaryOp.MODULO, left, unary());
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        enter();
        try {
            if (acceptSymbol("-")) {
                if (peek().kind() == Kind.NUMBER) {
                    return numberLiteral(next(), true);
                }
                return new Expr.Unary(UnaryOp.NEGATE, unary());
            }
            acceptSymbol("+");
            return primary();
        } finally {
            depth--;
        }
    }

    private Expr primary() {
        Token t = peek();
        switch (t.kind()) {
            case NUMBER -> {
                next();
                return numberLiteral(t, false);
            }
            case STRING -> {
                next();
                return new Expr.Literal(Value.of(t.text()));
            }
            case SYMBOL -> {
                if (t.text().equals("(")) {
                    next();
                    Expr inner = expression();
                    expectSymbol(")");
                    return inner;
                }
                throw error(t, "Unexpected '" + t.text() + "'");
            }
            case EOF -> throw error(t, "Unexpected end of input");
            case WORD, QUOTED_WORD -> {
                return wordExpression(t);
            }
            default -> throw error(t, "Unexpected token");
        }
    }

    private Expr wordExpression(Token t) {
        if (t.kind() == Kind.WORD) {
            String lower = t.text().toLowerCase(Locale.ROOT);
            switch (lower) {
                case "true" -> {
                    next();
                    return new Expr.Literal(Value.of(true));
                }
                case "false" -> {
                    next();
                    return new Expr.Literal(Value.of(false));
                }
                case "null" -> {
                    next();
                    return new Expr.Literal(Value.NULL);
                }
                default -> { }
            }
            if (isSymbol(peek(1), "(")) {
                return functionCall();
            }
        }
        String first = identifier();
        if (acceptSymbol(".")) {
            return new Expr.ColumnRef(first, identifier());
        }
        return new Expr.ColumnRef(null, first);
    }

    private Expr functionCall() {
        Token nameToken = next();
        String name = nameToken.text().toUpperCase(Locale.ROOT);
        expectSymbol("(");
        AggregateFunction aggregate = null;
        for (AggregateFunction f : AggregateFunction.values()) {
            if (f.name().equals(name)) {
                aggregate = f;
            }
        }
        if (aggregate != null) {
            if (aggregate == AggregateFunction.COUNT && acceptSymbol("*")) {
                expectSymbol(")");
                return new Expr.Aggregate(aggregate, null, false);
            }
            boolean distinct = acceptKeyword("distinct");
            Expr argument = expression();
            expectSymbol(")");
            return new Expr.Aggregate(aggregate, argument, distinct);
        }
        ScalarFunction scalar = null;
        for (ScalarFunction f : ScalarFunction.values()) {
            if (f.name().equals(name)) {
                scalar = f;
            }
        }
        if (scalar == null) {
            throw error(nameToken, "Unknown function " + nameToken.text());
        }
        List<Expr> args = new ArrayList<>();
        do {
            args.add(expression());
        } while (acceptSymbol(","));
        expectSymbol(")");
        if (!scalar.acceptsArgumentCount(args.size())) {
            throw error(nameToken, "Wrong number of arguments for " + name);
        }
        return new Expr.Call(scalar, args);
    }

    private Expr numberLiteral(Token t, boolean negative) {
        String text = (negative ? "-" : "") + t.text();
        if (t.text().matches(".*[.eE].*")) {
            try {
                return new Expr.Literal(Value.of(Double.parseDouble(text)));
            } catch (NumberFormatException e) {
                throw error(t, "Invalid number " + t.text());
            }
        }
        return new Expr.Literal(Value.of(parseLong(t, text)));
    }

    private long parseLong(Token t, String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw error(t, "Integer literal out of range: " + t.text());
        }
    }

    // ---- token helpers ----

    private void enter() {
        if (++depth > MAX_NESTING) {
            throw new SqlException("Expression is nested too deeply (limit " + MAX_NESTING + ")");
        }
    }

    private Token peek() {
        return peek(0);
    }

    private Token peek(int offset) {
        return tokens.get(Math.min(index + offset, tokens.size() - 1));
    }

    private Token next() {
        Token t = peek();
        if (index < tokens.size() - 1) {
            index++;
        }
        return t;
    }

    private boolean isKeyword(Token t, String keyword) {
        return t.kind() == Kind.WORD && t.text().equalsIgnoreCase(keyword);
    }

    private boolean isSymbol(Token t, String symbol) {
        return t.kind() == Kind.SYMBOL && t.text().equals(symbol);
    }

    private boolean isIdentifierToken(Token t) {
        return t.kind() == Kind.QUOTED_WORD
                || (t.kind() == Kind.WORD && !RESERVED.contains(t.text().toLowerCase(Locale.ROOT)));
    }

    private boolean isImplicitAlias(Token t) {
        return isIdentifierToken(t);
    }

    private boolean acceptKeyword(String keyword) {
        if (isKeyword(peek(), keyword)) {
            next();
            return true;
        }
        return false;
    }

    private void expectKeyword(String keyword) {
        if (!acceptKeyword(keyword)) {
            throw error(peek(), "Expected " + keyword.toUpperCase(Locale.ROOT));
        }
    }

    private boolean acceptSymbol(String symbol) {
        if (isSymbol(peek(), symbol)) {
            next();
            return true;
        }
        return false;
    }

    private void expectSymbol(String symbol) {
        if (!acceptSymbol(symbol)) {
            throw error(peek(), "Expected '" + symbol + "'");
        }
    }

    private void expectEnd() {
        if (peek().kind() != Kind.EOF) {
            throw error(peek(), "Unexpected input after the end of the statement; only one statement is allowed");
        }
    }

    private String identifier() {
        Token t = next();
        if (t.kind() == Kind.QUOTED_WORD) {
            return t.text();
        }
        if (t.kind() == Kind.WORD) {
            if (RESERVED.contains(t.text().toLowerCase(Locale.ROOT))) {
                throw error(t, "'" + t.text() + "' is a reserved word; quote it with double quotes to use it as a name");
            }
            return t.text();
        }
        throw error(t, "Expected a name");
    }

    private SqlException error(Token at, String message) {
        String found = at.kind() == Kind.EOF ? "end of input" : "'" + at.text() + "'";
        return new SqlException(message + " at position " + at.position() + " (found " + found + ")");
    }
}
