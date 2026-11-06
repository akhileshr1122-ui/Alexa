package com.haloapps.halobot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.halo.remote.DeviceStore
import com.halo.remote.NetUtil
import com.halo.remote.PrefsCredentialStore
import com.halo.remote.core.ConnState
import com.halo.remote.core.Discovery
import com.halo.remote.core.Key
import com.halo.remote.core.TvDevice
import com.halo.remote.core.TvDriver
import kotlin.concurrent.thread

/**
 * Wi-Fi TV control, reusing OneBeam's drivers (Samsung Tizen, Fire TV, Google/Android TV, LG, Roku).
 * The robot keeps one active TV; the user picks it in settings ("Find TVs").
 */
class TvHub(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val store = PrefsCredentialStore(ctx)
    private var driver: TvDriver? = null
    var onState: ((ConnState) -> Unit)? = null
    @Volatile var lastState: ConnState = ConnState.Idle; private set

    val device: TvDevice? get() = driver?.device ?: DeviceStore.last(ctx)
    val hasTv: Boolean get() = device != null

    private fun drv(): TvDriver? {
        driver?.let { return it }
        val d = DeviceStore.last(ctx) ?: return null
        return use(d)
    }

    fun use(d: TvDevice): TvDriver {
        driver?.close()
        DeviceStore.remember(ctx, d)
        val n = TvDriver.create(d, store)
        n.listener = { s -> lastState = s; main.post { onState?.invoke(s) } }
        driver = n
        return n
    }

    fun connect() { drv()?.connect() }

    fun press(k: Key): Boolean { val d = drv() ?: return false; d.press(k); return true }
    fun pressMany(k: Key, times: Int): Boolean { val d = drv() ?: return false; repeat(times) { d.press(k) }; return true }
    fun launch(name: String, cb: (String?) -> Unit): Boolean {
        val d = drv() ?: return false
        d.launchByName(name) { label -> main.post { cb(label) } }
        return true
    }
    fun powerOn(): Boolean { val d = drv() ?: return false; d.powerOn(); return true }
    fun powerOff(): Boolean { val d = drv() ?: return false; d.powerOff(); return true }
    fun type(text: String): Boolean { val d = drv() ?: return false; if (!d.supportsText) return false; d.text(text); return true }

    /** Scans the Wi-Fi for TVs (~8 s). [onFound] runs on the main thread for each TV. */
    fun discover(onFound: (TvDevice) -> Unit, onDone: (String?) -> Unit) {
        val ip = NetUtil.localIpv4(ctx)
        if (ip == null) { onDone("Connect the phone to Wi-Fi first."); return }
        thread(name = "tv-discovery") {
            try {
                Discovery(ip).run { d -> main.post { onFound(d) } }
                main.post { onDone(null) }
            } catch (e: Exception) {
                main.post { onDone(e.message ?: "Scan failed") }
            }
        }
    }

    fun close() { driver?.close(); driver = null }
}
