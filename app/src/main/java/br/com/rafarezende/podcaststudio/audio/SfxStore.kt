package br.com.rafarezende.podcaststudio.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class PadKind { FILE, EMPTY }

data class PadConfig(
    val name: String,
    val kind: PadKind,
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

    init {
        migrateOldDefaultColors()
    }

    /**
     * Migração (nov/2026, layout do mockup): o padrão antigo era verde da
     * logo em todos os pads. Todo pad salvo que ainda esteja nesse verde
     * passa para a cor da sua posição. Roda uma única vez (flag
     * "migrated_v3_colors"): o Rafael nunca personalizou cor de pad,
     * então na prática isso só troca o padrão antigo pelo novo. Pads com
     * som carregado mantêm o arquivo — só a cor muda. Quem escolher
     * "Verde logo" no seletor depois da migração não é afetado, porque a
     * flag já está marcada.
     */
    private fun migrateOldDefaultColors() {
        if (prefs.getBoolean("migrated_v3_colors", false)) return
        for (index in 0 until PAD_COUNT) {
            val raw = prefs.getString(key(index), null) ?: continue
            try {
                val json = JSONObject(raw)
                if (json.optInt("color", 0) == OLD_DEFAULT_GREEN) {
                    val current = get(index)
                    save(index, current.copy(color = defaultConfig(index).color))
                }
            } catch (e: Exception) {
                Log.w(TAG, "migracao de cor do pad $index falhou", e)
            }
        }
        prefs.edit().putBoolean("migrated_v3_colors", true).apply()
    }

    companion object {
        private const val TAG = "SfxStore"
        private const val PREFS_NAME = "sfx_pads"
        const val PAD_COUNT = 12

        // Cores do mockup DX Labs, na ordem dos pads. "Verde logo" continua
        // na lista: o seletor de cor (segurar o pad) oferece todas elas.
        // COLOR_NAMES e COLORS precisam continuar paralelas (mesma ordem).
        val COLOR_NAMES = arrayOf(
            "Roxo", "Laranja", "Azul", "Cinza escuro", "Verde",
            "Rosa", "Azul claro", "Violeta", "Amarelo", "Verde-água",
            "Vermelho", "Grafite", "Verde logo"
        )

        val COLORS = intArrayOf(
            0xFF7C5CFC.toInt(), // roxo
            0xFFFF8A3D.toInt(), // laranja
            0xFF3D7BFF.toInt(), // azul
            0xFF2E2E3A.toInt(), // cinza escuro
            0xFF2FBF71.toInt(), // verde
            0xFFEC5FA8.toInt(), // rosa
            0xFF4FA8FF.toInt(), // azul claro
            0xFF9B5CFF.toInt(), // violeta
            0xFFF2C230.toInt(), // amarelo
            0xFF2BB3A3.toInt(), // verde-água
            0xFFE9452B.toInt(), // vermelho
            0xFF3A3A44.toInt(), // grafite
            0xFFB8FB2A.toInt()  // verde da logo
        )

        /** Ícone (glifo) de cada pad, na ordem do mockup. */
        val PAD_ICONS = arrayOf(
            "♪", "👏", "⇄", "+", "👥", "😄",
            "💨", "🔔", "★", "📻", "🥁", "+"
        )

        /** Verde da logo: era o padrão único de todos os pads. */
        val OLD_DEFAULT_GREEN = 0xFFB8FB2A.toInt()

        /** Cache de PCM decodificado: chave = caminho + lastModified. */
        private val pcmCache = HashMap<String, ShortArray>()

        // Cada pad nasce com a cor da sua posição (ordem do mockup);
        // segurar o pad troca a cor.
        private val DEFAULT_COLOR_IDX = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

        fun defaultConfig(index: Int): PadConfig {
            // Todos os pads comecam vazios: nenhum som pre-carregado.
            val color = COLORS[DEFAULT_COLOR_IDX[index.coerceIn(0, PAD_COUNT - 1)]]
            return PadConfig("+ Som", PadKind.EMPTY, null, color)
        }
    }

    fun get(index: Int): PadConfig {
        val raw = prefs.getString(key(index), null) ?: return defaultConfig(index)
        return try {
            val json = JSONObject(raw)
            val kindName = json.optString("kind", "EMPTY")
            val savedColor = json.optInt("color", defaultConfig(index).color)
            // Migracao (V2.2): versoes antigas tinham sons de fabrica
            // (kind "BUILTIN"). Como nao ha mais nenhum som pre-carregado,
            // esses pads viram vazios — uma vez, ja persistido. Pads FILE
            // (sons do proprio usuario) sao preservados como estao.
            if (kindName == "BUILTIN") {
                // Se ainda estava no verde antigo, usa a cor da posição.
                val color = if (savedColor == OLD_DEFAULT_GREEN) {
                    defaultConfig(index).color
                } else {
                    savedColor
                }
                val converted = PadConfig("+ Som", PadKind.EMPTY, null, color)
                save(index, converted)
                return converted
            }
            val kind = try {
                PadKind.valueOf(kindName)
            } catch (e: Exception) {
                // Strings desconhecidas (ex.: formatos futuros/antigos)
                // sao tratadas como pad vazio, sem quebrar o parse.
                PadKind.EMPTY
            }
            val fileName = json.optString("file", "").ifBlank { null }
            PadConfig(
                name = json.optString("name", defaultConfig(index).name),
                kind = kind,
                fileName = fileName,
                color = savedColor
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
            json.put("file", config.fileName ?: "")
            json.put("color", config.color)
            prefs.edit().putString(key(index), json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "falha ao salvar pad $index", e)
        }
    }

    /**
     * Resolve o PCM do pad. EMPTY: sem som (null). FILE: decodifica com
     * cache — pode demorar na primeira vez, chame fora da UI thread.
     */
    fun pcmFor(index: Int): ShortArray? {
        val config = get(index)
        return when (config.kind) {
            PadKind.EMPTY -> null
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

    /** Apaga a copia de arquivo de um pad (ao esvaziar/trocar). */
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
