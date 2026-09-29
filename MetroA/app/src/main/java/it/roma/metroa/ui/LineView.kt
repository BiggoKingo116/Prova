package it.roma.metroa.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
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
                if (i in settings.favorites) Text("  ★", color = p.lineA, fontSize = 13.sp)
                if (nearby == i) Text("  · sei qui", color = p.lineA, fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.weight(1f))
                NextTrainLabel(i, settings, nextTrains)
            }
        }
        val lo = range.first.toFloat(); val hi = range.last.toFloat()
        trains.filter { it.position in lo..hi }.forEach { t ->
            val col = if (t.direction == Direction.TO_BATTISTINI) COL_BATTISTINI else COL_ANAGNINA
            key(t.id) { TrainMarker(t, along = y(yAt(centers, t.position)), across = col.dp, horizontal = false, settings) }
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

/**
 * Un treno sulla linea: [along] è la posizione lungo la linea (verticale o orizzontale), [across] il
 * centro della colonna (o riga) della sua direzione.
 */
@Composable
private fun TrainMarker(t: Train, along: Dp, across: Dp, horizontal: Boolean, s: AppSettings) {
    val p = LocalPalette.current
    // Le posizioni arrivano ogni secondo: un'interpolazione lineare di un secondo dà un movimento continuo
    val pos by animateDpAsState(along, if (s.smoothMotion) tween(1000, easing = LinearEasing) else snap(), label = "pos")
    val color by animateColorAsState(if (t.stopped) p.lineA else p.ink, tween(400), label = "colore")
    val glyphColor by animateColorAsState(if (t.stopped) Color.White else p.bg, tween(400), label = "freccia")
    // Entrata: il treno compare crescendo invece che di colpo
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
    // Fermo in banchina: leggero "respiro"
    val breathe by rememberInfiniteTransition(label = "sosta").animateFloat(
        1f, 1.12f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "respiro"
    )
    // Misure di un treno disegnato in verticale; in orizzontale il vagone si gira
    val (w0, h0) = when (s.trainStyle) {
        TrainStyle.ARROW -> 26f to 18f
        TrainStyle.DOT -> 14f to 14f
        TrainStyle.CAR -> 18f to 34f
    }
    val (w, h) = (if (horizontal && s.trainStyle == TrainStyle.CAR) h0 to w0 else w0 to h0)
        .let { (a, b) -> a * s.trainSize.scale to b * s.trainSize.scale }
    val up = movesUp(t.direction, s)
    val glyph = if (horizontal) (if (up) "◀" else "▶") else (if (up) "▲" else "▼")
    val (x, y) = if (horizontal) pos - (w / 2).dp to across - (h / 2).dp else across - (w / 2).dp to pos - (h / 2).dp
    Box(
        Modifier.offset(x = x, y = y)
            .graphicsLayer { alpha = appear.value; scaleX = appear.value; scaleY = appear.value }
            .scale(if (t.stopped && s.pulseStopped) breathe else 1f)
            .size(w.dp, h.dp)
            .clip(if (s.trainStyle == TrainStyle.DOT) CircleShape else RoundedCornerShape((minOf(w, h) / 2).dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (s.trainStyle != TrainStyle.DOT) Text(glyph, color = glyphColor, fontSize = (9 * s.trainSize.scale).sp)
    }
}

/** Margine prima della prima stazione e dopo l'ultima nella linea orizzontale. */
private const val H_PAD = 24f
/** Righe dei due binari nella linea orizzontale: in alto verso Battistini, in basso verso Anagnina. */
private const val ROW_BATTISTINI = 18f
private const val ROW_ANAGNINA = 46f

/**
 * La linea in orizzontale, per il telefono girato: stazioni da sinistra a destra (o al contrario se la
 * linea è capovolta), nomi in diagonale sotto i binari, stesse distanze e stessi treni della verticale.
 */
@Composable
fun HorizontalLineView(
    trains: List<Train>, settings: AppSettings, segmentSeconds: FloatArray?,
    selected: Int?, nearby: Int?, onSelect: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val centers = remember(settings, segmentSeconds) { stationCenters(settings, segmentSeconds) }
    val span = centers.max()
    val x = { c: Float -> (c + H_PAD).dp }
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Box(Modifier.width((span + 2 * H_PAD + 120).dp).height(190.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                for (row in listOf(ROW_BATTISTINI, ROW_ANAGNINA)) {
                    drawLine(p.lineSoft, Offset(x(0f).toPx(), row.dp.toPx()), Offset(x(span).toPx(), row.dp.toPx()),
                        4.dp.toPx(), StrokeCap.Round)
                }
            }
            for (i in STATIONS.indices) {
                val terminus = i == 0 || i == LAST
                val highlight = selected == i || nearby == i
                val nameColor by animateColorAsState(if (highlight) p.lineA else p.ink, tween(250), label = "nome")
                // Zona toccabile della stazione: la sua colonna sopra i binari
                Box(
                    Modifier.offset(x = x(centers[i]) - 16.dp).size(32.dp, 64.dp)
                        .clip(RoundedCornerShape(10.dp)).clickable { onSelect(i) }
                )
                Box(
                    Modifier.offset(x = x(centers[i]) - 6.dp, y = (ROW_BATTISTINI - 10).dp).size(12.dp, 48.dp)
                        .clip(RoundedCornerShape(6.dp)).background(if (terminus) p.lineA else p.surface)
                        .border(2.dp, p.lineA, RoundedCornerShape(6.dp))
                )
                Text(
                    STATIONS[i].name + if (i in settings.favorites) " ★" else "",
                    color = nameColor, fontSize = 13.sp, maxLines = 1, softWrap = false,
                    fontWeight = if (terminus || highlight) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.offset(x = x(centers[i]) - 2.dp, y = 66.dp)
                        .graphicsLayer { rotationZ = 45f; transformOrigin = TransformOrigin(0f, 0f) }
                        .clickable { onSelect(i) },
                )
            }
            trains.forEach { t ->
                val row = if (t.direction == Direction.TO_BATTISTINI) ROW_BATTISTINI else ROW_ANAGNINA
                key(t.id) { TrainMarker(t, along = x(yAt(centers, t.position)), across = row.dp, horizontal = true, settings) }
            }
        }
    }
}
