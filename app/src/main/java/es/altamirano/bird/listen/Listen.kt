package es.altamirano.bird.listen

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import es.altamirano.bird.R
import es.altamirano.bird.app
import es.altamirano.bird.core.Candidate
import es.altamirano.bird.core.Scorer
import es.altamirano.bird.core.Signal
import es.altamirano.bird.data.toDetection
import es.altamirano.bird.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Lo que enseña la pantalla de escuchar (el indicador de «qué está haciendo el modelo»). */
data class ListenState(
    val listening: Boolean = false,
    val analyzing: Boolean = false,
    val thinking: Candidate? = null,
    val candidates: List<Candidate> = emptyList(),
    val level: Float = 0f,
    val error: String? = null,
)

object ListenBus {
    val state = MutableStateFlow(ListenState())
    @Volatile var foreground = true
}

/**
 * Escucha con el micrófono en un servicio en primer plano de tipo `microphone` (con su
 * notificación fija). Sigue con la app minimizada, y se apaga sola pasados los minutos que diga
 * Ajustes. Desde Android 14 sólo se puede arrancar con la app delante: se arranca desde el botón.
 */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var clearJob: Job? = null
    private var backgroundSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (job?.isActive != true) job = scope.launch { listen() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ListenBus.state.update { ListenState() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.listening), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_bird)
            .setContentTitle(getString(R.string.listening)).setContentIntent(open).setOngoing(true)
            .addAction(0, getString(R.string.stop), stop).build()
    }

    @SuppressLint("MissingPermission")
    private suspend fun listen() {
        val app = applicationContext.app
        val models = app.models
        val scorer = Scorer(models.labels)
        // UNPROCESSED: el audio tal cual, sin el procesado del sistema (como `.measurement` en iOS).
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(Signal.RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minBuf = AudioRecord.getMinBufferSize(Signal.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = runCatching {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.UNPROCESSED).setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, Signal.RATE * 4)).build().takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull() ?: runCatching {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, Signal.RATE * 4)).build()
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            ListenBus.state.update { it.copy(error = getString(R.string.mic_failed)) }; stopSelf(); return
        }
        ListenBus.state.update { ListenState(listening = true) }
        rec.startRecording()
        val acc = FloatArray(Signal.WINDOW)
        var filled = 0
        val chunk = FloatArray(Signal.RATE / 10)
        try {
            while (scope.isActive) {
                val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                var peak = 0f; for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(chunk[i]))
                ListenBus.state.update { it.copy(level = peak) }
                var i = 0
                while (i < n) {
                    val take = minOf(n - i, Signal.WINDOW - filled)
                    System.arraycopy(chunk, i, acc, filled, take); filled += take; i += take
                    if (filled == Signal.WINDOW) {
                        analyze(acc.copyOf(), scorer)
                        // Se desliza 0,8 s: ~73 % de solape entre ventanas.
                        System.arraycopy(acc, Signal.HOP, acc, 0, Signal.WINDOW - Signal.HOP); filled = Signal.WINDOW - Signal.HOP
                    }
                }
                autoStopInBackground(app.prefs.backgroundMinutes)
            }
        } finally {
            rec.stop(); rec.release()
        }
    }

    private fun autoStopInBackground(minutes: Int) {
        if (ListenBus.foreground) { backgroundSince = 0; return }
        if (backgroundSince == 0L) backgroundSince = System.currentTimeMillis()
        if (System.currentTimeMillis() - backgroundSince > minutes * 60_000L) stopSelf()
    }

    private fun analyze(window: FloatArray, scorer: Scorer) {
        val app = applicationContext.app
        val s = app.prefs.settings.value
        if (s.clipGate && Signal.isClipped(window)) { idle(); return }
        if (s.signalGate && !Signal.hasBirdSignal(window)) { idle(); return }
        ListenBus.state.update { it.copy(analyzing = true) }
        val input = if (s.highPass) Signal.highPass(window, s.highPassCutoff) else window
        val logits = app.models.birdnet(input)
        val where = lastLocation()
        val meta = if (where != null && s.locationInfluence > 0) {
            app.models.filter.update(where.first, where.second, LocalDate.now()); app.models.filter.metaScores
        } else null
        val (cands, thinking) = scorer.score(logits, s, meta)
        if (cands.isNotEmpty()) {
            val d = cands.toDetection(where?.first, where?.second, photo = false)
            ListenBus.state.update { it.copy(analyzing = false, thinking = null, candidates = cands) }
            d?.let { app.history.record(it) }
            // El resultado se queda en pantalla 8 s salvo que llegue otro.
            clearJob?.cancel()
            clearJob = scope.launch { delay(8000); ListenBus.state.update { it.copy(candidates = emptyList()) } }
        } else ListenBus.state.update { it.copy(analyzing = false, thinking = thinking) }
    }

    private fun idle() = ListenBus.state.update { it.copy(analyzing = false, thinking = null) }

    /** Con `LocationManager`, no el de Google Play (GrapheneOS). Lo último conocido basta. */
    @SuppressLint("MissingPermission")
    private fun lastLocation(): Pair<Double, Double>? {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LocationManager::class.java)
        return listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
    }

    companion object {
        private const val CHANNEL = "escucha"
        private const val STOP = "parar"
        fun start(c: Context) = c.startForegroundService(Intent(c, ListenService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, ListenService::class.java).setAction(STOP))
    }
}

