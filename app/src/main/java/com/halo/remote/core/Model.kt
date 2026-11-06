package com.halo.remote.core

import java.io.File

/** The TV operating systems the app can talk to over Wi-Fi. */
enum class Platform(val label: String, val short: String) {
    ANDROID_TV("Google TV / Android TV", "Google TV"),
    SAMSUNG("Samsung Tizen", "Tizen"),
    LG("LG webOS", "webOS"),
    ROKU("Roku", "Roku"),
    FIRE_TV("Fire TV", "Fire TV"),
    MI_LEGACY("Mi TV (PatchWall, older)", "PatchWall");

    companion object {
        fun of(name: String?): Platform? = values().firstOrNull { it.name == name }
    }
}

/** Buttons the remote can send. Each driver maps them to its own codes. */
enum class Key(val androidCode: Int) {
    POWER(26), UP(19), DOWN(20), LEFT(21), RIGHT(22), OK(23),
    BACK(4), HOME(3), MENU(82), SETTINGS(176), INFO(165), GUIDE(172), SEARCH(84),
    VOL_UP(24), VOL_DOWN(25), MUTE(164), CH_UP(166), CH_DOWN(167), INPUT(178),
    PLAY_PAUSE(85), REWIND(89), FORWARD(90), STOP(86), DEL(67),
    D0(7), D1(8), D2(9), D3(10), D4(11), D5(12), D6(13), D7(14), D8(15), D9(16);

    val digit: Int? get() = if (name.length == 2 && name[0] == 'D') name[1] - '0' else null

    companion object {
        fun digit(n: Int): Key = valueOf("D$n")
    }
}

/** Streaming apps offered as shortcuts. Names only, no logos. */
enum class StreamingApp(val label: String) {
    NETFLIX("Netflix"), YOUTUBE("YouTube"), PRIME("Prime Video"), HOTSTAR("JioHotstar")
}

/** A TV the user has found or added. */
data class TvDevice(
    val host: String,
    val platform: Platform,
    val name: String,
    val brand: String? = null,
    val model: String? = null,
) {
    val id: String get() = "$host|${platform.name}"
}

/** Connection progress reported to the screen. */
sealed class ConnState {
    object Idle : ConnState()
    object Connecting : ConnState()
    /** Android TV shows a 6-character code on screen; user must type it in. */
    data class NeedsCode(val error: String? = null) : ConnState()
    /** Samsung / LG / Fire TV show an "Allow" prompt on the TV. */
    data class WaitingForApproval(val message: String) : ConnState()
    object Connected : ConnState()
    data class Failed(val message: String) : ConnState()
}

/** Where drivers keep tokens and keys between launches. */
interface CredentialStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    val identityFile: File
}

/** In-memory store used by tests. */
class MemoryCredentialStore(override val identityFile: File) : CredentialStore {
    private val map = HashMap<String, String>()
    override fun get(key: String): String? = synchronized(map) { map[key] }
    override fun put(key: String, value: String?) {
        synchronized(map) { if (value == null) map.remove(key) else map[key] = value }
    }
}

/**
 * Game controller buttons. [androidCode] is the Android key code a TV receives over Wi-Fi,
 * [hidBit] the bit in the Bluetooth gamepad report (-1 for the D-pad, which is a hat switch),
 * and [fallback] the plain remote key used on TVs that only understand remote keys.
 */
enum class PadButton(val label: String, val androidCode: Int, val hidBit: Int, val fallback: Key?) {
    A("A", 96, 0, Key.OK),
    B("B", 97, 1, Key.BACK),
    X("X", 99, 3, null),
    Y("Y", 100, 4, null),
    L1("L1", 102, 6, null),
    R1("R1", 103, 7, null),
    L2("L2", 104, 8, null),
    R2("R2", 105, 9, null),
    SELECT("Select", 109, 10, null),
    START("Start", 108, 11, Key.MENU),
    HOME("Home", 110, 12, Key.HOME),
    L3("L3", 106, 13, null),
    R3("R3", 107, 14, null),
    UP("Up", 19, -1, Key.UP),
    DOWN("Down", 20, -1, Key.DOWN),
    LEFT("Left", 21, -1, Key.LEFT),
    RIGHT("Right", 22, -1, Key.RIGHT);

    val isDpad: Boolean get() = hidBit < 0
}
