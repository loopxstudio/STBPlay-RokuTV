package com.stbplay.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted, versioned PIN verifier; no plaintext PIN is persisted. */
object PinHash {
    private const val ITERATIONS = 150_000
    fun valid(pin: String) = pin.length in 4..6 && pin.all { it in '0'..'9' }
    fun create(pin: String, algorithm: String = "PBKDF2WithHmacSHA256"): String {
        require(valid(pin))
        require(algorithm in listOf("PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1"))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS, algorithm)
        return "1|$algorithm|$ITERATIONS|${hex(salt)}|${hex(hash)}"
    }
    fun verify(pin: String, record: String): Boolean = runCatching {
        if (!valid(pin)) return false
        val parts = record.split('|')
        if (parts.size != 5 || parts[0] != "1") return false
        if (parts[1] !in listOf("PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1")) return false
        val iterations = parts[2].toInt()
        if (iterations !in 100_000..500_000) return false
        val salt = unhex(parts[3]); val expected = unhex(parts[4])
        if (salt.size != 16 || expected.size != 32) return false
        MessageDigest.isEqual(expected, derive(pin, salt, iterations, parts[1]))
    }.getOrDefault(false)
    private fun derive(pin: String, salt: ByteArray, iterations: Int, algorithm: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
