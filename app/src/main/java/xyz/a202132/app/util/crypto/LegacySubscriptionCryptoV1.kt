package xyz.a202132.app.util.crypto

import android.util.Base64

object LegacySubscriptionCryptoV1 {
    private const val PRIVATE_ALPHABET =
        "iWm124UFyO7KG9NBPhCj5kS8IYbHRvtselQcTA3ugVEo6and-Lprq_DJMw0ZzfxX"
    private const val STANDARD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeOrNull(value: String, clientIp: String?): String? = runCatching {
        val shift = shiftFromClientIp(clientIp ?: return null)
        val standardBase64 = buildString(value.length) {
            value.trim().forEach { character ->
                if (character == '=') {
                    append(character)
                } else {
                    val shiftedIndex = PRIVATE_ALPHABET.indexOf(character)
                    require(shiftedIndex >= 0)
                    val originalIndex = (shiftedIndex - shift + 64) % 64
                    append(STANDARD_ALPHABET[originalIndex])
                }
            }
        }
        String(Base64.decode(standardBase64, Base64.DEFAULT), Charsets.UTF_8)
            .takeIf { it.contains("://") }
    }.getOrNull()

    private fun shiftFromClientIp(rawValue: String): Int {
        var address = rawValue.substringBefore(',').trim()
        if (address.startsWith('[') && address.contains(']')) {
            address = address.substring(1, address.indexOf(']'))
        }
        val ipv4Address = if (address.contains(':') && address.contains('.')) {
            address.substringAfterLast(':')
        } else {
            address
        }
        val ipv4 = ipv4Address.split('.').takeIf { it.size == 4 }
        if (ipv4 != null) {
            val octets = ipv4.map { it.toIntOrNull() ?: throw IllegalArgumentException("Invalid IP") }
            require(octets.all { it in 0..255 })
            return octets.last() % 64
        }
        if (address.contains(':')) {
            val lastGroup = address.substringAfterLast(':')
            return if (lastGroup.isEmpty()) 0 else lastGroup.toInt(16) % 64
        }
        throw IllegalArgumentException("Invalid IP")
    }
}
