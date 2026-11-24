package de.dssax.loksteuerung

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.google.android.material.slider.Slider
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

    private fun scan(texts: List<String>, targetName: String = "gisela") {
        scenario.onActivity { activity ->
            activity.handleNfcTexts(texts) { actualUrl ->
                val expectedUrl = when (targetName) {
                    "gisela" -> "ws://10.195.24.105:8076/locomotive"
                    "80 012" -> "ws://10.195.24.142:8075/locomotive"
                    "blauwal" -> "ws://10.195.24.112:8075/locomotive"
                    else -> error("Unexpected test locomotive")
                }
                assertEquals(expectedUrl, actualUrl)
                url // Only the connection transport is redirected to the local test server.
            }
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

    @Test fun locomotiveNumberSelectsItsStateAndAvailableFunctions() {
        val payload = byteArrayOf(2) + "de".toByteArray() + "80  012".toByteArray()
        scan(listOf(NfcText.decode(payload)!!), "80 012")
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val numberedState = JSONObject(state).put("id", "80-012-live-id")
            .put("name", "ISE").put("number", "80 012")
            .put("hornSound", JSONObject.NULL).put("drivingSound", JSONObject.NULL)
            .put("smoking", false)
        serverSocket!!.send(numberedState.toString())
        waitForReady()
        scenario.onActivity {
            assertTrue(it.findViewById<TextView>(R.id.infoText).text.contains("80 012"))
            assertFalse(it.findViewById<MaterialSwitch>(R.id.hornSwitch).isEnabled)
            assertTrue(it.findViewById<MaterialSwitch>(R.id.smokingSwitch).isEnabled)
            it.findViewById<MaterialSwitch>(R.id.headLightSwitch).isChecked = true
        }
        val command = JSONObject(messages.poll(5, TimeUnit.SECONDS) ?: error("No command"))
        assertEquals("80-012-live-id", command.getString("id"))
        assertEquals("80 012", command.getString("number"))
        assertTrue(command.isNull("hornSound"))
    }

    @Test fun blauwalTextUsesItsOwnLiveId() {
        val payload = byteArrayOf(2) + "de".toByteArray() + "Blauwal".toByteArray()
        scan(listOf(NfcText.decode(payload)!!), "blauwal")
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        val blauwalState = JSONObject(state).put("id", "blauwal-live-id")
            .put("name", "Blauwaal").put("number", "Testlok")
        serverSocket!!.send(blauwalState.toString())
        waitForReady()
        scenario.onActivity {
            assertTrue(it.findViewById<TextView>(R.id.statusText).text.contains("Blauwaal"))
            assertFalse(it.findViewById<MaterialSwitch>(R.id.freigabeSwitch).isChecked)
            it.findViewById<Button>(R.id.stopButton).performClick()
        }
        val command = JSONObject(messages.poll(5, TimeUnit.SECONDS) ?: error("No command"))
        assertEquals("blauwal-live-id", command.getString("id"))
        assertEquals("Blauwaal", command.getString("name"))
        assertEquals(0, command.getInt("speed"))
        scan(listOf("Blauwaal"), "blauwal")
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        scenario.onActivity { assertTrue(it.findViewById<TextView>(R.id.statusText).text.contains("Blauwaal")) }
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

    @Test fun speedStopAndDirectionPreserveTheSameFullState() {
        scan(listOf("Gisela"))
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        serverSocket!!.send(state)
        waitForReady()
        scenario.onActivity {
            it.findViewById<MaterialSwitch>(R.id.freigabeSwitch).isChecked = true
        }
        onView(withId(R.id.speedSlider)).perform(swipeRight())
        val moving = JSONObject(messages.poll(5, TimeUnit.SECONDS) ?: error("No speed command"))
        assertTrue(moving.getInt("speed") > 0)
        assertEquals("gisela-live-id", moving.getString("id"))
        assertEquals(11, moving.length())
        assertTrue(moving.isNull("smoking"))
        scenario.onActivity {
            assertFalse(it.findViewById<Button>(R.id.btnBackward).isEnabled)
            it.findViewById<Button>(R.id.stopButton).performClick()
            assertEquals(0f, it.findViewById<Slider>(R.id.speedSlider).value)
        }
        val stopped = JSONObject(messages.poll(5, TimeUnit.SECONDS) ?: error("No stop command"))
        assertEquals(0, stopped.getInt("speed"))
        assertEquals(11, stopped.length())
        // Wait for the server's state to settle before the next action.
        Thread.sleep(500)
        scenario.onActivity {
            assertTrue(it.findViewById<Button>(R.id.btnBackward).isEnabled)
            it.findViewById<Button>(R.id.btnBackward).performClick()
        }
        val reversed = JSONObject(messages.poll(5, TimeUnit.SECONDS) ?: error("No direction command"))
        assertEquals(1, reversed.getInt("direction"))
        assertEquals(0, reversed.getInt("speed"))
        assertEquals("gisela-live-id", reversed.getString("id"))
        assertEquals(11, reversed.length())
    }

    @Test fun unsolicitedServerStateUpdatesUiWithoutSendingACommand() {
        scan(listOf("Gisela"))
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        serverSocket!!.send(state)
        waitForReady()
        val updated = JSONObject(state).put("speed", 37).put("headLight", true)
        serverSocket!!.send(updated.toString())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var displayed = false
        while (!displayed && System.nanoTime() < deadline) {
            scenario.onActivity {
                displayed = it.findViewById<Slider>(R.id.speedSlider).value == 37f &&
                    it.findViewById<MaterialSwitch>(R.id.headLightSwitch).isChecked
            }
            if (!displayed) Thread.sleep(50)
        }
        assertTrue("Server state was not displayed", displayed)
        assertNull(messages.poll(600, TimeUnit.MILLISECONDS))
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
