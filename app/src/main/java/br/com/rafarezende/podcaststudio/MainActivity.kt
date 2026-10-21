package br.com.rafarezende.podcaststudio

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import br.com.rafarezende.podcaststudio.audio.EffectPlayer
import br.com.rafarezende.podcaststudio.audio.PadConfig
import br.com.rafarezende.podcaststudio.audio.PadKind
import br.com.rafarezende.podcaststudio.audio.SfxStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ComponentActivity e subclasse direta de android.app.Activity (sem AppCompat
// e sem Compose) e e quem expoe o onBackPressedDispatcher usado abaixo.
class MainActivity : ComponentActivity() {

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
    private lateinit var gridPads: GridLayout
    private lateinit var seekFx: SeekBar
    private lateinit var btnStopFx: Button
    private lateinit var tabRecord: Button
    private lateinit var tabLibrary: Button
    private lateinit var pageRecord: View
    private lateinit var pageLibrary: View

    // Ultimo estado visto do servico: serve pra avisar "gravacao salva"
    // so na transicao de gravando/pausado -> parado (V2.1).
    private var lastServiceState = RecordingService.State.IDLE

    private val padButtons = ArrayList<Button>()
    private lateinit var sfxStore: SfxStore
    private val effectPlayer = EffectPlayer()
    private var pendingPadIndex = -1

    private val deviceIds = mutableListOf<Int>()
    private val deviceNames = mutableListOf<String>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var recordings: List<File> = emptyList()
    private lateinit var libraryAdapter: ArrayAdapter<String>

    private var pendingStart = false

    // Pedido de permissoes pelo Activity Result API (o override classico de
    // onRequestPermissionsResult nao existe no ComponentActivity e quebrou
    // o primeiro build).
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
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

    // Seletor de arquivo de som pro pad (OpenDocument, sem permissao de
    // armazenamento). O resultado e tratado em handlePadFilePicked().
    private val padFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val index = pendingPadIndex
        pendingPadIndex = -1
        if (uri != null && index >= 0) {
            handlePadFilePicked(index, uri)
        }
    }

    private var player: MediaPlayer? = null
    private var playerDialog: AlertDialog? = null

    private val serviceListener = object : RecordingService.Listener {
        override fun onStateChanged() {
            runOnUiThread {
                val state = RecordingService.state
                updateUi()
                if (state == RecordingService.State.IDLE) {
                    if (lastServiceState != RecordingService.State.IDLE) {
                        // Acabou de parar uma gravacao: avisa onde ela esta
                        // (a biblioteca agora fica na outra aba — V2.1).
                        Toast.makeText(
                            this@MainActivity,
                            "Gravação salva — veja na aba Biblioteca",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    refreshLibrary()
                }
                lastServiceState = state
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
        gridPads = findViewById(R.id.gridPads)
        seekFx = findViewById(R.id.seekFx)
        btnStopFx = findViewById(R.id.btnStopFx)
        tabRecord = findViewById(R.id.tabRecord)
        tabLibrary = findViewById(R.id.tabLibrary)
        pageRecord = findViewById(R.id.pageRecord)
        pageLibrary = findViewById(R.id.pageLibrary)

        tabRecord.setOnClickListener { showPage(false) }
        tabLibrary.setOnClickListener {
            showPage(true)
            refreshLibrary()
        }
        showPage(false)

        sfxStore = SfxStore(this)
        buildPads()

        val prefs = getSharedPreferences("podcast_studio", Context.MODE_PRIVATE)
        val savedFx = prefs.getInt("fx_volume", 80).coerceIn(0, 100)
        seekFx.progress = savedFx
        applyFxVolume(savedFx)
        seekFx.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyFxVolume(progress)
                prefs.edit().putInt("fx_volume", progress).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        btnStopFx.setOnClickListener {
            effectPlayer.stop()
            sendServiceAction(RecordingService.ACTION_STOP_EFFECTS)
        }

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
        effectPlayer.stop()
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
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    // ---------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------

    /** Alterna entre as paginas Gravar e Biblioteca (abas da V2.1). */
    private fun showPage(library: Boolean) {
        pageRecord.visibility = if (library) View.GONE else View.VISIBLE
        pageLibrary.visibility = if (library) View.VISIBLE else View.GONE
        val accent = ContextCompat.getColor(this, R.color.accent)
        val inactive = ContextCompat.getColor(this, R.color.surface_light)
        tabRecord.backgroundTintList =
            ColorStateList.valueOf(if (library) inactive else accent)
        tabLibrary.backgroundTintList =
            ColorStateList.valueOf(if (library) accent else inactive)
    }

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
    // Mesa de efeitos (soundboard)
    // ---------------------------------------------------------------

    private fun applyFxVolume(progress: Int) {
        val vol = progress.coerceIn(0, 100) / 100f
        RecordingService.effectsVolume = vol
        effectPlayer.volume = vol
    }

    private fun buildPads() {
        gridPads.removeAllViews()
        padButtons.clear()
        val density = resources.displayMetrics.density
        // Altura UNIFORME pra todos os pads (V2.1): nome longo nao pode
        // quebrar em 2 linhas nem mudar a altura da fileira (era a queixa
        // do "botao desalinhado" no celular).
        val heightPx = (52 * density).toInt()
        val marginPx = (4 * density).toInt()
        val padHPx = (4 * density).toInt()
        for (index in 0 until SfxStore.PAD_COUNT) {
            val button = Button(this)
            val params = GridLayout.LayoutParams()
            params.width = 0
            params.height = heightPx
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            params.setMargins(marginPx, marginPx, marginPx, marginPx)
            button.layoutParams = params
            button.setTextColor(Color.WHITE)
            button.textSize = 12f
            button.setSingleLine(true)
            button.maxLines = 1
            button.ellipsize = android.text.TextUtils.TruncateAt.END
            button.setPadding(padHPx, 0, padHPx, 0)
            button.setOnClickListener { onPadClicked(index) }
            button.setOnLongClickListener {
                showPadCustomization(index)
                true
            }
            gridPads.addView(button)
            padButtons.add(button)
            refreshPadButton(index)
        }
    }

    private fun refreshPadButton(index: Int) {
        val button = padButtons.getOrNull(index) ?: return
        val config = sfxStore.get(index)
        button.text = config.name
        button.backgroundTintList = ColorStateList.valueOf(config.color)
    }

    private fun refreshAllPads() {
        for (index in 0 until SfxStore.PAD_COUNT) {
            refreshPadButton(index)
        }
    }

    private fun onPadClicked(index: Int) {
        val config = sfxStore.get(index)
        when (RecordingService.state) {
            RecordingService.State.RECORDING -> {
                if (config.kind == PadKind.EMPTY) {
                    Toast.makeText(
                        this,
                        "Pad vazio — segure pra escolher um som.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                val intent = Intent(this, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_TRIGGER_PAD
                    putExtra(RecordingService.EXTRA_PAD_INDEX, index)
                }
                try {
                    startService(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Não foi possível tocar o efeito.", Toast.LENGTH_SHORT).show()
                }
            }
            RecordingService.State.PAUSED -> {
                Toast.makeText(
                    this,
                    "Continue a gravação pra o efeito entrar nela.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            RecordingService.State.IDLE -> {
                if (config.kind == PadKind.EMPTY) {
                    openPadFilePicker(index)
                } else {
                    playPadPreview(index)
                }
            }
        }
    }

    /** Preview do pad fora da gravacao (decodifica em thread de fundo). */
    private fun playPadPreview(index: Int) {
        Thread {
            val pcm = try {
                sfxStore.pcmFor(index)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (pcm != null) {
                    effectPlayer.play(pcm)
                } else {
                    Toast.makeText(
                        this,
                        "Não consegui tocar esse som.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    private fun showPadCustomization(index: Int) {
        val config = sfxStore.get(index)
        val options = ArrayList<String>()
        options.add("Trocar som (arquivo do aparelho)")
        options.add("Renomear")
        options.add("Trocar cor")
        if (index < 8) {
            options.add("Restaurar padrão")
        } else if (config.kind == PadKind.FILE) {
            options.add("Esvaziar pad")
        }
        AlertDialog.Builder(this)
            .setTitle(config.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> openPadFilePicker(index)
                    1 -> renamePad(index)
                    2 -> pickPadColor(index)
                    3 -> {
                        if (index < 8) resetPad(index) else clearPad(index)
                    }
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun openPadFilePicker(index: Int) {
        pendingPadIndex = index
        try {
            padFileLauncher.launch(arrayOf("audio/*"))
        } catch (e: Exception) {
            pendingPadIndex = -1
            Toast.makeText(this, "Nenhum seletor de arquivos disponível.", Toast.LENGTH_LONG).show()
        }
    }

    /** Copia o arquivo escolhido pro pad e valida decodificando (fundo). */
    private fun handlePadFilePicked(index: Int, uri: Uri) {
        val previous = sfxStore.get(index)
        val displayName = queryDisplayName(uri)
        val extension = extensionFromName(displayName)
        Toast.makeText(this, "Importando som…", Toast.LENGTH_SHORT).show()
        Thread {
            var importedName: String? = null
            var ok = false
            try {
                val file = sfxStore.importFile(index, uri, extension)
                importedName = file.name
                val probe = PadConfig(
                    name = previous.name,
                    kind = PadKind.FILE,
                    builtinId = null,
                    fileName = file.name,
                    color = previous.color
                )
                sfxStore.save(index, probe)
                // Valida: so vale se decodificar.
                ok = sfxStore.pcmFor(index) != null
                if (ok) {
                    val newName = if (previous.kind == PadKind.EMPTY ||
                        previous.name == "+ Som"
                    ) {
                        displayName.substringBeforeLast('.').ifBlank { previous.name }
                    } else {
                        previous.name
                    }
                    sfxStore.save(index, probe.copy(name = newName))
                }
            } catch (e: Exception) {
                ok = false
            }
            if (!ok) {
                // Desfaz: volta a configuracao anterior e apaga a copia.
                sfxStore.save(index, previous)
                if (importedName != null) {
                    sfxStore.deletePadFile(index, importedName)
                }
            }
            val success = ok
            runOnUiThread {
                refreshPadButton(index)
                if (success) {
                    Toast.makeText(this, "Som do pad atualizado.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Não consegui usar esse arquivo de áudio.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = "som"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getString(idx)
                    if (!value.isNullOrBlank()) name = value
                }
            }
        } catch (e: Exception) {
            // mantem o nome generico
        }
        return name
    }

    private fun extensionFromName(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "")
        return ext.ifBlank { "audio" }
    }

    private fun renamePad(index: Int) {
        val config = sfxStore.get(index)
        val edit = EditText(this).apply {
            setText(config.name)
            setSelection(config.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Renomear pad")
            .setView(edit)
            .setPositiveButton("Salvar") { _, _ ->
                val newName = edit.text.toString().trim()
                if (newName.isNotBlank()) {
                    sfxStore.save(index, config.copy(name = newName))
                    refreshPadButton(index)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickPadColor(index: Int) {
        val config = sfxStore.get(index)
        AlertDialog.Builder(this)
            .setTitle("Cor do pad")
            .setItems(SfxStore.COLOR_NAMES) { _, which ->
                sfxStore.save(index, config.copy(color = SfxStore.COLORS[which]))
                refreshPadButton(index)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun resetPad(index: Int) {
        val current = sfxStore.get(index)
        if (current.kind == PadKind.FILE) {
            sfxStore.deletePadFile(index, current.fileName)
        }
        sfxStore.save(index, SfxStore.defaultConfig(index))
        refreshPadButton(index)
        Toast.makeText(this, "Pad restaurado.", Toast.LENGTH_SHORT).show()
    }

    private fun clearPad(index: Int) {
        val current = sfxStore.get(index)
        if (current.kind == PadKind.FILE) {
            sfxStore.deletePadFile(index, current.fileName)
        }
        sfxStore.save(index, SfxStore.defaultConfig(index))
        refreshPadButton(index)
        Toast.makeText(this, "Pad esvaziado.", Toast.LENGTH_SHORT).show()
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
        // Contador de gravacoes no rotulo da aba (V2.1).
        if (::tabLibrary.isInitialized) {
            tabLibrary.text = if (files.isEmpty()) {
                "📚 Biblioteca"
            } else {
                "📚 Biblioteca (${files.size})"
            }
        }
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
