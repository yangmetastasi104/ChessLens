package com.chesslens.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StockfishManagerTest {
    @Test fun uciHandshakeAndSearchInfoAreParsed(): Unit = runBlocking {
        val script = File.createTempFile("fake-uci", ".sh")
        script.writeText(
            """#!/bin/sh
while IFS= read -r line; do
  case "${'$'}line" in
    uci) echo "id name UCI protocol test"; echo uciok ;;
    isready) echo readyok ;;
    "go depth"*) echo "info depth 7 score cp 42 pv e2e4 e7e5"; echo "bestmove e2e4 ponder e7e5" ;;
    quit) exit 0 ;;
  esac
done
"""
        )
        script.setExecutable(true)
        val manager = StockfishManager(script)
        try {
            val result = manager.analyze("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", SearchLimit(depth = 7))
            assertEquals("e2e4", result.bestMove)
            assertEquals(7, result.info?.depth)
            assertEquals(.42f, result.info?.evaluationPawns)
            assertEquals(listOf("e2e4", "e7e5"), result.info?.principalVariation)
        } finally {
            manager.close()
            script.delete()
        }
    }

    @Test(expected = EngineUnavailableException::class)
    fun missingEngineDoesNotReturnInventedAnalysis(): Unit = runBlocking {
        StockfishManager(File("/definitely/not/a/stockfish")).analyze(
            "8/8/8/8/8/8/8/8 w - - 0 1"
        )
    }
}
