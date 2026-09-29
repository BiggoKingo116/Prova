package it.roma.metroa

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Widget per la schermata home: prossimi arrivi nella prima stazione preferita (o Termini).
 * Mostra orari e non minuti: Android aggiorna i widget al massimo ogni 30 minuti, e un orario resta
 * giusto anche fra un aggiornamento e l'altro. Mentre l'app è aperta viene aggiornato ogni minuto.
 */
class NextTrainsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread {
            try {
                render(context, manager, ids)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

        /** Aggiorna tutti i widget presenti (nessuno: non fa niente). Bloccante: chiamarla fuori dal thread principale. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NextTrainsWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val settings = SettingsStore(context.getSharedPreferences("settings", 0)).load()
            val station = settings.favorites.firstOrNull() ?: 11 // Termini
            val schedule = readCachedSchedule(context.filesDir)
            val model = ReportDb(context).use { DelayModel(it.recent()) }
            val now = ZonedDateTime.now(ROME)

            val views = RemoteViews(context.packageName, R.layout.widget_next_trains)
            views.setTextViewText(R.id.widget_station, "Ⓐ ${STATIONS[station].name}")
            val lines = Direction.entries.filterNot { isEndOfLine(station, it) }.map { dir ->
                val delay = model.forDirection(dir, now.toInstant().toEpochMilli(), now.hour).medianS
                val times = schedule?.nextArrivalTimes(station, dir, now, delay).orEmpty()
                    .joinToString("  ·  ") { Instant.ofEpochMilli(it).atZone(ROME).format(HH_MM) }
                "${if (dir == Direction.TO_ANAGNINA) "↓" else "↑"} ${STATIONS[dir.terminus].name}   " +
                    times.ifEmpty { "nessun treno" }
            }
            views.setTextViewText(R.id.widget_line1, lines.getOrElse(0) { "" })
            views.setTextViewText(R.id.widget_line2, lines.getOrElse(1) { "" })
            views.setTextViewText(
                R.id.widget_footer,
                when {
                    schedule == null -> "Apri l'app per scaricare l'orario"
                    settings.favorites.isEmpty() -> "Aggiornato alle ${now.format(HH_MM)} · scegli una stazione preferita nell'app"
                    else -> "Aggiornato alle ${now.format(HH_MM)} · stima dall'orario e dalle segnalazioni"
                },
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
