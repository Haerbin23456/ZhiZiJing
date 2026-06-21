package com.example.zhizijing.ui.room

object RoomCodeParser {
    private const val ROOM_CODE_LENGTH = 6

    fun parseExactSixDigits(rawValue: String?): String? {
        val digits = rawValue.orEmpty().filter { it.isDigit() }
        return digits.takeIf { it.length == ROOM_CODE_LENGTH }
    }
}
