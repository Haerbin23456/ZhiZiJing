package com.example.zhizijing

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout

    private enum class Screen(val title: String, val subtitle: String) {
        LOGIN("智姿镜", "多手机协同运动姿态评估系统"),
        HOME("训练首页", "快速开始训练、查看最近表现和历史记录"),
        ROOM("创建训练房间", "主控端生成房间码并等待摄像头节点加入"),
        JOIN("加入训练房间", "摄像头节点输入房间码并连接主控端"),
        DEVICE("设备组队", "分配正面机位、侧面机位并查看节点状态"),
        PREPARE("训练准备", "确认站位、机位和人体入镜状态"),
        CAMERA("摄像头节点", "CameraX 预览、ML Kit 姿态识别和骨架绘制"),
        SQUAT("深蹲实时评估", "计数、评分并提示下蹲深度和姿态问题"),
        JUMPING_JACK("开合跳计数", "统计次数、时长和平均节奏"),
        RESULT("训练结果", "展示评分、主要问题和改进建议"),
        HISTORY("历史记录", "查看每次训练记录和报告导出状态"),
        MODEL("模型训练", "关键点序列动作分类器和 TFLite 接入说明")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showScreen(Screen.LOGIN)
    }

    private fun showScreen(screen: Screen) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 251))
        }

        val scrollView = ScrollView(this).apply {
            addView(root)
        }

        setContentView(scrollView)
        renderHeader(screen)

        when (screen) {
            Screen.LOGIN -> renderLogin()
            Screen.HOME -> renderHome()
            Screen.ROOM -> renderRoom()
            Screen.JOIN -> renderJoin()
            Screen.DEVICE -> renderDeviceGroup()
            Screen.PREPARE -> renderPrepare()
            Screen.CAMERA -> renderCameraNode()
            Screen.SQUAT -> renderSquat()
            Screen.JUMPING_JACK -> renderJumpingJack()
            Screen.RESULT -> renderResult()
            Screen.HISTORY -> renderHistory()
            Screen.MODEL -> renderModel()
        }
    }

    private fun renderHeader(screen: Screen) {
        root.addView(
            TextView(this).apply {
                text = screen.title
                textSize = 28f
                setTextColor(Color.rgb(21, 32, 43))
                setPadding(dp(20), dp(26), dp(20), dp(4))
                gravity = Gravity.START
            }
        )
        root.addView(
            TextView(this).apply {
                text = screen.subtitle
                textSize = 15f
                setTextColor(Color.rgb(86, 99, 114))
                setPadding(dp(20), 0, dp(20), dp(18))
            }
        )
    }

    private fun renderLogin() {
        addPanel(
            "账号入口",
            listOf(
                "用户名：admin",
                "密码：123456",
                "登录成功后进入训练首页，后续接入 Room + DataStore"
            )
        )
        addPrimaryButton("登录 / 注册并进入首页") { showScreen(Screen.HOME) }
        addSecondaryButton("查看模型训练说明") { showScreen(Screen.MODEL) }
    }

    private fun renderHome() {
        addMetricRow("最近训练", "深蹲 18 次", "平均评分 86")
        addPanel(
            "今日概览",
            listOf(
                "深蹲平均评分：86",
                "开合跳累计次数：42",
                "最近问题：下蹲深度不足、背部前倾"
            )
        )
        addPrimaryButton("创建训练房间") { showScreen(Screen.ROOM) }
        addSecondaryButton("加入训练房间") { showScreen(Screen.JOIN) }
        addSecondaryButton("深蹲评估入口") { showScreen(Screen.SQUAT) }
        addSecondaryButton("开合跳计数入口") { showScreen(Screen.JUMPING_JACK) }
        addSecondaryButton("历史记录") { showScreen(Screen.HISTORY) }
    }

    private fun renderRoom() {
        addBigCode("房间码", "839 204")
        addPanel(
            "主控端职责",
            listOf(
                "开启 Nearby advertising",
                "展示房间码和二维码",
                "等待正面 / 侧面摄像头节点加入",
                "开始后进入设备组队页面"
            )
        )
        addPrimaryButton("已有节点加入，进入设备组队") { showScreen(Screen.DEVICE) }
        addSecondaryButton("返回首页") { showScreen(Screen.HOME) }
    }

    private fun renderJoin() {
        addBigCode("输入房间码", "839 204")
        addPanel(
            "节点端职责",
            listOf(
                "开启 Nearby discovery",
                "请求连接主控端",
                "接收 FRONT_CAMERA / SIDE_CAMERA 机位角色",
                "加入成功后进入摄像头节点页面"
            )
        )
        addPrimaryButton("加入成功，进入摄像头节点") { showScreen(Screen.CAMERA) }
        addSecondaryButton("返回首页") { showScreen(Screen.HOME) }
    }

    private fun renderDeviceGroup() {
        addPanel(
            "已连接设备",
            listOf(
                "主控手机：HOST，在线",
                "小米节点：FRONT_CAMERA，电量 82%，延迟 38ms",
                "荣耀节点：SIDE_CAMERA，电量 76%，延迟 44ms"
            )
        )
        addPanel(
            "可执行操作",
            listOf(
                "分配正面机位",
                "分配侧面机位",
                "重新分配机位",
                "发送同步倒计时"
            )
        )
        addPrimaryButton("开始同步倒计时") { showScreen(Screen.PREPARE) }
        addSecondaryButton("返回房间") { showScreen(Screen.ROOM) }
    }

    private fun renderPrepare() {
        addMetricRow("倒计时", "3 秒", "动作：深蹲")
        addPanel(
            "站位提示",
            listOf(
                "正面机位：手机放在身体正前方，能看到左右膝盖和脚踝",
                "侧面机位：手机放在身体侧面，能看到肩、髋、膝、踝",
                "人体识别状态：全身入镜，关键点置信度正常"
            )
        )
        addPrimaryButton("倒计时结束，开始深蹲评估") { showScreen(Screen.SQUAT) }
        addSecondaryButton("查看摄像头节点画面") { showScreen(Screen.CAMERA) }
    }

    private fun renderCameraNode() {
        addSkeletonPreview()
        addPanel(
            "节点端实时状态",
            listOf(
                "CameraX Preview：待接入",
                "ML Kit Pose Detection：待接入",
                "骨架 Overlay：原型占位",
                "上传策略：MVP 优先上传 ANALYSIS_SUMMARY"
            )
        )
        addPrimaryButton("模拟上传分析结果") { showScreen(Screen.SQUAT) }
        addSecondaryButton("返回设备组队") { showScreen(Screen.DEVICE) }
    }

    private fun renderSquat() {
        addMetricRow("深蹲次数", "12", "当前评分 88")
        addPanel(
            "实时姿态指标",
            listOf(
                "当前阶段：RISING",
                "膝关节角度：92°",
                "躯干前倾角：14°",
                "下蹲深度：GOOD",
                "问题提示：第 8 次背部前倾明显"
            )
        )
        addPrimaryButton("结束训练，查看结果") { showScreen(Screen.RESULT) }
        addSecondaryButton("切换到开合跳计数") { showScreen(Screen.JUMPING_JACK) }
        addSecondaryButton("返回首页") { showScreen(Screen.HOME) }
    }

    private fun renderJumpingJack() {
        addMetricRow("开合跳次数", "42", "平均节奏 48/min")
        addPanel(
            "计数逻辑",
            listOf(
                "CLOSED -> OPENING -> OPEN -> CLOSING -> CLOSED",
                "脚踝横向距离增大且手腕高于肩膀时进入 OPEN",
                "人体丢失时暂停计数并提示调整站位"
            )
        )
        addPrimaryButton("结束训练，查看结果") { showScreen(Screen.RESULT) }
        addSecondaryButton("返回首页") { showScreen(Screen.HOME) }
    }

    private fun renderResult() {
        addMetricRow("训练完成", "12 次", "平均评分 88")
        addPanel(
            "主要问题与建议",
            listOf(
                "主要问题：背部前倾、下蹲深度不足",
                "建议：收紧核心，保持胸部打开",
                "建议：让髋部接近膝盖高度，增加下蹲幅度",
                "报告：PDF / JSON 后续接入 PdfDocument 和本地文件存储"
            )
        )
        addPrimaryButton("保存并查看历史记录") { showScreen(Screen.HISTORY) }
        addSecondaryButton("再来一次") { showScreen(Screen.PREPARE) }
        addSecondaryButton("返回首页") { showScreen(Screen.HOME) }
    }

    private fun renderHistory() {
        addPanel(
            "训练记录",
            listOf(
                "2026-05-23  深蹲 12 次  平均评分 88",
                "2026-05-22  开合跳 42 次  平均节奏 48/min",
                "2026-05-21  深蹲 18 次  平均评分 86"
            )
        )
        addPanel(
            "历史详情后续功能",
            listOf(
                "按日期查看",
                "按动作类型筛选",
                "点击进入详情",
                "删除训练记录",
                "导出 PDF / JSON 报告"
            )
        )
        addPrimaryButton("查看最近一次结果") { showScreen(Screen.RESULT) }
        addSecondaryButton("返回首页") { showScreen(Screen.HOME) }
    }

    private fun renderModel() {
        addPanel(
            "模型训练路线",
            listOf(
                "ML Kit 提取人体关键点，不训练人体检测模型",
                "使用关键点序列训练动作分类器",
                "输出 SQUAT / JUMPING_JACK / UNKNOWN 和置信度",
                "最终导出 action_classifier.tflite、labels.txt、model_meta.json"
            )
        )
        addPanel(
            "推荐数据集",
            listOf(
                "Kaggle Physical Exercise Recognition Time Series Dataset",
                "Penn Action Dataset",
                "UCF101 Body Weight Squats / Jumping Jack",
                "APP 自采关键点 JSON 作为补充数据"
            )
        )
        addPrimaryButton("返回首页") { showScreen(Screen.HOME) }
    }

    private fun addMetricRow(leftTitle: String, middle: String, right: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        row.addView(metricCard(leftTitle, "状态"))
        row.addView(metricCard(middle, "核心指标"))
        row.addView(metricCard(right, "表现"))
        root.addView(row)
    }

    private fun metricCard(value: String, label: String): TextView {
        return TextView(this).apply {
            text = "$value\n$label"
            textSize = 14f
            setTextColor(Color.rgb(31, 45, 61))
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }
    }

    private fun addPanel(title: String, items: List<String>) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(16), dp(6), dp(16), dp(12))
            }
        }
        panel.addView(
            TextView(this).apply {
                text = title
                textSize = 18f
                setTextColor(Color.rgb(21, 32, 43))
                setPadding(0, 0, 0, dp(8))
            }
        )
        items.forEach { item ->
            panel.addView(
                TextView(this).apply {
                    text = "• $item"
                    textSize = 15f
                    setTextColor(Color.rgb(78, 92, 108))
                    setPadding(0, dp(4), 0, dp(4))
                }
            )
        }
        root.addView(panel)
    }

    private fun addBigCode(label: String, code: String) {
        root.addView(
            TextView(this).apply {
                text = "$label\n$code"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(16, 88, 117))
                setBackgroundColor(Color.WHITE)
                setPadding(dp(20), dp(22), dp(20), dp(22))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(16), 0, dp(16), dp(14))
                }
            }
        )
    }

    private fun addSkeletonPreview() {
        root.addView(
            TextView(this).apply {
                text = """
                    摄像头预览占位

                       ○
                      /|\
                      / \

                    后续接入 CameraX + ML Kit 后显示真实画面和骨架线
                """.trimIndent()
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(31, 45, 61))
                setBackgroundColor(Color.rgb(232, 244, 242))
                setPadding(dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(260)
                ).apply {
                    setMargins(dp(16), 0, dp(16), dp(14))
                }
            }
        )
    }

    private fun addPrimaryButton(text: String, onClick: (View) -> Unit) {
        addButton(text, Color.rgb(11, 105, 135), Color.WHITE, onClick)
    }

    private fun addSecondaryButton(text: String, onClick: (View) -> Unit) {
        addButton(text, Color.WHITE, Color.rgb(11, 105, 135), onClick)
    }

    private fun addButton(text: String, backgroundColor: Int, textColor: Int, onClick: (View) -> Unit) {
        root.addView(
            Button(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(textColor)
                setBackgroundColor(backgroundColor)
                setOnClickListener(onClick)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)
                ).apply {
                    setMargins(dp(16), dp(4), dp(16), dp(8))
                }
            }
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
