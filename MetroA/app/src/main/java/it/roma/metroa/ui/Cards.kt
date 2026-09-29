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
