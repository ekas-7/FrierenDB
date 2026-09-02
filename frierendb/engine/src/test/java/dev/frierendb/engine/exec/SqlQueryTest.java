package dev.frierendb.engine.exec;

import static dev.frierendb.engine.TestDatabases.rows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.Database;
import dev.frierendb.engine.TestDatabases;
import dev.frierendb.engine.catalog.CatalogException;
import dev.frierendb.engine.sql.SqlException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlQueryTest {

    @TempDir
    Path dir;

    private Database db;

    @BeforeEach
    void setUp() {
        db = TestDatabases.university(dir);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static List<List<String>> table(String... rowsOfCsv) {
        return java.util.Arrays.stream(rowsOfCsv).map(r -> List.of(r.split(",", -1))).toList();
    }

    @Test
    void selectStarReturnsAllColumnsAndRows() {
        var result = db.query("select * from students", QueryOptions.unlimited());
        assertEquals(List.of("sid", "name", "major", "gpa"), result.columns());
        assertEquals(6, result.rows().size());
    }

    @Test
    void whereExcludesNullComparisons() {
        assertEquals(table("Ada", "Bob", "Cy"), rows(db, "select name from students where gpa > 3.0 order by sid"));
        assertEquals(table(), rows(db, "select name from students where gpa = null"));
        assertEquals(table("Di"), rows(db, "select name from students where gpa is null"));
        assertEquals(table("Fay"), rows(db, "select name from students where major is null"));
    }

    @Test
    void notAndOrFollowThreeValuedLogic() {
        assertEquals(table("Eve", "Fay"), rows(db, "select name from students where not (gpa > 3.0) order by sid"));
        assertEquals(table("Ada", "Bob", "Cy"),
                rows(db, "select name from students where gpa > 3.4 or major = 'CS' order by sid"));
        assertEquals(table("Di"), rows(db, "select name from students where major = 'Math' and gpa is null"));
    }

    @Test
    void inAndNotInHandleNulls() {
        assertEquals(table("Ada", "Bob", "Eve"),
                rows(db, "select name from students where major in ('CS', 'Art') order by sid"));
        assertEquals(table("Cy", "Di", "Eve"),
                rows(db, "select name from students where major not in ('CS') order by sid"));
        assertEquals(table(), rows(db, "select name from students where sid not in (1, null)"));
        assertEquals(table("Ada"), rows(db, "select name from students where sid in (1, null)"));
    }

    @Test
    void likeAndBetween() {
        assertEquals(table("Fay"), rows(db, "select name from students where name like '_a_'"));
        assertEquals(table("Eve"), rows(db, "select name from students where name like '%e'"));
        assertEquals(table("Ada", "Bob", "Cy", "Di", "Eve"),
                rows(db, "select name from students where name not like 'F%' order by sid"));
        assertEquals(table("Bob", "Cy", "Fay"),
                rows(db, "select name from students where gpa between 3.0 and 3.5 order by sid"));
    }

    @Test
    void orderByAscendingPutsNullsFirstAndDescendingPutsThemLast() {
        assertEquals(table("Di", "Eve", "Fay", "Bob", "Cy", "Ada"), rows(db, "select name from students order by gpa"));
        assertEquals(table("Ada", "Cy", "Bob", "Fay", "Eve", "Di"),
                rows(db, "select name from students order by gpa desc"));
    }

    @Test
    void orderByAcceptsAliasPositionAndUnselectedColumn() {
        assertEquals(table("Ada,3.75", "Cy,3.5"),
                rows(db, "select name, gpa as score from students order by score desc limit 2"));
        assertEquals(table("Ada", "Bob"), rows(db, "select name from students order by 1 limit 2"));
        assertEquals(table("Ada"), rows(db, "select name from students order by gpa desc limit 1"));
        assertThrows(SqlException.class, () -> rows(db, "select name from students order by 9"));
    }

    @Test
    void orderByHasMultipleKeysAndIsStable() {
        assertEquals(table("Ada,CS", "Bob,CS", "Eve,Art"),
                rows(db, "select name, major from students where sid in (1, 2, 5) order by major desc, name"));
    }

    @Test
    void limitStopsEarly() {
        assertEquals(table("Ada", "Bob"), rows(db, "select name from students order by sid limit 2"));
        assertEquals(table(), rows(db, "select name from students limit 0"));
    }

    @Test
    void distinctRemovesDuplicatesAndKeepsNullAsOneGroup() {
        assertEquals(table("NULL", "Art", "CS", "Math"),
                rows(db, "select distinct major from students order by major"));
    }

    @Test
    void distinctWithUnselectedOrderByIsRejected() {
        assertThrows(SqlException.class, () -> rows(db, "select distinct major from students order by gpa"));
    }

    @Test
    void groupByComputesAggregatesIgnoringNulls() {
        assertEquals(table("NULL,1,3.0", "Art,1,2.5", "CS,2,3.5", "Math,2,3.5"),
                rows(db, "select major, count(*), avg(gpa) from students group by major order by major"));
    }

    @Test
    void havingFiltersGroups() {
        assertEquals(table("CS", "Math"),
                rows(db, "select major from students group by major having count(*) > 1 order by major"));
        assertEquals(table("CS"),
                rows(db, "select major from students group by major having sum(gpa) > 6.5"));
    }

    @Test
    void globalAggregates() {
        assertEquals(table("6,5,16.0,3.2,2.5,3.75"),
                rows(db, "select count(*), count(gpa), sum(gpa), avg(gpa), min(gpa), max(gpa) from students"));
        assertEquals(table("21"), rows(db, "select sum(sid) from students"));
        assertEquals(table("3"), rows(db, "select count(distinct major) from students"));
        assertEquals(table("Ada,Fay"), rows(db, "select min(name), max(name) from students"));
    }

    @Test
    void aggregatesOverNoRowsFollowSqlRules() {
        assertEquals(table("0,NULL,NULL,NULL"),
                rows(db, "select count(*), sum(gpa), max(gpa), avg(gpa) from students where sid > 100"));
        assertEquals(table(), rows(db, "select major, count(*) from students where sid > 100 group by major"));
    }

    @Test
    void orderByAggregate() {
        assertEquals(table("Databases,3", "Algebra,2", "Painting,1"),
                rows(db, """
                        select c.title, count(*) as n from enrollments e join courses c on c.cid = e.cid
                        group by c.title order by n desc, c.title"""));
        assertEquals(table("Math", "CS", "Art"),
                rows(db, "select major from students where major is not null group by major order by count(*) desc, major desc"));
    }

    @Test
    void groupByValidationRejectsUngroupedColumns() {
        assertThrows(SqlException.class, () -> rows(db, "select name, count(*) from students"));
        assertThrows(SqlException.class, () -> rows(db, "select major, name from students group by major"));
        assertThrows(SqlException.class, () -> rows(db, "select * from students group by major"));
        assertThrows(SqlException.class, () -> rows(db, "select name from students where count(*) > 1"));
        assertThrows(SqlException.class, () -> rows(db, "select name from students having sid > 1"));
        assertThrows(SqlException.class, () -> rows(db, "select sum(count(*)) from students"));
    }

    @Test
    void groupedColumnMayBeQualifiedOrUnqualified() {
        assertEquals(table("CS,2"),
                rows(db, "select s.major, count(*) from students s where s.major = 'CS' group by major"));
    }

    @Test
    void innerJoin() {
        assertEquals(table("Ada,10", "Ada,11", "Bob,10", "Cy,10", "Cy,11", "Eve,12"),
                rows(db, """
                        select s.name, e.cid from students s join enrollments e on s.sid = e.sid
                        order by s.sid, e.cid"""));
    }

    @Test
    void leftJoinKeepsUnmatchedLeftRows() {
        assertEquals(table("Di", "Fay"),
                rows(db, """
                        select s.name from students s left join enrollments e on s.sid = e.sid
                        where e.cid is null order by s.name"""));
        assertEquals(table("Di,NULL"),
                rows(db, "select s.name, e.grade from students s left join enrollments e on s.sid = e.sid where s.sid = 4"));
    }

    @Test
    void threeWayJoin() {
        assertEquals(table("Ada,Databases", "Ada,Algebra", "Eve,Painting"),
                rows(db, """
                        select s.name, c.title from students s
                        join enrollments e on e.sid = s.sid
                        join courses c on c.cid = e.cid
                        where s.sid in (1, 5) order by s.sid, c.cid"""));
    }

    @Test
    void nonEquiJoinFallsBackToNestedLoops() {
        assertEquals(table("10"), rows(db, "select count(*) from students a join students b on a.gpa < b.gpa"));
        var plan = TestDatabases.render(db.query("explain select count(*) from students a join students b on a.gpa < b.gpa",
                QueryOptions.unlimited()));
        assertTrue(plan.stream().anyMatch(l -> l.get(0).contains("NestedLoopJoin")), plan.toString());
    }

    @Test
    void equiJoinUsesHashJoinEvenWithExtraConditions() {
        var plan = TestDatabases.render(db.query("""
                explain select s.name from students s join enrollments e on s.sid = e.sid and e.grade > 3.0""",
                QueryOptions.unlimited()));
        assertTrue(plan.stream().anyMatch(l -> l.get(0).contains("HashJoin")), plan.toString());
        assertEquals(table("Ada", "Ada", "Cy", "Eve"),
                rows(db, "select s.name from students s join enrollments e on s.sid = e.sid and e.grade > 3.0 order by s.sid, e.cid"));
    }

    @Test
    void joinKeysTreatEqualIntAndDoubleAsEqual() {
        db.execute("create table scores (id DOUBLE)");
        db.execute("insert into scores values (1.0), (2.5)");
        assertEquals(table("Ada"), rows(db, "select s.name from students s join scores c on s.sid = c.id"));
    }

    @Test
    void nullJoinKeysNeverMatch() {
        db.execute("create table a (k INT)");
        db.execute("create table b (k INT)");
        db.execute("insert into a values (1), (NULL)");
        db.execute("insert into b values (1), (NULL)");
        assertEquals(table("1"), rows(db, "select count(*) from a join b on a.k = b.k"));
    }

    @Test
    void ambiguousAndDuplicateNamesAreRejected() {
        assertThrows(SqlException.class,
                () -> rows(db, "select sid from students s join enrollments e on s.sid = e.sid"));
        assertThrows(SqlException.class,
                () -> rows(db, "select * from students join students on students.sid = students.sid"));
        assertThrows(SqlException.class, () -> rows(db, "select nope from students"));
        assertThrows(SqlException.class, () -> rows(db, "select x.* from students"));
        assertThrows(CatalogException.class, () -> rows(db, "select * from missing"));
    }

    @Test
    void qualifiedStarSelectsOnlyOneTable() {
        var result = db.query("select e.* from students s join enrollments e on s.sid = e.sid", QueryOptions.unlimited());
        assertEquals(List.of("sid", "cid", "grade"), result.columns());
    }

    @Test
    void arithmeticAndFunctions() {
        assertEquals(table("2,3,3.5,1"), rows(db, "select sid + 1, 7 / 2, 7 / 2.0, 7 % 2 from students where sid = 1"));
        assertEquals(table("ADA,3,3.14,CS,3"),
                rows(db, "select upper(name), length(name), round(3.14159, 2), coalesce(major, 'none'), abs(-3) from students where sid = 1"));
        assertEquals(table("none"), rows(db, "select coalesce(major, 'none') from students where sid = 6"));
        assertEquals(table("3.8"), rows(db, "select round(gpa, 1) from students where sid = 1"));
    }

    @Test
    void arithmeticErrorsSurfaceAsSqlErrors() {
        assertThrows(SqlException.class, () -> rows(db, "select 1 / 0 from students"));
        assertThrows(SqlException.class, () -> rows(db, "select 9223372036854775807 + 1 from students"));
        assertThrows(SqlException.class, () -> rows(db, "select name + 1 from students"));
        assertThrows(SqlException.class, () -> rows(db, "select * from students where name = 1"));
        assertThrows(SqlException.class, () -> rows(db, "select * from students where gpa"));
        assertThrows(SqlException.class, () -> rows(db, "select * from students where 1 and gpa > 1"));
    }

    @Test
    void explainDescribesThePlan() {
        var plan = TestDatabases.render(db.query("""
                explain select major, count(*) from students where gpa > 3.0
                group by major order by major limit 2""", QueryOptions.unlimited()));
        String text = plan.stream().map(l -> l.get(0)).reduce("", (a, b) -> a + b + "\n");
        for (String expected : List.of("Limit 2", "Sort", "Project", "Aggregate", "Filter (gpa > 3.0)", "Scan students")) {
            assertTrue(text.contains(expected), expected + " missing in:\n" + text);
        }
    }

    @Test
    void columnNamesAreInferredForExpressions() {
        var result = db.query("select count(*), upper(name) n, gpa * 2 from students where sid = 1 group by name, gpa",
                QueryOptions.unlimited());
        assertEquals(List.of("COUNT(*)", "n", "(gpa * 2)"), result.columns());
    }
}
