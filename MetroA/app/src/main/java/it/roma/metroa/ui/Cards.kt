package it.roma.metroa.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.roma.metroa.*

/** Riquadro arrotondato di base: cambia altezza con un'animazione invece che a scatti. */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        color = color,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .animateContentSize(spring(stiffness = 400f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

@Composable
fun AlertCard(a: Alert) {
    val p = LocalPalette.current
    InfoCard(color = p.lineSoft) {
        Text("⚠  Possibili problemi verso ${STATIONS[a.direction.terminus].name}", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val where = a.stations.take(3).joinToString(", ") { STATIONS[it].name } + if (a.stations.size > 3) "…" else ""
        Text(
            "${a.count} segnalazioni di ritardi oltre 5 minuti negli ultimi 15 minuti " +
                "(circa ${(a.medianS + 30) / 60} min, a $where).",
            color = p.ink, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** "Verso Anagnina in ritardo di 2 min secondo 3 segnalazioni. È così?" con conferma o smentita in un tocco. */
@Composable
fun ConfirmCard(dir: Direction, d: DelayEstimate, onAnswer: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    InfoCard {
        Text(
            "${directionLabel(dir)}: treni ${describeOffset(d.medianS)} secondo ${d.count} " +
                if (d.count == 1) "segnalazione" else "segnalazioni",
            color = p.ink, fontSize = 15.sp, lineHeight = 21.sp
        )
        Text("È così anche per te?", color = p.mute, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAnswer(true) },
                colors = ButtonDefaults.buttonColors(containerColor = p.lineA, contentColor = Color.White),
                shape = RoundedCornerShape(50),
            ) { Text("Confermo") }
            OutlinedButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onAnswer(false) },
                shape = RoundedCornerShape(50),
            ) { Text(if (d.medianS > 0) "No, in orario" else "Non è vero", color = p.ink) }
        }
    }
}

/**
 * Riquadro della posizione: chiede il permesso, avvisa se la localizzazione è spenta, mostra la ricerca
 * e poi la stazione (quella in cui si è, con i prossimi treni, o la più vicina con la distanza).
 */
@Composable
fun LocationCard(
    where: Where, arrivals: (Direction) -> List<Arrival>,
    onOpenStation: (Int) -> Unit, onAskPermission: () -> Unit, onOpenSettings: () -> Unit,
) {
    val p = LocalPalette.current
    when (where) {
        Where.Disabled -> Unit // posizione spenta dalle impostazioni dell'app: nessun riquadro
        Where.NoPermission -> InfoCard(onClick = onAskPermission) {
            Text("📍  Trova la stazione più vicina", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Scegli \"Posizione precisa\". Serve solo a questo e non lascia il telefono.",
                color = p.mute, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Where.Off -> InfoCard(onClick = onOpenSettings) {
            Text("📍  Localizzazione disattivata", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Tocca per attivarla nelle impostazioni del telefono.", color = p.mute, fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp))
        }
        Where.Searching -> InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = p.lineA, strokeWidth = 2.dp)
                Text("   Cerco la tua posizione…", color = p.mute, fontSize = 15.sp)
            }
        }
        is Where.Found -> InfoCard(onClick = { onOpenStation(where.station) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (where.atStation) "📍  Sei a ${STATIONS[where.station].name}"
                    else "📍  Stazione più vicina: ${STATIONS[where.station].name}",
                    color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Text(formatDistance(where.distanceM), color = p.mute, fontSize = 13.sp)
            }
            Direction.entries.forEach { d ->
                if (!isEndOfLine(where.station, d)) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(directionLabel(d), color = p.mute, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    ArrivalText(arrivals(d).firstOrNull(), fontSize = 16)
                }
            }
            if (!where.precise) {
                TextButton(onClick = onAskPermission, contentPadding = PaddingValues(0.dp)) {
                    Text("Posizione approssimativa (±${formatDistance(where.accuracyM)}): consenti quella precisa",
                        color = p.lineA, fontSize = 13.sp)
                }
            }
        }
    }
}

fun formatDistance(m: Int) = if (m < 1000) "$m m" else "%.1f km".format(java.util.Locale.ITALY, m / 1000.0)

fun arrivalLabel(a: Arrival?, departure: Boolean = false) = when (a) {
    null -> "—"
    Arrival.AtStation -> if (departure) "In partenza" else "In stazione"
    Arrival.Arriving -> "In arrivo"
    is Arrival.InMinutes -> "${a.minutes} min"
}

/** Testo dell'arrivo che scorre verso l'alto quando cambia ("3 min" → "2 min" → "In arrivo"). */
@Composable
fun ArrivalText(a: Arrival?, fontSize: Int, departure: Boolean = false, weight: FontWeight = FontWeight.SemiBold) {
    val p = LocalPalette.current
    AnimatedContent(
        targetState = arrivalLabel(a, departure),
        transitionSpec = {
            (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
        },
        label = "arrivo",
    ) { text ->
        Text(text, color = if (a == null) p.mute else p.lineA, fontSize = fontSize.sp, fontWeight = weight)
    }
}

/** Stazioni preferite con il prossimo treno per direzione; tocco su una riga per il pannello completo. */
@Composable
fun FavoritesCard(favorites: List<Int>, arrivals: (Int, Direction) -> Arrival?, onOpenStation: (Int) -> Unit) {
    val p = LocalPalette.current
    InfoCard {
        Text("★  Preferite", color = p.mute, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        favorites.forEach { st ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).clickable { onOpenStation(st) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(STATIONS[st].name, color = p.ink, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Direction.entries.filterNot { isEndOfLine(st, it) }.forEach { d ->
                    Text(if (d == Direction.TO_ANAGNINA) "  ↓ " else "  ↑ ", color = p.mute, fontSize = 13.sp)
                    ArrivalText(arrivals(st, d), fontSize = 15, departure = isStartOfLine(st, d))
                }
            }
        }
    }
}

/** Avviso "il treno sta arrivando" impostato, con l'orario e il pulsante per annullarlo. */
@Composable
fun TrainAlertCard(a: TrainAlert, onCancel: () -> Unit) {
    val p = LocalPalette.current
    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    val at = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(ROME).format(fmt) }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("🔔  Avviso alle ${at(a.alarmAtMs)}", color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Treno delle ${at(a.trainAtMs)} a ${STATIONS[a.station].name} verso ${STATIONS[a.direction.terminus].name}",
                    color = p.mute, fontSize = 13.sp)
            }
            TextButton(onClick = onCancel) { Text("Annulla", color = p.lineA) }
        }
    }
}

/** "m:ss" per le durate del viaggio. */
private fun duration(s: Int) = "${s / 60}:${"%02d".format(kotlin.math.abs(s) % 60)}"

/**
 * Viaggio "sono sul treno". In corso: prossima stazione, ritardo del treno e pulsanti per segnare la
 * stazione a mano o scendere. Concluso: quanto ci ha messo, tratto per tratto, rispetto all'orario.
 */
@Composable
fun TripCard(
    t: TripState, nowMs: Long, locationTracked: Boolean,
    onMark: () -> Unit, onFinish: () -> Unit, onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    InfoCard(color = if (t.active) p.lineSoft else p.surface) {
        if (t.active) {
            Text("🚇  Sul treno verso ${STATIONS[t.direction.terminus].name}", color = p.ink, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
            val next = t.nextStation
            // Appena registrata una stazione il treno è ancora lì: "A Flaminio", poi "Prossima: Lepanto"
            val where = if (t.atLastStation(nowMs)) "A ${STATIONS[t.lastStation].name}"
                else next?.let { "Prossima: ${STATIONS[it].name}" } ?: "Capolinea"
            Text(
                where + (t.currentOffsetS?.let { " · treno ${describeOffset(it)}" } ?: ""),
                color = p.ink, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "${t.passages.size} stazioni registrate · " +
                    if (locationTracked) "le riconosco dalla posizione, anche a schermo spento"
                    else "senza posizione: segnale a mano quando il treno si ferma",
                color = p.mute, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (next != null) Button(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onMark() },
                    colors = ButtonDefaults.buttonColors(containerColor = p.lineA, contentColor = Color.White),
                    shape = RoundedCornerShape(50),
                ) { Text("Siamo a ${STATIONS[next].name}", maxLines = 1) }
                OutlinedButton(onClick = onFinish, shape = RoundedCornerShape(50)) { Text("Sono sceso", color = p.ink) }
            }
        } else {
            val sum = summarize(t)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚇  Viaggio concluso", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Chiudi", color = p.lineA) }
            }
            if (sum == null) {
                Text("Troppe poche stazioni registrate per calcolare i tempi.", color = p.mute, fontSize = 14.sp)
            } else {
                Text(
                    "${STATIONS[sum.from].name} → ${STATIONS[sum.to].name}: ${duration(sum.actualS)} min" +
                        (sum.scheduledS?.let { " (orario ${duration(it)})" } ?: ""),
                    color = p.ink, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp),
                )
                sum.legs.forEach { leg ->
                    Row(Modifier.padding(top = 3.dp)) {
                        Text("${STATIONS[leg.from].name} → ${STATIONS[leg.to].name}", color = p.mute, fontSize = 13.sp,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        val slower = leg.scheduledS != null && leg.actualS > leg.scheduledS + 20
                        Text(duration(leg.actualS) + (leg.scheduledS?.let { " / ${duration(it)}" } ?: ""),
                            color = if (slower) p.lineA else p.ink, fontSize = 13.sp)
                    }
                }
                Text("Tempi reali / orario. Grazie: le stazioni registrate sono state condivise e migliorano le stime.",
                    color = p.mute, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Cosa dire all'utente dopo una segnalazione. */
fun outcomeText(o: ReportOutcome): String = when (o) {
    is ReportOutcome.Sent -> "Grazie! Segnalato: treno ${describeOffset(o.offsetS)}."
    ReportOutcome.TooSoon -> "Hai già segnalato questa stazione in questa direzione meno di 30 secondi fa."
    ReportOutcome.NoTrain -> "Nessun treno in orario vicino ad adesso: non inviata."
}

/** Messaggio da leggere una volta (per esempio una segnalazione rifiutata dal server), con "OK" per chiuderlo. */
@Composable
fun NoticeCard(text: String, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    InfoCard(color = p.lineSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚠  $text", color = p.ink, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK", color = p.lineA) }
        }
    }
}
