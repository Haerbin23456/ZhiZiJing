package com.example.zhizijing.ui.prepare

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.zhizijing.databinding.ActivityPrepareBinding
import com.example.zhizijing.domain.model.ActionType
import com.example.zhizijing.domain.model.DeviceRole
import com.example.zhizijing.nearby.connection.NearbyConnectionListener
import com.example.zhizijing.nearby.connection.NearbyConnectionMode
import com.example.zhizijing.nearby.connection.NearbyConnectionState
import com.example.zhizijing.nearby.connection.NearbyRoomSession
import com.example.zhizijing.nearby.message.NearbyMessage
import com.example.zhizijing.nearby.message.NearbyMessageType
import com.example.zhizijing.ui.analysis.ActionAnalysisActivity
import com.example.zhizijing.ui.camera.CameraNodeActivity

class PrepareActivity : ComponentActivity() {
    private lateinit var binding: ActivityPrepareBinding
    private var timer: CountDownTimer? = null
    private var currentActionType = ActionType.UNKNOWN
    private val nearbyListener = object : NearbyConnectionListener {
        override fun onNearbyStateChanged(state: NearbyConnectionState) = Unit

        override fun onNearbyMessageReceived(endpointId: String, message: NearbyMessage) {
            handleNearbyMessage(message)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrepareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentActionType = ActionType.fromNameOrUnknown(intent.getStringExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE))
        binding.prepareInfoText.text = """
            当前训练模式：${trainingModeText(currentActionType)}
            动作目标：${targetText(currentActionType)}
            正面机位：观察左右对称、腿部交替和手脚开合状态。
            侧面机位：观察躯干角度、俯撑/躺卧姿态和髋膝变化。
            多设备协同：主控端开始倒计时时会同步通知在线节点。
        """.trimIndent()
        binding.startCountdownButton.setOnClickListener {
            unsupportedTrainingReason(currentActionType)?.let { reason ->
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                binding.prepareInfoText.text = "暂不能开始训练。\n$reason"
                return@setOnClickListener
            }
            val blockReason = NearbyRoomSession.manager(this).startTrainingBlockReason()
            if (blockReason != null) {
                Toast.makeText(this, blockReason, Toast.LENGTH_LONG).show()
                binding.prepareInfoText.text = """
                    暂不能开始训练。
                    $blockReason
                """.trimIndent()
                return@setOnClickListener
            }
            NearbyRoomSession.manager(this).sendStartCountdown(3, currentActionType)
            startCountdown(currentActionType)
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

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    private fun startCountdown(actionType: ActionType) {
        binding.startCountdownButton.isEnabled = false
        timer?.cancel()
        timer = object : CountDownTimer(3000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.countdownText.text = ((millisUntilFinished / 1000L) + 1L).toString()
            }

            override fun onFinish() {
                binding.countdownText.text = "开始"
                NearbyRoomSession.manager(this@PrepareActivity).sendStartAnalysis(actionType)
                startActivity(
                    Intent(this@PrepareActivity, ActionAnalysisActivity::class.java)
                        .putExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE, actionType.name)
                )
            }
        }.start()
    }

    private fun handleNearbyMessage(message: NearbyMessage) {
        when (message.type) {
            NearbyMessageType.START_COUNTDOWN -> {
                if (timer != null) return
                currentActionType = message.actionType
                unsupportedTrainingReason(currentActionType)?.let { reason ->
                    Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                    binding.prepareInfoText.text = "暂不能开始训练。\n$reason"
                    return
                }
                val seconds = message.countdownSeconds ?: 3
                binding.prepareInfoText.text = """
                    已收到主控端同步倒计时。
                    当前训练模式：${trainingModeText(currentActionType)}
                    本机会在倒计时后进入摄像头节点识别页，并自动开始识别。
                """.trimIndent()
                startNodeCountdown(seconds, currentActionType)
            }
            NearbyMessageType.START_ANALYSIS -> {
                currentActionType = message.actionType
                unsupportedTrainingReason(currentActionType)?.let { reason ->
                    Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                    binding.prepareInfoText.text = "暂不能开始训练。\n$reason"
                    return
                }
                timer?.cancel()
                timer = null
                binding.countdownText.text = "开始"
                binding.prepareInfoText.text = """
                    主控端已开始训练。
                    当前训练模式：${trainingModeText(currentActionType)}
                    正在进入摄像头节点识别页。
                """.trimIndent()
                openCameraNodeFromRemote(currentActionType)
            }
            NearbyMessageType.END_TRAINING -> {
                timer?.cancel()
                timer = null
                binding.startCountdownButton.isEnabled = true
                binding.countdownText.text = "已结束"
                binding.prepareInfoText.text = """
                    主控端已结束本轮训练。
                    当前设备保持待命，可等待下一次主控端开始。
                """.trimIndent()
            }
            else -> Unit
        }
    }

    private fun startNodeCountdown(seconds: Int, actionType: ActionType) {
        binding.startCountdownButton.isEnabled = false
        timer?.cancel()
        timer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.countdownText.text = ((millisUntilFinished / 1000L) + 1L).toString()
            }

            override fun onFinish() {
                binding.countdownText.text = "开始"
                openCameraNodeFromRemote(actionType)
            }
        }.start()
    }

    private fun openCameraNodeFromRemote(actionType: ActionType) {
        startActivity(
            Intent(this, CameraNodeActivity::class.java)
                .putExtra(ActionAnalysisActivity.EXTRA_ACTION_TYPE, actionType.name)
                .putExtra(CameraNodeActivity.EXTRA_REMOTE_AUTO_START, true)
        )
    }

    private fun trainingModeText(actionType: ActionType): String =
        if (actionType == ActionType.UNKNOWN) "自动识别${ActionType.trainingActionNamesText()}" else actionType.displayName

    private fun targetText(actionType: ActionType): String =
        if (actionType == ActionType.UNKNOWN) {
            "完成任一支持动作后自动进入对应计数或保持统计。"
        } else {
            actionType.defaultTargetText
        }

    private fun unsupportedTrainingReason(actionType: ActionType): String? {
        if (actionType == ActionType.SQUAT) {
            val state = NearbyRoomSession.manager(this).currentState()
            if (state.localRole !in PRIMARY_SQUAT_ROLES) {
                return "深蹲训练需要先选择正面机位或侧面机位。"
            }
        }
        if (actionType != ActionType.JUMPING_JACK) return null
        val state = NearbyRoomSession.manager(this).currentState()
        val isSingleFront = state.mode == NearbyConnectionMode.IDLE &&
            state.localRole == DeviceRole.FRONT_CAMERA
        return if (isSingleFront) {
            null
        } else {
            "开合跳只支持单机正面机位训练，请退出多机位并切换为正面机位。"
        }
    }

    companion object {
        private val PRIMARY_SQUAT_ROLES = setOf(DeviceRole.FRONT_CAMERA, DeviceRole.SIDE_CAMERA)
    }
}
