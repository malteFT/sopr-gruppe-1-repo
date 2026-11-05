package de.dssax.loksteuerung

import android.content.Intent
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import de.dssax.loksteuerung.nfc.NfcText
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import de.dssax.loksteuerung.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var state: JSONObject? = null
    private var nfcAdapter: NfcAdapter? = null
    private var readingNfc = false
    private var connectedUrl: String? = null
    private var lastNfcText: String? = null
    private var lastNfcAt = 0L
    private var updatingUi = false
    private var lastSendAt = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingSend: Runnable? = null
    private val logLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            runOnUiThread {
                if (this@MainActivity.webSocket !== webSocket) return@runOnUiThread
                log("→ Verbunden")
                binding.statusText.text = getString(R.string.status_waiting)
                refreshControls()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runOnUiThread { if (this@MainActivity.webSocket === webSocket) onServerMessage(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runOnUiThread { log("… Server schließt Verbindung ($code $reason)") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val detail = if (reason.isNotBlank()) ": $reason" else ""
            runOnUiThread { onDisconnected(webSocket, "Verbindung geschlossen (Code $code$detail)") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            runOnUiThread {
                onDisconnected(webSocket, "Fehler: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.urlEdit.setText(R.string.default_url)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        binding.nfcSettingsButton.isEnabled = nfcAdapter != null
        binding.nfcSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }

        binding.speedSlider.apply {
            valueFrom = SPEED_MIN.toFloat()
            valueTo = SPEED_MAX.toFloat()
            stepSize = 1f
            value = 0f
        }

        binding.connectButton.setOnClickListener {
            if (webSocket != null) disconnect() else connect()
        }

        binding.freigabeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                val s = state
                if (s != null && s.optInt("speed", 0) > 0) {
                    s.put("speed", 0)
                    setSliderValue(0)
                    log("Freigabe entzogen → Geschwindigkeit auf 0")
                    scheduleSend(0L)
                }
            }
            refreshControls()
        }

        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            val speed = value.toInt()
            binding.speedLabel.text = getString(R.string.speed_value, speed)
            if (!fromUser || updatingUi) return@addOnChangeListener
            state?.put("speed", speed)
            log("Geschwindigkeit → $speed")
            scheduleSend(SEND_DEBOUNCE_MS)
        }

        binding.speedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                if (updatingUi) return
                state?.put("speed", slider.value.toInt())
                scheduleSend(0L)
            }
        })

        binding.directionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingUi) return@addOnButtonCheckedListener
            if (!binding.freigabeSwitch.isChecked) return@addOnButtonCheckedListener
            val direction = if (checkedId == R.id.btnBackward) DIRECTION_BACKWARD else DIRECTION_FORWARD
            state?.put("direction", direction)
            log("Richtung → ${if (direction == DIRECTION_BACKWARD) "rückwärts" else "vorwärts"}")
            scheduleSend(0L)
        }

        bindFeatureSwitch(binding.headLightSwitch, "headLight", "Fahrlicht")
        bindFeatureSwitch(binding.cabinLightSwitch, "cabineLighting", "Kabinenlicht")
        bindFeatureSwitch(binding.hornSwitch, "hornSound", "Hupe")
        bindFeatureSwitch(binding.drivingSwitch, "drivingSound", "Fahrgeräusch")
        bindFeatureSwitch(binding.smokingSwitch, "smoking", "Rauch")

        binding.stopButton.setOnClickListener {
            val s = state
            if (s == null) {
                log("STOP: noch kein Zustand geladen – nichts gesendet")
                return@setOnClickListener
            }
            s.put("speed", 0)
            setSliderValue(0)
            log("NOT-STOP → Geschwindigkeit auf 0")
            scheduleSend(0L)
        }

        binding.speedLabel.text = getString(R.string.speed_zero)
        refreshControls()
        log("Bereit. Standard-URL: ${getString(R.string.default_url)}")
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        when {
            adapter == null -> binding.nfcStatusText.setText(R.string.nfc_unavailable)
            !adapter.isEnabled -> binding.nfcStatusText.setText(R.string.nfc_disabled)
            else -> {
                // Keep Android's NDEF check enabled: we need the stored text, not the tag UID.
                try {
                    adapter.enableReaderMode(this, this,
                        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
                    readingNfc = true
                    binding.nfcStatusText.setText(R.string.nfc_ready)
                } catch (_: IllegalStateException) {
                    binding.nfcStatusText.setText(R.string.nfc_disabled)
                }
            }
        }
    }

    override fun onPause() {
        readingNfc = false
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        // ReaderCallback runs off the UI thread. Read and close the tag on that thread.
        val result = runCatching {
            val ndef = Ndef.get(tag) ?: return@runCatching emptyList<String>()
            try {
                val message = ndef.cachedNdefMessage ?: run {
                    ndef.connect()
                    ndef.ndefMessage
                }
                message?.records.orEmpty().mapNotNull { record ->
                    if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT))
                        NfcText.decode(record.payload) else null
                }
            } finally {
                runCatching { ndef.close() }
            }
        }
        runOnUiThread {
            if (!readingNfc) return@runOnUiThread
            if (result.isFailure) {
                binding.nfcStatusText.setText(R.string.nfc_read_error)
                log(getString(R.string.nfc_read_error))
            } else handleNfcTexts(result.getOrThrow())
        }
    }

    @VisibleForTesting
    internal fun handleNfcTexts(texts: List<String>, connectionUrl: (String) -> String = { it }) {
        val names = texts.map(NfcText::key).filter { it.isNotEmpty() }.distinct()
        if (names.size != 1) {
            binding.nfcStatusText.setText(if (names.isEmpty()) R.string.nfc_no_text else R.string.nfc_ambiguous)
            return
        }
        val name = names.single()
        val targetUrl = nfcUrlForName(name)
        if (targetUrl == null) {
            binding.nfcStatusText.text = getString(R.string.nfc_unknown, name)
            log(getString(R.string.nfc_unknown, name))
            return
        }
        val url = connectionUrl(targetUrl)
        val now = SystemClock.elapsedRealtime()
        if (name == lastNfcText && now - lastNfcAt < 2000) return
        lastNfcText = name
        lastNfcAt = now
        binding.nfcStatusText.text = getString(R.string.nfc_selected, name, url)
        if (connectedUrl == url && webSocket != null) return
        disconnect()
        binding.urlEdit.setText(url)
        log("NFC-Text '$name' → $url")
        connect()
    }

    private fun nfcUrlForName(name: String): String? = when (name) {
        "gisela" -> getString(R.string.gisela_url)
        "blauwal", "blauwaal" -> getString(R.string.blauwal_url)
        "80 012", "ise" -> getString(R.string.default_url)
        else -> null
    }

    override fun onDestroy() {
        pendingSend?.let { uiHandler.removeCallbacks(it) }
        pendingSend = null
        webSocket?.cancel()
        webSocket = null
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        super.onDestroy()
    }

    private fun bindFeatureSwitch(sw: MaterialSwitch, key: String, label: String) {
        sw.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            val s = state
            if (s == null || !hasFeature(s, key)) return@setOnCheckedChangeListener
            s.put(key, checked)
            log("$label → ${if (checked) "ein" else "aus"}")
            scheduleSend(0L)
        }
    }

    private fun connect() {
        val url = binding.urlEdit.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            log("Bitte eine ws://- oder wss://-URL angeben")
            return
        }
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            log("Ungültige URL: $url")
            return
        }
        binding.statusText.text = getString(R.string.status_connecting)
        binding.connectButton.isEnabled = false
        log("→ Verbinde mit $url …")
        connectedUrl = url
        webSocket = httpClient.newWebSocket(request, listener)
        val connectingSocket = webSocket
        uiHandler.postDelayed({
            if (webSocket === connectingSocket) {
                binding.connectButton.isEnabled = true
                refreshControls()
            }
        }, 1500L)
    }

    private fun disconnect() {
        pendingSend?.let { uiHandler.removeCallbacks(it) }
        pendingSend = null
        // Cancel delayed commands and stop the old locomotive before a tag can select another.
        state?.takeIf { it.optInt("speed", 0) > 0 }?.let {
            it.put("speed", 0)
            doSend()
        }
        webSocket?.close(1000, "App getrennt")
        webSocket = null
        state = null
        connectedUrl = null
        binding.connectButton.isEnabled = true
        binding.freigabeSwitch.isChecked = false
        binding.statusText.text = getString(R.string.status_disconnected)
        log("→ Getrennt")
        refreshControls()
    }

    private fun onDisconnected(ws: WebSocket, reason: String) {
        log("… $reason")
        if (webSocket === ws) {
            webSocket = null
            state = null
            connectedUrl = null
            pendingSend?.let { uiHandler.removeCallbacks(it) }
            pendingSend = null
            binding.connectButton.isEnabled = true
            binding.freigabeSwitch.isChecked = false
            binding.statusText.text = getString(R.string.status_disconnected)
        }
        refreshControls()
    }

    private fun onServerMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            log("← (kein JSON) $text")
            return
        }
        if (!json.has("id")) {
            log("← $text")
            return
        }
        state = json
        log("← $text")
        val label = json.optString("name").ifBlank { json.optString("id") }
        binding.statusText.text = getString(R.string.status_connected, label)
        updateUiFromState()
        refreshControls()
    }

    private fun updateUiFromState() {
        val s = state ?: return
        updatingUi = true
        try {
            binding.idText.text = getString(R.string.id_value, s.optString("id", "–"))
            binding.infoText.text = getString(
                R.string.info_value,
                s.optString("name", "?"),
                s.optString("number", "?"),
                s.optInt("position", 0)
            )
            val speed = s.optInt("speed", 0)
            setSliderValue(speed)
            binding.speedLabel.text = getString(R.string.speed_value, speed)
            binding.directionToggle.check(
                if (s.optInt("direction", 0) == DIRECTION_BACKWARD) R.id.btnBackward else R.id.btnForward
            )
            updateFeatureSwitch(binding.headLightSwitch, s, "headLight")
            updateFeatureSwitch(binding.cabinLightSwitch, s, "cabineLighting")
            updateFeatureSwitch(binding.hornSwitch, s, "hornSound")
            updateFeatureSwitch(binding.drivingSwitch, s, "drivingSound")
            updateFeatureSwitch(binding.smokingSwitch, s, "smoking")
        } finally {
            updatingUi = false
        }
    }

    private fun updateFeatureSwitch(sw: MaterialSwitch, s: JSONObject, key: String) {
        sw.isChecked = hasFeature(s, key) && s.optBoolean(key, false)
    }

    private fun setSliderValue(speed: Int) {
        val previous = updatingUi
        updatingUi = true
        binding.speedSlider.value = speed.coerceIn(SPEED_MIN, SPEED_MAX).toFloat()
        updatingUi = previous
    }

    private fun refreshControls() {
        val s = state
        val ready = webSocket != null && s != null
        binding.connectButton.text =
            getString(if (webSocket != null) R.string.disconnect else R.string.connect)

        binding.freigabeSwitch.isEnabled = ready
        val motionAllowed = ready && binding.freigabeSwitch.isChecked
        val stopped = (s?.optInt("speed", 0) ?: 0) == 0
        val directionAllowed = motionAllowed && stopped

        binding.speedSlider.isEnabled = motionAllowed
        binding.btnForward.isEnabled = directionAllowed
        binding.btnBackward.isEnabled = directionAllowed
        binding.stopButton.isEnabled = ready

        binding.headLightSwitch.isEnabled = ready && hasFeature(s, "headLight")
        binding.cabinLightSwitch.isEnabled = ready && hasFeature(s, "cabineLighting")
        binding.hornSwitch.isEnabled = ready && hasFeature(s, "hornSound")
        binding.drivingSwitch.isEnabled = ready && hasFeature(s, "drivingSound")
        binding.smokingSwitch.isEnabled = ready && hasFeature(s, "smoking")
    }

    private fun hasFeature(s: JSONObject?, key: String): Boolean =
        s != null && s.has(key) && !s.isNull(key)

    private fun scheduleSend(delayMs: Long) {
        pendingSend?.let { uiHandler.removeCallbacks(it) }
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
        val ws = webSocket ?: return
        val s = state ?: return
        val payload = s.toString()
        lastSendAt = System.currentTimeMillis()
        val ok = ws.send(payload)
        log((if (ok) "→ " else "→ FEHLGESCHLAGEN: ") + payload)
    }

    private fun log(line: String) {
        logLines.addLast("[${timeFormat.format(Date())}] $line")
        while (logLines.size > 300) {
            logLines.removeFirst()
        }
        binding.logText.text = logLines.joinToString("\n")
        binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    companion object {
        private const val SPEED_MIN = 0
        private const val SPEED_MAX = 255
        private const val DIRECTION_FORWARD = 0
        private const val DIRECTION_BACKWARD = 1
        private const val SEND_DEBOUNCE_MS = 700L
        private const val MIN_SEND_GAP_MS = 400L
    }
}
