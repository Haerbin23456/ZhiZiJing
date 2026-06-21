package com.example.zhizijing.ui.room

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

data class RoomQrMatrix(
    val size: Int,
    val modules: List<Boolean>,
) {
    fun isDark(x: Int, y: Int): Boolean =
        modules[y * size + x]
}

private const val QR_VERSION_ONE_SIZE = 21

object RoomQrCodeEncoder {
    private const val DATA_CODEWORDS = 19
    private const val ERROR_CORRECTION_CODEWORDS = 7

    fun encodeRoomCode(roomCode: String): RoomQrMatrix {
        val normalized = RoomCodeParser.parseExactSixDigits(roomCode)
        require(normalized != null) { "房间码二维码仅支持 6 位数字。" }
        val data = encodeNumericData(normalized)
        val errorCorrection = ReedSolomon.computeRemainder(data, ERROR_CORRECTION_CODEWORDS)
        val allCodewords = data + errorCorrection
        return QrVersionOneMatrixBuilder(allCodewords).build()
    }

    private fun encodeNumericData(roomCode: String): IntArray {
        val bits = BitBuffer()
        bits.append(value = 0b0001, bitCount = 4)
        bits.append(value = roomCode.length, bitCount = 10)
        roomCode.chunked(3).forEach { group ->
            val bitCount = when (group.length) {
                3 -> 10
                2 -> 7
                else -> 4
            }
            bits.append(group.toInt(), bitCount)
        }
        bits.append(value = 0, bitCount = minOf(4, DATA_CODEWORDS * 8 - bits.size))
        while (bits.size % 8 != 0) {
            bits.append(value = 0, bitCount = 1)
        }
        val codewords = bits.toCodewords().toMutableList()
        var padIndex = 0
        val padCodewords = intArrayOf(0xEC, 0x11)
        while (codewords.size < DATA_CODEWORDS) {
            codewords += padCodewords[padIndex % padCodewords.size]
            padIndex += 1
        }
        return codewords.toIntArray()
    }
}

object RoomQrCodeRenderer {
    fun render(matrix: RoomQrMatrix, moduleSize: Int = 12, quietZoneModules: Int = 4): Bitmap {
        val imageSize = (matrix.size + quietZoneModules * 2) * moduleSize
        val bitmap = Bitmap.createBitmap(imageSize, imageSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawColor(Color.WHITE)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.isDark(x, y)) {
                    canvas.drawRect(
                        ((x + quietZoneModules) * moduleSize).toFloat(),
                        ((y + quietZoneModules) * moduleSize).toFloat(),
                        ((x + quietZoneModules + 1) * moduleSize).toFloat(),
                        ((y + quietZoneModules + 1) * moduleSize).toFloat(),
                        paint,
                    )
                }
            }
        }
        return bitmap
    }
}

private class QrVersionOneMatrixBuilder(
    private val codewords: IntArray,
) {
    private val modules = Array(SIZE) { BooleanArray(SIZE) }
    private val reserved = Array(SIZE) { BooleanArray(SIZE) }

    fun build(): RoomQrMatrix {
        drawFunctionPatterns()
        drawCodewords()
        drawFormatBits()
        return RoomQrMatrix(
            size = SIZE,
            modules = modules.flatMap { row -> row.toList() },
        )
    }

    private fun drawFunctionPatterns() {
        drawFinderPattern(0, 0)
        drawFinderPattern(SIZE - 7, 0)
        drawFinderPattern(0, SIZE - 7)
        drawTimingPatterns()
        reserveFormatAreas()
        setFunctionModule(8, SIZE - 8, true)
    }

    private fun drawFinderPattern(left: Int, top: Int) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val x = left + dx
                val y = top + dy
                if (x !in 0 until SIZE || y !in 0 until SIZE) continue
                val dark = dx in 0..6 && dy in 0..6 &&
                    (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                setFunctionModule(x, y, dark)
            }
        }
    }

    private fun drawTimingPatterns() {
        for (i in 8 until SIZE - 8) {
            setFunctionModule(i, 6, i % 2 == 0)
            setFunctionModule(6, i, i % 2 == 0)
        }
    }

    private fun reserveFormatAreas() {
        for (i in 0..8) {
            if (i != 6) {
                reserve(8, i)
                reserve(i, 8)
            }
        }
        for (i in 0..7) {
            reserve(SIZE - 1 - i, 8)
            reserve(8, SIZE - 1 - i)
        }
    }

    private fun drawCodewords() {
        val bits = codewords.flatMap { codeword ->
            (7 downTo 0).map { bit -> ((codeword ushr bit) and 1) == 1 }
        }
        var bitIndex = 0
        var upward = true
        var x = SIZE - 1
        while (x > 0) {
            if (x == 6) x -= 1
            val yRange: IntProgression = if (upward) SIZE - 1 downTo 0 else 0 until SIZE
            for (y in yRange) {
                for (dx in 0..1) {
                    val xx = x - dx
                    if (reserved[y][xx]) continue
                    val rawBit = bits.getOrElse(bitIndex) { false }
                    modules[y][xx] = rawBit xor mask(xx, y)
                    bitIndex += 1
                }
            }
            upward = !upward
            x -= 2
        }
    }

    private fun drawFormatBits() {
        val formatBits = formatBits(maskPattern = 0)
        for (i in 0..5) setFunctionModule(8, i, formatBits.bit(i))
        setFunctionModule(8, 7, formatBits.bit(6))
        setFunctionModule(8, 8, formatBits.bit(7))
        setFunctionModule(7, 8, formatBits.bit(8))
        for (i in 9..14) setFunctionModule(14 - i, 8, formatBits.bit(i))
        for (i in 0..7) setFunctionModule(SIZE - 1 - i, 8, formatBits.bit(i))
        for (i in 8..14) setFunctionModule(8, SIZE - 15 + i, formatBits.bit(i))
        setFunctionModule(8, SIZE - 8, true)
    }

    private fun setFunctionModule(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        reserved[y][x] = true
    }

    private fun reserve(x: Int, y: Int) {
        reserved[y][x] = true
    }

    private fun mask(x: Int, y: Int): Boolean =
        (x + y) % 2 == 0

    private fun Int.bit(index: Int): Boolean =
        ((this ushr index) and 1) != 0

    private fun formatBits(maskPattern: Int): Int {
        val errorCorrectionLevelBits = 0b01
        val data = (errorCorrectionLevelBits shl 3) or maskPattern
        var value = data shl 10
        val generator = 0x537
        for (i in 14 downTo 10) {
            if (((value ushr i) and 1) != 0) {
                value = value xor (generator shl (i - 10))
            }
        }
        return ((data shl 10) or value) xor 0x5412
    }

    companion object {
        private const val SIZE = QR_VERSION_ONE_SIZE
    }
}

private class BitBuffer {
    private val bits = mutableListOf<Boolean>()
    val size: Int get() = bits.size

    fun append(value: Int, bitCount: Int) {
        require(bitCount >= 0)
        for (i in bitCount - 1 downTo 0) {
            bits += ((value ushr i) and 1) != 0
        }
    }

    fun toCodewords(): List<Int> =
        bits.chunked(8).map { chunk ->
            chunk.fold(0) { value, bit -> (value shl 1) or if (bit) 1 else 0 }
        }
}

private object ReedSolomon {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if ((x and 0x100) != 0) {
                x = x xor 0x11D
            }
        }
        for (i in 255 until exp.size) {
            exp[i] = exp[i - 255]
        }
    }

    fun computeRemainder(data: IntArray, degree: Int): IntArray {
        val divisor = computeDivisor(degree)
        val result = IntArray(degree)
        data.forEach { codeword ->
            val factor = codeword xor result[0]
            for (i in 0 until degree - 1) {
                result[i] = result[i + 1]
            }
            result[degree - 1] = 0
            for (i in 0 until degree) {
                result[i] = result[i] xor multiply(divisor[i], factor)
            }
        }
        return result
    }

    private fun computeDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = multiply(result[j], root)
                if (j + 1 < result.size) {
                    result[j] = result[j] xor result[j + 1]
                }
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    private fun multiply(x: Int, y: Int): Int =
        if (x == 0 || y == 0) 0 else exp[log[x] + log[y]]
}
