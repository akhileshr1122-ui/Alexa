package com.halo.remote.core

/**
 * TV brands and which Wi-Fi control system their smart TVs use.
 * Many brands sell models on more than one system, so a brand can list several;
 * the scan decides which one a particular TV really uses.
 */
data class Brand(val name: String, val platforms: List<Platform>, val note: String? = null) {
    val initials: String
        get() {
            val words = name.split(' ', '-', '&').filter { it.isNotBlank() }
            return if (words.size >= 2) "${words[0][0]}${words[1][0]}".uppercase()
            else name.filter { it.isLetterOrDigit() }.take(2).uppercase()
        }
}

object Brands {
    private val A = Platform.ANDROID_TV
    private val S = Platform.SAMSUNG
    private val L = Platform.LG
    private val R = Platform.ROKU
    private val F = Platform.FIRE_TV
    private val M = Platform.MI_LEGACY

    val all: List<Brand> = listOf(
        Brand("Acer", listOf(A)),
        Brand("Airtel Xstream Box", listOf(A), "Set-top box"),
        Brand("Aiwa", listOf(A)),
        Brand("Akai", listOf(A)),
        Brand("Amazon Fire TV", listOf(F), "Sticks, Cube and Fire TV Edition TVs"),
        Brand("AmazonBasics", listOf(F)),
        Brand("Blaupunkt", listOf(A)),
        Brand("BPL", listOf(A)),
        Brand("Bush", listOf(A)),
        Brand("Chromecast with Google TV", listOf(A), "Streaming device"),
        Brand("Coocaa", listOf(A)),
        Brand("Croma", listOf(A, F)),
        Brand("Dish SmartVU", listOf(A), "Set-top box"),
        Brand("Element", listOf(R, F)),
        Brand("Haier", listOf(A)),
        Brand("Hisense", listOf(A, R, F), "VIDAA models aren't supported yet"),
        Brand("Hitachi", listOf(A, R)),
        Brand("Hyundai", listOf(A)),
        Brand("iFFALCON", listOf(A)),
        Brand("Infinix", listOf(A)),
        Brand("Insignia", listOf(F, R)),
        Brand("Intex", listOf(A)),
        Brand("Jio set-top box", listOf(A)),
        Brand("JVC", listOf(A, R, F)),
        Brand("Kevin", listOf(A)),
        Brand("Kodak", listOf(A)),
        Brand("LG", listOf(L)),
        Brand("Lloyd", listOf(A)),
        Brand("Marq", listOf(A)),
        Brand("Metz", listOf(A)),
        Brand("Micromax", listOf(A)),
        Brand("Motorola", listOf(A)),
        Brand("Nokia", listOf(A)),
        Brand("NVIDIA Shield", listOf(A), "Streaming device"),
        Brand("Onida", listOf(A, F)),
        Brand("OnePlus", listOf(A)),
        Brand("Onn", listOf(R, A)),
        Brand("Panasonic", listOf(A, F), "My Home Screen models aren't supported yet"),
        Brand("Philips", listOf(A, R)),
        Brand("Polaroid", listOf(A)),
        Brand("Realme", listOf(A)),
        Brand("Redmi", listOf(A, F)),
        Brand("Roku", listOf(R), "Players and Roku TVs"),
        Brand("Samsung", listOf(S)),
        Brand("Sansui", listOf(A)),
        Brand("Sanyo", listOf(A, R)),
        Brand("Sharp", listOf(A, R)),
        Brand("Skyworth", listOf(A)),
        Brand("Sony Bravia", listOf(A)),
        Brand("Tata Play Binge+", listOf(A), "Set-top box"),
        Brand("TCL", listOf(A, R)),
        Brand("Techwood", listOf(A)),
        Brand("Thomson", listOf(A)),
        Brand("Toshiba", listOf(A, F, R)),
        Brand("Vu", listOf(A)),
        Brand("Westinghouse", listOf(R, A)),
        Brand("Wyzr", listOf(A)),
        Brand("Xgimi", listOf(A), "Projector"),
        Brand("Xiaomi Mi TV", listOf(A, M, F)),
    ).sortedBy { it.name.lowercase() }

    fun search(query: String): List<Brand> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { it.name.lowercase().contains(q) }
    }

    /** Guesses a brand name from what the TV reports about itself. */
    fun match(manufacturer: String?, name: String?): Brand? {
        val text = ((manufacturer ?: "") + " " + (name ?: "")).lowercase()
        if (text.isBlank()) return null
        return all.sortedByDescending { it.name.length }.firstOrNull { b ->
            val key = b.name.lowercase().substringBefore(' ')
            key.length >= 2 && Regex("\\b${Regex.escape(key)}\\b").containsMatchIn(text)
        } ?: when {
            text.contains("xiaomi") || text.contains("mitv") -> all.first { it.name.startsWith("Xiaomi") }
            text.contains("sony") -> all.first { it.name.startsWith("Sony") }
            text.contains("amazon") -> all.first { it.name == "Amazon Fire TV" }
            else -> null
        }
    }
}
