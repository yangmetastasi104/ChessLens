package com.chesslens.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext

data class SearchLimit(val depth: Int = 18, val moveTimeMillis: Long? = null) {
    init {
        require(depth in 1..64)
        require(moveTimeMillis == null || moveTimeMillis in 50..120_000)
    }
}

data class EngineInfo(
    val depth: Int,
    val evaluationPawns: Float?,
    val mateIn: Int?,
    val principalVariation: List<String>
)

data class EngineResult(val bestMove: String?, val info: EngineInfo?)

/**
 * UCI process supervisor for a bundled Stockfish executable. It never fabricates an
 * evaluation: if the licensed engine binary is absent, construction/analyze reports
 * that explicitly. Search and protocol I/O are kept off the main thread.
 */
class StockfishManager(
    private val executable: File,
    private val initialize: suspend () -> Unit = {}
) : Closeable {
    private val searchMutex = Mutex()
    private val output = Channel<String>(Channel.UNLIMITED)
    @Volatile private var process: Process? = null
    @Volatile private var writer: Appendable? = null
    @Volatile private var readerThread: Thread? = null

    suspend fun analyze(
        fen: String,
        limit: SearchLimit = SearchLimit(),
        onInfo: (EngineInfo) -> Unit = {}
    ): EngineResult = searchMutex.withLock {
        require(fen.trim().split(Regex("\\s+")).size == 6) { "A complete FEN is required" }
        withContext(Dispatchers.IO) {
            startIfNeeded()
            send("ucinewgame")
            send("position fen ${fen.trim()}")
            send(if (limit.moveTimeMillis != null) "go movetime ${limit.moveTimeMillis}" else "go depth ${limit.depth}")
            var latest: EngineInfo? = null
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    val line = withTimeout(180_000) { output.receive() }
                    if (line.startsWith("info ")) {
                        parseInfo(line, fen)?.let {
                            latest = it
                            onInfo(it)
                        }
                    } else if (line.startsWith("bestmove ")) {
                        val move = line.substringAfter("bestmove ").substringBefore(' ').takeUnless { it == "0000" || it == "(none)" }
                        return@withContext EngineResult(move, latest)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                EngineResult(null, latest)
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) {
                    send("stop")
                    withTimeoutOrNull(1_000) {
                        while (output.receive().startsWith("bestmove ").not()) Unit
                    }
                }
                throw cancel
            }
        }
    }

    private suspend fun startIfNeeded() {
        if (process?.isAlive == true) return
        initialize()
        if (!executable.isFile) throw EngineUnavailableException(
            "Stockfish executable is missing at ${executable.absolutePath}. No evaluation was generated."
        )
        if (!executable.canExecute() && !executable.setExecutable(true))
            throw EngineUnavailableException("Stockfish executable is not runnable: ${executable.absolutePath}")
        val proc = ProcessBuilder(executable.absolutePath)
            .directory(executable.parentFile)
            .redirectErrorStream(true)
            .start()
        process = proc
        val sink = proc.outputStream.bufferedWriter(Charsets.UTF_8)
        writer = sink
        val thread = Thread({
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { output.trySend(it) }
                }
            } catch (_: Exception) { /* process termination closes the stream */ }
        }, "ChessLens-Stockfish-UCI").apply { isDaemon = true; start() }
        readerThread = thread
        send("uci")
        withTimeout(10_000) {
            while (output.receive() != "uciok") Unit
        }
        send("isready")
        withTimeout(5_000) {
            while (output.receive() != "readyok") Unit
        }
    }

    @Synchronized private fun send(command: String) {
        val sink = writer ?: throw EngineUnavailableException("Stockfish is not running")
        sink.append(command).append('\n')
        (sink as? java.io.Writer)?.flush()
    }

    private fun parseInfo(line: String, fen: String): EngineInfo? {
        val tokens = line.split(Regex("\\s+"))
        val depth = tokens.indexOf("depth").takeIf { it >= 0 }?.let { tokens.getOrNull(it + 1)?.toIntOrNull() } ?: return null
        val scoreAt = tokens.indexOf("score")
        val whiteToMove = fen.trim().split(Regex("\\s+"))[1] == "w"
        var cp: Float? = null
        var mate: Int? = null
        if (scoreAt >= 0 && scoreAt + 2 < tokens.size) {
            when (tokens[scoreAt + 1]) {
                "cp" -> tokens[scoreAt + 2].toFloatOrNull()?.let { cp = (if (whiteToMove) it else -it) / 100f }
                "mate" -> tokens[scoreAt + 2].toIntOrNull()?.let { mate = if (whiteToMove) it else -it }
            }
        }
        val pvIndex = tokens.indexOf("pv")
        val pv = if (pvIndex >= 0) tokens.drop(pvIndex + 1).takeWhile { it.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?")) } else emptyList()
        return EngineInfo(depth, cp, mate, pv)
    }

    override fun close() {
        runCatching { send("quit") }
        val proc = process
        process = null
        writer = null
        proc?.destroy()
        readerThread?.interrupt()
        readerThread = null
        output.close()
    }

    companion object {
        private const val ENGINE_ASSET = "stockfish/arm64-v8a/stockfish"
        private val assetSizes = mapOf(
            ENGINE_ASSET to 674_208L,
            "stockfish/nn-b1a57edbea57.nnue" to 65_429_575L,
            "stockfish/nn-baff1ede1f90.nnue" to 3_480_122L
        )
        private val networkAssets = listOf(
            "stockfish/nn-b1a57edbea57.nnue" to "nn-b1a57edbea57.nnue",
            "stockfish/nn-baff1ede1f90.nnue" to "nn-baff1ede1f90.nnue"
        )

        fun bundled(context: Context): StockfishManager {
            val appContext = context.applicationContext
            val engineDirectory = File(appContext.noBackupFilesDir, "chesslens-stockfish")
            val executable = File(engineDirectory, "stockfish")
            return StockfishManager(executable) {
                if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                    throw EngineUnavailableException("The bundled Stockfish build currently supports ARM64 only.")
                }
                try {
                    if (!engineDirectory.isDirectory && !engineDirectory.mkdirs()) {
                        throw EngineUnavailableException("Could not create the private Stockfish directory.")
                    }
                    val assets = listOf(ENGINE_ASSET to executable) +
                        networkAssets.map { (asset, filename) -> asset to File(engineDirectory, filename) }
                    for ((assetPath, destination) in assets) {
                        val assetSize = assetSizes.getValue(assetPath)
                        if (destination.isFile && destination.length() == assetSize) continue
                        val temporary = File(engineDirectory, "${destination.name}.partial")
                        try {
                            appContext.assets.open(assetPath).use { input ->
                                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                            }
                            if (temporary.length() != assetSize) {
                                throw EngineUnavailableException("Bundled Stockfish file has an invalid size: ${destination.name}")
                            }
                            if (!temporary.renameTo(destination)) {
                                destination.delete()
                                if (!temporary.renameTo(destination)) {
                                    throw EngineUnavailableException("Could not install bundled Stockfish file ${destination.name}")
                                }
                            }
                        } finally {
                            temporary.delete()
                        }
                    }
                    if (!executable.setExecutable(true, true) && !executable.canExecute()) {
                        throw EngineUnavailableException("Could not mark bundled Stockfish executable as runnable.")
                    }
                } catch (e: EngineUnavailableException) {
                    throw e
                } catch (e: Exception) {
                    throw EngineUnavailableException("Could not prepare bundled Stockfish: ${e.message}")
                }
            }
        }
    }
}

class EngineUnavailableException(message: String) : IllegalStateException(message)
