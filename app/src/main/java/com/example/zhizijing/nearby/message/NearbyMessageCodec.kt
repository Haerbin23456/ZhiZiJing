package com.example.zhizijing.nearby.message

interface NearbyMessageCodec<T> {
    fun encode(message: T): String
    fun decode(json: String): T
}
