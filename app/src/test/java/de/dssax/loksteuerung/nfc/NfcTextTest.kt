package de.dssax.loksteuerung.nfc

import org.junit.Assert.*
import org.junit.Test

class NfcTextTest {
    private fun utf8(text: String) = byteArrayOf(2) + "de".toByteArray() + text.toByteArray(Charsets.UTF_8)

    @Test fun readsNfcToolsTextWithoutLanguagePrefix() {
        assertEquals("gisela", NfcText.decode(utf8("gisela")))
    }
    @Test fun readsUtf16AndDifferentLengthLanguageCode() {
        val payload = byteArrayOf(0x85.toByte()) + "de-DE".toByteArray() + "Gisela".toByteArray(Charsets.UTF_16)
        assertEquals("Gisela", NfcText.decode(payload))
    }
    @Test fun handlesUnicodeAndWhitespace() {
        assertEquals("Giséla", NfcText.decode(utf8("  Giséla\n")))
        assertEquals("gisela", NfcText.key(" GISELA "))
    }
    @Test fun rejectsEmptyAndTruncatedPayloads() {
        assertNull(NfcText.decode(byteArrayOf()))
        assertNull(NfcText.decode(byteArrayOf(10, 65)))
        assertNull(NfcText.decode(byteArrayOf(2) + "de".toByteArray()))
        assertNull(NfcText.decode(utf8("   ")))
    }
    @Test fun rejectsMalformedEncodingAndReservedStatusBit() {
        assertNull(NfcText.decode(byteArrayOf(0, 0xc3.toByte())))
        assertNull(NfcText.decode(byteArrayOf(0x40, 65)))
    }
}
