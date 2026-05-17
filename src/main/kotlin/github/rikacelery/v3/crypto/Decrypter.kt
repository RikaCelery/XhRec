package github.rikacelery.v3.crypto

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object Decrypter {
    private val hashCache = ConcurrentHashMap<String, ByteArray>()

    suspend fun decode(encryptedB64: String, key: String): String {
        val hash = hashCache.getOrPut(key) {
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        }
        val encrypted = Base64.getDecoder().decode(encryptedB64)
        val decrypted = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            decrypted[i] = (encrypted[i].toInt() xor (hash[i % hash.size].toInt() and 0xFF)).toByte()
        }
        return String(decrypted, Charsets.UTF_8)
    }
}
