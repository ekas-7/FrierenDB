package dev.frierendb.engine.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frierendb.engine.FrierenException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValueTest {

    @Test
    void nullSortsBeforeEverything() {
        List<Value> values = new ArrayList<>(List.of(Value.of(3), Value.NULL, Value.of(-5)));
        Collections.sort(values);
        assertEquals(List.of(Value.NULL, Value.of(-5), Value.of(3)), values);
    }

    @Test
    void nullEqualsNullInOrdering() {
        assertEquals(0, Value.NULL.compareTo(Value.NULL));
    }

    @Test
    void intAndDoubleCompareNumerically() {
        assertTrue(Value.of(2).compareTo(Value.of(2.5)) < 0);
        assertTrue(Value.of(3.5).compareTo(Value.of(3)) > 0);
        assertEquals(0, Value.of(4).compareTo(Value.of(4.0)));
    }

    @Test
    void textComparesLexicographically() {
        assertTrue(Value.of("apple").compareTo(Value.of("banana")) < 0);
    }

    @Test
    void falseSortsBeforeTrue() {
        assertTrue(Value.of(false).compareTo(Value.of(true)) < 0);
    }

    @Test
    void crossTypeComparisonIsRejected() {
        FrierenException e =
                assertThrows(FrierenException.class, () -> Value.of("1").compareTo(Value.of(1)));
        assertTrue(e.getMessage().contains("Cannot compare"));
    }

    @Test
    void textFactoryRejectsJavaNull() {
        assertThrows(NullPointerException.class, () -> Value.of((String) null));
    }

    @Test
    void sqlTypeNamesAreMapped() {
        assertEquals(DataType.INT, DataType.fromSqlName("bigint").orElseThrow());
        assertEquals(DataType.TEXT, DataType.fromSqlName("VarChar").orElseThrow());
        assertTrue(DataType.fromSqlName("blob").isEmpty());
    }
}
