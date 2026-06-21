package com.example.zhizijing.ui.room

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.zhizijing.data.datastore.AppSettingsDataStore
import com.example.zhizijing.databinding.ActivityJoinRoomBinding
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.nearby.connection.NearbyConnectionListener
import com.example.zhizijing.nearby.connection.NearbyConnectionState
import com.example.zhizijing.nearby.connection.NearbyPermissions
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.ui.device.DeviceGroupActivity

class JoinRoomActivity : ComponentActivity() {
    private lateinit var binding: ActivityJoinRoomBinding
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        // 扫码结果回填房间码
        val roomCode = RoomQrScanResultParser.parseRoomCode(
            result.data?.getStringExtra(RoomQrScannerActivity.EXTRA_ROOM_CODE)
        )
        if (result.resultCode == RESULT_OK && roomCode != null) {
            binding.roomCodeInput.setText(roomCode)
            AppSettingsDataStore.saveLastRoomCode(this, roomCode)
            Toast.makeText(this, "已填入房间码：${RoomCodeFormatter.display(roomCode)}", Toast.LENGTH_SHORT).show()
        }
    }
    private val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (NearbyPermissions.hasRuntimePermissions(this)) {
            startDiscovery()
        } else {
            binding.nearbyStatusText.text = "缺少多设备连接所需权限，无法搜索训练房间。"
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
        binding = ActivityJoinRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.roomCodeInput.setText(AppSettingsDataStore.load(this).lastRoomCode)
        binding.startDiscoveryButton.setOnClickListener { requestOrStartDiscovery() }
        binding.scanQrButton.setOnClickListener {
            qrScannerLauncher.launch(Intent(this, RoomQrScannerActivity::class.java))
        }
        binding.joinButton.setOnClickListener {
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

    private fun requestOrStartDiscovery() {
        val missingPermissions = NearbyPermissions.missingRuntimePermissions(this)
        if (missingPermissions.isEmpty()) {
            startDiscovery()
        } else {
            nearbyPermissionLauncher.launch(missingPermissions)
        }
    }

    // 节点按房间码搜索主控
    private fun startDiscovery() {
        val roomCode = RoomCodeParser.parseExactSixDigits(binding.roomCodeInput.text?.toString())
        if (roomCode == null) {
            Toast.makeText(this, "请输入主控端 6 位房间码", Toast.LENGTH_SHORT).show()
            return
        }
        AppSettingsDataStore.saveLastRoomCode(this, roomCode)
        runCatching {
            NearbyRoomSession.manager(this).startNode(roomCode)
        }.onFailure { error ->
            Toast.makeText(this, "训练房间搜索失败：${error.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatNearbyState(state: NearbyConnectionState): String {
        val devices = if (state.endpoints.isEmpty()) {
            "发现设备：暂无"
        } else {
            state.endpoints.joinToString(separator = "\n") { endpoint ->
                "设备：${endpoint.deviceName}，在线：${if (endpoint.isOnline) "是" else "否"}"
            }
        }
        return """
            ${state.statusText}
            房间码：${RoomCodeFormatter.display(state.roomCode)}
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
