package it.roma.metroa.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.roma.metroa.*

/** Altezza della riga di una stazione (nome e zona toccabile), centrata sulla sua posizione. */
private const val LABEL_H = 40f
/** Centro orizzontale delle due colonne: verso Battistini e verso Anagnina. */
private const val COL_BATTISTINI = 14f
private const val COL_ANAGNINA = 42f

/**
 * La linea con le stazioni e i treni, disegnata secondo le [settings]: distanza fra le stazioni (fissa o
 * proporzionale al tempo di viaggio), verso, stile e dimensione dei treni, minuti accanto ai nomi.
 * [range] limita le stazioni mostrate (l'anteprima nelle impostazioni ne usa poche).
 */
@Composable
fun LineView(
    trains: List<Train>,
    settings: AppSettings,
    segmentSeconds: FloatArray?,
    selected: Int?,
    nearby: Int?,
    nextTrains: Map<Direction, List<Arrival?>>,
    range: IntRange = 0..LAST,
    onSelect: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val centers = remember(settings, segmentSeconds) { stationCenters(settings, segmentSeconds) }
    val top = range.minOf { centers[it] }
    val bottom = range.maxOf { centers[it] }
    val y = { c: Float -> (c - top + LABEL_H / 2).dp }

    Box(Modifier.fillMaxWidth().height((bottom - top + LABEL_H).dp)) {
        Canvas(Modifier.width(56.dp).fillMaxHeight()) {
            for (x in listOf(COL_BATTISTINI, COL_ANAGNINA)) {
                drawLine(p.lineSoft, Offset(x.dp.toPx(), y(top).toPx()), Offset(x.dp.toPx(), y(bottom).toPx()),
                    4.dp.toPx(), StrokeCap.Round)
            }
        }
        for (i in range) {
            val s = STATIONS[i]
            val terminus = i == 0 || i == LAST
            val highlight = selected == i || nearby == i
            val nameColor by animateColorAsState(if (highlight) p.lineA else p.ink, tween(250), label = "nome")
            Row(
                Modifier.offset(y = y(centers[i]) - (LABEL_H / 2).dp).fillMaxWidth().height(LABEL_H.dp)
                    .clip(RoundedCornerShape(12.dp)).clickable { onSelect(i) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.padding(start = 8.dp).size(40.dp, 12.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (terminus) p.lineA else p.surface)
                        .border(2.dp, p.lineA, RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(24.dp))
                Text(s.name, fontSize = 17.sp, color = nameColor, maxLines = 1,
                    fontWeight = if (terminus || highlight) FontWeight.SemiBold else FontWeight.Normal)
                s.interchange?.let { Text("  $it", color = p.mute, fontSize = 12.sp, maxLines = 1) }
                if (nearby == i) Text("  · sei qui", color = p.lineA, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                NextTrainLabel(i, settings, nextTrains)
            }
        }
        val lo = range.first.toFloat(); val hi = range.last.toFloat()
        trains.filter { it.position in lo..hi }.forEach { t ->
            key(t.id) { TrainMarker(t, y(yAt(centers, t.position)), movesUp(t.direction, settings), settings) }
        }
    }
}

/** Minuti al prossimo treno accanto al nome della stazione, secondo l'impostazione scelta. */
@Composable
private fun NextTrainLabel(station: Int, s: AppSettings, next: Map<Direction, List<Arrival?>>) {
    val p = LocalPalette.current
    val dirs = when (s.nextTrainLabels) {
        NextTrainLabels.NONE -> return
        NextTrainLabels.TO_BATTISTINI -> listOf(Direction.TO_BATTISTINI)
        NextTrainLabels.TO_ANAGNINA -> listOf(Direction.TO_ANAGNINA)
        NextTrainLabels.BOTH -> listOf(Direction.TO_BATTISTINI, Direction.TO_ANAGNINA)
    }
    val parts = dirs.mapNotNull { d ->
        if (isEndOfLine(station, d)) return@mapNotNull null
        val a = next[d]?.getOrNull(station)
        val text = when (a) {
            null -> "–"
            Arrival.AtStation -> "●"
            Arrival.Arriving -> "<1′"
            is Arrival.InMinutes -> "${a.minutes}′"
        }
        (if (dirs.size > 1) (if (movesUp(d, s)) "↑" else "↓") else "") + text
    }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString("  "), color = p.mute, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(end = 6.dp))
    }
}

@Composable
private fun TrainMarker(t: Train, y: androidx.compose.ui.unit.Dp, up: Boolean, s: AppSettings) {
    val p = LocalPalette.current
    // Le posizioni arrivano ogni secondo: un'interpolazione lineare di un secondo dà un movimento continuo
    val animY by animateDpAsState(y, if (s.smoothMotion) tween(1000, easing = LinearEasing) else snap(), label = "y")
    val color by animateColorAsState(if (t.stopped) p.lineA else p.ink, tween(400), label = "colore")
    val glyphColor by animateColorAsState(if (t.stopped) Color.White else p.bg, tween(400), label = "freccia")
    // Entrata: il treno compare crescendo invece che di colpo
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
    // Fermo in banchina: leggero "respiro"
    val breathe by rememberInfiniteTransition(label = "sosta").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "respiro"
    )
    val (w, h) = when (s.trainStyle) {
        TrainStyle.ARROW -> 26f to 18f
        TrainStyle.DOT -> 14f to 14f
        TrainStyle.CAR -> 18f to 34f
    }.let { (w, h) -> w * s.trainSize.scale to h * s.trainSize.scale }
    val col = if (t.direction == Direction.TO_BATTISTINI) COL_BATTISTINI else COL_ANAGNINA
    Box(
        Modifier.offset(x = (col - w / 2).dp, y = animY - (h / 2).dp)
            .graphicsLayer { alpha = appear.value; scaleX = appear.value; scaleY = appear.value }
            .scale(if (t.stopped && s.pulseStopped) breathe else 1f)
            .size(w.dp, h.dp)
            .clip(if (s.trainStyle == TrainStyle.DOT) CircleShape else RoundedCornerShape((minOf(w, h) / 2).dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (s.trainStyle != TrainStyle.DOT) {
            Text(if (up) "▲" else "▼", color = glyphColor, fontSize = (9 * s.trainSize.scale).sp)
        }
    }
}
