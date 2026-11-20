package com.andychen.magpie.shared.usage

/** The destination to which a saved gateway key is bound. */
class UsageOrigin private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is UsageOrigin && value == other.value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        private val address = Regex(
            """(?i)https://(\[[0-9a-f:.]+]|[a-z0-9.-]+)(?::([0-9]{1,5}))?(?:/v1)?/?"""
        )
        private val dnsLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")

        fun parse(raw: String): UsageOrigin? {
            val match = address.matchEntire(raw.trim()) ?: return null
            val host = match.groupValues[1].lowercase()
            val canonicalHost = if (host.startsWith('[')) {
                val ip = ipv6(host.substring(1, host.length - 1)) ?: return null
                "[$ip]"
            } else {
                if (host.length > 253 || !host.removeSuffix(".").split('.').all(dnsLabel::matches)) {
                    return null
                }
                host
            }
            val rawPort = match.groupValues[2]
            val port = if (rawPort.isEmpty()) 443 else rawPort.toInt()
            if (port !in 1..65535) return null
            return UsageOrigin("https://$canonicalHost" + if (port == 443) "" else ":$port")
        }

        private fun ipv6(address: String): String? {
            val parts = address.split("::")
            if (parts.size > 2) return null
            val left = parts.first().takeIf(String::isNotEmpty)?.split(':').orEmpty()
            val right = if (parts.size == 2) {
                parts.last().takeIf(String::isNotEmpty)?.split(':').orEmpty()
            } else emptyList()
            val groups = left + right
            val words = groups.flatMapIndexed { index, group ->
                if ('.' in group && index == groups.lastIndex && address.endsWith(group)) {
                    val bytes = group.split('.').map { it.toIntOrNull() ?: return null }
                    if (bytes.size != 4 || bytes.any { it !in 0..255 }) return null
                    listOf((bytes[0] shl 8) + bytes[1], (bytes[2] shl 8) + bytes[3])
                } else {
                    if (group.length !in 1..4) return null
                    listOf(group.toIntOrNull(16) ?: return null)
                }
            }
            val missing = 8 - words.size
            if (parts.size == 1 && missing != 0 || parts.size == 2 && missing < 1) return null
            val expanded = if (parts.size == 1) words else
                words.take(left.size) + List(missing) { 0 } + words.drop(left.size)
            return expanded.joinToString(":") { it.toString(16) }
        }
    }
}
