package br.com.rafarezende.podcaststudio

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ComponentActivity e subclasse direta de android.app.Activity (sem AppCompat
// e sem Compose) e e quem expoe o onBackPressedDispatcher usado abaixo.
class MainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }

    private lateinit var etTitle: EditText
    private lateinit var tvTimer: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var pbLevel: ProgressBar
    private lateinit var spDevice: Spinner
    private lateinit var btnRecord: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var lvLibrary: ListView

    private val deviceIds = mutableListOf<Int>()
    private val deviceNames = mutableListOf<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var recordings: List<File> = emptyList()
    private lateinit var libraryAdapter: ArrayAdapter<String>

    private var pendingStart = false

    private var player: MediaPlayer? = null
    private var playerDialog: AlertDialog? = null

    private val serviceListener = object : RecordingService.Listener {
        override fun onStateChanged() {
            runOnUiThread {
                updateUi()
                if (RecordingService.state == RecordingService.State.IDLE) {
                    refreshLibrary()
                }
            }
        }

        override fun onTick(elapsedMillis: Long, maxAmplitude: Int) {
            runOnUiThread {
                tvTimer.text = formatElapsed(elapsedMillis)
                pbLevel.progress = maxAmplitude
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTitle = findViewById(R.id.etTitle)
        tvTimer = findViewById(R.id.tvTimer)
        tvStatus = findViewById(R.id.tvStatus)
        tvEmpty = findViewById(R.id.tvEmpty)
        pbLevel = findViewById(R.id.pbLevel)
        spDevice = findViewById(R.id.spDevice)
        btnRecord = findViewById(R.id.btnRecord)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        lvLibrary = findViewById(R.id.lvLibrary)

        if (etTitle.text.toString().isBlank()) {
            etTitle.setText(defaultTitle())
        }

        deviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            deviceNames
        )
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spDevice.adapter = deviceAdapter

        libraryAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            mutableListOf<String>()
        )
        lvLibrary.adapter = libraryAdapter
        lvLibrary.setOnItemClickListener { _, _, position, _ ->
            val file = recordings.getOrNull(position)
            if (file != null) {
                showFileOptions(file)
            }
        }

        btnRecord.setOnClickListener { onRecordClicked() }
        btnPause.setOnClickListener { onPauseClicked() }
        btnStop.setOnClickListener { sendServiceAction(RecordingService.ACTION_STOP) }

        // IMPORTANTE: callback explicito (NAO usar lambda solta em
        // addCallback -- isso quebrou o build de outro app do Rafael).
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (RecordingService.state != RecordingService.State.IDLE) {
                    // Gravando: minimiza, a gravacao continua no servico.
                    moveTaskToBack(true)
                } else {
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        RecordingService.listener = serviceListener
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        RecordingService.listener = serviceListener
        refreshDevices()
        refreshLibrary()
        updateUi()
        // Restaura o cronometro na volta pra tela.
        tvTimer.text = formatElapsed(RecordingService.elapsedMillis())
    }

    override fun onDestroy() {
        stopPlayer()
        if (RecordingService.listener === serviceListener) {
            RecordingService.listener = null
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Botoes / gravacao
    // ---------------------------------------------------------------

    private fun onRecordClicked() {
        if (RecordingService.state != RecordingService.State.IDLE) {
            Toast.makeText(this, "Já existe uma gravação em andamento.", Toast.LENGTH_SHORT).show()
            return
        }
        if (hasRecordPermission()) {
            if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
                pendingStart = true
                requestNeededPermissions()
                return
            }
            startRecording()
        } else {
            pendingStart = true
            requestNeededPermissions()
        }
    }

    private fun onPauseClicked() {
        when (RecordingService.state) {
            RecordingService.State.RECORDING ->
                sendServiceAction(RecordingService.ACTION_PAUSE)
            RecordingService.State.PAUSED ->
                sendServiceAction(RecordingService.ACTION_RESUME)
            RecordingService.State.IDLE -> Unit
        }
    }

    private fun startRecording() {
        val fileName = uniqueFileName(sanitizeFileName(etTitle.text.toString()))
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_FILE_NAME, fileName)
            putExtra(RecordingService.EXTRA_DEVICE_ID, selectedDeviceId())
        }
        startServiceCompat(intent)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, RecordingService::class.java).apply {
            this.action = action
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível falar com o serviço de gravação.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startServiceCompat(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível iniciar a gravação.", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectedDeviceId(): Int {
        val pos = spDevice.selectedItemPosition
        return if (pos >= 0 && pos < deviceIds.size) deviceIds[pos] else -1
    }

    // ---------------------------------------------------------------
    // Permissoes
    // ---------------------------------------------------------------

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (!hasRecordPermission()) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasRecordPermission()) {
            if (pendingStart) {
                pendingStart = false
                startRecording()
            }
        } else {
            pendingStart = false
            Toast.makeText(
                this,
                "Sem permissão de microfone não dá pra gravar.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------

    private fun updateUi() {
        val state = RecordingService.state
        when (state) {
            RecordingService.State.RECORDING -> {
                tvStatus.text = "● Gravando — sem limite de tempo"
                btnPause.text = "Pausar"
                btnRecord.isEnabled = false
                btnPause.isEnabled = true
                btnStop.isEnabled = true
                spDevice.isEnabled = false
            }
            RecordingService.State.PAUSED -> {
                tvStatus.text = "❚❚ Pausado"
                btnPause.text = "Continuar"
                btnRecord.isEnabled = false
                btnPause.isEnabled = true
                btnStop.isEnabled = true
                spDevice.isEnabled = false
                pbLevel.progress = 0
            }
            RecordingService.State.IDLE -> {
                tvStatus.text = "Pronto para gravar"
                btnPause.text = "Pausar"
                btnRecord.isEnabled = true
                btnPause.isEnabled = false
                btnStop.isEnabled = false
                spDevice.isEnabled = true
                pbLevel.progress = 0
            }
        }

        if (state != RecordingService.State.IDLE) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun refreshDevices() {
        val previouslySelected = selectedDeviceId()
        deviceIds.clear()
        deviceNames.clear()
        deviceIds.add(-1)
        deviceNames.add("Padrão do aparelho")
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (device in devices) {
                val name = try {
                    device.productName?.toString()?.trim().orEmpty()
                } catch (e: Exception) {
                    ""
                }
                val label = if (name.isNotBlank()) name else "Dispositivo ${device.id}"
                deviceIds.add(device.id)
                deviceNames.add(label)
            }
        } catch (e: Exception) {
            // Sem dispositivos listados, o padrao continua valendo.
        }
        deviceAdapter.notifyDataSetChanged()
        val idx = deviceIds.indexOf(previouslySelected)
        if (idx >= 0) {
            spDevice.setSelection(idx)
        }
    }

    // ---------------------------------------------------------------
    // Biblioteca
    // ---------------------------------------------------------------

    private fun refreshLibrary() {
        val dir = RecordingService.recordingsDir(this)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".m4a", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        recordings = files

        libraryAdapter.clear()
        for (file in files) {
            libraryAdapter.add(describeFile(file))
        }
        libraryAdapter.notifyDataSetChanged()
        tvEmpty.visibility = if (files.isEmpty()) TextView.VISIBLE else TextView.GONE
        lvLibrary.visibility = if (files.isEmpty()) ListView.GONE else ListView.VISIBLE
    }

    private fun describeFile(file: File): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
            .format(Date(file.lastModified()))
        val sizeMb = file.length().toDouble() / (1024.0 * 1024.0)
        val sizeText = String.format(Locale.US, "%.1f MB", sizeMb)
        val duration = formatDuration(durationMillis(file))
        val title = file.name.removeSuffix(".m4a")
        return "$title\n$date  •  $duration  •  $sizeText"
    }

    private fun durationMillis(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            raw?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignora
            }
        }
    }

    private fun showFileOptions(file: File) {
        val title = file.name.removeSuffix(".m4a")
        val options = arrayOf("Ouvir", "Compartilhar", "Renomear", "Apagar")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> playFile(file)
                    1 -> shareFile(file)
                    2 -> renameFile(file)
                    3 -> confirmDelete(file)
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ---------------------------------------------------------------
    // Reproducao
    // ---------------------------------------------------------------

    private fun playFile(file: File) {
        stopPlayer()
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setOnCompletionListener {
                stopPlayer()
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível reproduzir este arquivo.", Toast.LENGTH_LONG).show()
            stopPlayer()
            return
        }

        playerDialog = AlertDialog.Builder(this)
            .setTitle("Reproduzindo")
            .setMessage(file.name.removeSuffix(".m4a"))
            .setPositiveButton("Parar reprodução") { _, _ -> stopPlayer() }
            .setOnDismissListener { stopPlayer() }
            .show()
    }

    private fun stopPlayer() {
        try {
            if (player?.isPlaying == true) {
                player?.stop()
            }
        } catch (e: Exception) {
            // ignora
        }
        try {
            player?.release()
        } catch (e: Exception) {
            // ignora
        }
        player = null
        val dialog = playerDialog
        playerDialog = null
        if (dialog != null && dialog.isShowing) {
            // Evita loop com o OnDismissListener.
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }

    // ---------------------------------------------------------------
    // Compartilhar / renomear / apagar
    // ---------------------------------------------------------------

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name.removeSuffix(".m4a"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartilhar episódio"))
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível compartilhar este arquivo.", Toast.LENGTH_LONG).show()
        }
    }

    private fun renameFile(file: File) {
        val currentName = file.name.removeSuffix(".m4a")
        val edit = EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Renomear")
            .setView(edit)
            .setPositiveButton("Renomear") { _, _ ->
                val newName = sanitizeFileName(edit.text.toString()).removeSuffix(".m4a")
                if (newName.isBlank()) {
                    Toast.makeText(this, "Nome inválido.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val target = File(file.parentFile, "$newName.m4a")
                if (target.exists() && target.absolutePath != file.absolutePath) {
                    Toast.makeText(this, "Já existe um arquivo com esse nome.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (file.renameTo(target)) {
                    Toast.makeText(this, "Renomeado.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Não foi possível renomear.", Toast.LENGTH_SHORT).show()
                }
                refreshLibrary()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Apagar gravação?")
            .setMessage(file.name.removeSuffix(".m4a"))
            .setPositiveButton("Apagar") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Apagado.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Não foi possível apagar.", Toast.LENGTH_SHORT).show()
                }
                refreshLibrary()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun defaultTitle(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return "Episódio " + fmt.format(Date())
    }

    private fun sanitizeFileName(raw: String): String {
        var name = raw.trim()
        if (name.isBlank()) {
            name = defaultTitle()
        }
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        name = name.replace(Regex("\\s+"), " ")
        if (name.length > 80) {
            name = name.substring(0, 80).trim()
        }
        if (!name.endsWith(".m4a")) {
            name = "$name.m4a"
        }
        return name
    }

    private fun uniqueFileName(baseName: String): String {
        val dir = RecordingService.recordingsDir(this)
        var candidate = baseName
        var counter = 1
        while (File(dir, candidate).exists()) {
            val stem = baseName.removeSuffix(".m4a")
            candidate = "$stem ($counter).m4a"
            counter++
        }
        return candidate
    }

    private fun formatElapsed(millis: Long): String {
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "00:00"
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
