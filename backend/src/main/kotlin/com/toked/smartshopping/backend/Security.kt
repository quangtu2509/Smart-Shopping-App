package com.toked.smartshopping.backend

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class Security {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    fun token(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun passwordHash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = derive(password, salt)
        return "\$argon2id\$v=19\$m=19456,t=2,p=1\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id" || parts[3] != "m=19456,t=2,p=1") return false
        val decoder = Base64.getUrlDecoder()
        val actual = derive(password, decoder.decode(parts[4]))
        return MessageDigest.isEqual(actual, decoder.decode(parts[5]))
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
            .withMemoryAsKB(19456).withIterations(2).withParallelism(1).build()
        val chars = password.toCharArray()
        return try {
            ByteArray(32).also { output -> Argon2BytesGenerator().apply { init(parameters) }.generateBytes(chars, output) }
        } finally { chars.fill('\u0000') }
    }
}

data class AuthPolicy(
    val accessSeconds: Long = 900, val sessionSeconds: Long = 2592000,
    val rateLimit: Int = 20, val rateWindowSeconds: Long = 60,
    val lockThreshold: Int = 10, val lockSeconds: Long = 900,
) {
    init {
        require(accessSeconds > 0 && sessionSeconds >= accessSeconds)
        require(rateLimit > 0 && rateWindowSeconds > 0 && lockThreshold > 0 && lockSeconds > 0)
    }
}
