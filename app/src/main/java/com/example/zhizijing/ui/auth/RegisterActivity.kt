package com.example.zhizijing.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.data.repository.AuthRepository
import com.example.zhizijing.databinding.ActivityRegisterBinding
import com.example.zhizijing.ui.main.MainActivity
import com.example.zhizijing.utils.AppExecutors

class RegisterActivity : ComponentActivity() {
    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.createAccountButton.setOnClickListener { register() }
        binding.backLoginButton.setOnClickListener { finish() }
    }

    private fun register() {
        val username = binding.usernameEdit.text.toString().trim()
        val nickname = binding.nicknameInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        if (username.isBlank()) {
            Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.isBlank()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show()
            return
        }
        binding.createAccountButton.isEnabled = false
        AppExecutors.io.execute {
            val result = AuthRepository.register(this, username, password, nickname)
            runOnUiThread {
                binding.createAccountButton.isEnabled = true
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                if (result.isSuccess) {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                }
            }
        }
    }
}
