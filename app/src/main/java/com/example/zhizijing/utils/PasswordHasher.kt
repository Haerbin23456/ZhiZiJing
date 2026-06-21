package com.example.zhizijing.utils

import java.security.MessageDigest

object PasswordHasher {
    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
