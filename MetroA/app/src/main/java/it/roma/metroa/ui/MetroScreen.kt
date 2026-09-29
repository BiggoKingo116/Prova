package it.roma.metroa.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import it.roma.metroa.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ROW_H = 46.dp

@Composable
fun MetroScreen(vm: MetroViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    var selected by remember { mutableStateOf<Int?>(null) }
    // null = linea; altrimenti sezione segnalazioni, con la stazione da cui ci si arriva (-1 = nessuna)
    var feedbackFrom by rememberSaveable { mutableStateOf<Int?>(null) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    feedbackFrom?.let { from ->
        BackHandler { feedbackFrom = null }
        FeedbackScreen(vm, state, from.takeIf { it >= 0 }) { feedbackFrom = null }
        return
    }

    Column(
        Modifier.fillMaxSize().background(p.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp)
    ) {
        Header(state, onRetry = vm::load, onFeedback = { feedbackFrom = -1 })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            if (state.delays.values.any { it.count > 0 }) {
                Column(Modifier.padding(top = 10.dp)) {
                    Direction.entries.forEach { d -> DelayLine(d, state.delays[d] ?: NO_DATA) }
                }
            }
            Row(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
                Text("↑", Modifier.width(28.dp), color = p.mute, fontSize = 12.sp)
                Text("↓", Modifier.width(28.dp), color = p.mute, fontSize = 12.sp)
            }
            LineView(state.trains, selected) { selected = it }
            Footer(onFeedback = { feedbackFrom = -1 })
        }
    }

    selected?.let { i ->
        // Ricalcolati a ogni secondo, così i minuti scalano mentre il pannello è aperto
        val toBattistini = remember(i, now / 1000, state.trains) { vm.arrivals(i, Direction.TO_BATTISTINI) }
        val toAnagnina = remember(i, now / 1000, state.trains) { vm.arrivals(i, Direction.TO_ANAGNINA) }
        StationSheet(
            i, toBattistini, toAnagnina,
            onArrived = { dir -> vm.reportArrivedNow(i, dir) },
            onFeedback = { selected = null; feedbackFrom = i },
        ) { selected = null }
    }
}

@Composable
private fun Header(state: UiState, onRetry: () -> Unit, onFeedback: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(p.lineA), contentAlignment = Alignment.Center) {
            Text("A", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Metro A", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("Battistini – Anagnina", color = p.mute, fontSize = 14.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${state.trains.size}", color = p.ink, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Text("treni in linea", color = p.mute, fontSize = 12.sp)
        }
    }
    OutlinedButton(onClick = onFeedback, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text("Segnala anticipo o ritardo", color = p.lineA)
    }
    val progress = state.progress
    when {
        progress != null -> {
            val pct = progress.fraction?.let { " ${(it * 100).toInt()}%" } ?: "…"
            Text("${progress.label}$pct", color = p.mute, fontSize = 13.sp)
            if (progress.fraction != null) {
                LinearProgressIndicator({ progress.fraction }, Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = p.lineA, trackColor = p.rule)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = p.lineA, trackColor = p.rule)
            }
        }
        state.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Orario non disponibile: ${state.error}", Modifier.weight(1f), color = p.lineA, fontSize = 13.sp)
            TextButton(onClick = onRetry) { Text("Riprova", color = p.lineA) }
        }
        state.scheduleDownloadedAtMs != null -> {
            val day = SimpleDateFormat("d/M", Locale.ITALY).format(Date(state.scheduleDownloadedAtMs))
            Text("Posizioni stimate dall'orario ATAC (aggiornato il $day), non in tempo reale",
                color = p.mute, fontSize = 13.sp)
        }
        else -> Text("Carico l'orario…", color = p.mute, fontSize = 13.sp)
    }
    HorizontalDivider(Modifier.padding(top = 10.dp), color = p.rule)
}

@Composable
private fun LineView(trains: List<Train>, selected: Int?, onSelect: (Int) -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(ROW_H * STATIONS.size)) {
        Canvas(Modifier.width(56.dp).fillMaxHeight()) {
            val top = ROW_H.toPx() / 2; val bottom = size.height - ROW_H.toPx() / 2
            for (x in listOf(14.dp, 42.dp)) {
                drawLine(p.lineSoft, Offset(x.toPx(), top), Offset(x.toPx(), bottom), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        Column {
            STATIONS.forEachIndexed { i, s ->
                val terminus = i == 0 || i == LAST
                Row(
                    Modifier.fillMaxWidth().height(ROW_H).clickable { onSelect(i) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.padding(start = 8.dp).size(40.dp, 12.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (terminus) p.lineA else p.surface)
                            .border(2.dp, p.lineA, RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        s.name, fontSize = 17.sp,
                        color = if (selected == i) p.lineA else p.ink,
                        fontWeight = if (terminus || selected == i) FontWeight.SemiBold else FontWeight.Normal
                    )
                    s.interchange?.let { Text("  $it", color = p.mute, fontSize = 12.sp) }
                }
            }
        }
        trains.forEach { t -> key(t.id) { TrainMarker(t) } }
    }
}

@Composable
private fun TrainMarker(t: Train) {
    val p = LocalPalette.current
    val pos by animateFloatAsState(t.position, tween(1500), label = "pos")
    val x: Dp = when (t.direction) {
        Direction.TO_BATTISTINI -> 1.dp
        Direction.TO_ANAGNINA -> 29.dp
    }
    val glyph = when (t.direction) { Direction.TO_BATTISTINI -> "▲"; Direction.TO_ANAGNINA -> "▼" }
    // Fermo in banchina: rosso linea A; in viaggio: scuro
    Box(
        Modifier.offset(x = x, y = ROW_H * pos + ROW_H / 2 - 9.dp)
            .size(26.dp, 18.dp).clip(RoundedCornerShape(9.dp)).background(if (t.stopped) p.lineA else p.ink),
        contentAlignment = Alignment.Center
    ) { Text(glyph, color = if (t.stopped) androidx.compose.ui.graphics.Color.White else p.bg, fontSize = 9.sp) }
}

@Composable
private fun Footer(onFeedback: () -> Unit) {
    val p = LocalPalette.current
    Text(
        "Colonna sinistra: verso Battistini. Destra: verso Anagnina. In rosso i treni fermi in stazione. " +
            "Tocca una stazione per i prossimi passaggi.\n\n" +
            "Il feed in tempo reale di Roma Mobilità non include la metropolitana: posizioni e arrivi sono " +
            "calcolati dall'orario programmato (GTFS di Roma Mobilità, dati ATAC) e corretti con le segnalazioni " +
            "degli utenti. Quelle degli ultimi 20 minuti contano di più; altrimenti si usa la media della stessa fascia oraria.",
        color = p.mute, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 24.dp)
    )
    TextButton(onClick = onFeedback, contentPadding = PaddingValues(0.dp)) { Text("Apri le segnalazioni", color = p.lineA) }
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
            DirectionRow("Verso Battistini", terminus = index == 0, departure = index == LAST, toBattistini) {
                onArrived(Direction.TO_BATTISTINI)
            }
            DirectionRow("Verso Anagnina", terminus = index == LAST, departure = index == 0, toAnagnina) {
                onArrived(Direction.TO_ANAGNINA)
            }
            HorizontalDivider(color = p.rule)
            TextButton(onClick = onFeedback, contentPadding = PaddingValues(0.dp)) {
                Text("Treni in anticipo o in ritardo? Segnala", color = p.lineA)
            }
        }
    }
}

private fun label(a: Arrival) = when (a) {
    Arrival.AtStation -> "In stazione"
    Arrival.Arriving -> "In arrivo"
    is Arrival.InMinutes -> "${a.minutes} min"
}

/** [departure]: capolinea di partenza, dove si segnala la partenza invece dell'arrivo. */
@Composable
private fun DirectionRow(label: String, terminus: Boolean, departure: Boolean, arrivals: List<Arrival>, onArrived: () -> Int?) {
    val p = LocalPalette.current
    var result by remember { mutableStateOf<String?>(null) }
    HorizontalDivider(color = p.rule)
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(label, color = p.mute, fontSize = 14.sp)
        if (terminus) {
            Text("Capolinea", color = p.mute, fontSize = 16.sp)
        } else {
            if (arrivals.isEmpty()) {
                Text("Nessun treno in programma", color = p.mute, fontSize = 16.sp)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    val first = arrivals.first()
                    Text(
                        if (first == Arrival.AtStation && departure) "In partenza" else label(first),
                        color = p.lineA, fontSize = 30.sp, fontWeight = FontWeight.SemiBold
                    )
                    if (arrivals.size > 1) {
                        Text("   poi ${arrivals.drop(1).joinToString(", ") { label(it) }}", color = p.ink, fontSize = 17.sp,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
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
