package dev.frierendb.engine.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.sql.Expr.BinaryOp;
import dev.frierendb.engine.sql.Statement.Select;
import dev.frierendb.engine.types.DataType;
import dev.frierendb.engine.types.Value;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest {

    private static Select select(String sql) {
        return assertInstanceOf(Select.class, Parser.parse(sql));
    }

    private static Expr where(String sql) {
        return select(sql).where();
    }

    private static Expr col(String name) {
        return new Expr.ColumnRef(null, name);
    }

    private static Expr lit(long v) {
        return new Expr.Literal(Value.of(v));
    }

    @Test
    void parsesBasicSelectWithAliasesAndStar() {
        Select s = select("SELECT s.name AS n, gpa g, * FROM students s");
        assertEquals("s", s.from().alias());
        assertEquals("students", s.from().name());
        assertEquals(3, s.items().size());
        assertEquals("n", ((Statement.Projection) s.items().get(0)).alias());
        assertEquals("g", ((Statement.Projection) s.items().get(1)).alias());
        assertEquals(new Statement.Star(null), s.items().get(2));
    }

    @Test
    void parsesQualifiedStar() {
        assertEquals(new Statement.Star("s"), select("select s.* from students s").items().get(0));
    }

    @Test
    void keywordsAreCaseInsensitive() {
        assertTrue(select("SeLeCt DiStInCt a FrOm t").distinct());
    }

    @Test
    void andBindsTighterThanOr() {
        Expr e = where("select a from t where a = 1 or b = 2 and c = 3");
        Expr.Binary or = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(BinaryOp.OR, or.op());
        assertEquals(BinaryOp.AND, assertInstanceOf(Expr.Binary.class, or.right()).op());
    }

    @Test
    void arithmeticPrecedenceAndParentheses() {
        Expr e = where("select a from t where 1 + 2 * 3 = 7");
        Expr.Binary eq = assertInstanceOf(Expr.Binary.class, e);
        Expr.Binary add = assertInstanceOf(Expr.Binary.class, eq.left());
        assertEquals(BinaryOp.ADD, add.op());
        assertEquals(BinaryOp.MULTIPLY, assertInstanceOf(Expr.Binary.class, add.right()).op());

        Expr.Binary grouped = assertInstanceOf(Expr.Binary.class,
                assertInstanceOf(Expr.Binary.class, where("select a from t where (1 + 2) * 3 = 9")).left());
        assertEquals(BinaryOp.MULTIPLY, grouped.op());
    }

    @Test
    void notAppliesToTheWholeComparison() {
        Expr.Binary and = assertInstanceOf(Expr.Binary.class, where("select a from t where not a = 1 and b = 2"));
        assertEquals(BinaryOp.AND, and.op());
        Expr.Unary not = assertInstanceOf(Expr.Unary.class, and.left());
        assertEquals(Expr.UnaryOp.NOT, not.op());
        assertEquals(BinaryOp.EQ, assertInstanceOf(Expr.Binary.class, not.operand()).op());
    }

    @Test
    void parsesInBetweenLikeAndIsNull() {
        assertEquals(new Expr.InList(col("a"), List.of(lit(1), lit(2)), false),
                where("select a from t where a in (1, 2)"));
        assertEquals(new Expr.InList(col("a"), List.of(lit(1)), true),
                where("select a from t where a not in (1)"));
        assertEquals(new Expr.IsNull(col("a"), true), where("select a from t where a is not null"));
        assertEquals(new Expr.IsNull(col("a"), false), where("select a from t where a is null"));
        assertEquals(BinaryOp.LIKE, ((Expr.Binary) where("select a from t where a like 'x%'")).op());
        Expr.Unary notLike = assertInstanceOf(Expr.Unary.class, where("select a from t where a not like 'x%'"));
        assertEquals(BinaryOp.LIKE, ((Expr.Binary) notLike.operand()).op());
        Expr.Binary between = assertInstanceOf(Expr.Binary.class, where("select a from t where a between 1 and 5"));
        assertEquals(BinaryOp.AND, between.op());
    }

    @Test
    void parsesAggregatesGroupByHavingOrderLimit() {
        Select s = select("""
                SELECT major, COUNT(*), AVG(gpa) FROM students
                GROUP BY major HAVING COUNT(*) > 2 ORDER BY AVG(gpa) DESC, major LIMIT 5""");
        assertEquals(1, s.groupBy().size());
        assertTrue(s.having() != null);
        assertEquals(2, s.orderBy().size());
        assertFalse(s.orderBy().get(0).ascending());
        assertTrue(s.orderBy().get(1).ascending());
        assertEquals(5L, s.limit());
        Expr.Aggregate count = assertInstanceOf(Expr.Aggregate.class,
                ((Statement.Projection) s.items().get(1)).expr());
        assertNull(count.argument());
    }

    @Test
    void parsesCountDistinct() {
        Expr.Aggregate agg = assertInstanceOf(Expr.Aggregate.class,
                ((Statement.Projection) select("select count(distinct major) from students").items().get(0)).expr());
        assertTrue(agg.distinct());
    }

    @Test
    void parsesJoins() {
        Select s = select("""
                select * from students s
                join enrollments e on s.sid = e.sid
                left outer join courses c on c.cid = e.cid""");
        assertEquals(2, s.joins().size());
        assertEquals(Statement.JoinType.INNER, s.joins().get(0).type());
        assertEquals(Statement.JoinType.LEFT, s.joins().get(1).type());
        assertEquals("c", s.joins().get(1).table().alias());
    }

    @Test
    void parsesScalarFunctions() {
        Expr.Call call = assertInstanceOf(Expr.Call.class,
                ((Statement.Projection) select("select round(gpa, 1) from t").items().get(0)).expr());
        assertEquals(Expr.ScalarFunction.ROUND, call.function());
        assertEquals(2, call.arguments().size());
    }

    @Test
    void parsesNegativeNumbersIncludingLongMinimum() {
        assertEquals(new Expr.Literal(Value.of(-9223372036854775808L)),
                ((Expr.Binary) where("select a from t where a = -9223372036854775808")).right());
        assertEquals(new Expr.Literal(Value.of(-2.5)),
                ((Expr.Binary) where("select a from t where a = -2.5")).right());
        assertInstanceOf(Expr.Unary.class, ((Expr.Binary) where("select a from t where a = -b")).right());
    }

    @Test
    void parsesStringEscapesAndComments() {
        Select s = select("select a from t -- trailing comment\n where name = 'O''Brien'");
        assertEquals(new Expr.Literal(Value.of("O'Brien")), ((Expr.Binary) s.where()).right());
    }

    @Test
    void quotedIdentifiersMayBeReservedWords() {
        Select s = select("select \"select\" from \"table\"");
        assertEquals("table", s.from().name());
    }

    @Test
    void parsesInsertWithMultipleRows() {
        Statement.Insert insert = assertInstanceOf(Statement.Insert.class,
                Parser.parse("insert into t (a, b) values (1, 'x'), (2, null)"));
        assertEquals(List.of("a", "b"), insert.columns());
        assertEquals(2, insert.rows().size());
        assertEquals(new Expr.Literal(Value.NULL), insert.rows().get(1).get(1));
    }

    @Test
    void parsesUpdateDeleteCreateDropExplain() {
        Statement.Update update = assertInstanceOf(Statement.Update.class,
                Parser.parse("update t set a = a + 1, b = 'z' where a < 3"));
        assertEquals(2, update.assignments().size());
        Statement.Delete delete = assertInstanceOf(Statement.Delete.class, Parser.parse("delete from t where a = 1"));
        assertTrue(delete.where() != null);
        Statement.CreateTable create = assertInstanceOf(Statement.CreateTable.class,
                Parser.parse("create table if not exists t (id int not null, name varchar(20), score double)"));
        assertTrue(create.ifNotExists());
        assertEquals(DataType.TEXT, create.columns().get(1).type());
        assertFalse(create.columns().get(0).nullable());
        assertTrue(create.columns().get(2).nullable());
        assertEquals(new Statement.DropTable("t", true), Parser.parse("drop table if exists t"));
        assertInstanceOf(Statement.Explain.class, Parser.parse("explain select a from t"));
    }

    @Test
    void readOnlyClassification() {
        assertTrue(Parser.parse("select a from t").isReadOnly());
        assertTrue(Parser.parse("explain select a from t").isReadOnly());
        assertFalse(Parser.parse("insert into t values (1)").isReadOnly());
        assertFalse(Parser.parse("update t set a = 1").isReadOnly());
        assertFalse(Parser.parse("delete from t").isReadOnly());
        assertFalse(Parser.parse("drop table t").isReadOnly());
        assertFalse(Parser.parse("create table t (a int)").isReadOnly());
    }

    @Test
    void allowsOneTrailingSemicolon() {
        assertInstanceOf(Select.class, Parser.parse("select a from t;"));
    }

    @Test
    void rejectsMultipleStatements() {
        SqlException e = assertThrows(SqlException.class, () -> Parser.parse("select a from t; drop table t"));
        assertTrue(e.getMessage().contains("only one statement"));
        assertThrows(SqlException.class, () -> Parser.parse("select a from t;;"));
    }

    @Test
    void parseScriptSplitsStatements() {
        List<Statement> statements = Parser.parseScript("create table t (a int); insert into t values (1);; select a from t;");
        assertEquals(3, statements.size());
    }

    @Test
    void reportsSyntaxErrorsWithPosition() {
        SqlException e = assertThrows(SqlException.class, () -> Parser.parse("select from t"));
        assertTrue(e.getMessage().contains("position"), e.getMessage());
        assertThrows(SqlException.class, () -> Parser.parse("select a from"));
        assertThrows(SqlException.class, () -> Parser.parse("select a from t where"));
        assertThrows(SqlException.class, () -> Parser.parse("select a from t garbage garbage"));
        assertThrows(SqlException.class, () -> Parser.parse(""));
    }

    @Test
    void rejectsUnterminatedStringsAndStrangeCharacters() {
        assertThrows(SqlException.class, () -> Parser.parse("select 'abc from t"));
        assertThrows(SqlException.class, () -> Parser.parse("select a from t where a = #"));
    }

    @Test
    void rejectsUnknownFunctionsAndWrongArity() {
        assertThrows(SqlException.class, () -> Parser.parse("select pg_sleep(10) from t"));
        assertThrows(SqlException.class, () -> Parser.parse("select lower(a, b) from t"));
    }

    @Test
    void rejectsCommaJoinsAndReservedNames() {
        assertThrows(SqlException.class, () -> Parser.parse("select * from a, b"));
        assertThrows(SqlException.class, () -> Parser.parse("select a from select"));
    }

    @Test
    void rejectsOutOfRangeAndMalformedLimits() {
        assertThrows(SqlException.class, () -> Parser.parse("select a from t where a = 99999999999999999999"));
        assertThrows(SqlException.class, () -> Parser.parse("select a from t limit -1"));
        assertThrows(SqlException.class, () -> Parser.parse("select a from t limit 1.5"));
    }

    @Test
    void rejectsPathologicalNesting() {
        String deep = "(".repeat(5000) + "1" + ")".repeat(5000);
        SqlException e = assertThrows(SqlException.class, () -> Parser.parse("select a from t where a = " + deep));
        assertTrue(e.getMessage().contains("nested too deeply"));
        String nots = "not ".repeat(5000) + "a = 1";
        assertThrows(SqlException.class, () -> Parser.parse("select a from t where " + nots));
        String minuses = "- ".repeat(5000) + "a";
        SqlException minusError =
                assertThrows(SqlException.class, () -> Parser.parse("select a from t where a = " + minuses));
        assertTrue(minusError.getMessage().contains("nested too deeply"));
    }

    @Test
    void unknownColumnTypeIsRejected() {
        assertThrows(SqlException.class, () -> Parser.parse("create table t (a blob)"));
    }

    @Test
    void parsesCreateAndDropIndex() {
        assertEquals(new Statement.CreateIndex("idx_gpa", "students", "gpa", false),
                Parser.parse("CREATE INDEX idx_gpa ON students (gpa)"));
        assertEquals(new Statement.CreateIndex("i", "t", "c", true),
                Parser.parse("create index if not exists i on t(c);"));
        assertEquals(new Statement.DropIndex("idx_gpa", false), Parser.parse("DROP INDEX idx_gpa"));
        assertEquals(new Statement.DropIndex("i", true), Parser.parse("drop index if exists i"));
        assertFalse(Parser.parse("create index i on t(c)").isReadOnly());
        assertFalse(Parser.parse("drop index i").isReadOnly());
    }

    @Test
    void rejectsMalformedIndexStatements() {
        assertThrows(SqlException.class, () -> Parser.parse("create index on t (c)"));
        assertThrows(SqlException.class, () -> Parser.parse("create index i on t"));
        assertThrows(SqlException.class, () -> Parser.parse("create index i on t ()"));
        SqlException multi = assertThrows(SqlException.class, () -> Parser.parse("create index i on t (a, b)"));
        assertTrue(multi.getMessage().contains("exactly one column"));
        assertThrows(SqlException.class, () -> Parser.parse("create unique index i on t (a)"));
        assertThrows(SqlException.class, () -> Parser.parse("drop index"));
    }

    @Test
    void indexIsNotAReservedWord() {
        Select s = select("select index from t");
        assertEquals(1, s.items().size());
    }

    @Test
    void parsesTransactionControl() {
        assertEquals(new Statement.Begin(), Parser.parse("BEGIN"));
        assertEquals(new Statement.Begin(), Parser.parse("begin transaction;"));
        assertEquals(new Statement.Commit(), Parser.parse("COMMIT"));
        assertEquals(new Statement.Commit(), Parser.parse("commit transaction"));
        assertEquals(new Statement.Rollback(), Parser.parse("ROLLBACK"));
        assertEquals(new Statement.Rollback(), Parser.parse("rollback transaction"));
        assertFalse(Parser.parse("begin").isReadOnly());
        assertFalse(Parser.parse("commit").isReadOnly());
        assertFalse(Parser.parse("rollback").isReadOnly());
        assertThrows(SqlException.class, () -> Parser.parse("begin work now"));
        assertThrows(SqlException.class, () -> Parser.parse("commit; commit"));
    }

    @Test
    void transactionWordsAreNotReserved() {
        assertEquals(4, select("select begin, commit, rollback, transaction from t").items().size());
    }
}
