package dev.frierendb.engine.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.sql.Expr.BinaryOp;
import dev.frierendb.engine.sql.SqlException;
import dev.frierendb.engine.types.Value;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SqlValuesTest {

    @Test
    void likeSupportsPercentAndUnderscore() {
        assertTrue(SqlValues.like("abc", "a%c"));
        assertTrue(SqlValues.like("ac", "a%c"));
        assertTrue(SqlValues.like("abc", "a_c"));
        assertFalse(SqlValues.like("abc", "a_"));
        assertTrue(SqlValues.like("", "%"));
        assertFalse(SqlValues.like("", "_"));
        assertTrue(SqlValues.like("anything", "%"));
        assertTrue(SqlValues.like("50% off", "50% off"), "there is no escape syntax; % in a pattern is always a wildcard");
        assertFalse(SqlValues.like("abc", "ABC"));
        assertTrue(SqlValues.like("abcabc", "%bc"));
        assertFalse(SqlValues.like("abcabd", "%bc"));
    }

    @Test
    void likeDoesNotBlowUpOnPathologicalPatterns() {
        String text = "a".repeat(20_000) + "b";
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertFalse(SqlValues.like(text, "%a%a%a%a%a%a%a%a%c")));
    }

    @Test
    void integerArithmeticStaysIntegral() {
        assertEquals(Value.of(3L), SqlValues.arithmetic(BinaryOp.DIVIDE, Value.of(7L), Value.of(2L)));
        assertEquals(Value.of(-3L), SqlValues.arithmetic(BinaryOp.DIVIDE, Value.of(-7L), Value.of(2L)));
        assertEquals(Value.of(1L), SqlValues.arithmetic(BinaryOp.MODULO, Value.of(7L), Value.of(2L)));
    }

    @Test
    void mixedArithmeticPromotesToDouble() {
        assertEquals(Value.of(3.5), SqlValues.arithmetic(BinaryOp.DIVIDE, Value.of(7L), Value.of(2.0)));
    }

    @Test
    void nullPropagatesThroughArithmeticAndComparison() {
        assertEquals(Value.NULL, SqlValues.arithmetic(BinaryOp.ADD, Value.NULL, Value.of(1L)));
        assertEquals(Value.NULL, SqlValues.compare(BinaryOp.EQ, Value.NULL, Value.NULL));
    }

    @Test
    void overflowAndDivisionByZeroAreErrors() {
        assertThrows(SqlException.class,
                () -> SqlValues.arithmetic(BinaryOp.ADD, Value.of(Long.MAX_VALUE), Value.of(1L)));
        assertThrows(SqlException.class,
                () -> SqlValues.arithmetic(BinaryOp.MULTIPLY, Value.of(Long.MAX_VALUE), Value.of(2L)));
        assertThrows(SqlException.class,
                () -> SqlValues.arithmetic(BinaryOp.DIVIDE, Value.of(Long.MIN_VALUE), Value.of(-1L)));
        assertThrows(SqlException.class, () -> SqlValues.arithmetic(BinaryOp.DIVIDE, Value.of(1L), Value.of(0L)));
        assertThrows(SqlException.class, () -> SqlValues.arithmetic(BinaryOp.DIVIDE, Value.of(1.0), Value.of(0.0)));
        assertThrows(SqlException.class, () -> SqlValues.arithmetic(BinaryOp.MODULO, Value.of(1L), Value.of(0L)));
        assertThrows(SqlException.class, () -> SqlValues.negate(Value.of(Long.MIN_VALUE)));
    }

    @Test
    void moduloByMinusOneDoesNotOverflow() {
        assertEquals(Value.of(0L), SqlValues.arithmetic(BinaryOp.MODULO, Value.of(Long.MIN_VALUE), Value.of(-1L)));
    }

    @Test
    void comparingIncompatibleTypesIsASqlError() {
        assertThrows(SqlException.class, () -> SqlValues.compare(BinaryOp.EQ, Value.of("1"), Value.of(1L)));
    }

    @Test
    void arithmeticOnTextIsAnError() {
        assertThrows(SqlException.class, () -> SqlValues.arithmetic(BinaryOp.ADD, Value.of("a"), Value.of(1L)));
    }

    @Test
    void canonicalFormMakesEqualNumbersHashAlike() {
        assertEquals(SqlValues.canonical(Value.of(2.0)), SqlValues.canonical(Value.of(2L)));
        assertFalse(SqlValues.canonical(Value.of(2.5)).equals(SqlValues.canonical(Value.of(2L))));
    }
}
