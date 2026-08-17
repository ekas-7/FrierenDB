package dev.frierendb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildEnvironmentTest {

    @Test
    void runsOnJava21() {
        assertEquals(21, Runtime.version().feature());
    }
}
