package com.example.casc.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.example.casc.domain.Enums.*;
import org.junit.jupiter.api.Test;

class EnumsTest {
    @Test
    void parsesExactSpellingOnly() {
        assertEquals(TargetTableType.TRANSACTION, Enums.parse(TargetTableType.class, "Transaction").orElseThrow());
        assertTrue(Enums.parse(TargetTableType.class, "transaction").isEmpty());
        assertTrue(Enums.parse(InterfaceType.class, "Sourcing").isEmpty());
    }

    @Test
    void missingBooleanDiffersFromFalse() {
        var missing = new CasCManifest.LogicalService("A", null, null, null);
        var off = new CasCManifest.LogicalService("A", Boolean.FALSE, null, null);
        assertNull(missing.isDeployedOnPrem());
        assertEquals(Boolean.FALSE, off.isDeployedOnPrem());
    }
}
