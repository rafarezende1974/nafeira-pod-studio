package br.com.rafarezende.podcaststudio.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class PadKind { BUILTIN, FILE, EMPTY }

data class PadConfig(
    val name: String,
    val kind: PadKind,
    val builtinId: SfxSynth.BuiltinId?,
    val fileName: String?,
    val color: Int
)

/**
 * Configuracao dos 12 pads da mesa de efeitos, persistida em
 * SharedPreferences (JSON via org.json, sem dependencia nova).
 * Arquivos de som do usuario sao copiados pra filesDir/sfx/.
 */
class SfxStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SfxStore"
        private const val PREFS_NAME = "sfx_pads"
        const val PAD_COUNT = 12

        val COLOR_NAMES = arrayOf(
            "Vermelho", "Laranja", "Amarelo", "Verde",
            "Azul", "Roxo", "Rosa", "Cinza"
        )

        val COLORS = intArrayOf(
            0xFFE9452B.toInt(), // vermelho
            0xFFF07D1A.toInt(), // laranja
            0xFFE8B90F.toInt(), // amarelo
            0xFF35B558.toInt(), // verde
            0xFF2B7DE9.toInt(), // azul
            0xFF8B5CF6.toInt(), // roxo
            0xFFE94F9A.toInt(), // rosa
            0xFF77778A.toInt()  // cinza
        )

        /** Cache de PCM decodificado: chave = caminho + lastModified. */
        private val pcmCache = HashMap<String, ShortArray>()

        private val DEFAULT_COLOR_IDX = intArrayOf(0, 2, 1, 4, 5, 0, 3, 6, 7, 7, 7, 7)

        fun defaultConfig(index: Int): PadConfig {
            val color = COLORS[DEFAULT_COLOR_IDX[index.coerceIn(0, PAD_COUNT - 1)]]
            return when (index) {
                0 -> PadConfig("👏 Aplausos", PadKind.BUILTIN, SfxSynth.BuiltinId.APPLAUSE, null, color)
                1 -> PadConfig("😂 Risadas", PadKind.BUILTIN, SfxSynth.BuiltinId.LAUGH, null, color)
                2 -> PadConfig("🔔 Campainha", PadKind.BUILTIN, SfxSynth.BuiltinId.BELL, null, color)
                3 -> PadConfig("✨ Ding", PadKind.BUILTIN, SfxSynth.BuiltinId.DING, null, color)
                4 -> PadConfig("🥁 Ba dum tss", PadKind.BUILTIN, SfxSynth.BuiltinId.STING, null, color)
                5 -> PadConfig("📯 Buzina", PadKind.BUILTIN, SfxSynth.BuiltinId.HORN, null, color)
                6 -> PadConfig("💨 Transição", PadKind.BUILTIN, SfxSynth.BuiltinId.WHOOSH, null, color)
                7 -> PadConfig("🎬 Vinheta", PadKind.BUILTIN, SfxSynth.BuiltinId.JINGLE, null, color)
                else -> PadConfig("+ Som", PadKind.EMPTY, null, null, color)
            }
        }
    }

    fun get(index: Int): PadConfig {
        val raw = prefs.getString(key(index), null) ?: return defaultConfig(index)
        return try {
            val json = JSONObject(raw)
            val kindName = json.optString("kind", "EMPTY")
            val kind = try {
                PadKind.valueOf(kindName)
            } catch (e: Exception) {
                PadKind.EMPTY
            }
            val builtinName = json.optString("builtin", "")
            val builtin = if (builtinName.isNotBlank()) {
                try {
                    SfxSynth.BuiltinId.valueOf(builtinName)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            val fileName = json.optString("file", "").ifBlank { null }
            PadConfig(
                name = json.optString("name", defaultConfig(index).name),
                kind = kind,
                builtinId = builtin,
                fileName = fileName,
                color = json.optInt("color", defaultConfig(index).color)
            )
        } catch (e: Exception) {
            Log.w(TAG, "config do pad $index ilegivel, usando padrao", e)
            defaultConfig(index)
        }
    }

    fun all(): List<PadConfig> = (0 until PAD_COUNT).map { get(it) }

    fun save(index: Int, config: PadConfig) {
        try {
            val json = JSONObject()
            json.put("name", config.name)
            json.put("kind", config.kind.name)
            json.put("builtin", config.builtinId?.name ?: "")
            json.put("file", config.fileName ?: "")
            json.put("color", config.color)
            prefs.edit().putString(key(index), json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "falha ao salvar pad $index", e)
        }
    }

    /**
     * Resolve o PCM do pad. BUILTIN: sintetiza (rapido). FILE: decodifica
     * com cache — pode demorar na primeira vez, chame fora da UI thread.
     */
    fun pcmFor(index: Int): ShortArray? {
        val config = get(index)
        return when (config.kind) {
            PadKind.EMPTY -> null
            PadKind.BUILTIN -> config.builtinId?.let { SfxSynth.get(it) }
            PadKind.FILE -> {
                val fileName = config.fileName ?: return null
                val file = File(sfxDir(), fileName)
                if (!file.exists()) {
                    Log.w(TAG, "arquivo do pad $index sumiu: $fileName")
                    return null
                }
                val cacheKey = file.absolutePath + "@" + file.lastModified()
                synchronized(pcmCache) {
                    val cached = pcmCache[cacheKey]
                    if (cached != null) return cached
                }
                val decoded = SfxDecoder.decode(file)
                if (decoded != null) {
                    synchronized(pcmCache) {
                        pcmCache[cacheKey] = decoded
                    }
                }
                decoded
            }
        }
    }

    fun sfxDir(): File {
        val dir = File(appContext.filesDir, "sfx")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Copia o audio escolhido pelo usuario pra filesDir/sfx/pad_<index>.<ext>,
     * apagando a copia anterior daquele pad. Retorna o arquivo copiado.
     */
    @Throws(Exception::class)
    fun importFile(index: Int, uri: Uri, extension: String): File {
        val ext = extension.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "audio" }
        val dir = sfxDir()
        // Apaga copias anteriores deste pad (qualquer extensao).
        dir.listFiles { f -> f.name.startsWith("pad_$index.") }?.forEach { old ->
            try {
                old.delete()
            } catch (e: Exception) {
                // ignora
            }
        }
        synchronized(pcmCache) {
            val it = pcmCache.keys.iterator()
            while (it.hasNext()) {
                if (it.next().contains("pad_$index.")) it.remove()
            }
        }
        val target = File(dir, "pad_$index.$ext")
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Nao foi possivel abrir o arquivo escolhido.")
        input.use { inp ->
            FileOutputStream(target).use { out ->
                inp.copyTo(out)
            }
        }
        if (target.length() == 0L) {
            target.delete()
            throw IllegalStateException("Arquivo vazio.")
        }
        return target
    }

    /** Apaga a copia de arquivo de um pad (ao esvaziar/restaurar/trocar). */
    fun deletePadFile(index: Int, fileName: String?) {
        if (fileName.isNullOrBlank()) return
        try {
            val file = File(sfxDir(), fileName)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "falha ao apagar arquivo do pad $index", e)
        }
        synchronized(pcmCache) {
            val it = pcmCache.keys.iterator()
            while (it.hasNext()) {
                if (it.next().contains(fileName)) it.remove()
            }
        }
    }

    private fun key(index: Int) = "pad_$index"
}
