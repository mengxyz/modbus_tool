package dev.modbustool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValueFormulaTest {
    @Test
    fun evaluatesSensorScalingFormula() {
        assertEquals("61.3", ValueFormula.evaluate("value / 10", 613.0).getOrThrow())
        assertEquals("21.3", ValueFormula.evaluate("(x - 400) / 10", 613.0).getOrThrow())
    }

    @Test
    fun rejectsCodeAndUnknownNames() {
        assertTrue(ValueFormula.evaluate("System.exit(0)", 1.0).isFailure)
    }
}
