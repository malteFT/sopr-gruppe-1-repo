package de.dssax.loksteuerung.nfc

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Decodes an NFC Forum RTD Text payload, excluding its status byte and language code. */
object NfcText {
    fun decode(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xff
        if (status and 0x40 != 0) return null
        val textOffset = 1 + (status and 0x3f)
        if (textOffset >= payload.size) return null
        val charset = if (status and 0x80 == 0) StandardCharsets.UTF_8 else StandardCharsets.UTF_16
        return runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload, textOffset, payload.size - textOffset))
                .toString().trim().removePrefix("\uFEFF").trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private val whitespace = Regex("[\\s\\p{Zs}]+")

    fun key(text: String): String = text.replace(whitespace, " ").trim().lowercase(Locale.ROOT)
}
