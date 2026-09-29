package it.roma.metroa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.roma.metroa.*
import kotlinx.coroutines.delay

/** Tratto di linea usato nell'anteprima: Barberini → Manzoni. */
private val PREVIEW_RANGE = 9..13
private val PREVIEW_HEIGHT = 170.dp

@Composable
fun SettingsScreen(vm: MetroViewModel, state: UiState, s: AppSettings, onBack: () -> Unit) {
    val p = LocalPalette.current
    val set = vm::updateSettings

    Column(Modifier.fillMaxSize().background(p.bg).windowInsetsPadding(WindowInsets.systemBars)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(top = 12.dp)) {
                Text("← Linea", color = p.lineA)
            }
            Text("Impostazioni", color = p.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            // L'anteprima resta ferma in alto mentre si scorrono le opzioni, ma ad altezza fissa: con la
            // linea molto allungata verrebbe altrimenti a occupare tutto lo schermo e la lista non scorrerebbe
            InfoCard(Modifier.padding(top = 8.dp)) {
                Text("Anteprima", color = p.mute, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                Box(Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).clipToBounds()) {
                    Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true)) { Preview(s, state.segmentSeconds) }
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)
        ) {
            Section("Linea")
            InfoCard {
                Text("Distanza fra le stazioni", color = p.ink, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = s.stationSpacing.toFloat(),
                        onValueChange = { v -> set { it.copy(stationSpacing = v.toInt()) } },
                        valueRange = AppSettings.MIN_SPACING.toFloat()..AppSettings.MAX_SPACING.toFloat(),
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = p.lineA, activeTrackColor = p.lineA),
                    )
                    Text(
                        when {
                            s.stationSpacing < 44 -> "Compatta"
                            s.stationSpacing < 64 -> "Normale"
                            else -> "Larga"
                        },
                        color = p.mute, fontSize = 13.sp, modifier = Modifier.width(72.dp).padding(start = 8.dp),
                    )
                }
                Toggle("Distanze reali", "Le tratte più lunghe da percorrere sono disegnate più lunghe",
                    s.proportionalSpacing) { v -> set { it.copy(proportionalSpacing = v) } }
                Toggle("Capovolgi la linea", "Anagnina in alto, Battistini in basso", s.reversed) { v ->
                    set { it.copy(reversed = v) }
                }
                Choice("Minuti accanto alle stazioni", NextTrainLabels.entries, s.nextTrainLabels, { it.label }) { v ->
                    set { it.copy(nextTrainLabels = v) }
                }
                Toggle("Linea orizzontale col telefono girato", "Stazioni da sinistra a destra, nomi in diagonale",
                    s.horizontalInLandscape) { v -> set { it.copy(horizontalInLandscape = v) } }
            }

            Section("Stazioni preferite")
            InfoCard {
                if (s.favorites.isEmpty()) {
                    Text("Nessuna. Tocca una stazione sulla linea e poi ☆ Preferita: comparirà in cima con i " +
                        "prossimi treni, e la prima anche nel widget della schermata home.",
                        color = p.mute, fontSize = 14.sp, lineHeight = 19.sp)
                }
                s.favorites.forEachIndexed { n, st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("★  ${STATIONS[st].name}", color = p.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (n == 0) Text("widget", color = p.mute, fontSize = 12.sp)
                        // Portare in cima: diventa la stazione del widget
                        if (n > 0) TextButton(onClick = { set { it.copy(favorites = listOf(st) + (it.favorites - st)) } }) {
                            Text("In cima", color = p.lineA)
                        }
                        TextButton(onClick = { set { it.toggleFavorite(st) } }) { Text("Togli", color = p.mute) }
                    }
                }
            }

            Section("Avvisi")
            InfoCard {
                Choice("Avvisami prima dell'arrivo di", AppSettings.ALERT_LEADS, s.alertLeadMinutes, { "$it min" }) { v ->
                    set { it.copy(alertLeadMinutes = v) }
                }
                Text("Nel pannello di una stazione tocca 🔔 Avvisami: arriva una notifica prima del prossimo treno.",
                    color = p.mute, fontSize = 13.sp, lineHeight = 18.sp)
            }

            Section("Treni")
            InfoCard {
                Choice("Forma", TrainStyle.entries, s.trainStyle, { it.label }) { v -> set { it.copy(trainStyle = v) } }
                Choice("Dimensione", TrainSize.entries, s.trainSize, { it.label }) { v -> set { it.copy(trainSize = v) } }
                Toggle("Movimento fluido", "Se spento i treni si spostano a scatti, ogni secondo", s.smoothMotion) { v ->
                    set { it.copy(smoothMotion = v) }
                }
                Toggle("Treni fermi che pulsano", "Evidenzia i treni fermi in banchina", s.pulseStopped) { v ->
                    set { it.copy(pulseStopped = v) }
                }
            }

            Section("Generale")
            InfoCard {
                Choice("Tema", ThemeMode.entries, s.theme, { it.label }) { v -> set { it.copy(theme = v) } }
                Toggle("Schermo sempre acceso", "Mentre l'app è aperta, utile in banchina", s.keepScreenOn) { v ->
                    set { it.copy(keepScreenOn = v) }
                }
                Toggle("Usa la posizione", "Per mostrare la stazione più vicina; non lascia il telefono", s.useLocation) { v ->
                    set { it.copy(useLocation = v) }
                }
            }

            OutlinedButton(
                // Le stazioni preferite restano: sono una scelta, non un'impostazione di aspetto
                onClick = { set { AppSettings(favorites = it.favorites) } },
                enabled = s != AppSettings(favorites = s.favorites),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                shape = RoundedCornerShape(50),
            ) {
                Text("Ripristina le impostazioni predefinite",
                    color = if (s != AppSettings(favorites = s.favorites)) p.lineA else p.mute)
            }
        }
    }
}

/** Tratto di linea con treni simulati che si muovono come quelli veri, per vedere subito l'effetto delle scelte. */
@Composable
private fun Preview(s: AppSettings, segmentSeconds: FloatArray?) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val span = (PREVIEW_RANGE.last - PREVIEW_RANGE.first).toFloat()
    // Un treno per senso che percorre il tratto in 20 secondi, e uno fermo a Repubblica per metà del tempo
    val f = (tick % 20) / 20f
    val trains = listOf(
        Train("a${tick / 20}", PREVIEW_RANGE.first + span * f, Direction.TO_ANAGNINA),
        Train("b${(tick + 10) / 20}", PREVIEW_RANGE.last - span * ((tick + 10) % 20) / 20f, Direction.TO_BATTISTINI),
        Train("c", 10f, Direction.TO_ANAGNINA, stopped = true).takeIf { tick % 10 < 5 },
    ).filterNotNull()
    val next = Direction.entries.associateWith { d ->
        List(STATIONS.size) { i -> if (i == 10 && d == Direction.TO_ANAGNINA) Arrival.AtStation else Arrival.InMinutes(1 + (i * 3 + tick / 20) % 7) }
    }
    LineView(trains, s, segmentSeconds, selected = null, nearby = null, nextTrains = next, range = PREVIEW_RANGE) {}
}

@Composable
private fun Section(title: String) {
    Text(title, color = LocalPalette.current.mute, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp, start = 4.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 16.sp)
            Text(subtitle, color = p.mute, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = p.lineA, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, color = p.ink, fontSize = 16.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, colors = chipColors())
            }
        }
    }
}
