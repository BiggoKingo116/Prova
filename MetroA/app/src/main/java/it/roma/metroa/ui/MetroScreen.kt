package it.roma.metroa.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.roma.metroa.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ROW_H = 46.dp

@Composable
fun MetroScreen(vm: MetroViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    // null = linea; altrimenti sezione segnalazioni, con la stazione da cui ci si arriva (-1 = nessuna)
    var feedbackFrom by rememberSaveable { mutableStateOf<Int?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.startLocation()
    }
    val requestLocation = {
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Le due schermate scorrono di lato come pagine
    AnimatedContent(
        targetState = feedbackFrom,
        transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally(tween(320)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(320))) togetherWith
                (slideOutHorizontally(tween(320)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(200)))
        },
        label = "schermata",
    ) { from ->
        if (from != null) {
            BackHandler { feedbackFrom = null }
            FeedbackScreen(vm, state, from.takeIf { it >= 0 }, onRequestLocation = requestLocation) { feedbackFrom = null }
        } else {
            LineScreen(vm, state, onFeedback = { feedbackFrom = it ?: -1 }, onRequestLocation = requestLocation)
        }
    }
}

@Composable
private fun LineScreen(vm: MetroViewModel, state: UiState, onFeedback: (Int?) -> Unit, onRequestLocation: () -> Unit) {
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
        Header(state, onRetry = vm::load)

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

        InfoCard(Modifier.padding(top = 8.dp)) {
            Row(Modifier.padding(bottom = 6.dp)) {
                Text("↑ Battistini  ↓ Anagnina", Modifier.weight(1f), color = p.mute, fontSize = 12.sp)
                Text("in rosso: fermi in stazione", color = p.mute, fontSize = 12.sp)
            }
            LineView(state.trains, selected, state.location.station) { selected = it }
        }
        Footer()
    }

    selected?.let { i ->
        // Ricalcolati a ogni secondo, così i minuti scalano mentre il pannello è aperto
        val toBattistini = remember(i, now / 1000) { vm.arrivals(i, Direction.TO_BATTISTINI) }
        val toAnagnina = remember(i, now / 1000) { vm.arrivals(i, Direction.TO_ANAGNINA) }
        StationSheet(
            i, toBattistini, toAnagnina,
            onArrived = { dir -> vm.reportArrivedNow(i, dir) },
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
private fun Header(state: UiState, onRetry: () -> Unit) {
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
private fun LineView(trains: List<Train>, selected: Int?, nearby: Int?, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(ROW_H * STATIONS.size)) {
        Canvas(Modifier.width(56.dp).fillMaxHeight()) {
            val top = ROW_H.toPx() / 2; val bottom = size.height - ROW_H.toPx() / 2
            for (x in listOf(14.dp, 42.dp)) {
                drawLine(p.lineSoft, Offset(x.toPx(), top), Offset(x.toPx(), bottom), 4.dp.toPx(), StrokeCap.Round)
            }
        }
        Column {
            STATIONS.forEachIndexed { i, s ->
                val terminus = i == 0 || i == LAST
                val highlight = selected == i || nearby == i
                val nameColor by animateColorAsState(if (highlight) p.lineA else p.ink, tween(250), label = "nome")
                Row(
                    Modifier.fillMaxWidth().height(ROW_H).clip(RoundedCornerShape(12.dp)).clickable { onSelect(i) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.padding(start = 8.dp).size(40.dp, 12.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (terminus) p.lineA else p.surface)
                            .border(2.dp, p.lineA, RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        s.name, fontSize = 17.sp, color = nameColor,
                        fontWeight = if (terminus || highlight) FontWeight.SemiBold else FontWeight.Normal
                    )
                    s.interchange?.let { Text("  $it", color = p.mute, fontSize = 12.sp) }
                    if (nearby == i) Text("  · sei qui", color = p.lineA, fontSize = 12.sp)
                }
            }
        }
        trains.forEach { t -> key(t.id) { TrainMarker(t) } }
    }
}

@Composable
private fun TrainMarker(t: Train) {
    val p = LocalPalette.current
    // Le posizioni arrivano ogni secondo: un'interpolazione lineare di un secondo dà un movimento continuo
    val pos by animateFloatAsState(t.position, tween(1000, easing = LinearEasing), label = "pos")
    val color by animateColorAsState(if (t.stopped) p.lineA else p.ink, tween(400), label = "colore")
    val glyphColor by animateColorAsState(if (t.stopped) Color.White else p.bg, tween(400), label = "freccia")
    // Entrata: il treno compare crescendo invece che di colpo
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
    // Fermo in banchina: leggero "respiro"
    val breathe by rememberInfiniteTransition(label = "sosta").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "respiro"
    )
    val x: Dp = when (t.direction) {
        Direction.TO_BATTISTINI -> 1.dp
        Direction.TO_ANAGNINA -> 29.dp
    }
    val glyph = when (t.direction) { Direction.TO_BATTISTINI -> "▲"; Direction.TO_ANAGNINA -> "▼" }
    Box(
        Modifier.offset(x = x, y = ROW_H * pos + ROW_H / 2 - 9.dp)
            .graphicsLayer { alpha = appear.value; scaleX = appear.value; scaleY = appear.value }
            .scale(if (t.stopped) breathe else 1f)
            .size(26.dp, 18.dp).clip(RoundedCornerShape(9.dp)).background(color),
        contentAlignment = Alignment.Center
    ) { Text(glyph, color = glyphColor, fontSize = 9.sp) }
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
    onArrived: (Direction) -> Int?, onFeedback: () -> Unit, onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.surface) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Text(STATIONS[index].name, color = p.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            DirectionRow(index, Direction.TO_BATTISTINI, toBattistini) { onArrived(Direction.TO_BATTISTINI) }
            DirectionRow(index, Direction.TO_ANAGNINA, toAnagnina) { onArrived(Direction.TO_ANAGNINA) }
            HorizontalDivider(color = p.rule)
            TextButton(onClick = onFeedback, contentPadding = PaddingValues(0.dp)) {
                Text("Treni in anticipo o in ritardo? Segnala", color = p.lineA)
            }
        }
    }
}

@Composable
private fun DirectionRow(station: Int, dir: Direction, arrivals: List<Arrival>, onArrived: () -> Int?) {
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
                result?.let { Text("  $it", color = p.mute, fontSize = 12.sp) }
            }
        }
    }
}
