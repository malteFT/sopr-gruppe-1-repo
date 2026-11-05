package de.dssax.loksteuerung

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import de.dssax.loksteuerung.nfc.NfcText
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NfcConnectionTest {
    private lateinit var server: MockWebServer
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val messages = LinkedBlockingQueue<String>()
    private var serverSocket: WebSocket? = null
    private val opened = CountDownLatch(1)
    private lateinit var url: String
    private val state = """{"id":"gisela-live-id","speed":0,"direction":0,"number":"0815","name":"Gisela","headLight":false,"cabineLighting":null,"hornSound":false,"drivingSound":false,"smoking":null,"position":0}"""

    @Before fun setup() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { serverSocket = webSocket; opened.countDown() }
            override fun onMessage(webSocket: WebSocket, text: String) { messages.offer(text); webSocket.send(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        url = server.url("/locomotive").toString().replace("http:", "ws:")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun cleanup() { scenario.close(); server.shutdown() }

    private fun scan(texts: List<String>) {
        scenario.onActivity { activity ->
            activity.handleNfcTexts(texts) { name -> if (name == "gisela") url else null }
        }
    }

    private fun waitForReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity { ready = it.findViewById<MaterialSwitch>(R.id.freigabeSwitch).isEnabled }
            if (ready) return
            Thread.sleep(50)
        }
        fail("No state received")
    }

    @Test fun giselaTextConnectsAutomaticallyAndPreservesCompleteControlProtocol() {
        val payload = byteArrayOf(2) + "de".toByteArray() + "gisela".toByteArray()
        scan(listOf(NfcText.decode(payload)!!))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        scenario.onActivity {
            assertEquals(url, it.findViewById<TextInputEditText>(R.id.urlEdit).text.toString())
            assertFalse(it.findViewById<MaterialSwitch>(R.id.freigabeSwitch).isEnabled)
        }
        assertNull(messages.poll()) // A scan itself sends no locomotive commands.
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        serverSocket!!.send(state)
        waitForReady()
        scenario.onActivity {
            assertFalse(it.findViewById<MaterialSwitch>(R.id.freigabeSwitch).isChecked)
            assertFalse(it.findViewById<MaterialSwitch>(R.id.cabinLightSwitch).isEnabled)
            assertFalse(it.findViewById<MaterialSwitch>(R.id.smokingSwitch).isEnabled)
            it.findViewById<MaterialSwitch>(R.id.headLightSwitch).isChecked = true
        }
        val light = JSONObject(messages.poll(5, TimeUnit.SECONDS) ?: error("No light command"))
        assertEquals("gisela-live-id", light.getString("id"))
        assertTrue(light.getBoolean("headLight"))
        assertTrue(light.isNull("smoking"))
        assertEquals(11, light.length())
        scenario.onActivity { it.findViewById<Button>(R.id.stopButton).performClick() }
        assertEquals(0, JSONObject(messages.poll(5, TimeUnit.SECONDS) ?: error("No stop command")).getInt("speed"))
    }

    @Test fun unknownEmptyAndConflictingTextsCannotConnect() {
        scan(listOf("unbekannt"))
        scenario.onActivity { assertTrue(it.findViewById<TextView>(R.id.nfcStatusText).text.contains("Unbekannter")) }
        scan(emptyList())
        scan(listOf("gisela", "ise"))
        scenario.onActivity {
            assertEquals(it.getString(R.string.default_url), it.findViewById<TextInputEditText>(R.id.urlEdit).text.toString())
            assertFalse(it.findViewById<MaterialSwitch>(R.id.freigabeSwitch).isEnabled)
        }
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun repeatedTextDoesNotReconnectOrResetDrivingRelease() {
        scan(listOf(" GISELA "))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        serverSocket!!.send(state)
        waitForReady()
        scenario.onActivity { it.findViewById<MaterialSwitch>(R.id.freigabeSwitch).isChecked = true }
        Thread.sleep(2100) // Outside the duplicate-scan suppression window.
        scan(listOf("gisela"))
        scenario.onActivity { assertTrue(it.findViewById<MaterialSwitch>(R.id.freigabeSwitch).isChecked) }
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }
}
