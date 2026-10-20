package br.com.rafarezende.podcaststudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Servico em primeiro plano que grava audio SEM limite de tempo.
 * O limite passa a ser apenas o espaco livre do aparelho.
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

        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_DEVICE_ID = "extra_device_id"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "recording_channel"

        @Volatile
        var state: State = State.IDLE

        @Volatile
        var currentFile: File? = null

        /** Momento (wall clock) em que a gravacao atual comecou. */
        @Volatile
        var startTime: Long = 0L

        /** Inicio do trecho atual (elapsedRealtime), usado no cronometro. */
        @Volatile
        var segmentStartMillis: Long = 0L

        /** Tempo acumulado dos trechos ja pausados (o cronometro pausa junto). */
        @Volatile
        var accumulatedMillis: Long = 0L

        @Volatile
        var listener: Listener? = null

        fun elapsedMillis(): Long {
            return if (state == State.RECORDING && segmentStartMillis > 0L) {
                accumulatedMillis + (SystemClock.elapsedRealtime() - segmentStartMillis)
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

    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (state != State.IDLE) {
                val amplitude = if (state == State.RECORDING) currentAmplitude() else 0
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
            recorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "erro ao parar recorder no onDestroy", e)
        }
        releaseRecorder()
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

        @Suppress("DEPRECATION")
        val newRecorder = MediaRecorder()
        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioSamplingRate(44100)
            newRecorder.setAudioEncodingBitRate(128000)
            newRecorder.setAudioChannels(1)
            newRecorder.setOutputFile(file.absolutePath)

            if (deviceId >= 0 && Build.VERSION.SDK_INT >= 28) {
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val device = devices.firstOrNull { it.id == deviceId }
                    if (device != null) {
                        newRecorder.setPreferredDevice(device)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setPreferredDevice falhou, usando padrao", e)
                }
            }

            newRecorder.prepare()
            newRecorder.start()
        } catch (e: Exception) {
            Log.e(TAG, "falha ao iniciar gravacao", e)
            try {
                newRecorder.release()
            } catch (ignored: Exception) {
            }
            if (file.exists()) {
                file.delete()
            }
            state = State.IDLE
            notifyStateChanged()
            stopSelf()
            return
        }

        recorder = newRecorder
        currentFile = file
        startTime = System.currentTimeMillis()
        segmentStartMillis = SystemClock.elapsedRealtime()
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
        try {
            recorder?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "pause falhou", e)
            return
        }
        accumulatedMillis = elapsedMillis()
        state = State.PAUSED
        updateNotification("Pausado")
        notifyStateChanged()
    }

    private fun resumeRecording() {
        if (state != State.PAUSED) return
        try {
            recorder?.resume()
        } catch (e: Exception) {
            Log.w(TAG, "resume falhou", e)
            return
        }
        segmentStartMillis = SystemClock.elapsedRealtime()
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
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Arquivo pode ficar curto/corrompido se parar cedo demais;
            // mantemos o arquivo mesmo assim e apenas registramos.
            Log.w(TAG, "stop do recorder falhou", e)
        }
        releaseRecorder()
        releaseWakeLock()
        state = State.IDLE
        segmentStartMillis = 0L
        // accumulatedMillis fica com o total final ate a proxima gravacao.
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notifyStateChanged()
        stopSelf()
    }

    private fun releaseRecorder() {
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "release do recorder falhou", e)
        }
        recorder = null
    }

    private fun currentAmplitude(): Int {
        return try {
            recorder?.maxAmplitude ?: 0
        } catch (e: Exception) {
            0
        }
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
            .setContentTitle("Na Feira Pod Studio")
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
