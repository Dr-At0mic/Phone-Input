package app.phoneinput.protocol

/** What the desktop's QR code tells the phone: where to connect. */
data class PairingInfo(
    val name: String,
    val addresses: List<String>,
    val port: Int = PhoneInputProtocol.AUDIO_PORT,
    val os: HostOs = HostOs.Unknown,
)

/**
 * `phoneinput://connect?n=<name>&h=<ip>[,<ip>…]&p=<port>&os=<code>`
 *
 * A URI rather than bare data, so the phone's own camera app can also open PhoneInput from it.
 */
object PairingUri {
    const val SCHEME = "phoneinput"
    private const val PREFIX = "$SCHEME://connect?"

    fun build(info: PairingInfo): String = buildString {
        append(PREFIX)
        append("n=").append(encode(info.name))
        append("&h=").append(info.addresses.joinToString(",") { encode(it) })
        append("&p=").append(info.port)
        append("&os=").append(info.os.code)
    }

    fun parse(text: String): PairingInfo? {
        val raw = text.trim()
        if (!raw.startsWith(PREFIX, ignoreCase = true)) return null
        val params = raw.substring(PREFIX.length).split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()
        val addresses = params["h"].orEmpty().split(',').map { decode(it).trim() }.filter { isAddress(it) }
        if (addresses.isEmpty()) return null
        val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: PhoneInputProtocol.AUDIO_PORT
        val name = params["n"]?.let(::decode)?.take(PhoneInputProtocol.MAX_NAME_CHARS)?.ifBlank { null } ?: addresses.first()
        return PairingInfo(name, addresses, port, HostOs.of(params["os"]?.toIntOrNull() ?: 0))
    }

    /** IPv4 or a plain host name; rejects anything with characters that don't belong in one. */
    private fun isAddress(s: String): Boolean =
        s.isNotEmpty() && s.length <= 253 && s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }

    private fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 0x80 || ch in "-._~") append(ch) else append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }

    private fun decode(s: String): String {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out += v.toByte()
                    i += 3
                    continue
                }
            }
            if (c == '+') out += ' '.code.toByte() else c.toString().encodeToByteArray().forEach { out += it }
            i++
        }
        return out.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}

/** <u16 big-endian length><packet> framing for the TCP (USB) transport. */
object StreamFraming {
    fun frame(packet: ByteArray): ByteArray {
        require(packet.size <= 0xFFFF)
        val out = ByteArray(packet.size + 2)
        out[0] = (packet.size ushr 8).toByte()
        out[1] = packet.size.toByte()
        packet.copyInto(out, 2)
        return out
    }
}
