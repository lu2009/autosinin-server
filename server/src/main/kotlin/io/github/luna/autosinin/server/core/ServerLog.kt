package io.github.luna.autosinin.server.core

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque

object ServerLog {
    private const val MAX_BUFFER = 300
    private val buffer = ConcurrentLinkedDeque<String>()
    private val logger = LoggerFactory.getLogger("autosinin")

    fun i(tag: String, msg: String) {
        val line = "${Instant.now()} [I/$tag] $msg"
        logger.info("[$tag] $msg")
        push(line)
    }

    fun w(tag: String, msg: String) {
        val line = "${Instant.now()} [W/$tag] $msg"
        logger.warn("[$tag] $msg")
        push(line)
    }

    fun recent(limit: Int = 100): List<String> = buffer.toList().takeLast(limit)

    private fun push(line: String) {
        buffer.addLast(line)
        while (buffer.size > MAX_BUFFER) {
            buffer.pollFirst()
        }
    }
}
