package com.github.aura.asbox.player.engine.iso

fun interface RangeBlockReader {
    fun read(start: Long, length: Int): ByteArray
}
