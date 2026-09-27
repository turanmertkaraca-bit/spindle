package dev.spindle.core.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class SessionId(val value: String) {
    override fun toString() = value
}

@JvmInline
@Serializable
value class MessageId(val value: String) {
    override fun toString() = value
}

@JvmInline
@Serializable
value class PartId(val value: String) {
    override fun toString() = value
}

/** Monotonic id generator. Not ULID yet — good enough and sortable. */
object Ids {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    fun new(prefix: String): String =
        prefix + "_" + java.lang.Long.toUnsignedString(
            (System.currentTimeMillis() shl 20) xor counter.incrementAndGet(), 36,
        )
}
