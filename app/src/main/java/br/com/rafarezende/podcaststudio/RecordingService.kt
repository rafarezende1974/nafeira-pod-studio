package br.com.rafarezende.podcaststudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import br.com.rafarezende.podcaststudio.audio.RecorderEngine
import br.com.rafarezende.podcaststudio.audio.SfxStore
import java.io.File

/**
 * Servico em primeiro plano que grava audio SEM limite de tempo.
 * O limite passa a ser apenas o espaco livre do aparelho.
 *
 * V2: o motor e o RecorderEngine (AudioRecord + mixagem + AAC), que
 * mistura os efeitos da mesa de som digitalmente na gravacao.
 */
class RecordingService : Service() {

    enum class State { IDLE, RECORDING, PAUSED }

    interface Listener {
        fun onStateChanged()
        fun onTick(elapsedMillis: Long, maxAmplitude: Int)
    }

    companion object {
        private const val TAG = "RecordingService"

        const val ACTION_START = "br.com.rafarezende.podcaststudio.action.START"
        const val ACTION_PAUSE = "br.com.rafarezende.podcaststudio.action.PAUSE"
        const val ACTION_RESUME = "br.com.rafarezende.podcaststudio.action.RESUME"
        const val ACTION_STOP = "br.com.rafarezende.podcaststudio.action.STOP"
        const val ACTION_TRIGGER_PAD =
            "br.com.rafarezende.podcaststudio.action.TRIGGER_PAD"
        const val ACTION_STOP_EFFECTS =
            "br.com.rafarezende.podcaststudio.action.STOP_EFFECTS"

        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_PAD_INDEX = "extra_pad_index"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"

        @Volatile
        var state: State = State.IDLE

        @Volatile
        var currentFile: File? = null

        /** Momento (wall clock) em que a gravacao atual comecou. */
        @Volatile
        var startTime: Long = 0L

        /** Tempo gravado ate a ultima pausa (sincronizado do engine). */
        @Volatile
        var accumulatedMillis: Long = 0L

        /** Volume dos efeitos (0f..1f), lido pelo engine a cada frame. */
        @Volatile
        var effectsVolume: Float = 0.8f

        @Volatile
        var listener: Listener? = null

        /** Instancia ativa do motor (so existe gravando/pausado). */
        @Volatile
        var engine: RecorderEngine? = null

        fun elapsedMillis(): Long {
            val eng = engine
            return if (eng != null && state != State.IDLE) {
                eng.elapsedMillis()
            } else {
                accumulatedMillis
            }
        }

        fun recordingsDir(context: Context): File {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: context.filesDir
            val dir = File(base, "recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (state != State.IDLE) {
                val amplitude = if (state == State.RECORDING) {
                    engine?.currentPeak() ?: 0
                } else {
                    0
                }
                try {
                    listener?.onTick(elapsedMillis(), amplitude)
                } catch (e: Exception) {
                    Log.w(TAG, "listener onTick falhou", e)
                }
                handler.postDelayed(this, 250L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "gravacao.m4a"
                val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                startRecording(fileName, deviceId)
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            ACTION_TRIGGER_PAD -> {
                val padIndex = intent.getIntExtra(EXTRA_PAD_INDEX, -1)
                if (padIndex >= 0) triggerPad(padIndex)
            }
            ACTION_STOP_EFFECTS -> engine?.stopEffects()
            else -> {
                // Reinicio do sistema etc.: se nao esta gravando, nao fica preso.
                if (state == State.IDLE) {
                    return START_NOT_STICKY
                }
            }
        }
        return if (state != State.IDLE) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        try {
            engine?.stopAndJoin()
        } catch (e: Exception) {
            Log.w(TAG, "erro ao parar engine no onDestroy", e)
        }
        engine = null
        releaseWakeLock()
        if (state != State.IDLE) {
            state = State.IDLE
            try {
                listener?.onStateChanged()
            } catch (e: Exception) {
                Log.w(TAG, "listener onStateChanged falhou", e)
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Gravacao
    // ---------------------------------------------------------------

    private fun startRecording(fileName: String, deviceId: Int) {
        if (state != State.IDLE) {
            Log.w(TAG, "startRecording ignorado: ja gravando/pausado")
            return
        }

        val safeName = if (fileName.endsWith(".m4a")) fileName else "$fileName.m4a"
        val file = File(recordingsDir(this), safeName)

        val newEngine = RecorderEngine(applicationContext)
        newEngine.effectsVolume = effectsVolume
        try {
            newEngine.start(file, deviceId)
        } catch (e: Exception) {
            Log.e(TAG, "falha ao iniciar gravacao", e)
            try {
                newEngine.stopAndJoin()
            } catch (ignored: Exception) {
            }
            if (file.exists()) {
                file.delete()
            }
            engine = null
            state = State.IDLE
            notifyStateChanged()
            stopSelf()
            return
        }

        engine = newEngine
        currentFile = file
        startTime = System.currentTimeMillis()
        accumulatedMillis = 0L
        state = State.RECORDING

        startForegroundWithType(buildNotification("Gravando…"))
        acquireWakeLock()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        notifyStateChanged()
    }

    private fun pauseRecording() {
        if (state != State.RECORDING) return
        val eng = engine ?: return
        try {
            eng.pause()
        } catch (e: Exception) {
            Log.w(TAG, "pause falhou", e)
            return
        }
        accumulatedMillis = eng.elapsedMillis()
        state = State.PAUSED
        updateNotification("Pausado")
        notifyStateChanged()
    }

    private fun resumeRecording() {
        if (state != State.PAUSED) return
        val eng = engine ?: return
        try {
            eng.resume()
        } catch (e: Exception) {
            Log.w(TAG, "resume falhou", e)
            return
        }
        state = State.RECORDING
        updateNotification("Gravando…")
        notifyStateChanged()
    }

    private fun stopRecording() {
        if (state == State.IDLE) {
            stopSelf()
            return
        }
        handler.removeCallbacks(tickRunnable)
        val eng = engine
        engine = null
        if (eng != null) {
            accumulatedMillis = eng.elapsedMillis()
            try {
                // Finaliza o arquivo (EOS + muxer) numa thread separada pra
                // nao travar a main thread; o servico se encerra no fim.
                val stopper = Thread {
                    try {
                        eng.stopAndJoin()
                    } catch (e: Exception) {
                        Log.w(TAG, "stopAndJoin falhou", e)
                    }
                }
                stopper.start()
                try {
                    stopper.join(3000)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "join do stop interrompido", e)
                }
            } catch (e: Exception) {
                Log.w(TAG, "stop do engine falhou", e)
            }
        }
        releaseWakeLock()
        state = State.IDLE
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notifyStateChanged()
        stopSelf()
    }

    // ---------------------------------------------------------------
    // Efeitos
    // ---------------------------------------------------------------

    private fun triggerPad(padIndex: Int) {
        if (state != State.RECORDING) return
        val eng = engine ?: return
        eng.effectsVolume = effectsVolume
        // A resolucao do PCM do pad (arquivo do usuario) usa cache; ainda
        // assim rodamos fora da main thread por seguranca (a primeira
        // decodificacao de um arquivo pode demorar).
        Thread {
            try {
                val pcm = SfxStore(applicationContext).pcmFor(padIndex)
                if (pcm != null) {
                    eng.triggerEffect(pcm)
                } else {
                    Log.w(TAG, "pad $padIndex sem som")
                }
            } catch (e: Exception) {
                Log.w(TAG, "triggerPad $padIndex falhou", e)
            }
        }.start()
    }

    // ---------------------------------------------------------------
    // WakeLock
    // ---------------------------------------------------------------

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PodcastStudio:RecordingWakeLock"
            )
            wakeLock?.setReferenceCounted(false)
            // Sem timeout: a gravacao nao tem limite de tempo.
            // O WakeLock e liberado no STOP / onDestroy.
            @Suppress("DEPRECATION")
            wakeLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "acquire WakeLock falhou", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "release WakeLock falhou", e)
        }
        wakeLock = null
    }

    // ---------------------------------------------------------------
    // Notificacao
    // ---------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gravação",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Gravação de podcast em andamento"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Pod Estúdio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "startForeground com tipo falhou, tentando sem tipo", e)
            }
        }
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground falhou", e)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "updateNotification falhou", e)
        }
    }

    private fun notifyStateChanged() {
        try {
            listener?.onStateChanged()
        } catch (e: Exception) {
            Log.w(TAG, "listener onStateChanged falhou", e)
        }
    }
}
