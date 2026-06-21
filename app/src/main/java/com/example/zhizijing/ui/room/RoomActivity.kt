package com.example.zhizijing.ui.room

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.databinding.ActivityRoomBinding
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.nearby.connection.NearbyConnectionListener
import com.example.zhizijing.nearby.connection.NearbyConnectionState
import com.example.zhizijing.nearby.connection.NearbyPermissions
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.ui.device.DeviceGroupActivity

class RoomActivity : ComponentActivity() {
    private lateinit var binding: ActivityRoomBinding
    private lateinit var roomCode: String
    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (NearbyPermissions.hasRuntimePermissions(this)) {
            startAdvertising()
        } else {
            binding.nearbyStatusText.text = "缺少多设备连接所需权限，无法创建训练房间。"
        }
    }
    private val nearbyListener = object : NearbyConnectionListener {
        override fun onNearbyStateChanged(state: NearbyConnectionState) {
            runOnUiThread {
                binding.nearbyStatusText.text = formatNearbyState(state)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 主控房间码初始化
        roomCode = ((100000..999999).random()).toString()
        AppSettingsDataStore.saveLastRoomCode(this, roomCode)
        binding.roomCodeText.text = "训练房间码：${RoomCodeFormatter.display(roomCode)}"
        renderRoomQrCode()
        binding.startNearbyButton.setOnClickListener { requestOrStartAdvertising() }
        binding.nextButton.setOnClickListener {
            startActivity(Intent(this, DeviceGroupActivity::class.java))
        }
        binding.backButton.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        NearbyRoomSession.manager(this).addListener(nearbyListener)
    }

    override fun onStop() {
        NearbyRoomSession.manager(this).removeListener(nearbyListener)
        super.onStop()
    }

    private fun requestOrStartAdvertising() {
        val missingPermissions = NearbyPermissions.missingRuntimePermissions(this)
        if (missingPermissions.isEmpty()) {
            startAdvertising()
        } else {
            nearbyPermissionLauncher.launch(missingPermissions)
        }
    }

    // 主控连接广播启动
    private fun startAdvertising() {
        runCatching {
            NearbyRoomSession.manager(this).startHost(roomCode)
        }.onFailure { error ->
            Toast.makeText(this, "多设备连接启动失败：${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderRoomQrCode() {
        runCatching {
            RoomQrCodeRenderer.render(RoomQrCodeEncoder.encodeRoomCode(roomCode), moduleSize = 8)
        }.onSuccess { bitmap ->
            binding.roomQrImage.setImageBitmap(bitmap)
            binding.roomQrHintText.text = "二维码内容：${RoomCodeFormatter.display(roomCode)}"
        }.onFailure { error ->
            binding.roomQrHintText.text = "二维码生成失败：${error.message}"
        }
    }

    private fun formatNearbyState(state: NearbyConnectionState): String {
        val devices = if (state.endpoints.isEmpty()) {
            "已连接节点：暂无"
        } else {
            state.endpoints.joinToString(separator = "\n") { endpoint ->
                "设备：${endpoint.deviceName}，在线：${if (endpoint.isOnline) "是" else "否"}"
            }
        }
        return """
            ${state.statusText}
            房间码：${RoomCodeFormatter.display(roomCode)}
            本机身份：${state.localRole.displayText()}
            $devices
        """.trimIndent()
    }

    private fun DeviceRole.displayText(): String =
        when (this) {
            DeviceRole.HOST -> "主控端"
            DeviceRole.FRONT_CAMERA -> "正面机位"
            DeviceRole.SIDE_CAMERA -> "侧面机位"
            DeviceRole.BACKUP_CAMERA -> "备用机位"
            DeviceRole.UNKNOWN -> "未分配"
        }
}
