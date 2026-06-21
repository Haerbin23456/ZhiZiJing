package com.example.zhizijing.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.R
import com.example.zhizijing.data.repository.AuthRepository
import com.example.zhizijing.databinding.ActivityMainBinding
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.ui.auth.LoginActivity
import com.example.zhizijing.ui.history.HistoryActivity
import com.example.zhizijing.ui.room.JoinRoomActivity
import com.example.zhizijing.ui.room.RoomActivity
import com.example.zhizijing.ui.settings.SettingsActivity
import com.example.zhizijing.utils.AppExecutors

object ProjectStatus {
    const val appTitle = "智姿镜"
    const val summary = "智姿镜是一款面向日常运动训练的姿态记录与复盘工具，可通过摄像头完成动作识别、保存训练记录，并与其他手机协同记录训练过程，帮助你查看姿势表现和训练报告。"
}

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderProjectStatus()
        bindTabs()
        bindActions()
    }

    // 首页状态文案集中渲染
    private fun renderProjectStatus() {
        binding.appTitle.text = ProjectStatus.appTitle
        binding.appSummary.text = ProjectStatus.summary
        binding.supportedActionsText.text = "支持识别动作：${ActionType.trainingActionNamesText()}"
    }

    // 首页功能入口集中绑定
    private fun bindActions() {
        binding.createRoomButton.setOnClickListener {
            startActivity(Intent(this, RoomActivity::class.java))
        }
        binding.joinRoomButton.setOnClickListener {
            startActivity(Intent(this, JoinRoomActivity::class.java))
        }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.logoutButton.setOnClickListener {
            logout()
        }
    }

    private fun bindTabs() {
        binding.trainingTab.setOnClickListener { selectTab(MainTab.TRAINING) }
        binding.spaceTab.setOnClickListener { selectTab(MainTab.SPACE) }
        binding.settingsTab.setOnClickListener { selectTab(MainTab.SETTINGS) }
        selectTab(MainTab.TRAINING)
    }

    private fun selectTab(tab: MainTab) {
        binding.trainingPanel.visibility = if (tab == MainTab.TRAINING) View.VISIBLE else View.GONE
        binding.spacePanel.visibility = if (tab == MainTab.SPACE) View.VISIBLE else View.GONE
        binding.settingsPanel.visibility = if (tab == MainTab.SETTINGS) View.VISIBLE else View.GONE

        renderTab(binding.trainingTab, tab == MainTab.TRAINING)
        renderTab(binding.spaceTab, tab == MainTab.SPACE)
        renderTab(binding.settingsTab, tab == MainTab.SETTINGS)
    }

    private fun renderTab(tabView: TextView, selected: Boolean) {
        tabView.setBackgroundResource(
            if (selected) R.drawable.bg_zzj_tab_selected else R.drawable.bg_zzj_tab_unselected
        )
        tabView.setTextColor(getColor(if (selected) R.color.white else R.color.zzj_text_muted))
    }

    // 退出登录后重建任务栈
    private fun logout() {
        binding.logoutButton.isEnabled = false
        AppExecutors.io.execute {
            AuthRepository.logout(this)
            runOnUiThread {
                NearbyRoomSession.stopIfCreated()
                Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }
    }

    private enum class MainTab {
        TRAINING,
        SPACE,
        SETTINGS,
    }
}
