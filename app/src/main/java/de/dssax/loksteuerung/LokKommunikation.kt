package de.dssax.loksteuerung

import android.os.Handler
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Subsystemkomponente für die Verbindung; kennt weder Fassade noch Activity. */
class LokKommunikation(private val uiHandler: Handler) {
    private val httpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null

    var onOpen: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    val hasConnection: Boolean get() = socket != null
    val connectionToken: Any? get() = socket

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            uiHandler.post { if (socket === webSocket) onOpen?.invoke() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            uiHandler.post { if (socket === webSocket) onMessage?.invoke(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            uiHandler.post { onLog?.invoke("… Server schließt Verbindung ($code $reason)") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val detail = if (reason.isNotBlank()) ": $reason" else ""
            disconnected(webSocket, "Verbindung geschlossen (Code $code$detail)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            disconnected(webSocket, "Fehler: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun connect(url: String) {
        val request = Request.Builder().url(url).build()
        socket = httpClient.newWebSocket(request, listener)
    }

    fun send(payload: String): Boolean? = socket?.send(payload)

    fun disconnect() {
        val previous = socket
        socket = null
        previous?.close(1000, "App getrennt")
    }

    private fun disconnected(webSocket: WebSocket, reason: String) {
        uiHandler.post {
            if (socket === webSocket) {
                socket = null
                onDisconnected?.invoke(reason)
            } else onLog?.invoke("… $reason")
        }
    }

    fun close() {
        // Registrierte Rückmeldungen dürfen die zerstörte Oberfläche nicht festhalten.
        onOpen = null
        onMessage = null
        onDisconnected = null
        onLog = null
        socket?.cancel()
        socket = null
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
