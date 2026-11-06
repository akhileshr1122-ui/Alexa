package com.halo.remote.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What SSDP (the "who's there?" broadcast most TVs answer) tells us about a device. */
data class SsdpInfo(val friendlyName: String?, val manufacturer: String?, val model: String?)

/**
 * Finds TVs on the local network two ways at once:
 * 1. SSDP broadcast for names and makers.
 * 2. A quick check of each address for the ports each TV system listens on.
 */
class Discovery(private val localIp: String) {

    companion object {
        val PORTS = intArrayOf(6466, 8002, 8001, 3000, 3001, 8060, 5555, 6095)

        fun classify(ports: Set<Int>, info: SsdpInfo?): Platform? {
            val maker = info?.manufacturer?.lowercase() ?: ""
            val what = ((info?.model ?: "") + " " + (info?.friendlyName ?: "")).lowercase()
            // Echo speakers are also made by Amazon; Fire TV model numbers start with AFT.
            val fireTv = maker.contains("amazon") && (what.contains("fire") || what.contains("aft"))
            return when {
                // What the TV says about itself beats port guesses (e.g. a Samsung that also exposes other ports).
                maker.contains("samsung") && (8001 in ports || 8002 in ports || what.contains("tv")) -> Platform.SAMSUNG
                maker.contains("lg electronics") && (3000 in ports || 3001 in ports || what.contains("webos") || what.contains("tv")) -> Platform.LG
                maker.contains("roku") -> Platform.ROKU
                8060 in ports -> Platform.ROKU
                3000 in ports || 3001 in ports -> Platform.LG
                8001 in ports || 8002 in ports -> Platform.SAMSUNG
                6466 in ports -> Platform.ANDROID_TV
                5555 in ports || fireTv -> Platform.FIRE_TV
                6095 in ports -> Platform.MI_LEGACY
                else -> null
            }
        }

        fun cleanName(name: String?): String? =
            name?.replace(Regex("^\\[TV]\\s*"), "")?.replace("&amp;", "&")?.trim()?.takeIf { it.isNotEmpty() }

        fun xmlTag(xml: String, tag: String): String? =
            Regex("<$tag>([^<]*)</$tag>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()

        fun locationOf(response: String): String? =
            response.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
    }

    private val ssdp = ConcurrentHashMap<String, SsdpInfo>()
    private val openPorts = ConcurrentHashMap<String, MutableSet<Int>>()
    private val reported = ConcurrentHashMap<String, TvDevice>()
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /** Blocks for about 6–8 seconds. [onFound] may be called again for the same host with a better name. */
    fun run(onFound: (TvDevice) -> Unit) {
        val ssdpThread = Thread({ runSsdp(onFound) }, "ssdp").apply { isDaemon = true; start() }
        scanPorts(onFound)
        ssdpThread.join(5000)
        // Fire TVs with ADB off still answer SSDP: list them so the app can explain the setup step.
        for ((host, info) in ssdp) report(host, onFound, info)
    }

    private fun report(host: String, onFound: (TvDevice) -> Unit, info: SsdpInfo? = ssdp[host]) {
        val ports = openPorts[host] ?: emptySet<Int>()
        val platform = classify(ports, info) ?: return
        val name = cleanName(info?.friendlyName)
            ?: info?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ").ifBlank { null } }
            ?: "${platform.short} TV"
        val device = TvDevice(host, platform, name, info?.manufacturer, info?.model)
        if (reported[host] != device) {
            reported[host] = device
            onFound(device)
        }
    }

    private fun scanPorts(onFound: (TvDevice) -> Unit) {
        val prefix = localIp.substringBeforeLast('.') + "."
        val pool = Executors.newFixedThreadPool(96)
        for (i in 1..254) {
            val host = prefix + i
            if (host == localIp) continue
            pool.execute {
                for (port in PORTS) {
                    if (cancelled) return@execute
                    try {
                        Socket().use { it.connect(InetSocketAddress(host, port), 400) }
                        openPorts.getOrPut(host) { ConcurrentHashMap.newKeySet() }.add(port)
                    } catch (_: Exception) {
                    }
                }
                if (openPorts.containsKey(host)) {
                    samsungInfo(host)?.let { ssdp[host] = it }
                    report(host, onFound)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, TimeUnit.SECONDS)
    }

    private fun runSsdp(onFound: (TvDevice) -> Unit) {
        val locations = HashMap<String, String>()
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf("ssdp:all", "urn:dial-multiscreen-org:service:dial:1", "roku:ecp")
                for (round in 0 until 2) {
                    for (st in targets) {
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                        val bytes = msg.toByteArray()
                        sock.send(DatagramPacket(bytes, bytes.size, group, 1900))
                    }
                    val until = System.currentTimeMillis() + 1800
                    val buf = ByteArray(4096)
                    while (System.currentTimeMillis() < until && !cancelled) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            sock.receive(p)
                            val host = p.address.hostAddress ?: continue
                            val loc = locationOf(String(p.data, 0, p.length)) ?: continue
                            // Prefer description URLs that describe the TV itself.
                            if (!locations.containsKey(host) || loc.contains("dial", true) || loc.contains("dmr", true)) {
                                locations[host] = loc
                            }
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return
        }
        for ((host, loc) in locations) {
            if (cancelled) return
            val info = fetchDescription(loc) ?: continue
            if (ssdp[host]?.manufacturer == "Samsung") continue
            ssdp[host] = info
            if (openPorts.containsKey(host)) report(host, onFound, info)
        }
    }

    /** Samsung TVs describe themselves at http://TV:8001/api/v2/ even when SSDP is quiet. */
    private fun samsungInfo(host: String): SsdpInfo? = try {
        val c = URL("http://$host:8001/api/v2/").openConnection() as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 1200
        try {
            val body = c.inputStream.use { String(it.readBytes()) }
            val json = Json.obj(body)
            val dev = json.obj("device")
            val type = (dev?.str("type") ?: json.str("type") ?: "").lowercase()
            if (type.contains("samsung") || body.contains("Samsung", ignoreCase = true)) {
                SsdpInfo(cleanName(dev?.str("name") ?: json.str("name")) ?: "Samsung TV", "Samsung", dev?.str("modelName"))
            } else null
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchDescription(location: String): SsdpInfo? = try {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        try {
            val xml = c.inputStream.use { String(it.readBytes()) }
            SsdpInfo(xmlTag(xml, "friendlyName"), xmlTag(xml, "manufacturer"), xmlTag(xml, "modelName"))
        } finally {
            c.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
