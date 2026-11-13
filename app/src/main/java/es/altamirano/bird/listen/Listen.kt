package es.altamirano.bird.listen

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import es.altamirano.bird.R
import es.altamirano.bird.app
import es.altamirano.bird.lastLocation
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
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

    /** Los últimos 15 s del micrófono, para poder compartirlos (y ver qué le llega al modelo). */
    private val recent = FloatArray(Signal.RATE * 15)
    private var recentPos = 0
    private var recentFilled = 0
    @Synchronized fun clearRecent() { recentPos = 0; recentFilled = 0 }
    @Synchronized fun addRecent(x: FloatArray) {
        for (v in x) { recent[recentPos] = v; recentPos = (recentPos + 1) % recent.size }
        recentFilled = minOf(recent.size, recentFilled + x.size)
    }
    @Synchronized fun recentAudio(): FloatArray =
        FloatArray(recentFilled) { recent[(recentPos - recentFilled + it + recent.size) % recent.size] }
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
        // El error (micrófono, modelo) se queda a la vista hasta que se vuelva a escuchar.
        ListenBus.state.update { ListenState(error = it.error) }
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
        val scorer = Scorer(app.models.labels)
        // UNPROCESSED: el audio tal cual, sin el procesado del sistema (como `.measurement` en iOS).
        // Llega flojo porque no hay control automático de ganancia, pero a BirdNET le da igual: normaliza
        // cada ventana (comprobado: el mismo logit con el audio a 1/10.000). Lo que sí le estorbaría es
        // la supresión de ruido de las fuentes de voz, que se come los cantos.
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(Signal.RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minBuf = AudioRecord.getMinBufferSize(Signal.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufBytes = maxOf(minBuf, Signal.RATE * 4 * 4)           // 4 s de margen
        fun build(source: Int) = runCatching {
            AudioRecord.Builder().setAudioSource(source).setAudioFormat(format).setBufferSizeInBytes(bufBytes).build()
                .also { if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); error("sin iniciar") } }
        }.getOrNull()
        val rec = build(MediaRecorder.AudioSource.UNPROCESSED) ?: build(MediaRecorder.AudioSource.MIC)
        if (rec == null) {
            ListenBus.state.update { it.copy(error = getString(R.string.mic_failed)) }; stopSelf(); return
        }
        ListenBus.state.update { ListenState(listening = true) }
        ListenBus.clearRecent()
        // La captura no espera nunca al modelo: si el análisis tarda (la primera vez carga el modelo y
        // calcula el filtro de lugar), el micrófono no pierde muestras y las ventanas siguen siendo
        // audio seguido. Antes se analizaba dentro del bucle de lectura y el búfer de 1 s se desbordaba.
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        val pending = AtomicInteger(0)
        val analysis = scope.launch { analyzeLoop(chunks, pending, scorer) }
        rec.startRecording()
        try {
            withContext(Dispatchers.IO) {
                val chunk = FloatArray(Signal.RATE / 10)
                while (isActive) {
                    val n = rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    val part = chunk.copyOf(n)
                    var sum = 0.0; for (v in part) sum += v * v
                    ListenBus.state.update { it.copy(level = kotlin.math.sqrt(sum / n).toFloat()) }
                    ListenBus.addRecent(part)
                    pending.addAndGet(n); chunks.trySend(part)
                    autoStopInBackground(app.prefs.backgroundMinutes)
                }
            }
        } finally {
            chunks.close(); analysis.cancel()
            rec.stop(); rec.release()
        }
    }

    /** Ventanas de 3 s cada 0,8 s (~73 % de solape, como whoBIRD) sobre el audio capturado. */
    private suspend fun analyzeLoop(chunks: Channel<FloatArray>, pending: AtomicInteger, scorer: Scorer) {
        val acc = FloatArray(Signal.WINDOW)
        var filled = 0
        for (c in chunks) {
            pending.addAndGet(-c.size)
            var i = 0
            while (i < c.size) {
                val take = minOf(c.size - i, Signal.WINDOW - filled)
                System.arraycopy(c, i, acc, filled, take); filled += take; i += take
                if (filled == Signal.WINDOW) {
                    // Si el modelo falla, se dice y se para: antes el análisis moría callado, el
                    // micrófono seguía grabando y la cola de audio crecía sin fin.
                    try {
                        analyze(acc.copyOf(), scorer)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("BirdDroid", "Análisis fallido", e)
                        ListenBus.state.update { it.copy(error = getString(R.string.analysis_failed)) }
                        stopSelf()
                        return
                    }
                    System.arraycopy(acc, Signal.HOP, acc, 0, Signal.WINDOW - Signal.HOP); filled = Signal.WINDOW - Signal.HOP
                    // Si el móvil no da abasto y se acumula más de una ventana, se salta a lo último:
                    // mejor analizar lo que suena ahora que lo de hace diez segundos.
                    if (pending.get() > Signal.WINDOW) {
                        while (pending.get() > Signal.WINDOW - Signal.HOP) {
                            val old = chunks.tryReceive().getOrNull() ?: break
                            pending.addAndGet(-old.size)
                        }
                        filled = 0; i = c.size
                    }
                }
            }
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

    companion object {
        private const val CHANNEL = "escucha"
        private const val STOP = "parar"
        fun start(c: Context) = c.startForegroundService(Intent(c, ListenService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, ListenService::class.java).setAction(STOP))
    }
}

