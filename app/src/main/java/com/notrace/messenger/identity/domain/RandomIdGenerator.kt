package com.notrace.messenger.identity.domain

import java.security.SecureRandom

/**
 * Generates the permanent random identity ID (Phase 0 locked decision #1).
 *
 * Format: 12 decimal digits, SecureRandom-sourced, displayed grouped in
 * 4s ("1234 5678 9012") — chosen over hex/base32 because it's easy for
 * two people to read aloud or type manually to each other when adding a
 * contact in person, which the plan's "contact discovery" flow assumes
 * as the primary path in a V1 with no phone-number/central directory.
 *
 * 10^12 possible values (~40 bits of entropy) is not meant to resist
 * brute-force *guessing* of a specific stranger's ID on its own — it
 * only has to be non-sequential/non-enumerable so contacts can't be
 * trivially scanned. It is not a secret and is not treated as one
 * (unlike the DB passphrase or identity keys); anyone who has it can
 * attempt to add this device as a contact, same as a phone number would
 * work in a phone-number-based app.
 */
object RandomIdGenerator {
    private const val DIGIT_COUNT = 12
    private val random = SecureRandom()

    fun generate(): String {
        val sb = StringBuilder(DIGIT_COUNT)
        repeat(DIGIT_COUNT) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    /** "123456789012" -> "1234 5678 9012", purely for display/QR-adjacent copy text. */
    fun format(rawId: String): String =
        rawId.chunked(4).joinToString(" ")

    /** Reverses [format] and strips any other whitespace a user might paste in. */
    fun normalize(input: String): String =
        input.filter { it.isDigit() }
}
