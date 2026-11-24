package de.dssax.loksteuerung

import org.json.JSONObject

/** Daten für die Oberfläche; JSON-Verarbeitung bleibt im Subsystem. */
data class LokAnzeige(
    val id: String,
    val name: String,
    val label: String,
    val number: String,
    val position: Int,
    val speed: Int,
    val direction: Int,
    val features: Map<String, Boolean?>
)

/** Bewahrt den vollständigen Serverzustand für Read-Modify-Write auf. */
class LokZustand {
    private var json: JSONObject? = null

    fun receive(text: String): Boolean {
        val received = JSONObject(text)
        if (!received.has("id")) return false
        json = received
        return true
    }

    fun setSpeed(speed: Int) { json?.put("speed", speed) }
    fun setDirection(direction: Int) { json?.put("direction", direction) }

    fun setFeature(key: String, enabled: Boolean): Boolean {
        val current = json ?: return false
        if (!current.has(key) || current.isNull(key)) return false
        current.put(key, enabled)
        return true
    }

    fun clear() { json = null }
    fun payload(): String? = json?.toString()

    fun snapshot(): LokAnzeige? {
        val current = json ?: return null
        val features = listOf("headLight", "cabineLighting", "hornSound", "drivingSound", "smoking")
            .associateWith { key ->
                if (current.has(key) && !current.isNull(key)) current.optBoolean(key, false) else null
            }
        return LokAnzeige(
            id = current.optString("id", "–"),
            name = current.optString("name", "?"),
            label = current.optString("name").ifBlank { current.optString("id") },
            number = current.optString("number", "?"),
            position = current.optInt("position", 0),
            speed = current.optInt("speed", 0),
            direction = current.optInt("direction", 0),
            features = features
        )
    }
}
