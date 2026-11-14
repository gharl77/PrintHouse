package com.printhouse.app

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le cadre vu depuis Home Assistant, par découverte MQTT : un appareil avec un interrupteur
 * « Écran », des boutons photo suivante / précédente, la photo en cours (image et légende), la luminosité
 * et le choix de la source. (La lecture du capteur de présence, elle, passe par le jeton : voir [HaLink].)
 */
class MqttDevice(private val app: App) : MqttClient.Listener {
    private val client = MqttClient(this)
    private val s get() = app.settings
    private val id get() = s.deviceId
    private val base get() = "printhouse/$id"

    val active get() = s.mqttEnabled && s.mqttHost.isNotBlank()
    val connected get() = client.connected
    val error get() = client.lastError

    /** (Re)connecte selon les réglages ; à appeler après tout changement MQTT. */
    fun restart() {
        if (active) client.start(s.mqttHost.trim(), s.mqttPort, s.mqttUser, s.mqttPass, id, "$base/status")
        else client.stop()
        app.refreshLocks()
    }

    private val sources get() = listOf("all" to tr("Toute la bibliothèque"), "favorites" to tr("Favoris"), "albums" to tr("Albums choisis"))
    private val shares get() = listOf(0 to tr("Jamais"), 25 to tr("1 photo sur 4"), 50 to tr("1 photo sur 2"), 100 to tr("Seulement des souvenirs"))
    @Volatile private var lastImage: ByteArray? = null

    override fun onMqttConnected() {
        failures = 0
        fun JSONObject.state(key: String) = put("state_topic", "$base/$key")
        fun JSONObject.command(key: String) = put("command_topic", "$base/$key/set")
        entity("switch", "screen", tr("Écran"), "mdi:image-frame") { state("screen").command("screen") }
        entity("button", "next", tr("Photo suivante"), "mdi:skip-next") { command("next") }
        entity("button", "previous", tr("Photo précédente"), "mdi:skip-previous") { command("previous") }
        entity("sensor", "photo", tr("Photo affichée"), "mdi:image-text") { state("photo") }
        entity("sensor", "battery", tr("Batterie"), "mdi:battery") { state("battery").put("device_class", "battery").put("unit_of_measurement", "%").put("state_class", "measurement") }
        entity("binary_sensor", "charging", tr("En charge"), "mdi:power-plug") { state("charging").put("device_class", "battery_charging") }
        entity("image", "picture", tr("Photo en cours"), "mdi:image") { put("image_topic", "$base/picture").put("content_type", "image/jpeg") }
        for ((key, name) in listOf("brightness_day" to tr("Luminosité de jour"), "brightness_evening" to tr("Luminosité du soir"))) {
            entity("number", key, name, "mdi:brightness-6") {
                state(key).command(key).put("min", 1).put("max", 100).put("step", 1).put("unit_of_measurement", "%").put("mode", "slider")
            }
        }
        entity("switch", "guest", tr("Mode invités"), "mdi:account-group") { state("guest").command("guest") }
        entity("select", "source", tr("Source des photos"), "mdi:image-multiple") { state("source").command("source").put("options", JSONArray(sources.map { it.second })) }
        entity("select", "memories", tr("Ce jour-là"), "mdi:calendar-heart") { state("memories").command("memories").put("options", JSONArray(shares.map { it.second })) }
        client.publish("$base/status", "online", retain = true)
        client.subscribe("$base/+/set")
        publishState()
        lastImage?.let { client.publish("$base/picture", it, retain = true) }
    }

    private var failures = 0

    override fun onMqttDisconnected(error: String?) {
        // Un premier échec isolé est banal (le broker ferme l'ancienne session au redémarrage) :
        // on ne note que les échecs qui se répètent.
        if (error != null && ++failures == 2) app.events.add("MQTT : $error")
    }

    override fun onMqttMessage(topic: String, payload: String) {
        val value = payload.trim()
        when (topic.removePrefix("$base/").removeSuffix("/set")) {
            "screen" -> app.setScreen(value.equals("ON", ignoreCase = true))
            "next" -> app.step(1)
            "previous" -> app.step(-1)
            "brightness_day" -> value.toFloatOrNull()?.let { s.dayBrightness = it.toInt().coerceIn(1, 100); app.settingsChanged(null) }
            "brightness_evening" -> value.toFloatOrNull()?.let { s.eveningBrightness = it.toInt().coerceIn(1, 100); app.settingsChanged(null) }
            "guest" -> { s.guestMode = value.equals("ON", ignoreCase = true); app.settingsChanged(1) }
            "source" -> sources.firstOrNull { it.second == value }?.let { s.sourceMode = it.first; app.settingsChanged(1) }
            "memories" -> shares.firstOrNull { it.second == value }?.let { s.memoryShare = it.first }
        }
        publishState()
    }

    fun publishState() {
        client.publish("$base/screen", if (app.screenOff) "OFF" else "ON", retain = true)
        client.publish("$base/photo", app.shownCaption.ifEmpty { "—" }.take(250), retain = true)
        if (app.battery >= 0) client.publish("$base/battery", app.battery.toString(), retain = true)
        client.publish("$base/charging", if (app.charging) "ON" else "OFF", retain = true)
        client.publish("$base/guest", if (s.guestMode) "ON" else "OFF", retain = true)
        client.publish("$base/brightness_day", s.dayBrightness.toString(), retain = true)
        client.publish("$base/brightness_evening", s.eveningBrightness.toString(), retain = true)
        client.publish("$base/source", (sources.firstOrNull { it.first == s.sourceMode } ?: sources[0]).second, retain = true)
        client.publish("$base/memories", shares.minByOrNull { kotlin.math.abs(it.first - s.memoryShare) }!!.second, retain = true)
    }

    /** L'image affichée sur le cadre (JPEG), pour un tableau de bord. */
    fun publishImage(jpeg: ByteArray) {
        lastImage = jpeg
        client.publish("$base/picture", jpeg, retain = true)
    }

    private fun entity(component: String, key: String, name: String, icon: String, build: JSONObject.() -> Unit) {
        val device = JSONObject()
            .put("identifiers", JSONArray().put(id))
            .put("name", s.deviceName.ifBlank { "PrintHouse" })
            .put("manufacturer", "PrintHouse")
            .put("model", Build.MODEL)
            .put("sw_version", app.version)
        app.web.url()?.let { device.put("configuration_url", it) }
        val o = JSONObject()
            .put("name", name)
            .put("unique_id", "${id}_$key")
            .put("availability_topic", "$base/status")
            .put("icon", icon)
            .put("device", device)
        o.build()
        client.publish("homeassistant/$component/$id/$key/config", o.toString(), retain = true)
    }
}
