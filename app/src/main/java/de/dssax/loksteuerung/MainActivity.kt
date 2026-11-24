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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding

    private var nfcAdapter: NfcAdapter? = null
    private var readingNfc = false
    private var connectedUrl: String? = null
    private var lastNfcText: String? = null
    private var lastNfcAt = 0L
    private var updatingUi = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val communication = LokKommunikation(uiHandler)
    private val steuerung = LokSteuerung(LokZustand(), communication, uiHandler)

    private val logLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Rückmeldungen werden auf dem UI-Thread zugestellt.
        steuerung.onLog = ::log
        steuerung.onState = ::onServerState
        communication.onLog = ::log
        communication.onOpen = {
            log("→ Verbunden")
            binding.statusText.text = getString(R.string.status_waiting)
            refreshControls()
        }
        communication.onDisconnected = ::onDisconnected

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
            if (communication.hasConnection) disconnect() else connect()
        }

        binding.freigabeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked && steuerung.revokeDrivingRelease()) setSliderValue(0)
            refreshControls()
        }

        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            val speed = value.toInt()
            binding.speedLabel.text = getString(R.string.speed_value, speed)
            if (!fromUser || updatingUi) return@addOnChangeListener
            steuerung.setSpeed(speed)
        }

        binding.speedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                if (updatingUi) return
                steuerung.finishSpeedChange(slider.value.toInt())
            }
        })

        binding.directionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingUi) return@addOnButtonCheckedListener
            if (!binding.freigabeSwitch.isChecked) return@addOnButtonCheckedListener
            val direction = if (checkedId == R.id.btnBackward) DIRECTION_BACKWARD else DIRECTION_FORWARD
            steuerung.setDirection(direction)
        }

        bindFeatureSwitch(binding.headLightSwitch, "headLight", "Fahrlicht")
        bindFeatureSwitch(binding.cabinLightSwitch, "cabineLighting", "Kabinenlicht")
        bindFeatureSwitch(binding.hornSwitch, "hornSound", "Hupe")
        bindFeatureSwitch(binding.drivingSwitch, "drivingSound", "Fahrgeräusch")
        bindFeatureSwitch(binding.smokingSwitch, "smoking", "Rauch")

        binding.stopButton.setOnClickListener {
            if (steuerung.stop()) setSliderValue(0)
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
        if (connectedUrl == url && communication.hasConnection) return
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
        steuerung.close()
        communication.close()
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindFeatureSwitch(sw: MaterialSwitch, key: String, label: String) {
        sw.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            steuerung.setFeature(key, checked, label)
        }
    }

    private fun connect() {
        val url = binding.urlEdit.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            log("Bitte eine ws://- oder wss://-URL angeben")
            return
        }
        try {
            // Einrichtung bleibt ein direkter Zugang zur Kommunikationskomponente.
            communication.connect(url)
        } catch (e: IllegalArgumentException) {
            log("Ungültige URL: $url")
            return
        }
        binding.statusText.text = getString(R.string.status_connecting)
        binding.connectButton.isEnabled = false
        log("→ Verbinde mit $url …")
        connectedUrl = url
        val connectingSocket = communication.connectionToken
        uiHandler.postDelayed({
            if (communication.connectionToken === connectingSocket) {
                binding.connectButton.isEnabled = true
                refreshControls()
            }
        }, 1500L)
    }

    private fun disconnect() {
        steuerung.beforeDisconnect()
        communication.disconnect()
        connectedUrl = null
        binding.connectButton.isEnabled = true
        binding.freigabeSwitch.isChecked = false
        binding.statusText.text = getString(R.string.status_disconnected)
        log("→ Getrennt")
        refreshControls()
    }

    private fun onDisconnected(reason: String) {
        log("… $reason")
        steuerung.connectionLost()
        connectedUrl = null
        binding.connectButton.isEnabled = true
        binding.freigabeSwitch.isChecked = false
        binding.statusText.text = getString(R.string.status_disconnected)
        refreshControls()
    }

    private fun onServerState(state: LokAnzeige) {
        binding.statusText.text = getString(R.string.status_connected, state.label)
        updateUiFromState(state)
        refreshControls()
    }

    private fun updateUiFromState(s: LokAnzeige) {
        updatingUi = true
        try {
            binding.idText.text = getString(R.string.id_value, s.id)
            binding.infoText.text = getString(
                R.string.info_value,
                s.name,
                s.number,
                s.position
            )
            val speed = s.speed
            setSliderValue(speed)
            binding.speedLabel.text = getString(R.string.speed_value, speed)
            binding.directionToggle.check(
                if (s.direction == DIRECTION_BACKWARD) R.id.btnBackward else R.id.btnForward
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

    private fun updateFeatureSwitch(sw: MaterialSwitch, s: LokAnzeige, key: String) {
        sw.isChecked = s.features[key] ?: false
    }

    private fun setSliderValue(speed: Int) {
        val previous = updatingUi
        updatingUi = true
        binding.speedSlider.value = speed.coerceIn(SPEED_MIN, SPEED_MAX).toFloat()
        updatingUi = previous
    }

    private fun refreshControls() {
        val s = steuerung.displayState
        val ready = communication.hasConnection && s != null
        binding.connectButton.text =
            getString(if (communication.hasConnection) R.string.disconnect else R.string.connect)

        binding.freigabeSwitch.isEnabled = ready
        val motionAllowed = ready && binding.freigabeSwitch.isChecked
        val stopped = (s?.speed ?: 0) == 0
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

    private fun hasFeature(s: LokAnzeige?, key: String): Boolean =
        s?.features?.get(key) != null

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
    }
}
