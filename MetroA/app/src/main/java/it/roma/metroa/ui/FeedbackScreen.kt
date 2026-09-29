package it.roma.metroa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.roma.metroa.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Timing(val label: String) { EARLY("In anticipo"), ON_TIME("In orario"), LATE("In ritardo") }

/** Sezione segnalazioni: dire se i treni sono in anticipo o in ritardo, e vedere cosa segnalano gli altri. */
@Composable
fun FeedbackScreen(
    vm: MetroViewModel, state: UiState, initialStation: Int?, onRequestLocation: () -> Unit, onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    // Stazione di partenza: quella da cui si arriva, poi quella vicina, poi Termini
    var station by rememberSaveable { mutableIntStateOf(initialStation ?: state.location.station ?: 11) }
    // Se la posizione arriva dopo, e l'utente non ha ancora scelto, si passa alla stazione vicina
    var picked by rememberSaveable { mutableStateOf(initialStation != null) }
    LaunchedEffect(state.location.station) {
        val n = state.location.station
        if (!picked && n != null) station = n
    }
    var dir by rememberSaveable { mutableStateOf(Direction.TO_ANAGNINA) }
    var timing by rememberSaveable { mutableStateOf<Timing?>(null) }
    var minutes by rememberSaveable { mutableIntStateOf(2) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(p.bg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← Linea", color = p.lineA) }
        }
        Text("Segnala", color = p.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Dì se i treni sono in anticipo o in ritardo rispetto all'orario. Le segnalazioni sono condivise " +
                "con tutti e correggono le stime dell'app.",
            color = p.mute, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp)
        )

        SectionTitle("Situazione adesso")
        state.alerts.forEach { a -> key("alert", a.direction) { Appearing { AlertCard(a) } } }
        InfoCard {
            Direction.entries.forEach { d -> DelayLine(d, state.delays[d] ?: NO_DATA) }
        }
        Direction.entries.forEach { d ->
            Appearing(visible = d in state.askConfirm) {
                ConfirmCard(d, state.delays[d] ?: NO_DATA) { yes ->
                    vm.answerLive(d, yes)
                    message = if (yes) "Grazie per la conferma!" else "Grazie! Segnato: in orario."
                }
            }
        }

        SectionTitle("Dove sei")
        StationPicker(station) { station = it; picked = true; message = null }
        val nearby = state.location.station
        when {
            state.location == Where.NoPermission -> TextButton(onClick = onRequestLocation, contentPadding = PaddingValues(0.dp)) {
                Text("📍 Usa la mia posizione", color = p.lineA)
            }
            nearby != null && nearby != station -> TextButton(
                onClick = { station = nearby; picked = true; message = null },
                contentPadding = PaddingValues(0.dp),
            ) { Text("📍 Sei a ${STATIONS[nearby].name}? Usala", color = p.lineA) }
            nearby != null -> Text("📍 Stazione più vicina a te", color = p.mute, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Direction.entries.forEach { d ->
                FilterChip(
                    selected = dir == d, onClick = { dir = d; message = null },
                    label = { Text(directionLabel(d)) },
                    colors = chipColors(),
                )
            }
        }
        val terminus = isEndOfLine(station, dir)
        if (terminus) {
            Text("${STATIONS[station].name} è il capolinea in questa direzione: scegli l'altra.",
                color = p.lineA, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }

        SectionTitle("Il treno è arrivato adesso?")
        Text("Premi appena il treno entra in stazione: l'app calcola da sola quanto era avanti o indietro.",
            color = p.mute, fontSize = 13.sp, lineHeight = 18.sp)
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                message = vm.reportArrivedNow(station, dir)
                    ?.let { "Grazie! Treno ${describeOffset(it)} rispetto all'orario." }
                    ?: "Nessun treno in orario in questi 10 minuti: usa la scelta qui sotto."
            },
            enabled = !terminus,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.lineA, contentColor = Color.White),
        ) { Text(if (isStartOfLine(station, dir)) "Treno partito adesso" else "Treno arrivato adesso") }

        SectionTitle("Oppure dimmi com'è")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Timing.entries.forEach { t ->
                FilterChip(selected = timing == t, onClick = { timing = t; message = null },
                    label = { Text(t.label) }, colors = chipColors())
            }
        }
        if (timing == Timing.EARLY || timing == Timing.LATE) {
            Text("Di quanti minuti?", color = p.mute, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 5, 10).forEach { m ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m") }, colors = chipColors())
                }
            }
        }
        OutlinedButton(
            onClick = {
                val t = timing ?: return@OutlinedButton
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val offset = when (t) { Timing.EARLY -> -minutes * 60; Timing.ON_TIME -> 0; Timing.LATE -> minutes * 60 }
                vm.reportManual(station, dir, offset)
                message = "Grazie! Segnalato: ${describeOffset(offset)}."
                timing = null
            },
            enabled = timing != null && !terminus,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) { Text("Invia segnalazione", color = if (timing != null && !terminus) p.lineA else p.mute) }

        Appearing(visible = message != null) {
            InfoCard(Modifier.padding(top = 6.dp), color = p.lineSoft) {
                Text(message.orEmpty(), color = p.ink, fontSize = 14.sp)
            }
        }

        SectionTitle("Ultime segnalazioni (20 minuti)")
        if (state.recentReports.isEmpty()) {
            Text("Nessuna segnalazione recente.", color = p.mute, fontSize = 14.sp)
        } else {
            val fmt = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }
            state.recentReports.take(20).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(fmt.format(Date(r.timeMs)), color = p.mute, fontSize = 14.sp, modifier = Modifier.width(52.dp))
                    Text("${STATIONS[r.station].name} → ${STATIONS[r.direction.terminus].name}",
                        color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    val kind = when (r.source) { ReportSource.CONFIRM -> "✓ "; ReportSource.DENY -> "✗ "; else -> "" }
                    Text(kind + formatOffsetShort(r.offsetS), color = if (r.offsetS > 60) p.lineA else p.ink, fontSize = 14.sp)
                }
            }
        }

        SyncLine(state.sync, onRetry = vm::sync)
    }
}

@Composable
private fun SectionTitle(text: String) {
    val p = LocalPalette.current
    HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 10.dp), color = p.rule)
    Text(text, color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = LocalPalette.current.lineA,
    selectedLabelColor = Color.White,
    labelColor = LocalPalette.current.ink,
)

@Composable
fun DelayLine(dir: Direction, d: DelayEstimate) {
    val p = LocalPalette.current
    val text = when {
        d.count == 0 -> "nessuna segnalazione"
        d.live -> "${describeOffset(d.medianS)} (${d.count} segnalazioni negli ultimi 20 min)"
        else -> "di solito ${describeOffset(d.medianS)} a quest'ora (${d.count} segnalazioni)"
    }
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(directionLabel(dir) + ": ", color = p.mute, fontSize = 14.sp)
        Text(text, color = if (d.live && d.medianS > 60) p.lineA else p.ink, fontSize = 14.sp,
            fontWeight = if (d.live) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun StationPicker(station: Int, onPick: (Int) -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, p.rule, RoundedCornerShape(8.dp))
                .clickable { open = true }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(STATIONS[station].name, color = p.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text("▾", color = p.mute)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            STATIONS.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { onPick(i); open = false })
            }
        }
    }
}

@Composable
private fun SyncLine(s: SyncStatus, onRetry: () -> Unit) {
    val p = LocalPalette.current
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }
    val text = when {
        !s.configured -> "Server delle segnalazioni non configurato: per ora restano su questo telefono."
        s.error != null -> "Non collegato al server (${s.error})" + if (s.pending > 0) ": ${s.pending} da inviare" else ""
        s.lastSyncMs != null -> "Segnalazioni condivise, aggiornate alle ${fmt.format(Date(s.lastSyncMs))}" +
            if (s.pending > 0) " · ${s.pending} da inviare" else ""
        else -> "Collegamento al server…"
    }
    HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 8.dp), color = p.rule)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = p.mute, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (s.configured && s.error != null) TextButton(onClick = onRetry) { Text("Riprova", color = p.lineA) }
    }
}

fun directionLabel(d: Direction) = "Verso ${STATIONS[d.terminus].name}"

/** "in ritardo di 2 min", "in anticipo di 1 min", "in orario" (sotto i 30 secondi). */
fun describeOffset(s: Int): String {
    val m = (kotlin.math.abs(s) + 30) / 60
    return when {
        m == 0 -> "in orario"
        s > 0 -> "in ritardo di $m min"
        else -> "in anticipo di $m min"
    }
}

fun formatOffsetShort(s: Int): String {
    val m = (kotlin.math.abs(s) + 30) / 60
    return if (m == 0) "in orario" else "${if (s > 0) "+" else "−"}$m min"
}
