package com.example.devicesync.feature.add_device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualConnectionValidatorTest {
    @Test
    fun validate_returnsEmptyIpErrorWhenIpIsBlank() {
        val result = ManualConnectionValidator.validate("", "53321")

        assertEquals(ManualConnectionError.EMPTY_IP, result.ipError)
        assertNull(result.portError)
    }

    @Test
    fun validate_returnsPortNumberErrorWhenPortIsNotNumber() {
        val result = ManualConnectionValidator.validate("192.168.1.10", "abc")

        assertNull(result.ipError)
        assertEquals(ManualConnectionError.PORT_NOT_NUMBER, result.portError)
    }

    @Test
    fun validate_returnsPortRangeErrorWhenPortIsOutOfRange() {
        val result = ManualConnectionValidator.validate("192.168.1.10", "70000")

        assertNull(result.ipError)
        assertEquals(ManualConnectionError.PORT_OUT_OF_RANGE, result.portError)
    }

    @Test
    fun validate_returnsValidResultWhenIpAndPortAreValid() {
        val result = ManualConnectionValidator.validate("192.168.1.10", "53321")

        assertTrue(result.isValid)
        assertNull(result.ipError)
        assertNull(result.portError)
    }
}
