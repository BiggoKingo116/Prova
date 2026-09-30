package it.roma.metroa.dev

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.roma.metroa.*
import it.roma.metroa.ui.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY_HM = DateTimeFormatter.ofPattern("d/M HH:mm")
private fun fmt(ms: Long, f: DateTimeFormatter = HM) = Instant.ofEpochMilli(ms).atZone(ROME).format(f)

/** Numeri dei convogli: lettere, cifre e trattini, fino a 12 caratteri (lo stesso controllo del database). */
val TRAIN_NUMBER = Regex("^[A-Za-z0-9-]{1,12}$")

private enum class Tab(val label: String) { TRAINS("Treni"), GPS("GPS"), DATA("Dati") }

@Composable
fun DevScreen(vm: DevViewModel, openMetroA: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().background(p.bg).windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MetroA Dev", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(s.email ?: "non connesso", color = p.mute, fontSize = 12.sp)
            }
            TextButton(onClick = openMetroA) { Text("MetroA", color = p.lineA) }
            if (s.email != null) TextButton(onClick = vm::logout) { Text("Esci", color = p.mute) }
        }
        if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.lineA, trackColor = p.rule)
        s.message?.let { m ->
            Box(Modifier.padding(horizontal = 16.dp)) { NoticeCard(m, vm::dismissMessage) }
        }
        when {
            s.email == null -> LoginPane(vm)
            s.isDeveloper == false -> NotDeveloperPane()
            s.isDeveloper == null -> Text("Controllo l'account…", color = p.mute, modifier = Modifier.padding(16.dp))
            else -> DeveloperTabs(vm, s)
        }
    }
}

@Composable
private fun LoginPane(vm: DevViewModel) {
    val p = LocalPalette.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp)) {
        Text("Accesso sviluppatore", color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Con l'account creato nella dashboard di Supabase (Authentication → Users).",
            color = p.mute, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(
            onClick = { vm.login(email, password) }, enabled = email.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = p.lineA, contentColor = Color.White),
        ) { Text("Entra") }
    }
}

@Composable
private fun NotDeveloperPane() {
    val p = LocalPalette.current
    InfoCard(Modifier.padding(16.dp)) {
        Text("Questo account non è uno sviluppatore.", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Chi gestisce il database deve aggiungerlo in SQL Editor:\n" +
            "insert into public.developers (user_id) select id from auth.users where email = '…';",
            color = p.mute, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun DeveloperTabs(vm: DevViewModel, s: DevState) {
    var tab by rememberSaveable { mutableStateOf(Tab.TRAINS) }
    val p = LocalPalette.current
    TabRow(selectedTabIndex = tab.ordinal, containerColor = p.bg, contentColor = p.lineA) {
        Tab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
    }
    Box(Modifier.fillMaxSize()) {
        when (tab) {
            Tab.TRAINS -> TrainsTab(vm, s)
            Tab.GPS -> GpsTab(vm, s)
            Tab.DATA -> DataTab(vm, s)
        }
    }
}

// ---------------- Numeri dei treni ----------------

@Composable
private fun TrainsTab(vm: DevViewModel, s: DevState) {
    val p = LocalPalette.current
    var station by rememberSaveable { mutableIntStateOf(11) }
    var dir by rememberSaveable { mutableStateOf(Direction.TO_ANAGNINA) }
    var number by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var chosenTrip by rememberSaveable { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }
    val trains = remember(station, dir, tick, s.scheduleReady) { vm.trainsAt(station, dir) }
    // Di base il treno più vicino ad adesso
    val chosen = trains.firstOrNull { it.tripId == chosenTrip } ?: trains.firstOrNull()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Dove hai visto il treno", color = p.mute, fontSize = 13.sp)
        StationPicker(station) { station = it; chosenTrip = null }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Direction.entries.filterNot { isEndOfLine(station, it) }.forEach { d ->
                FilterChip(selected = dir == d, onClick = { dir = d; chosenTrip = null }, label = { Text(directionLabel(d)) },
                    colors = chipColors())
            }
        }

        Text("Quale corsa dell'orario", color = p.mute, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        when {
            s.scheduleProgress != null -> Text("${s.scheduleProgress.label}…", color = p.mute, fontSize = 14.sp)
            trains.isEmpty() -> Text("Nessuna corsa in orario vicino ad adesso: il numero si salva senza corsa.",
                color = p.mute, fontSize = 14.sp)
            else -> trains.take(6).forEach { t ->
                val now = System.currentTimeMillis()
                val min = ((t.atStationMs - now) / 60_000).toInt()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen?.tripId == t.tripId, onClick = { chosenTrip = t.tripId },
                        colors = RadioButtonDefaults.colors(selectedColor = p.lineA))
                    Column {
                        Text(t.label, color = p.ink, fontSize = 15.sp)
                        Text("a ${STATIONS[station].name} alle ${fmt(t.atStationMs)} " +
                            if (min >= 0) "(tra $min min)" else "(${-min} min fa)", color = p.mute, fontSize = 12.sp)
                    }
                }
            }
        }

        OutlinedTextField(number, { number = it.take(12) }, label = { Text("Numero del treno (es. 312)") }, singleLine = true,
            isError = number.isNotEmpty() && !TRAIN_NUMBER.matches(number.trim()),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        OutlinedTextField(note, { note = it.take(200) }, label = { Text("Nota (facoltativa)") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(
            onClick = { vm.saveTrainNumber(station, dir, chosen, number, note); number = ""; note = "" },
            enabled = TRAIN_NUMBER.matches(number.trim()) && !s.busy,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = p.lineA, contentColor = Color.White),
        ) { Text("Salva numero") }

        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Numeri registrati (${s.trainNumbers.size})", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = vm::loadTrainNumbers) { Text("Aggiorna", color = p.lineA) }
        }
        // Quante volte è stato visto ogni convoglio
        val counts = s.trainNumbers.groupingBy { it.number }.eachCount().entries.sortedByDescending { it.value }
        if (counts.isNotEmpty()) {
            Text(counts.take(12).joinToString("  ") { "${it.key}×${it.value}" }, color = p.mute, fontSize = 13.sp)
        }
        s.trainNumbers.forEach { t ->
            var confirm by remember { mutableStateOf(false) }
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Treno ${t.number}", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${fmt(t.timeMs, DAY_HM)} · ${STATIONS[t.station].name} → ${STATIONS[t.direction.terminus].name}",
                            color = p.mute, fontSize = 13.sp)
                        t.label?.let { Text(it, color = p.mute, fontSize = 13.sp) }
                        t.note?.let { Text("“$it”", color = p.ink, fontSize = 13.sp) }
                    }
                    TextButton(onClick = { confirm = true }) { Text("Elimina", color = p.mute) }
                }
            }
            if (confirm) ConfirmDialog("Eliminare il treno ${t.number} del ${fmt(t.timeMs, DAY_HM)}?", { confirm = false }) {
                vm.deleteTrainNumber(t.id)
            }
        }
    }
}

// ---------------- Diagnostica GPS ----------------

@Composable
private fun GpsTab(vm: DevViewModel, s: DevState) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hasLocationPermission(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = hasLocationPermission(context)
    }
    // La posizione si segue solo mentre questa scheda è aperta
    DisposableEffect(allowed) {
        if (allowed) vm.startGps()
        onDispose { vm.stopGps() }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (!allowed) {
            Button(onClick = { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                shape = RoundedCornerShape(50)) { Text("Consenti la posizione") }
        } else {
            Text("Fonti: " + s.providers.entries.joinToString("  ") { "${it.key} ${if (it.value) "✓" else "✗"}" } +
                if (!hasPreciseLocation(context)) "  · solo posizione approssimativa" else "",
                color = p.mute, fontSize = 13.sp)

            val best = s.gps.firstOrNull { it.accepted }
            InfoCard(Modifier.padding(top = 8.dp)) {
                if (best == null) {
                    Text("In attesa della prima posizione…", color = p.mute)
                } else {
                    val w = best.where
                    Text(if (w.atStation) "Sei a ${STATIONS[w.station].name}" else "Più vicina: ${STATIONS[w.station].name}",
                        color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${w.distanceM} m · ±${w.accuracyM} m · ${best.provider} · ${(now - best.fixMs) / 1000} s fa",
                        color = p.mute, fontSize = 13.sp)
                    Text(
                        when {
                            w.atStation -> "Abbastanza vicina e precisa per \"sei a\"."
                            w.accuracyM > PRECISE_ENOUGH_M -> "Posizione troppo vaga per dire \"sei a\" (oltre $PRECISE_ENOUGH_M m)."
                            else -> "Troppo lontana dalla stazione per dire \"sei a\"."
                        }, color = p.ink, fontSize = 13.sp,
                    )
                    Text("%.5f, %.5f".format(best.lat, best.lon), color = p.mute, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    // Le tre stazioni più vicine, per vedere quanto è netta la scelta
                    val near = STATIONS.indices.sortedBy { distanceToStation(best.lat, best.lon, it) }.take(3)
                    Text(near.joinToString("  ·  ") { "${STATIONS[it].name} ${distanceToStation(best.lat, best.lon, it).toInt()} m" },
                        color = p.mute, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            Text("Ultime posizioni (✓ usata, ✗ scartata perché peggiore della precedente)", color = p.mute, fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            s.gps.forEach { f ->
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text("${fmt(f.receivedMs, HMS)} ${if (f.accepted) "✓" else "✗"} ${f.provider.padEnd(7)} ±${f.accuracyM.toInt()}m " +
                        "${STATIONS[f.where.station].name} ${f.where.distanceM}m${if (f.where.atStation) " ●" else ""}",
                        color = if (f.accepted) p.ink else p.mute, fontSize = 12.sp, fontFamily = FontFamily.Monospace, softWrap = false)
                }
            }
        }
    }
}

// ---------------- Dati del database ----------------

private enum class DataView(val label: String) { REPORTS("Segnalazioni"), TRIPS("Viaggi"), SEGMENTS("Tratte"), POINTS("Percorsi GPS") }

@Composable
private fun DataTab(vm: DevViewModel, s: DevState) {
    val p = LocalPalette.current
    var view by rememberSaveable { mutableStateOf(DataView.REPORTS) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DataView.entries.forEach { v -> FilterChip(selected = view == v, onClick = { view = v }, label = { Text(v.label) }, colors = chipColors()) }
            TextButton(onClick = vm::loadData) { Text("Aggiorna", color = p.lineA) }
        }
        when (view) {
            DataView.REPORTS -> ReportsList(vm, s.reports)
            DataView.TRIPS -> TripsList(vm, s.trips)
            DataView.SEGMENTS -> SegmentsList(s.segments)
            DataView.POINTS -> PointsList(vm, s.pointTrips)
        }
    }
}

@Composable
private fun ReportsList(vm: DevViewModel, reports: List<ReportRow>) {
    val p = LocalPalette.current
    Text("Ultime ${reports.size} segnalazioni", color = p.mute, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    reports.forEach { r ->
        var menu by remember { mutableStateOf(false) }
        var confirmOne by remember { mutableStateOf(false) }
        var confirmDevice by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${fmt(r.timeMs, DAY_HM)}  ${STATIONS[r.station].name} → ${STATIONS[r.direction.terminus].name}",
                    color = p.ink, fontSize = 14.sp)
                Text("${r.source} · ${formatOffsetShort(r.offsetS)} · tel ${r.deviceId?.take(8) ?: "?"}" +
                    (r.tripId?.let { " · viaggio ${it.take(8)}" } ?: ""), color = p.mute, fontSize = 12.sp)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋯", color = p.mute, fontSize = 18.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Elimina questa") }, onClick = { menu = false; confirmOne = true })
                    if (r.deviceId != null) DropdownMenuItem(text = { Text("Elimina tutto di questo telefono") },
                        onClick = { menu = false; confirmDevice = true })
                }
            }
        }
        if (confirmOne) ConfirmDialog("Eliminare questa segnalazione?", { confirmOne = false }) { vm.deleteReport(r.id) }
        if (confirmDevice) ConfirmDialog("Eliminare TUTTE le segnalazioni e i percorsi del telefono ${r.deviceId?.take(8)}?",
            { confirmDevice = false }) { r.deviceId?.let(vm::deleteDevice) }
    }
}

@Composable
private fun TripsList(vm: DevViewModel, trips: List<TripRow>) {
    val p = LocalPalette.current
    Text("Viaggi \"sono sul treno\" fra le ultime segnalazioni", color = p.mute, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    trips.forEach { t ->
        var confirm by remember { mutableStateOf(false) }
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${STATIONS[t.stations.first()].name} → ${STATIONS[t.stations.last()].name}", color = p.ink,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${fmt(t.firstMs, DAY_HM)}–${fmt(t.lastMs)} · ${t.stations.size} stazioni · ${(t.lastMs - t.firstMs) / 60_000} min · " +
                        "viaggio ${t.tripId.take(8)}", color = p.mute, fontSize = 12.sp)
                }
                TextButton(onClick = { confirm = true }) { Text("Elimina", color = p.mute) }
            }
        }
        if (confirm) ConfirmDialog("Eliminare il viaggio (stazioni e percorso GPS)?", { confirm = false }) { vm.deleteTrip(t.tripId) }
    }
}

@Composable
private fun SegmentsList(segments: List<SegmentRow>) {
    val p = LocalPalette.current
    Text("Tempo medio per tratta negli ultimi 60 giorni, dai viaggi", color = p.mute, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    if (segments.isEmpty()) Text("Nessun viaggio ancora.", color = p.mute)
    segments.forEach { g ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("${STATIONS[g.from].name} → ${STATIONS[g.to].name}", color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${g.avgS / 60}:${"%02d".format(g.avgS % 60)}  (${g.trips})", color = p.ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun PointsList(vm: DevViewModel, trips: List<PointTrip>) {
    val p = LocalPalette.current
    Text("Percorsi GPS condivisi (solo da chi l'ha scelto)", color = p.mute, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
    if (trips.isEmpty()) Text("Nessun percorso ancora.", color = p.mute)
    trips.forEach { t ->
        var confirm by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${fmt(t.firstMs, DAY_HM)}–${fmt(t.lastMs)} · ${t.count} punti", color = p.ink, fontSize = 14.sp)
                Text("viaggio ${t.tripId.take(8)} · tel ${t.deviceId?.take(8) ?: "?"}", color = p.mute, fontSize = 12.sp)
            }
            TextButton(onClick = { confirm = true }) { Text("Elimina", color = p.mute) }
        }
        if (confirm) ConfirmDialog("Eliminare il viaggio (stazioni e percorso GPS)?", { confirm = false }) { vm.deleteTrip(t.tripId) }
    }
}

@Composable
private fun ConfirmDialog(text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Elimina", color = LocalPalette.current.lineA) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
