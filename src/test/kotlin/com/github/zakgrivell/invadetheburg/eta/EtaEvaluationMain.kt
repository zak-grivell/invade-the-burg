package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.SessionHistory
import com.github.zakgrivell.invadetheburg.services.eta.evaluate
import java.nio.file.Files
import java.nio.file.Path

/** Evaluates the ETA baselines on real session logs: `EtaEvaluationMain <logDir> [seedFile]`. */
object EtaEvaluationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Usage: EtaEvaluationMain <logDir> [seedFile]" }
        val logDir = Path.of(args[0])
        val seedFile = args.getOrNull(1)?.let(Path::of)
        require(Files.isDirectory(logDir)) { "Log directory not found: $logDir" }
        require(seedFile == null || Files.isRegularFile(seedFile)) { "Seed file not found: $seedFile" }

        // The seed may live inside logDir (as in the test resources); it must not also count as a log.
        val files = SessionHistory.logFiles(logDir, exclude = seedFile)
        val fromLogs = SessionHistory.loadLogs(logDir, exclude = seedFile)
        val fromSeed = seedFile?.let(SessionHistory::loadSeed).orEmpty()

        println("Log files loaded (${files.size}): ${files.joinToString { it.fileName.toString() }.ifEmpty { "none" }}")
        println("Seed file: ${seedFile ?: "none"}")
        println("Stretches from logs: ${fromLogs.size}, from seed: ${fromSeed.size}")
        println()
        println(evaluate((fromLogs + fromSeed).sortedBy { it.startTs }, minSamples = 5).toMarkdown())
    }
}
