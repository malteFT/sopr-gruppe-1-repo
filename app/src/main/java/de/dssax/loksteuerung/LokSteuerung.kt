package de.dssax.loksteuerung

import android.os.Handler

/** Fassade: koordiniert Zustandsänderung und Versand für Bedienaktionen.
 * Alle Aufrufe und Rückmeldungen werden wie im Ausgangscode auf dem UI-Thread verarbeitet.
 */
class LokSteuerung(
    private val state: LokZustand,
    private val communication: LokKommunikation,
    private val uiHandler: Handler
) {
    var onState: ((LokAnzeige) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null
    val displayState: LokAnzeige? get() = state.snapshot()

    private var pendingSend: Runnable? = null
    private var lastSendAt = 0L

    init {
        // Die Kommunikationskomponente kennt nur die registrierte Funktion.
        communication.onMessage = { text ->
            val accepted = try {
                state.receive(text)
            } catch (_: Exception) {
                onLog?.invoke("← (kein JSON) $text")
                null
            }
            if (accepted != null) {
                onLog?.invoke("← $text")
                if (accepted) state.snapshot()?.let { onState?.invoke(it) }
            }
        }
    }

    fun setSpeed(speed: Int) {
        state.setSpeed(speed)
        onLog?.invoke("Geschwindigkeit → $speed")
        scheduleSend(SEND_DEBOUNCE_MS)
    }

    fun finishSpeedChange(speed: Int) {
        state.setSpeed(speed)
        scheduleSend(0L)
    }

    fun stop(): Boolean {
        if (state.snapshot() == null) {
            onLog?.invoke("STOP: noch kein Zustand geladen – nichts gesendet")
            return false
        }
        state.setSpeed(0)
        onLog?.invoke("NOT-STOP → Geschwindigkeit auf 0")
        scheduleSend(0L)
        return true
    }

    fun revokeDrivingRelease(): Boolean {
        if ((state.snapshot()?.speed ?: 0) <= 0) return false
        state.setSpeed(0)
        onLog?.invoke("Freigabe entzogen → Geschwindigkeit auf 0")
        scheduleSend(0L)
        return true
    }

    fun setDirection(direction: Int) {
        state.setDirection(direction)
        onLog?.invoke("Richtung → ${if (direction == 1) "rückwärts" else "vorwärts"}")
        scheduleSend(0L)
    }

    fun setFeature(key: String, enabled: Boolean, label: String) {
        if (!state.setFeature(key, enabled)) return
        onLog?.invoke("$label → ${if (enabled) "ein" else "aus"}")
        scheduleSend(0L)
    }

    fun beforeDisconnect() {
        cancelPendingSend()
        // Wie vorher: die alte Lok stoppen, bevor eine andere ausgewählt wird.
        if ((state.snapshot()?.speed ?: 0) > 0) {
            state.setSpeed(0)
            doSend()
        }
        state.clear()
    }

    fun connectionLost() {
        cancelPendingSend()
        state.clear()
    }

    private fun scheduleSend(delayMs: Long) {
        cancelPendingSend()
        val remainingGap = lastSendAt + MIN_SEND_GAP_MS - System.currentTimeMillis()
        val delay = maxOf(delayMs, if (remainingGap > 0) remainingGap else 0L)
        val runnable = Runnable {
            pendingSend = null
            doSend()
        }
        pendingSend = runnable
        uiHandler.postDelayed(runnable, delay)
    }

    private fun doSend() {
        if (!communication.hasConnection) return
        val payload = state.payload() ?: return
        lastSendAt = System.currentTimeMillis()
        val ok = communication.send(payload) ?: return
        onLog?.invoke((if (ok) "→ " else "→ FEHLGESCHLAGEN: ") + payload)
    }

    private fun cancelPendingSend() {
        pendingSend?.let { uiHandler.removeCallbacks(it) }
        pendingSend = null
    }

    fun close() {
        cancelPendingSend()
        communication.onMessage = null
        onState = null
        onLog = null
        state.clear()
    }

    companion object {
        private const val SEND_DEBOUNCE_MS = 700L
        private const val MIN_SEND_GAP_MS = 400L
    }
}
