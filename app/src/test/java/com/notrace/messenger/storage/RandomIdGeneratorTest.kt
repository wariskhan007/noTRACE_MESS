package com.notrace.messenger.storage

import com.notrace.messenger.identity.domain.RandomIdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomIdGeneratorTest {

    @Test
    fun generate_produces12Digits() {
        val id = RandomIdGenerator.generate()
        assertEquals(12, id.length)
        assertTrue(id.all { it.isDigit() })
    }

    @Test
    fun generate_isNotConstant() {
        val a = RandomIdGenerator.generate()
        val b = RandomIdGenerator.generate()
        assertNotEquals(a, b)
    }

    @Test
    fun format_groupsInFours() {
        assertEquals("1234 5678 9012", RandomIdGenerator.format("123456789012"))
    }

    @Test
    fun normalize_stripsWhitespaceAndNonDigits() {
        assertEquals("123456789012", RandomIdGenerator.normalize("1234 5678-9012"))
    }
}
