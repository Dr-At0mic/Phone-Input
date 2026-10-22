package app.phoneinput.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingTest {
    @Test
    fun roundTripWithSpecialCharacters() {
        val info = PairingInfo("Anandhu’s PC & Studio #1", listOf("192.168.1.100", "10.0.0.7"), 5823, HostOs.Windows)
        val uri = PairingUri.build(info)
        assertEquals(true, uri.startsWith("phoneinput://connect?"))
        assertEquals(info, PairingUri.parse(uri))
    }

    @Test
    fun rejectsForeignOrBrokenCodes() {
        assertNull(PairingUri.parse("https://example.com"))
        assertNull(PairingUri.parse("phoneinput://connect?n=x"))
        assertNull(PairingUri.parse("phoneinput://connect?h=%3Cscript%3E"))
    }

    @Test
    fun defaultsForMissingFields() {
        val info = PairingUri.parse("phoneinput://connect?h=192.168.1.5")!!
        assertEquals("192.168.1.5", info.name)
        assertEquals(PhoneInputProtocol.AUDIO_PORT, info.port)
        assertEquals(HostOs.Unknown, info.os)
    }

    @Test
    fun framingPrefixesLength() {
        assertContentEquals(byteArrayOf(0, 3, 1, 2, 3), StreamFraming.frame(byteArrayOf(1, 2, 3)))
        assertEquals(974, StreamFraming.frame(ByteArray(972)).size)
    }
}
