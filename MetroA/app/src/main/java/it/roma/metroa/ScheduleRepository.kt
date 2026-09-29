package it.roma.metroa

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** GTFS statico di Roma Servizi per la Mobilità (dati ATAC, licenza CC-BY): ~48 MB. */
const val STATIC_GTFS_URL = "https://romamobilita.it/wp-content/uploads/shared/rome_static_gtfs.zip"

/** Riscarica l'orario al più una volta a settimana, o prima se non copre più la data di oggi. */
private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

data class Progress(val label: String, val fraction: Float?)

/** Scarica il GTFS, ne estrae la Metro A e tiene in [filesDir] solo quella (poche centinaia di KB). */
class ScheduleRepository(private val filesDir: File, private val cacheDir: File) {
    private val cache = File(filesDir, "metro_a_schedule.txt")
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun loadCached(): Schedule? = withContext(Dispatchers.IO) {
        runCatching { cache.reader().use { Schedule.read(it) } }.getOrNull()
    }

    fun needsRefresh(schedule: Schedule?, today: LocalDate): Boolean =
        schedule == null || !schedule.covers(today) ||
            System.currentTimeMillis() - schedule.downloadedAtMs > MAX_AGE_MS

    suspend fun download(onProgress: (Progress) -> Unit): Schedule = withContext(Dispatchers.IO) {
        val zipFile = File(cacheDir, "rome_static_gtfs.zip")
        try {
            onProgress(Progress("Scarico l'orario ATAC", null))
            val request = Request.Builder().url(STATIC_GTFS_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Il server ha risposto ${response.code}")
                val body = response.body ?: throw IOException("Risposta vuota")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    zipFile.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 512 * 1024) {
                                lastReport = done
                                onProgress(Progress("Scarico l'orario ATAC", if (total > 0) done.toFloat() / total else null))
                            }
                        }
                    }
                }
            }

            onProgress(Progress("Estraggo le corse della Metro A", 0f))
            val schedule = ZipFile(zipFile).use { zip ->
                GtfsParser.parse(zip) { onProgress(Progress("Estraggo le corse della Metro A", it)) }
            }

            val tmp = File(filesDir, "${cache.name}.tmp")
            tmp.writer().use { schedule.write(it) }
            if (!tmp.renameTo(cache)) throw IOException("Impossibile salvare l'orario")
            schedule
        } finally {
            zipFile.delete()
        }
    }
}
