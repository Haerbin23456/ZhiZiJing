package com.example.zhizijing.ui.room

object RoomQrScanResultParser {
    fun parseRoomCode(rawValue: String?): String? =
        RoomCodeParser.parseExactSixDigits(rawValue)
}
