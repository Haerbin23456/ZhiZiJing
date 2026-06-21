package com.example.zhizijing.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.data.entity.UserEntity
import com.example.zhizijing.data.local.AppDatabaseProvider
import com.example.zhizijing.utils.PasswordHasher

data class AuthResult(
    val user: UserEntity?,
    val message: String,
) {
    val isSuccess: Boolean get() = user != null
}

object AuthRepository {
    fun register(
        context: Context,
        username: String,
        password: String,
        nickname: String,
    ): AuthResult {
        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank()) return AuthResult(null, "请输入用户名")
        if (password.isBlank()) return AuthResult(null, "请输入密码")

        val now = System.currentTimeMillis()
        val user = UserEntity(
            normalizedUsername,
            PasswordHasher.sha256(password),
            nickname.ifBlank { normalizedUsername },
            now,
            now,
        )
        return try {
            val userId = AppDatabaseProvider.get(context).userDao().insert(user)
            user.userId = userId
            AppSettingsDataStore.saveLogin(context, userId, normalizedUsername)
            AuthResult(user, "注册成功")
        } catch (_: SQLiteConstraintException) {
            AuthResult(null, "用户名已存在，请直接登录")
        }
    }

    fun login(context: Context, username: String, password: String): AuthResult {
        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank()) return AuthResult(null, "请输入用户名")
        if (password.isBlank()) return AuthResult(null, "请输入密码")

        val dao = AppDatabaseProvider.get(context).userDao()
        val user = dao.findByUsername(normalizedUsername)
            ?: return AuthResult(null, "用户不存在，请先注册")
        if (user.passwordHash != PasswordHasher.sha256(password)) {
            return AuthResult(null, "密码不正确")
        }

        user.lastLoginAt = System.currentTimeMillis()
        dao.update(user)
        AppSettingsDataStore.saveLogin(context, user.userId, user.username)
        return AuthResult(user, "登录成功")
    }

    fun currentUser(context: Context): UserEntity? {
        val settings = AppSettingsDataStore.load(context)
        if (!settings.isLoggedIn || settings.lastUserId <= 0L) return null
        return AppDatabaseProvider.get(context).userDao().findById(settings.lastUserId)
    }

    fun logout(context: Context) {
        AppSettingsDataStore.clearLogin(context)
    }
}
