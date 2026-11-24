package com.nuvio.app.features.servers.webdav

/** Tiny MD5 for PikPak captcha signing (commonMain; no JVM MessageDigest). */
internal object PikPakMd5 {
    fun hex(input: String): String = hex(input.encodeToByteArray())

    fun hex(input: ByteArray): String {
        val digest = digest(input)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private fun digest(message: ByteArray): ByteArray {
        val s = IntArray(4)
        s[0] = 0x67452301
        s[1] = 0xefcdab89.toInt()
        s[2] = 0x98badcfe.toInt()
        s[3] = 0x10325476
        val bitLen = message.size.toLong() * 8L
        val paddedLen = ((message.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLen)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLen - 8 + i] = ((bitLen ushr (8 * i)) and 0xff).toByte()
        }
        var offset = 0
        while (offset < paddedLen) {
            val w = IntArray(16)
            for (i in 0 until 16) {
                val j = offset + i * 4
                w[i] = (padded[j].toInt() and 0xff) or
                    ((padded[j + 1].toInt() and 0xff) shl 8) or
                    ((padded[j + 2].toInt() and 0xff) shl 16) or
                    ((padded[j + 3].toInt() and 0xff) shl 24)
            }
            var a = s[0]
            var b = s[1]
            var c = s[2]
            var d = s[3]
            for (i in 0 until 64) {
                val (f, g) = when {
                    i < 16 -> (b and c) or (b.inv() and d) to i
                    i < 32 -> (d and b) or (d.inv() and c) to (5 * i + 1) % 16
                    i < 48 -> (b xor c xor d) to (3 * i + 5) % 16
                    else -> (c xor (b or d.inv())) to (7 * i) % 16
                }
                val temp = d
                d = c
                c = b
                val sum = (a + f + K[i] + w[g])
                b = b + leftRotate(sum, R[i])
                a = temp
            }
            s[0] += a
            s[1] += b
            s[2] += c
            s[3] += d
            offset += 64
        }
        val out = ByteArray(16)
        for (i in 0 until 4) {
            out[i * 4] = (s[i] and 0xff).toByte()
            out[i * 4 + 1] = ((s[i] ushr 8) and 0xff).toByte()
            out[i * 4 + 2] = ((s[i] ushr 16) and 0xff).toByte()
            out[i * 4 + 3] = ((s[i] ushr 24) and 0xff).toByte()
        }
        return out
    }

    private fun leftRotate(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private val HEX = "0123456789abcdef".toCharArray()
    private val R = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )
    private val K = IntArray(64) { i ->
        (kotlin.math.floor(kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0)).toLong().toInt()
    }
}
