package it.roma.metroa

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Avviso impostato: suona a [alarmAtMs], qualche minuto prima dell'arrivo previsto [trainAtMs]. */
data class TrainAlert(val station: Int, val direction: Direction, val trainAtMs: Long, val alarmAtMs: Long)

private const val CHANNEL = "arrivi"
private const val PREFS = "train_alert"

/**
 * Avviso "il treno sta arrivando": un allarme di sistema che mostra una notifica. Ne esiste al più uno;
 * impostarne un altro sostituisce il precedente.
 */
object TrainAlerts {
    fun current(context: Context): TrainAlert? {
        val p = context.getSharedPreferences(PREFS, 0)
        if (!p.contains("station")) return null
        val a = TrainAlert(
            p.getInt("station", 0), Direction.valueOf(p.getString("direction", Direction.TO_ANAGNINA.name)!!),
            p.getLong("trainAt", 0), p.getLong("alarmAt", 0),
        )
        // Un avviso già suonato (o il cui treno è passato) non è più "in corso"
        return a.takeIf { System.currentTimeMillis() < it.trainAtMs }
    }

    fun set(context: Context, alert: TrainAlert) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putInt("station", alert.station).putString("direction", alert.direction.name)
            .putLong("trainAt", alert.trainAtMs).putLong("alarmAt", alert.alarmAtMs).apply()
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        // Allarme esatto se il sistema lo consente; altrimenti uno che Android può ritardare di poco
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alert.alarmAtMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alert.alarmAtMs, pi)
        }
    }

    fun cancel(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().clear().apply()
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, TrainAlertReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Riceve l'allarme e mostra la notifica. */
class TrainAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = TrainAlerts.current(context) ?: return
        val minutes = ((a.trainAtMs - System.currentTimeMillis() + 30_000) / 60_000).coerceAtLeast(0)
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Arrivo del treno", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                if (minutes <= 0) "Il treno sta arrivando a ${STATIONS[a.station].name}"
                else "Treno a ${STATIONS[a.station].name} tra $minutes min"
            )
            .setContentText("Direzione ${STATIONS[a.direction.terminus].name} · stima dall'orario e dalle segnalazioni")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(1, n)
    }
}
