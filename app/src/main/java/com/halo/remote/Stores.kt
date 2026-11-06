package com.halo.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.halo.remote.core.CredentialStore
import com.halo.remote.core.Platform
import com.halo.remote.core.TvDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address

/** Tokens, pairing keys and the phone's certificate, kept in the app's private storage. */
class PrefsCredentialStore(ctx: Context) : CredentialStore {
    private val prefs = ctx.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    override val identityFile: File = File(ctx.filesDir, "identity.p12")
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/** Saved TVs, most recently used first. */
object DeviceStore {
    private const val PREFS = "devices"

    fun load(ctx: Context): List<TvDevice> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val platform = Platform.of(o.optString("platform")) ?: return@mapNotNull null
                TvDevice(
                    host = o.getString("host"),
                    platform = platform,
                    name = o.optString("name", platform.short),
                    brand = o.optString("brand").ifEmpty { null },
                    model = o.optString("model").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<TvDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("host", d.host); put("platform", d.platform.name); put("name", d.name)
                put("brand", d.brand ?: ""); put("model", d.model ?: "")
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
    }

    /** Adds or moves the TV to the top of the list. */
    fun remember(ctx: Context, d: TvDevice) {
        save(ctx, listOf(d) + load(ctx).filter { it.id != d.id })
    }

    fun forget(ctx: Context, d: TvDevice) {
        save(ctx, load(ctx).filter { it.id != d.id })
    }

    fun last(ctx: Context): TvDevice? = load(ctx).firstOrNull()
}

object Settings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun welcomed(ctx: Context) = prefs(ctx).getBoolean("welcomed", false)
    fun setWelcomed(ctx: Context) = prefs(ctx).edit().putBoolean("welcomed", true).apply()
    fun touchpad(ctx: Context) = prefs(ctx).getBoolean("touchpad", false)
    fun setTouchpad(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("touchpad", on).apply()

    /** Remote pad: 0 = D-pad, 1 = touchpad, 2 = air mouse. Falls back to the older touchpad switch. */
    fun padMode(ctx: Context) = prefs(ctx).getInt("padMode", if (touchpad(ctx)) 1 else 0)
    fun setPadMode(ctx: Context, mode: Int) = prefs(ctx).edit().putInt("padMode", mode).putBoolean("touchpad", mode == 1).apply()
}

object NetUtil {
    /** The phone's IPv4 address on Wi-Fi or Ethernet, or null if it isn't on a local network. */
    fun localIpv4(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        return cm.getLinkProperties(net)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
