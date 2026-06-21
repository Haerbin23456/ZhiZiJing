package com.example.zhizijing.ui.room

object RoomCodeFormatter {
    fun normalize(input: String): String =
        input.filter { it.isDigit() }.take(6)

    fun display(input: String): String {
        val normalized = normalize(input)
        return if (normalized.isBlank()) {
            input
        } else {
            normalized.chunked(3).joinToString(" ")
        }
    }
}
