package github.rikacelery.v2

import java.security.MessageDigest
import java.util.*


object Decrypter {
    private val cachedHash: MutableMap<String, ByteArray> = HashMap()

    private fun computeHash(key: String): ByteArray {
        /** 计算密钥的 SHA-256 哈希，缓存结果 */
        if (!cachedHash.containsKey(key)) {
            val digest = MessageDigest.getInstance("SHA-256")
            cachedHash[key] = digest.digest(key.toByteArray(Charsets.UTF_8))
        }
        return cachedHash[key]!!
    }

    fun decode(encryptedB64: String, key: String): String {
        /**
         * 解密函数
         * @param encryptedB64 Base64 编码的密文
         * @param key 解密密钥
         * @return 解密后的明文字符串
         */
        // 1. 计算密钥的 SHA-256 哈希（字节数组）
        val hashBytes = computeHash(key)
        val hashLen = hashBytes.size

        // 2. Base64 解码密文
        val encryptedData = Base64.getDecoder().decode(encryptedB64)

        // 3. 异或解密
        val decryptedBytes = ByteArray(encryptedData.size)
        for (i in encryptedData.indices) {
            val cipherByte = encryptedData[i].toInt() and 0xFF  // 转为无符号字节
            val keyByte = hashBytes[i % hashLen].toInt() and 0xFF
            decryptedBytes[i] = (cipherByte xor keyByte).toByte()
        }

        // 4. UTF-8 解码为字符串
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun decodev3(encryptedB64: String, keyB64: String): String {
        /**
         * 解密函数
         * @param encryptedB64 Base64 编码的密文
         * @param key 解密密钥
         * @return 解密后的明文字符串
         */
        val key = Base64.getDecoder().decode(keyB64)
        val keyLen = key.size

        // 2. Base64 解码密文
        val encryptedData = Base64.getDecoder().decode(encryptedB64)

        // 3. 异或解密
        val decryptedBytes = ByteArray(encryptedData.size)
        for (i in encryptedData.indices) {
            val cipherByte = encryptedData[i].toInt() and 0xFF  // 转为无符号字节
            val keyByte = key[i % keyLen].toInt() and 0xFF
            decryptedBytes[i] = (cipherByte xor keyByte).toByte()
        }

        // 4. UTF-8 解码为字符串
        return String(decryptedBytes, Charsets.UTF_8)
    }
}