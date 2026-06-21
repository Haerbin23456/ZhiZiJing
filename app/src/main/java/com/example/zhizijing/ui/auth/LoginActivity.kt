package com.example.zhizijing.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.data.repository.AuthRepository
import com.example.zhizijing.databinding.ActivityLoginBinding
import com.example.zhizijing.ui.main.MainActivity
import com.example.zhizijing.utils.AppExecutors

class LoginActivity : ComponentActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAutoLogin()
        binding.loginButton.setOnClickListener { login() }
        binding.registerButton.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    // 已登录用户直接进主页
    private fun checkAutoLogin() {
        AppExecutors.io.execute {
            val user = AuthRepository.currentUser(this)
            if (user != null) {
                runOnUiThread { openMain() }
            }
        }
    }

    private fun login() {
        val username = binding.usernameEdit.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        if (username.isBlank()) {
            Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.isBlank()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show()
            return
        }
        binding.loginButton.isEnabled = false
        AppExecutors.io.execute {
            val result = AuthRepository.login(this, username, password)
            runOnUiThread {
                binding.loginButton.isEnabled = true
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.isSuccess) openMain()
            }
        }
    }

    // 登录成功后关闭入口页
    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
