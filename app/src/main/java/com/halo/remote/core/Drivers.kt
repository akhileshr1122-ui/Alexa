package com.halo.remote.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

// =====================================================================================
// Samsung (Tizen, 2016+): WebSocket on 8002 (secure, with token) or 8001.
// =====================================================================================
class SamsungDriver(device: TvDevice, store: CredentialStore) : TvDriver(device, store) {

    override val supportsText = true
    override val apps = setOf(StreamingApp.NETFLIX, StreamingApp.YOUTUBE, StreamingApp.PRIME)
    override val supportedKeys = Key.values().filter { keyCode(it) != null }.toSet()

    private var ws: WebSocketClient? = null

    companion object {
        private val NAME = Base64Compat.encode("OneBeam Remote".toByteArray())

        fun keyCode(key: Key): String? = when (key) {
            Key.POWER -> "KEY_POWER"
            Key.UP -> "KEY_UP"; Key.DOWN -> "KEY_DOWN"; Key.LEFT -> "KEY_LEFT"; Key.RIGHT -> "KEY_RIGHT"
            Key.OK -> "KEY_ENTER"; Key.BACK -> "KEY_RETURN"; Key.HOME -> "KEY_HOME"
            Key.MENU -> "KEY_MENU"; Key.SETTINGS -> "KEY_MENU"; Key.INFO -> "KEY_INFO"; Key.GUIDE -> "KEY_GUIDE"
            Key.VOL_UP -> "KEY_VOLUP"; Key.VOL_DOWN -> "KEY_VOLDOWN"; Key.MUTE -> "KEY_MUTE"
            Key.CH_UP -> "KEY_CHUP"; Key.CH_DOWN -> "KEY_CHDOWN"; Key.INPUT -> "KEY_SOURCE"
            Key.PLAY_PAUSE -> "KEY_PLAY_BACK"; Key.REWIND -> "KEY_REWIND"; Key.FORWARD -> "KEY_FF"
            Key.PLAY -> "KEY_PLAY"; Key.PAUSE -> "KEY_PAUSE"
            Key.STOP -> "KEY_STOP"; Key.DEL -> null; Key.SEARCH -> null
            else -> key.digit?.let { "KEY_$it" }
        }

        fun appId(app: StreamingApp): String = when (app) {
            StreamingApp.NETFLIX -> "3201907018807"
            StreamingApp.YOUTUBE -> "111299001912"
            StreamingApp.PRIME -> "3201910019365"
            StreamingApp.HOTSTAR -> ""
        }

        fun keyMessage(code: String) = Json.write(
            mapOf(
                "method" to "ms.remote.control",
                "params" to mapOf("Cmd" to "Click", "DataOfCmd" to code, "Option" to "false", "TypeOfRemote" to "SendRemoteKey")
            )
        )
    }

    override fun doConnect() {
        close(ws)
        val token = store.get(credKey("token"))
        val base = "/api/v2/channels/samsung.remote.control?name=$NAME"
        val client = try {
            WebSocketClient("wss://${device.host}:8002$base" + (token?.let { "&token=$it" } ?: ""), 6000).also { it.connect() }
        } catch (e: IOException) {
            try {
                WebSocketClient("ws://${device.host}:8001$base", 6000).also { it.connect() }
            } catch (e2: IOException) {
                throw UserFacingException("The Samsung TV didn't answer at ${device.host}. Make sure the TV is fully on (not in standby) and on the same Wi-Fi. On the TV, open Settings → General → External Device Manager → Device Connection Manager and set Access Notification to On (or First Time Only).")
            }
        }
        ws = client
        if (token == null) emit(ConnState.WaitingForApproval("Choose Allow on your TV to let OneBeam control it."))
        client.setReadTimeout(45000)
        while (true) {
            val msg = try { Json.obj(client.readText()) } catch (e: SocketTimeoutException) {
                throw UserFacingException("The TV didn't confirm. Choose Allow on the TV and try again.")
            }
            when (msg.str("event")) {
                "ms.channel.connect" -> {
                    msg.obj("data")?.str("token")?.let { store.put(credKey("token"), it) }
                    break
                }
                "ms.channel.unauthorized" -> {
                    store.put(credKey("token"), null)
                    throw UserFacingException("The TV blocked OneBeam. On the TV open Settings → General → External Device Manager → Device Connection Manager and allow it.")
                }
                "ms.channel.timeOut" -> throw UserFacingException("The TV prompt timed out. Try again and choose Allow.")
            }
        }
        client.setReadTimeout(0)
        Thread({
            try { while (!closed) onMessage(client.readText()) } catch (e: Exception) { if (ws === client) connectionLost() }
        }, "samsung-reader").apply { isDaemon = true; start() }
        emit(ConnState.Connected)
        if (store.get(credKey("mac")) == null) try {
            val info = Json.obj(URL("http://${device.host}:8001/api/v2/").openConnection().apply { connectTimeout = 3000; readTimeout = 3000 }.getInputStream().bufferedReader().readText())
            info.obj("device")?.str("wifiMac")?.let { store.put(credKey("mac"), it) }
        } catch (_: Exception) {}
    }

    override fun doSend(key: Key) {
        val code = keyCode(key) ?: return
        (ws ?: throw UserFacingException("Not connected")).sendText(keyMessage(code))
    }

    override fun doText(text: String) {
        val c = ws ?: throw UserFacingException("Not connected")
        c.sendText(Json.write(mapOf("method" to "ms.channel.emit", "params" to mapOf("event" to "custom.remote.textReceived", "to" to "broadcast"))))
        c.sendText(Json.write(mapOf("method" to "ms.remote.control", "params" to mapOf(
            "Cmd" to Base64Compat.encode(text.toByteArray()), "DataOfCmd" to "base64", "TypeOfRemote" to "SendInputString"))))
        c.sendText(Json.write(mapOf("method" to "ms.remote.control", "params" to mapOf("TypeOfRemote" to "SendInputEnd"))))
    }

    override fun doLaunch(app: StreamingApp) {
        if (appId(app).isEmpty()) return
        (ws ?: throw UserFacingException("Not connected")).sendText(Json.write(mapOf(
            "method" to "ms.channel.emit",
            "params" to mapOf("event" to "ed.apps.launch", "to" to "host",
                "data" to mapOf("action_type" to "DEEP_LINK", "appId" to appId(app), "metaTag" to ""))
        )))
    }

    override fun doClose() { close(ws); ws = null }

    // ---- Halo Bot: launch any installed app by name, power on with Wake-on-LAN ----
    @Volatile private var installed: List<Triple<String, String, Int>> = emptyList()

    private fun onMessage(text: String) {
        val m = try { Json.obj(text) } catch (_: Exception) { return }
        if (m.str("event") != "ed.installedApp.get") return
        val list = (m.obj("data")?.get("data") as? List<*>) ?: return
        installed = list.mapNotNull { o ->
            val a = o as? Map<*, *> ?: return@mapNotNull null
            val id = a["appId"] as? String ?: return@mapNotNull null
            val name = a["name"] as? String ?: return@mapNotNull null
            val type = (a["app_type"] as? Number)?.toInt() ?: 2
            Triple(id, name, type)
        }
    }

    private val known = mapOf(
        "netflix" to "3201907018807", "youtube" to "111299001912", "prime" to "3201910019365", "amazon" to "3201910019365",
        "disney" to "3201901017640", "spotify" to "3201606009684", "appletv" to "3201807016597", "plex" to "3201512006963",
    )

    override fun doLaunchByName(name: String): String? {
        val n = norm(name)
        val c = ws ?: throw UserFacingException("Not connected")
        if (installed.isEmpty()) {
            c.sendText(Json.write(mapOf("method" to "ms.channel.emit", "params" to mapOf("event" to "ed.installedApp.get", "to" to "host"))))
            for (i in 0 until 20) { if (installed.isNotEmpty()) break; Thread.sleep(100) }
        }
        val hit = installed.firstOrNull { norm(it.second) == n } ?: installed.firstOrNull { norm(it.second).contains(n) || (n.length > 3 && n.contains(norm(it.second))) }
        val (id, label, type) = hit ?: known.entries.firstOrNull { n.contains(it.key) }?.let { Triple(it.value, name, 2) } ?: return null
        c.sendText(Json.write(mapOf(
            "method" to "ms.channel.emit",
            "params" to mapOf("event" to "ed.apps.launch", "to" to "host",
                "data" to mapOf("action_type" to if (type == 4) "NATIVE_LAUNCH" else "DEEP_LINK", "appId" to id, "metaTag" to ""))
        )))
        return label
    }

    override fun doPowerOn() {
        val mac = store.get(credKey("mac"))
        if (mac != null) {
            val hex = mac.replace(":", "").replace("-", "")
            if (hex.length == 12) {
                val macBytes = ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val packet = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) packet[i] = 0xFF.toByte()
                for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)
                val parts = device.host.split(".")
                val subnetBcast = if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.255" else "255.255.255.255"
                java.net.DatagramSocket().use { sock ->
                    sock.broadcast = true
                    repeat(3) {
                        for (addr in listOf("255.255.255.255", subnetBcast)) for (port in intArrayOf(9, 7))
                            try { sock.send(java.net.DatagramPacket(packet, packet.size, java.net.InetAddress.getByName(addr), port)) } catch (_: Exception) {}
                        Thread.sleep(150)
                    }
                }
                return
            }
        }
        // No MAC known yet: a TV in network standby still accepts the power key.
        if (state == ConnState.Connected) doSend(Key.POWER)
        else throw UserFacingException("I need to connect to the Samsung TV once while it's on before I can turn it on. Also turn on Settings → General → Network → Expert Settings → Power On with Mobile.")
    }
}

private fun close(ws: WebSocketClient?) { try { ws?.close() } catch (_: Exception) {} }

// =====================================================================================
// LG webOS: WebSocket on 3000 (or 3001 secure). Buttons go over a second "pointer" socket.
// =====================================================================================
class LgDriver(device: TvDevice, store: CredentialStore) : TvDriver(device, store) {

    override val supportsText = true
    override val apps = setOf(StreamingApp.NETFLIX, StreamingApp.YOUTUBE, StreamingApp.PRIME)

    override val supportedKeys = Key.values().filter { button(it) != null || it == Key.POWER || it == Key.PLAY_PAUSE }.toSet()

    private var main: WebSocketClient? = null
    private var pointer: WebSocketClient? = null
    private var nextId = 1
    private var playing = true

    companion object {
        val PERMISSIONS = listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY",
            "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING",
            "CONTROL_INPUT_TEXT", "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER",
            "CONTROL_TV_SCREEN", "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
            "READ_INSTALLED_APPS", "READ_NETWORK_STATE", "READ_POWER_STATE", "READ_RUNNING_APPS",
            "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "SEARCH", "UPDATE_FROM_REMOTE_APP",
        )

        fun registerMessage(clientKey: String?): String {
            val payload = LinkedHashMap<String, Any?>()
            payload["forcePairing"] = false
            payload["pairingType"] = "PROMPT"
            payload["manifest"] = mapOf("appVersion" to "1.1", "manifestVersion" to 1, "permissions" to PERMISSIONS)
            if (clientKey != null) payload["client-key"] = clientKey
            return Json.write(mapOf("type" to "register", "id" to "register_0", "payload" to payload))
        }

        fun button(key: Key): String? = when (key) {
            Key.UP -> "UP"; Key.DOWN -> "DOWN"; Key.LEFT -> "LEFT"; Key.RIGHT -> "RIGHT"; Key.OK -> "ENTER"
            Key.BACK -> "BACK"; Key.HOME -> "HOME"; Key.MENU -> "MENU"; Key.SETTINGS -> "QMENU"
            Key.INFO -> "INFO"; Key.GUIDE -> "GUIDE"; Key.VOL_UP -> "VOLUMEUP"; Key.VOL_DOWN -> "VOLUMEDOWN"
            Key.MUTE -> "MUTE"; Key.CH_UP -> "CHANNELUP"; Key.CH_DOWN -> "CHANNELDOWN"; Key.INPUT -> "INPUT_HUB"
            Key.REWIND -> "REWIND"; Key.FORWARD -> "FASTFORWARD"; Key.STOP -> "STOP"
            Key.POWER, Key.PLAY_PAUSE, Key.DEL, Key.SEARCH -> null
            else -> key.digit?.toString()
        }

        fun appId(app: StreamingApp) = when (app) {
            StreamingApp.NETFLIX -> "netflix"
            StreamingApp.YOUTUBE -> "youtube.leanback.v4"
            StreamingApp.PRIME -> "amazon"
            StreamingApp.HOTSTAR -> "hotstar"
        }
    }

    override fun doConnect() {
        doClose()
        val ws = try {
            WebSocketClient("ws://${device.host}:3000/", 6000).also { it.connect() }
        } catch (e: IOException) {
            WebSocketClient("wss://${device.host}:3001/", 6000).also { it.connect() }
        }
        main = ws
        ws.sendText(registerMessage(store.get(credKey("client-key"))))
        ws.setReadTimeout(60000)
        while (true) {
            val msg = try { Json.obj(ws.readText()) } catch (e: SocketTimeoutException) {
                throw UserFacingException("The TV didn't confirm. Choose Accept on the TV and try again.")
            }
            when (msg.str("type")) {
                "response" -> if (msg.obj("payload")?.str("pairingType") == "PROMPT") {
                    emit(ConnState.WaitingForApproval("Choose Accept on your TV to let OneBeam control it."))
                }
                "registered" -> {
                    msg.obj("payload")?.str("client-key")?.let { store.put(credKey("client-key"), it) }
                    break
                }
                "error" -> {
                    store.put(credKey("client-key"), null)
                    throw UserFacingException("The TV declined the connection: ${msg.str("error") ?: "unknown reason"}")
                }
            }
        }
        // Ask for the pointer/button socket.
        val id = "ptr_${nextId++}"
        ws.sendText(Json.write(mapOf("id" to id, "type" to "request", "uri" to "ssap://com.webos.service.networkinput/getPointerInputSocket")))
        var path: String? = null
        ws.setReadTimeout(8000)
        while (path == null) {
            val msg = Json.obj(ws.readText())
            if (msg.str("id") == id) path = msg.obj("payload")?.str("socketPath")
                ?: throw UserFacingException("The TV didn't provide a button channel.")
        }
        val p = WebSocketClient(path, 6000).also { it.connect() }
        pointer = p
        ws.setReadTimeout(0)
        startReader(ws, "lg-main")
        startReader(p, "lg-pointer")
        emit(ConnState.Connected)
    }

    private fun startReader(c: WebSocketClient, name: String) {
        Thread({
            try { while (!closed) c.readText() } catch (e: Exception) { if (main === c || pointer === c) connectionLost() }
        }, name).apply { isDaemon = true; start() }
    }

    private fun request(uri: String, payload: Map<String, Any?> = emptyMap()) {
        (main ?: throw UserFacingException("Not connected")).sendText(
            Json.write(mapOf("id" to "req_${nextId++}", "type" to "request", "uri" to "ssap://$uri", "payload" to payload))
        )
    }

    override fun doSend(key: Key) {
        when (key) {
            Key.POWER -> request("system/turnOff")
            Key.PLAY_PAUSE -> {
                request(if (playing) "media.controls/pause" else "media.controls/play")
                playing = !playing
            }
            else -> {
                val b = button(key) ?: return
                (pointer ?: throw UserFacingException("Not connected")).sendText("type:button\nname:$b\n\n")
            }
        }
    }

    override val supportsPointer = true

    override fun doPointerMove(dx: Int, dy: Int) {
        (pointer ?: throw UserFacingException("Not connected")).sendText("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n")
    }

    override fun doPointerClick() {
        (pointer ?: throw UserFacingException("Not connected")).sendText("type:click\n\n")
    }

    override fun doText(text: String) = request("com.webos.service.ime/insertText", mapOf("text" to text, "replace" to 0))

    override fun doLaunch(app: StreamingApp) = request("system.launcher/launch", mapOf("id" to appId(app)))

    override fun doClose() {
        close(pointer); close(main)
        pointer = null; main = null
    }
}

// =====================================================================================
// Roku (and Roku TVs from TCL, Hisense, Sharp...): simple HTTP "External Control Protocol".
// =====================================================================================
class RokuDriver(device: TvDevice, store: CredentialStore) : TvDriver(device, store) {

    override val supportsText = true
    override val apps = setOf(StreamingApp.NETFLIX, StreamingApp.YOUTUBE, StreamingApp.PRIME)
    override val supportedKeys = Key.values().filter { keyName(it) != null }.toSet()

    companion object {
        fun keyName(key: Key): String? = when (key) {
            Key.POWER -> "Power"; Key.UP -> "Up"; Key.DOWN -> "Down"; Key.LEFT -> "Left"; Key.RIGHT -> "Right"
            Key.OK -> "Select"; Key.BACK -> "Back"; Key.HOME -> "Home"; Key.MENU -> "Info"; Key.SETTINGS -> "Info"
            Key.INFO -> "Info"; Key.SEARCH -> "Search"; Key.VOL_UP -> "VolumeUp"; Key.VOL_DOWN -> "VolumeDown"
            Key.MUTE -> "VolumeMute"; Key.CH_UP -> "ChannelUp"; Key.CH_DOWN -> "ChannelDown"; Key.INPUT -> "InputTuner"
            Key.PLAY_PAUSE -> "Play"; Key.REWIND -> "Rev"; Key.FORWARD -> "Fwd"; Key.STOP -> null
            Key.DEL -> "Backspace"; Key.GUIDE -> null
            else -> key.digit?.let { "Lit_$it" }
        }

        fun appId(app: StreamingApp) = when (app) {
            StreamingApp.NETFLIX -> "12"; StreamingApp.YOUTUBE -> "837"; StreamingApp.PRIME -> "13"; StreamingApp.HOTSTAR -> ""
        }
    }

    private fun post(path: String) {
        val c = URL("http://${device.host}:8060/$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.doOutput = true
        c.setFixedLengthStreamingMode(0)
        try {
            c.outputStream.close()
            val code = c.responseCode
            if (code == 403) throw UserFacingException("The Roku blocked control. On the Roku open Settings → System → Advanced system settings → Control by mobile apps → Network access and choose Default or Permissive.")
            if (code !in 200..299) throw IOException("Roku returned $code")
        } finally {
            c.disconnect()
        }
    }

    override fun doConnect() {
        val c = URL("http://${device.host}:8060/query/device-info").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        try {
            if (c.responseCode == 403) throw UserFacingException("The Roku blocked control. On the Roku open Settings → System → Advanced system settings → Control by mobile apps and allow it.")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
        emit(ConnState.Connected)
    }

    override fun doSend(key: Key) { keyName(key)?.let { post("keypress/$it") } }

    override fun doText(text: String) {
        for (ch in text) post("keypress/Lit_" + URLEncoder.encode(ch.toString(), "UTF-8").replace("+", "%20"))
    }

    override fun doLaunch(app: StreamingApp) { appId(app).takeIf { it.isNotEmpty() }?.let { post("launch/$it") } }

    override fun doClose() {}
}

// =====================================================================================
// Older Xiaomi Mi TVs running PatchWall (before Android TV): HTTP on port 6095.
// =====================================================================================
class MiLegacyDriver(device: TvDevice, store: CredentialStore) : TvDriver(device, store) {

    override val supportedKeys = setOf(Key.POWER, Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT, Key.OK, Key.HOME,
        Key.BACK, Key.MENU, Key.SETTINGS, Key.VOL_UP, Key.VOL_DOWN)

    companion object {
        fun keyName(key: Key): String? = when (key) {
            Key.POWER -> "power"; Key.UP -> "up"; Key.DOWN -> "down"; Key.LEFT -> "left"; Key.RIGHT -> "right"
            Key.OK -> "enter"; Key.HOME -> "home"; Key.BACK -> "back"; Key.MENU, Key.SETTINGS -> "menu"
            Key.VOL_UP -> "volumeup"; Key.VOL_DOWN -> "volumedown"
            else -> null
        }
    }

    private fun get(keycode: String) {
        val c = URL("http://${device.host}:6095/controller?action=keyevent&keycode=$keycode").openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        try { c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    override fun doConnect() {
        Socket().use { it.connect(InetSocketAddress(device.host, 6095), 4000) }
        emit(ConnState.Connected)
    }

    override fun doSend(key: Key) { keyName(key)?.let { get(it) } }
    override fun doClose() {}
}

// =====================================================================================
// Amazon Fire TV (sticks, Cube, Fire TV Edition TVs): Android Debug Bridge over Wi-Fi.
// Needs "ADB debugging" switched on once in the Fire TV's Developer options.
//
// Speed: `input keyevent` starts a new Java process for every press (~0.5–1 s on a Fire TV).
// Instead we start Android's "monkey" input server once and keep a socket to it open,
// so each press is a single line of text (a few milliseconds). Falls back to `input` if needed.
// =====================================================================================
class FireTvDriver(device: TvDevice, store: CredentialStore) : TvDriver(device, store) {

    override val supportsText = true
    override val apps = setOf(StreamingApp.NETFLIX, StreamingApp.YOUTUBE)

    private val identity by lazy { ClientIdentity.loadOrCreate(store.identityFile) }
    private var adb: Adb? = null
    private var shell: Adb.Stream? = null
    @Volatile private var fast: Adb.Stream? = null

    companion object {
        const val MONKEY_PORT = 1080

        fun appPackage(app: StreamingApp) = when (app) {
            StreamingApp.NETFLIX -> "com.netflix.ninja"
            StreamingApp.YOUTUBE -> "com.amazon.firetv.youtube"
            else -> null
        }

        /** Makes text safe for `input text`: spaces become %s, the rest is single-quoted. */
        fun shellText(text: String): String =
            "'" + text.replace(" ", "%s").replace("'", "'\\''") + "'"
    }

    override fun doConnect() {
        doClose()
        val a = Adb(device.host, identity) { emit(ConnState.WaitingForApproval(it)) }
        a.connect()
        a.startReader { if (adb === a) connectionLost() }
        adb = a
        shell = a.open("shell:")
        emit(ConnState.Connected)
        // Set up the fast channel in the background so the first presses aren't held up.
        Thread({ try { startFastChannel(a) } catch (_: Exception) {} }, "firetv-fast").apply { isDaemon = true; start() }
    }

    /** Starts the monkey input server and connects to it. Silent fallback if it isn't available. */
    private fun startFastChannel(a: Adb) {
        val sh = shell ?: return
        // Reuse a server left running from a previous session, otherwise start one.
        fast = tryOpenMonkey(a)
        if (fast != null) return
        a.write(sh, "monkey --port $MONKEY_PORT >/dev/null 2>&1 &\n")
        for (i in 0 until 8) {
            Thread.sleep(300)
            fast = tryOpenMonkey(a)
            if (fast != null) return
        }
    }

    private fun tryOpenMonkey(a: Adb): Adb.Stream? = try {
        a.open("tcp:$MONKEY_PORT", 1500).also { it.onClose = { if (fast === it) fast = null } }
    } catch (e: Exception) {
        null
    }

    private fun shell(cmd: String) {
        val a = adb ?: throw UserFacingException("Not connected")
        a.write(shell ?: throw UserFacingException("Not connected"), cmd + "\n")
    }

    override fun doSend(key: Key) {
        val a = adb ?: throw UserFacingException("Not connected")
        val f = fast
        if (f != null && key != Key.PLAY && key != Key.PAUSE && key != Key.PLAY_PAUSE) {
            try {
                a.write(f, "press ${key.androidCode}\n")
                return
            } catch (e: Exception) {
                fast = null
            }
        }
        shell("input keyevent ${key.androidCode}")
    }

    override val gameButtons = PadButton.values().toSet()
    override val nativeGamepad = true

    /** The monkey server can hold a key down, so games see real presses and releases. */
    override fun doGame(button: PadButton, down: Boolean) {
        val a = adb ?: throw UserFacingException("Not connected")
        val f = fast
        if (f != null) {
            try {
                a.write(f, "key ${if (down) "down" else "up"} ${button.androidCode}\n")
                return
            } catch (e: Exception) {
                fast = null
            }
        }
        if (down) shell("input keyevent ${button.androidCode}")
    }

    override fun doText(text: String) = shell("input text ${shellText(text)}")
    override fun doLaunch(app: StreamingApp) {
        appPackage(app)?.let { shell("monkey -p $it -c android.intent.category.LAUNCHER 1") }
    }

    override fun doClose() {
        adb?.close()
        adb = null; shell = null; fast = null
    }

    // ---- Halo Bot: any app by name, power ----
    private val known = mapOf(
        "netflix" to "com.netflix.ninja", "youtube" to "com.amazon.firetv.youtube", "prime" to "com.amazon.firebat",
        "amazon" to "com.amazon.firebat", "disney" to "com.disney.disneyplus", "spotify" to "com.spotify.tv.android",
        "plex" to "com.plexapp.android", "twitch" to "tv.twitch.android.viewer", "kodi" to "org.xbmc.kodi",
    )
    @Volatile private var packages: List<String> = emptyList()

    override fun doLaunchByName(name: String): String? {
        val n = norm(name)
        val a = adb ?: throw UserFacingException("Not connected")
        if (packages.isEmpty()) packages = a.output("shell:pm list packages", 4000).lines().mapNotNull { l -> l.trim().removePrefix("package:").takeIf { it.isNotBlank() && it.contains('.') } }
        val fromList = packages.firstOrNull { pk -> pk.split('.').any { seg -> seg.length > 2 && (norm(seg) == n || (n.length > 3 && norm(seg).startsWith(n))) } }
            ?: packages.firstOrNull { pk -> n.length > 3 && norm(pk).contains(n) }
        val pkg = known.entries.firstOrNull { n.contains(it.key) }?.value?.takeIf { packages.isEmpty() || it in packages } ?: fromList ?: return null
        shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        return name
    }

    override fun doPowerOff() = shell("input keyevent 223")
    override fun doPowerOn() {
        if (state != ConnState.Connected) { emit(ConnState.Connecting); doConnect() }
        shell("input keyevent 224")
    }
}

/** Minimal ADB client: connect, authenticate with our RSA key, and run several streams at once. */
class Adb(private val host: String, private val identity: ClientIdentity, private val onPrompt: (String) -> Unit) {

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val VERSION = 0x01000000
        const val MAX_DATA = 256 * 1024

        fun pack(cmd: Int, arg0: Int, arg1: Int, data: ByteArray): ByteArray {
            var sum = 0
            for (b in data) sum += (b.toInt() and 0xFF)
            val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(cmd xor -1)
            buf.put(data)
            return buf.array()
        }
    }

    class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** One logical connection inside ADB (a shell, or a forwarded TCP port on the device). */
    class Stream(val localId: Int) {
        @Volatile var remoteId = 0
        @Volatile var open = false
        @Volatile var onClose: ((Stream) -> Unit)? = null
        @Volatile var onData: ((ByteArray) -> Unit)? = null
        val canWrite = Semaphore(0)
        val opened = CountDownLatch(1)
    }

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextId = AtomicInteger(1)
    @Volatile private var alive = false

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        synchronized(this) { output.write(pack(cmd, a0, a1, data)); output.flush() }
    }

    private fun read(): Msg {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        if (len < 0 || len > 16 * 1024 * 1024) throw IOException("Bad ADB message")
        val data = ByteArray(len)
        input.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    fun connect() {
        socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, 5555), 5000)
        } catch (e: IOException) {
            throw UserFacingException("Can't reach the Fire TV. On the Fire TV open Settings → My Fire TV → Developer options and turn on ADB debugging. (If you don't see Developer options, open About and click the device name 7 times.)")
        }
        socket.tcpNoDelay = true
        socket.soTimeout = 10000
        input = DataInputStream(socket.getInputStream().buffered())
        output = socket.getOutputStream()
        send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var signed = false
        var sentKey = false
        while (true) {
            val m = try { read() } catch (e: SocketTimeoutException) {
                throw UserFacingException("The Fire TV didn't confirm. Choose Allow on the TV (tick Always allow) and try again.")
            }
            when (m.cmd) {
                CNXN -> break
                AUTH -> when {
                    m.arg0 == 1 && !signed -> {
                        signed = true
                        send(AUTH, 2, 0, AdbCrypto.signToken(identity.privateKey, m.data))
                    }
                    m.arg0 == 1 && !sentKey -> {
                        sentKey = true
                        send(AUTH, 3, 0, AdbCrypto.androidPublicKey(identity.publicKey, "halo@remote") + byteArrayOf(0))
                        onPrompt("On your TV, tick “Always allow from this computer” and choose OK.")
                        socket.soTimeout = 60000
                    }
                    else -> throw UserFacingException("The Fire TV refused the connection.")
                }
                else -> {}
            }
        }
        socket.soTimeout = 0
        alive = true
    }

    /** Reads everything the device sends and routes it to the right stream. */
    fun startReader(onLost: () -> Unit) {
        Thread({
            try {
                while (alive) {
                    val m = read()
                    val s = streams[m.arg1] ?: continue
                    when (m.cmd) {
                        OKAY -> {
                            if (!s.open) { s.remoteId = m.arg0; s.open = true; s.opened.countDown() }
                            s.canWrite.release()
                        }
                        WRTE -> { s.onData?.invoke(m.data); send(OKAY, s.localId, s.remoteId) }
                        CLSE -> {
                            s.open = false
                            streams.remove(s.localId)
                            s.opened.countDown()
                            if (s.remoteId != 0) try { send(CLSE, s.localId, s.remoteId) } catch (_: Exception) {}
                            s.onClose?.invoke(s)
                        }
                    }
                }
            } catch (e: Exception) {
                if (alive) { alive = false; onLost() }
            }
        }, "adb-reader").apply { isDaemon = true; start() }
    }

    /** Opens "shell:" or "tcp:PORT" on the device. */
    fun open(destination: String, timeoutMs: Long = 5000): Stream {
        val s = Stream(nextId.getAndIncrement())
        streams[s.localId] = s
        send(OPEN, s.localId, 0, (destination + "\u0000").toByteArray())
        if (!s.opened.await(timeoutMs, TimeUnit.MILLISECONDS) || !s.open) {
            streams.remove(s.localId)
            throw IOException("Couldn't open $destination on the Fire TV")
        }
        return s
    }

    /** Runs a one-shot service like "shell:pm list packages" and returns its output. */
    fun output(destination: String, timeoutMs: Long): String {
        val sb = StringBuffer()
        val done = CountDownLatch(1)
        val s = Stream(nextId.getAndIncrement())
        s.onData = { sb.append(String(it)) }
        s.onClose = { done.countDown() }
        streams[s.localId] = s
        send(OPEN, s.localId, 0, (destination + "\u0000").toByteArray())
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        streams.remove(s.localId)
        return sb.toString()
    }

    fun write(s: Stream, text: String) {
        if (!alive || !s.open) throw EOFException("Stream closed")
        if (!s.canWrite.tryAcquire(5, TimeUnit.SECONDS)) throw SocketTimeoutException("Fire TV is busy")
        send(WRTE, s.localId, s.remoteId, text.toByteArray())
    }

    fun close() {
        alive = false
        try { socket.close() } catch (_: Exception) {}
    }
}
