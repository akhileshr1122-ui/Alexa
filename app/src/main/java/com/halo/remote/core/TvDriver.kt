package com.halo.remote.core

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * Base class for every TV protocol. All network work runs on one background thread
 * per TV so button presses arrive in order.
 */
abstract class TvDriver(val device: TvDevice, protected val store: CredentialStore) {

    var listener: ((ConnState) -> Unit)? = null

    @Volatile
    var state: ConnState = ConnState.Idle
        private set

    @Volatile
    protected var closed = false

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tv-${device.platform.name.lowercase()}").apply { isDaemon = true }
    }

    open val supportsText: Boolean = false
    open val apps: Set<StreamingApp> = emptySet()
    open val supportedKeys: Set<Key> = Key.values().toSet()

    /** Game buttons this TV can receive over Wi-Fi. By default, those with a plain remote-key equivalent. */
    open val gameButtons: Set<PadButton> get() = PadButton.values().filter { it.fallback != null && it.fallback in supportedKeys }.toSet()

    /** True when this TV receives real gamepad key codes over Wi-Fi (Fire TV, Google TV). */
    open val nativeGamepad: Boolean = false

    /** True when the TV shows a real on-screen pointer we can move (LG webOS). Others navigate by D-pad. */
    open val supportsPointer: Boolean = false

    /** Moves the TV's pointer by a few pixels. Only on TVs with [supportsPointer]. */
    fun pointerMove(dx: Int, dy: Int) = run {
        if (ensureConnected()) doPointerMove(dx, dy)
    }

    /** Clicks where the TV's pointer is. */
    fun pointerClick() = run {
        if (ensureConnected()) doPointerClick()
    }

    protected open fun doPointerMove(dx: Int, dy: Int) {}
    protected open fun doPointerClick() {}

    protected fun emit(s: ConnState) {
        state = s
        listener?.invoke(s)
    }

    protected fun credKey(name: String) = "${device.platform.name}:${device.host}:$name"

    fun connect() = run {
        emit(ConnState.Connecting)
        doConnect()
    }

    fun press(key: Key) = run {
        if (ensureConnected()) doSend(key)
    }

    fun text(text: String) = run {
        if (ensureConnected()) doText(text)
    }

    /** A game button going down or up. TVs without hold support get one press on the way down. */
    fun game(button: PadButton, down: Boolean) = run {
        if (ensureConnected()) doGame(button, down)
    }

    fun launch(app: StreamingApp) = run {
        if (ensureConnected()) doLaunch(app)
    }

    /** Halo Bot: opens any app by its spoken name. [onResult] gets the app label, or null if not found. */
    fun launchByName(name: String, onResult: (String?) -> Unit) = run {
        if (ensureConnected()) onResult(doLaunchByName(name)) else onResult(null)
    }
    protected open fun doLaunchByName(name: String): String? {
        val n = norm(name)
        val app = apps.firstOrNull { norm(it.label).contains(n) || n.contains(norm(it.label)) || (it == StreamingApp.PRIME && (n.contains("prime") || n.contains("amazon"))) }
            ?: return null
        doLaunch(app)
        return app.label
    }

    fun powerOff() = run { if (ensureConnected()) doPowerOff() }
    protected open fun doPowerOff() { doSend(Key.POWER) }
    fun powerOn() = run { doPowerOn() }
    protected open fun doPowerOn() { if (ensureConnected()) doSend(Key.POWER) }

    /** Android TV only: the code shown on the TV screen. */
    fun submitCode(code: String) = run { doSubmitCode(code.trim().uppercase()) }

    fun close() {
        closed = true
        worker.execute {
            try { doClose() } catch (_: Exception) {}
        }
        worker.shutdown()
    }

    private fun ensureConnected(): Boolean {
        if (state == ConnState.Connected) return true
        if (state is ConnState.Failed || state == ConnState.Idle) {
            emit(ConnState.Connecting)
            doConnect()
        }
        return state == ConnState.Connected
    }

    private fun run(block: () -> Unit) {
        if (closed) return
        try {
            worker.execute {
                try {
                    block()
                } catch (e: Exception) {
                    if (!closed) {
                        try { doClose() } catch (_: Exception) {}
                        emit(ConnState.Failed(friendly(e)))
                    }
                }
            }
        } catch (_: Exception) {
            // worker shut down
        }
    }

    /** Called by reader threads when the TV drops the connection. */
    protected fun connectionLost() {
        if (!closed && state == ConnState.Connected) emit(ConnState.Failed("Connection to the TV was lost. Tap any button to reconnect."))
    }

    protected abstract fun doConnect()
    protected abstract fun doSend(key: Key)
    protected open fun doText(text: String) {}
    protected open fun doGame(button: PadButton, down: Boolean) {
        val k = button.fallback ?: return
        if (down && k in supportedKeys) doSend(k)
    }
    protected open fun doLaunch(app: StreamingApp) {}
    protected open fun doSubmitCode(code: String) {}
    protected abstract fun doClose()

    companion object {
        /** Lowercase letters and digits only, for fuzzy app-name matching. */
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        fun friendly(e: Throwable): String = when (e) {
            is UserFacingException -> e.message ?: "Something went wrong"
            is ConnectException, is NoRouteToHostException ->
                "Can't reach the TV. Check it's switched on and on the same Wi-Fi as this phone."
            is SocketTimeoutException -> "The TV didn't answer in time. Check it's on and on the same Wi-Fi."
            is UnknownHostException -> "That TV address isn't valid."
            is SSLException -> "The TV refused the secure connection. Try pairing again."
            is EOFException -> "The TV closed the connection."
            is IOException -> "Network error: ${e.message ?: "unknown"}"
            else -> e.message ?: e.javaClass.simpleName
        }

        fun create(device: TvDevice, store: CredentialStore): TvDriver = when (device.platform) {
            Platform.ANDROID_TV -> AndroidTvDriver(device, store)
            Platform.SAMSUNG -> SamsungDriver(device, store)
            Platform.LG -> LgDriver(device, store)
            Platform.ROKU -> RokuDriver(device, store)
            Platform.FIRE_TV -> FireTvDriver(device, store)
            Platform.MI_LEGACY -> MiLegacyDriver(device, store)
        }
    }
}

/** An error whose message is written for the person using the app. */
class UserFacingException(message: String) : IOException(message)

/** Thrown when an Android TV doesn't recognise this phone yet. */
class NeedsPairingException : IOException("Pairing required")
