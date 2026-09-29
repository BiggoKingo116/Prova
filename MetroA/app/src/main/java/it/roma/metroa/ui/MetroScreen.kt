package it.roma.metroa.ui

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.roma.metroa.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Le pagine dell'app: la linea, le segnalazioni (aperte da una stazione o no) e le impostazioni. */
private sealed interface Page {
    data object Line : Page
    data class Feedback(val fromStation: Int?) : Page
    data object Settings : Page
}

/** Per ricordare la pagina aperta anche dopo una rotazione dello schermo. */
private val PageSaver = androidx.compose.runtime.saveable.Saver<Page, Int>(
    save = { when (it) { Page.Line -> -3; Page.Settings -> -2; is Page.Feedback -> it.fromStation ?: -1 } },
    restore = { when (it) { -3 -> Page.Line; -2 -> Page.Settings; -1 -> Page.Feedback(null); else -> Page.Feedback(it) } },
)

@Composable
fun MetroScreen(vm: MetroViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var page by rememberSaveable(stateSaver = PageSaver) { mutableStateOf<Page>(Page.Line) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.startLocation()
    }
    val requestLocation = {
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Le due schermate scorrono di lato come pagine
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != Page.Line
            (slideInHorizontally(tween(320)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(320))) togetherWith
                (slideOutHorizontally(tween(320)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(200)))
        },
        label = "schermata",
    ) { current ->
        if (current != Page.Line) BackHandler { page = Page.Line }
        when (current) {
            Page.Line -> LineScreen(
                vm, state, settings,
                onFeedback = { page = Page.Feedback(it) },
                onSettings = { page = Page.Settings },
                onRequestLocation = requestLocation,
            )
            is Page.Feedback -> FeedbackScreen(vm, state, current.fromStation, onRequestLocation = requestLocation) {
                page = Page.Line
            }
            Page.Settings -> SettingsScreen(vm, state, settings) { page = Page.Line }
        }
    }
}

@Composable
private fun LineScreen(
    vm: MetroViewModel, state: UiState, settings: AppSettings,
    onFeedback: (Int?) -> Unit, onSettings: () -> Unit, onRequestLocation: () -> Unit,
) {
    val p = LocalPalette.current
    var selected by remember { mutableStateOf<Int?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    Column(
        Modifier.fillMaxSize().background(p.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        Header(state, onRetry = vm::load, onSettings = onSettings)

        state.alerts.forEach { a -> key("alert", a.direction) { Appearing { AlertCard(a) } } }

        val context = LocalContext.current
        val found = (state.location as? Where.Found)?.station
        val nearbyArrivals = remember(found, now / 1000) {
            found?.let { n -> Direction.entries.associateWith { vm.arrivals(n, it) } }
        }
        LocationCard(
            state.location, arrivals = { d -> nearbyArrivals?.get(d).orEmpty() },
            onOpenStation = { selected = it },
            onAskPermission = onRequestLocation,
            onOpenSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )

        val favoriteArrivals = remember(settings.favorites, now / 1000) {
            settings.favorites.associateWith { st -> Direction.entries.associateWith { vm.arrivals(st, it).firstOrNull() } }
        }
        Appearing(visible = settings.favorites.isNotEmpty()) {
            FavoritesCard(settings.favorites, arrivals = { st, d -> favoriteArrivals[st]?.get(d) }) { selected = it }
        }
        val alert = state.trainAlert
        Appearing(visible = alert != null) { if (alert != null) TrainAlertCard(alert, onCancel = vm::cancelTrainAlert) }

        Direction.entries.forEach { d ->
            Appearing(visible = d in state.askConfirm) {
                ConfirmCard(d, state.delays[d] ?: NO_DATA) { yes -> vm.answerLive(d, yes) }
            }
        }

        // Stima in corso per direzione, se ci sono segnalazioni (anche solo storiche)
        Appearing(visible = state.delays.values.any { it.count > 0 }) {
            InfoCard {
                Direction.entries.forEach { d -> DelayLine(d, state.delays[d] ?: NO_DATA) }
            }
        }

        val haptic = LocalHapticFeedback.current
        Button(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onFeedback(null) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = p.lineA, contentColor = Color.White),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Segnala anticipo o ritardo", fontSize = 16.sp) }

        val nextTrains = remember(settings.nextTrainLabels, now / 1000) {
            if (settings.nextTrainLabels == NextTrainLabels.NONE) emptyMap()
            else Direction.entries.associateWith { vm.nextAtEveryStation(it) }
        }
        InfoCard(Modifier.padding(top = 8.dp)) {
            Row(Modifier.padding(bottom = 6.dp)) {
                val (up, down) = if (settings.reversed) "Anagnina" to "Battistini" else "Battistini" to "Anagnina"
                Text("↑ $up  ↓ $down", Modifier.weight(1f), color = p.mute, fontSize = 12.sp)
                Text("in rosso: fermi in stazione", color = p.mute, fontSize = 12.sp)
            }
            // Telefono girato: linea in orizzontale (se non disattivato nelle impostazioni)
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (landscape && settings.horizontalInLandscape) {
                HorizontalLineView(state.trains, settings, state.segmentSeconds, selected, state.location.station) {
                    selected = it
                }
            } else {
                LineView(state.trains, settings, state.segmentSeconds, selected, state.location.station, nextTrains) {
                    selected = it
                }
            }
        }
        Footer()
    }

    // Avviso "il treno sta arrivando": su Android 13+ serve prima il permesso per le notifiche
    val context = LocalContext.current
    var alertAfterPermission by remember { mutableStateOf<Pair<Int, Direction>?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alertAfterPermission?.let { (st, d) -> if (granted) vm.setTrainAlert(st, d) }
        alertAfterPermission = null
    }
    val setAlert = { st: Int, d: Direction ->
        if (!TrainAlerts.canNotify(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            alertAfterPermission = st to d
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            "Consenti le notifiche per ricevere l'avviso"
        } else {
            vm.setTrainAlert(st, d)?.let { "Ti avviso ${settings.alertLeadMinutes} min prima del treno" }
                ?: "Nessun treno abbastanza lontano da avvisare"
        }
    }

    selected?.let { i ->
        // Ricalcolati a ogni secondo, così i minuti scalano mentre il pannello è aperto
        val toBattistini = remember(i, now / 1000) { vm.arrivals(i, Direction.TO_BATTISTINI) }
        val toAnagnina = remember(i, now / 1000) { vm.arrivals(i, Direction.TO_ANAGNINA) }
        StationSheet(
            i, toBattistini, toAnagnina,
            favorite = i in settings.favorites,
            alert = state.trainAlert?.takeIf { it.station == i },
            onToggleFavorite = { vm.updateSettings { it.toggleFavorite(i) } },
            onArrived = { dir -> vm.reportArrivedNow(i, dir) },
            onAlert = { dir -> setAlert(i, dir) },
            onCancelAlert = vm::cancelTrainAlert,
            onFeedback = { selected = null; onFeedback(i) },
        ) { selected = null }
    }
}

/** Contenuto che entra aprendosi e sfumando, ed esce chiudendosi: niente salti nella pagina. */
@Composable
fun Appearing(visible: Boolean = true, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }.apply { targetState = visible }
    AnimatedVisibility(
        visibleState = state,
        enter = expandVertically(spring(stiffness = 300f)) + fadeIn(tween(300)),
        exit = shrinkVertically(spring(stiffness = 300f)) + fadeOut(tween(200)),
    ) { content() }
}

@Composable
private fun Header(state: UiState, onRetry: () -> Unit, onSettings: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(p.lineA), contentAlignment = Alignment.Center) {
            Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Metro A", color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("Battistini – Anagnina", color = p.mute, fontSize = 14.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            AnimatedContent(
                targetState = state.trains.size,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "treni",
            ) { n -> Text("$n", color = p.ink, fontSize = 30.sp, fontWeight = FontWeight.Light) }
            Text("treni in linea", color = p.mute, fontSize = 12.sp)
        }
        IconButton(onClick = onSettings) { Text("⚙", fontSize = 22.sp, color = p.mute) }
    }
    val progress = state.progress
    Box(Modifier.animateContentSize(spring(stiffness = 400f)).padding(bottom = 6.dp)) {
        when {
            progress != null -> Column {
                val pct = progress.fraction?.let { " ${(it * 100).toInt()}%" } ?: "…"
                Text("${progress.label}$pct", color = p.mute, fontSize = 13.sp)
                val animated by animateFloatAsState(progress.fraction ?: 0f, tween(400), label = "progresso")
                val bar = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(50))
                if (progress.fraction != null) {
                    LinearProgressIndicator({ animated }, bar, color = p.lineA, trackColor = p.rule)
                } else {
                    LinearProgressIndicator(bar, color = p.lineA, trackColor = p.rule)
                }
            }
            state.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Orario non disponibile: ${state.error}", Modifier.weight(1f), color = p.lineA, fontSize = 13.sp)
                TextButton(onClick = onRetry) { Text("Riprova", color = p.lineA) }
            }
            state.scheduleDownloadedAtMs != null -> {
                val day = SimpleDateFormat("d/M", Locale.ITALY).format(Date(state.scheduleDownloadedAtMs))
                Text("Stime dall'orario ATAC del $day corrette con le segnalazioni", color = p.mute, fontSize = 13.sp)
            }
            else -> Text("Carico l'orario…", color = p.mute, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Footer() {
    val p = LocalPalette.current
    Text(
        "Colonna sinistra: verso Battistini. Destra: verso Anagnina. Tocca una stazione per i prossimi passaggi.\n\n" +
            "Il feed in tempo reale di Roma Mobilità non include la metropolitana: posizioni e arrivi sono " +
            "calcolati dall'orario programmato (GTFS di Roma Mobilità, dati ATAC) e corretti con le segnalazioni " +
            "degli utenti. Quelle degli ultimi 20 minuti contano di più; altrimenti si usa la media della stessa fascia oraria.",
        color = p.mute, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 20.dp, start = 4.dp, end = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationSheet(
    index: Int, toBattistini: List<Arrival>, toAnagnina: List<Arrival>,
    favorite: Boolean, alert: TrainAlert?,
    onToggleFavorite: () -> Unit, onArrived: (Direction) -> Int?, onAlert: (Direction) -> String,
    onCancelAlert: () -> Unit, onFeedback: () -> Unit, onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(STATIONS[index].name, color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onToggleFavorite() }) {
                    AnimatedContent(favorite, label = "preferita") { fav ->
                        Text(if (fav) "★ Preferita" else "☆ Preferita", color = if (fav) p.lineA else p.mute, fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            for (dir in listOf(Direction.TO_BATTISTINI, Direction.TO_ANAGNINA)) {
                DirectionRow(
                    index, dir, if (dir == Direction.TO_BATTISTINI) toBattistini else toAnagnina,
                    alert = alert?.takeIf { it.direction == dir },
                    onArrived = { onArrived(dir) }, onAlert = { onAlert(dir) }, onCancelAlert = onCancelAlert,
                )
            }
            HorizontalDivider(color = p.rule)
            TextButton(onClick = onFeedback, contentPadding = PaddingValues(0.dp)) {
                Text("Treni in anticipo o in ritardo? Segnala", color = p.lineA)
            }
        }
    }
}

@Composable
private fun DirectionRow(
    station: Int, dir: Direction, arrivals: List<Arrival>, alert: TrainAlert?,
    onArrived: () -> Int?, onAlert: () -> String, onCancelAlert: () -> Unit,
) {
    // Al capolinea di partenza si segnala la partenza invece dell'arrivo
    val departure = isStartOfLine(station, dir)
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    var result by remember { mutableStateOf<String?>(null) }
    HorizontalDivider(color = p.rule)
    Column(Modifier.padding(vertical = 10.dp).animateContentSize(spring(stiffness = 400f))) {
        Text(directionLabel(dir), color = p.mute, fontSize = 14.sp)
        if (isEndOfLine(station, dir)) {
            Text("Capolinea", color = p.mute, fontSize = 16.sp)
        } else {
            if (arrivals.isEmpty()) {
                Text("Nessun treno in programma", color = p.mute, fontSize = 16.sp)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    ArrivalText(arrivals.first(), fontSize = 30, departure = departure)
                    if (arrivals.size > 1) {
                        Text("   poi ${arrivals.drop(1).joinToString(", ") { arrivalLabel(it) }}", color = p.ink, fontSize = 17.sp,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        result = onArrived()?.let { "Grazie! Treno ${describeOffset(it)}" }
                            ?: "Nessun treno in orario vicino ad adesso"
                    },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(if (departure) "Treno partito adesso" else "Treno arrivato adesso", color = p.lineA) }
                Spacer(Modifier.weight(1f))
                if (alert != null) {
                    TextButton(onClick = onCancelAlert, contentPadding = PaddingValues(0.dp)) {
                        Text("🔔 Annulla avviso", color = p.lineA)
                    }
                } else {
                    TextButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); result = onAlert() },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("🔔 Avvisami", color = p.lineA) }
                }
            }
            result?.let { Text(it, color = p.mute, fontSize = 12.sp) }
        }
    }
}
