package com.github.zakgrivell.invadetheburg.eta

import com.github.zakgrivell.invadetheburg.services.eta.Stretch
import com.github.zakgrivell.invadetheburg.services.eta.evaluate
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Temporary CSV reader until session history can supply observed stretches. */
object EtaEvaluationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Usage: EtaEvaluationMain <logDir> [seed]" }
        args.getOrNull(1)?.let { require(it.toLongOrNull() != null) { "seed must be an integer" } }

        val lines = Files.readAllLines(Path.of(args[0]).resolve("stretches.csv"))
        require(lines.firstOrNull() == "startTs,durationS") { "Expected startTs,durationS header" }
        val stretches = lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val columns = line.split(',')
            require(columns.size == 2) { "Expected startTs,durationS: $line" }
            Stretch(Instant.parse(columns[0].trim()), columns[1].trim().toDouble())
        }
        println(evaluate(stretches, minSamples = 5).toMarkdown())
    }
}
