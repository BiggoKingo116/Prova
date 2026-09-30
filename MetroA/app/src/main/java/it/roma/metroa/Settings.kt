package it.roma.metroa

import android.content.SharedPreferences

enum class TrainStyle(val label: String) { ARROW("Freccia"), DOT("Pallino"), CAR("Vagone") }
enum class TrainSize(val label: String, val scale: Float) { SMALL("Piccoli", 0.8f), MEDIUM("Medi", 1f), LARGE("Grandi", 1.3f) }
enum class NextTrainLabels(val label: String) { NONE("No"), TO_BATTISTINI("↑ Battistini"), TO_ANAGNINA("↓ Anagnina"), BOTH("Entrambi") }
enum class ThemeMode(val label: String) { SYSTEM("Sistema"), LIGHT("Chiaro"), DARK("Scuro") }

/** Preferenze dell'utente; i valori predefiniti riproducono l'aspetto originale dell'app. */
data class AppSettings(
    /** Distanza fra una stazione e la successiva sulla linea, in dp. */
    val stationSpacing: Int = 46,
    /** Tratte lunghe (in minuti di viaggio) disegnate più lunghe. */
    val proportionalSpacing: Boolean = false,
    /** Anagnina in alto invece di Battistini. */
    val reversed: Boolean = false,
    val nextTrainLabels: NextTrainLabels = NextTrainLabels.NONE,
    val trainStyle: TrainStyle = TrainStyle.ARROW,
    val trainSize: TrainSize = TrainSize.MEDIUM,
    /** Treni che scorrono in modo continuo invece di saltare ogni secondo. */
    val smoothMotion: Boolean = true,
    /** Treni fermi in banchina che "respirano". */
    val pulseStopped: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = false,
    val useLocation: Boolean = true,
    /** Stazioni preferite, nell'ordine in cui sono state aggiunte; la prima è quella del widget. */
    val favorites: List<Int> = emptyList(),
    /** Con il telefono girato la linea si dispone in orizzontale. */
    val horizontalInLandscape: Boolean = true,
    /** Quanti minuti prima dell'arrivo suona l'avviso "il treno sta arrivando". */
    val alertLeadMinutes: Int = 2,
    /** Durante "sono sul treno" condivide anche il percorso GPS (visibile solo al gestore del database). Scelta dell'utente. */
    val shareGpsTrack: Boolean = false,
) {
    companion object {
        const val MIN_SPACING = 36
        const val MAX_SPACING = 96
        val ALERT_LEADS = listOf(1, 2, 3, 5)
    }

    fun toggleFavorite(station: Int) =
        copy(favorites = if (station in favorites) favorites - station else favorites + station)
}

/** Legge e scrive [AppSettings] nelle SharedPreferences; un valore mancante o sconosciuto torna al predefinito. */
class SettingsStore(private val prefs: SharedPreferences) {
    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            stationSpacing = prefs.getInt("stationSpacing", d.stationSpacing)
                .coerceIn(AppSettings.MIN_SPACING, AppSettings.MAX_SPACING),
            proportionalSpacing = prefs.getBoolean("proportionalSpacing", d.proportionalSpacing),
            reversed = prefs.getBoolean("reversed", d.reversed),
            nextTrainLabels = enumPref("nextTrainLabels", d.nextTrainLabels),
            trainStyle = enumPref("trainStyle", d.trainStyle),
            trainSize = enumPref("trainSize", d.trainSize),
            smoothMotion = prefs.getBoolean("smoothMotion", d.smoothMotion),
            pulseStopped = prefs.getBoolean("pulseStopped", d.pulseStopped),
            theme = enumPref("theme", d.theme),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            useLocation = prefs.getBoolean("useLocation", d.useLocation),
            favorites = prefs.getString("favorites", "").orEmpty().split(',')
                .mapNotNull { it.toIntOrNull()?.takeIf { i -> i in STATIONS.indices } }.distinct(),
            horizontalInLandscape = prefs.getBoolean("horizontalInLandscape", d.horizontalInLandscape),
            alertLeadMinutes = prefs.getInt("alertLeadMinutes", d.alertLeadMinutes)
                .takeIf { it in AppSettings.ALERT_LEADS } ?: d.alertLeadMinutes,
            shareGpsTrack = prefs.getBoolean("shareGpsTrack", d.shareGpsTrack),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt("stationSpacing", s.stationSpacing)
            .putBoolean("proportionalSpacing", s.proportionalSpacing)
            .putBoolean("reversed", s.reversed)
            .putString("nextTrainLabels", s.nextTrainLabels.name)
            .putString("trainStyle", s.trainStyle.name)
            .putString("trainSize", s.trainSize.name)
            .putBoolean("smoothMotion", s.smoothMotion)
            .putBoolean("pulseStopped", s.pulseStopped)
            .putString("theme", s.theme.name)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("useLocation", s.useLocation)
            .putString("favorites", s.favorites.joinToString(","))
            .putBoolean("horizontalInLandscape", s.horizontalInLandscape)
            .putInt("alertLeadMinutes", s.alertLeadMinutes)
            .putBoolean("shareGpsTrack", s.shareGpsTrack)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default
}

/**
 * Posizione verticale (in dp, dal centro della prima stazione in alto) del centro di ogni stazione.
 * Con [AppSettings.proportionalSpacing] ogni tratta è lunga in proporzione al tempo di viaggio medio
 * ([segmentSeconds]), ma mai meno di [AppSettings.MIN_SPACING] perché i nomi restino leggibili.
 */
fun stationCenters(s: AppSettings, segmentSeconds: FloatArray?): FloatArray {
    val gaps = FloatArray(LAST) { seg ->
        if (!s.proportionalSpacing || segmentSeconds == null) s.stationSpacing.toFloat()
        else maxOf(AppSettings.MIN_SPACING.toFloat(), s.stationSpacing * segmentSeconds[seg] / segmentSeconds.average().toFloat())
    }
    val centers = FloatArray(STATIONS.size)
    // In alto c'è Battistini (indice 0), o Anagnina se la linea è capovolta
    var y = 0f
    var prev: Int? = null
    for (i in if (s.reversed) (LAST downTo 0) else (0..LAST)) {
        if (prev != null) y += gaps[minOf(i, prev)]
        centers[i] = y
        prev = i
    }
    return centers
}

/** Posizione verticale di un treno all'indice frazionario [pos], fra i centri delle due stazioni vicine. */
fun yAt(centers: FloatArray, pos: Float): Float {
    val i = pos.toInt().coerceIn(0, LAST)
    val j = minOf(i + 1, LAST)
    return centers[i] + (centers[j] - centers[i]) * (pos - i)
}

/** Sullo schermo il treno va verso l'alto? Dipende dalla direzione e da quale capolinea sta in alto. */
fun movesUp(d: Direction, s: AppSettings) = (d == Direction.TO_BATTISTINI) != s.reversed
