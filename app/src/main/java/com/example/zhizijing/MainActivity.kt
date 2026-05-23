package com.example.zhizijing

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ZhiZiJingTheme {
                ZhiZiJingApp()
            }
        }
    }
}

private enum class AppScreen(
    val title: String,
    val description: String,
    val icon: ImageVector,
) {
    Home("训练", "最近表现、训练入口和主流程状态", Icons.Rounded.Home),
    Room("房间", "创建房间、节点加入和机位分配", Icons.Rounded.QrCode2),
    Camera("节点", "CameraX、ML Kit 和骨架 Overlay 状态", Icons.Rounded.CameraAlt),
    Analysis("评估", "深蹲计数、评分和问题提示", Icons.Rounded.FitnessCenter),
    History("历史", "训练记录、详情和报告导出", Icons.Rounded.History),
}

private data class Metric(
    val label: String,
    val value: String,
    val detail: String,
)

private data class TaskItem(
    val title: String,
    val detail: String,
    val progress: Float,
)

@Composable
private fun ZhiZiJingTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val darkTheme = false
    val colorScheme = when {
        supportsDynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        supportsDynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            primary = Color(0xFF8BC5FF),
            secondary = Color(0xFFB8C9D8),
            tertiary = Color(0xFFD4BDEB),
        )
        else -> lightColorScheme(
            primary = Color(0xFF006A6A),
            secondary = Color(0xFF4A6363),
            tertiary = Color(0xFF525E7D),
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZhiZiJingApp() {
    var currentScreenName by rememberSaveable { mutableStateOf(AppScreen.Home.name) }
    val currentScreen = AppScreen.valueOf(currentScreenName)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 720.dp

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.fillMaxSize()) {
                if (useRail) {
                    AppNavigationRail(
                        currentScreen = currentScreen,
                        onScreenSelected = { currentScreenName = it.name },
                    )
                }

                Scaffold(
                    modifier = Modifier.weight(1f),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    text = currentScreen.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { currentScreenName = AppScreen.Home.name }) {
                                    Icon(Icons.Rounded.Sensors, contentDescription = "智姿镜")
                                }
                            },
                            actions = {
                                IconButton(onClick = { currentScreenName = AppScreen.Analysis.name }) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = "开始评估")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        if (!useRail) {
                            AppNavigationBar(
                                currentScreen = currentScreen,
                                onScreenSelected = { currentScreenName = it.name },
                            )
                        }
                    },
                ) { padding ->
                    AppContent(
                        screen = currentScreen,
                        contentPadding = padding,
                        onScreenSelected = { currentScreenName = it.name },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavigationBar(
    currentScreen: AppScreen,
    onScreenSelected: (AppScreen) -> Unit,
) {
    NavigationBar {
        AppScreen.entries.forEach { screen ->
            NavigationBarItem(
                selected = currentScreen == screen,
                onClick = { onScreenSelected(screen) },
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(
    currentScreen: AppScreen,
    onScreenSelected: (AppScreen) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        header = {
            Icon(
                imageVector = Icons.Rounded.Sensors,
                contentDescription = "智姿镜",
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        AppScreen.entries.forEach { screen ->
            NavigationRailItem(
                selected = currentScreen == screen,
                onClick = { onScreenSelected(screen) },
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
            )
        }
    }
}

@Composable
private fun AppContent(
    screen: AppScreen,
    contentPadding: PaddingValues,
    onScreenSelected: (AppScreen) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(0.98f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "智姿镜",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = screen.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (screen) {
            AppScreen.Home -> homeContent(onScreenSelected)
            AppScreen.Room -> roomContent(onScreenSelected)
            AppScreen.Camera -> cameraContent(onScreenSelected)
            AppScreen.Analysis -> analysisContent(onScreenSelected)
            AppScreen.History -> historyContent(onScreenSelected)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.homeContent(
    onScreenSelected: (AppScreen) -> Unit,
) {
    item {
        MetricGrid(
            metrics = listOf(
                Metric("最近训练", "深蹲 18 次", "平均评分 86"),
                Metric("协同设备", "2 台节点", "正面 + 侧面"),
                Metric("今日状态", "可演示", "MVP 优先"),
            ),
        )
    }
    item {
        PrimaryActionCard(
            title = "开始一次深蹲评估",
            detail = "按项目文档先跑通登录、房间、摄像头节点、姿态识别、计数评分和结果保存。",
            primaryText = "创建房间",
            secondaryText = "直接评估",
            onPrimaryClick = { onScreenSelected(AppScreen.Room) },
            onSecondaryClick = { onScreenSelected(AppScreen.Analysis) },
        )
    }
    item {
        StageCard(
            title = "M3 改造方向",
            items = listOf(
                TaskItem("Compose Material3 壳", "动态色、Scaffold、NavigationBar / Rail", 1f),
                TaskItem("核心页面信息架构", "训练首页、房间、节点、评估、历史", 0.75f),
                TaskItem("业务能力接入", "Room、CameraX、ML Kit、Nearby 后续逐步接入", 0.2f),
            ),
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.roomContent(
    onScreenSelected: (AppScreen) -> Unit,
) {
    item {
        CodeCard(label = "房间码", value = "839 204")
    }
    item {
        StageCard(
            title = "多设备协同流程",
            items = listOf(
                TaskItem("主控端创建房间", "Nearby advertising，展示房间码和二维码", 0.55f),
                TaskItem("节点端加入房间", "Nearby discovery，连接主控端", 0.45f),
                TaskItem("分配机位角色", "FRONT_CAMERA / SIDE_CAMERA 同步到节点", 0.35f),
            ),
        )
    }
    item {
        RolePanel(
            onPrimaryClick = { onScreenSelected(AppScreen.Camera) },
            onSecondaryClick = { onScreenSelected(AppScreen.Analysis) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.cameraContent(
    onScreenSelected: (AppScreen) -> Unit,
) {
    item {
        CameraPreviewPlaceholder()
    }
    item {
        InfoCard(
            title = "节点端职责",
            icon = Icons.Rounded.CameraAlt,
            chips = listOf("CameraX", "ML Kit", "Overlay", "ANALYSIS_SUMMARY"),
            lines = listOf(
                "打开摄像头预览并识别人体关键点。",
                "绘制骨架线，低置信度时提示调整站位。",
                "MVP 优先上传分析摘要，减少 Nearby 通信压力。",
            ),
            actionText = "模拟进入评估",
            onAction = { onScreenSelected(AppScreen.Analysis) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.analysisContent(
    onScreenSelected: (AppScreen) -> Unit,
) {
    item {
        MetricGrid(
            metrics = listOf(
                Metric("深蹲次数", "12", "状态 RISING"),
                Metric("当前评分", "88", "GOOD"),
                Metric("膝关节角", "92°", "最低点记录"),
            ),
        )
    }
    item {
        InfoCard(
            title = "实时姿态评估",
            icon = Icons.Rounded.FitnessCenter,
            chips = listOf("下蹲深度", "膝盖内扣", "背部前倾", "左右对称"),
            lines = listOf(
                "状态机：STANDING -> DESCENDING -> SQUATTING -> RISING。",
                "本轮主要问题：第 8 次背部前倾明显。",
                "建议：收紧核心，保持胸部打开，让髋部接近膝盖高度。",
            ),
            actionText = "结束并查看历史",
            onAction = { onScreenSelected(AppScreen.History) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.historyContent(
    onScreenSelected: (AppScreen) -> Unit,
) {
    items(
        listOf(
            "2026-05-23  深蹲 12 次  平均评分 88",
            "2026-05-22  开合跳 42 次  平均节奏 48/min",
            "2026-05-21  深蹲 18 次  平均评分 86",
        ),
    ) { record ->
        ListRecord(record)
    }
    item {
        InfoCard(
            title = "报告导出",
            icon = Icons.Rounded.Assessment,
            chips = listOf("Room", "JSON", "PdfDocument", "本地路径"),
            lines = listOf(
                "Room 只保存训练记录和文件路径。",
                "PDF 包含次数、评分、主要问题、建议和关键帧。",
                "JSON 可作为后续动作分类器训练数据来源。",
            ),
            actionText = "回到首页",
            onAction = { onScreenSelected(AppScreen.Home) },
        )
    }
}

@Composable
private fun MetricGrid(metrics: List<Metric>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(0.98f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        metrics.forEach { metric ->
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(116.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = metric.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = metric.value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = metric.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryActionCard(
    title: String,
    detail: String,
    primaryText: String,
    secondaryText: String,
    onPrimaryClick: () -> Unit,
    onSecondaryClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(0.98f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPrimaryClick) {
                    Icon(Icons.Rounded.Group, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(primaryText)
                }
                FilledTonalButton(onClick = onSecondaryClick) {
                    Icon(Icons.AutoMirrored.Rounded.DirectionsRun, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(secondaryText)
                }
            }
        }
    }
}

@Composable
private fun StageCard(
    title: String,
    items: List<TaskItem>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(0.98f),
        colors = CardDefaults.outlinedCardColors(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${(item.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(0.98f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun RolePanel(
    onPrimaryClick: () -> Unit,
    onSecondaryClick: () -> Unit,
) {
    InfoCard(
        title = "已连接设备",
        icon = Icons.Rounded.Devices,
        chips = listOf("HOST", "FRONT_CAMERA", "SIDE_CAMERA"),
        lines = listOf(
            "主控手机：在线，负责训练流程和结果保存。",
            "小米节点：正面机位，电量 82%，延迟 38ms。",
            "荣耀节点：侧面机位，电量 76%，延迟 44ms。",
        ),
        actionText = "查看节点画面",
        onAction = onPrimaryClick,
        secondaryText = "开始评估",
        onSecondaryAction = onSecondaryClick,
    )
}

@Composable
private fun CameraPreviewPlaceholder() {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.98f)
            .height(280.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.size(84.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.padding(22.dp),
                        tint = MaterialTheme.colorScheme.onSecondary,
                    )
                }
                Text(
                    text = "CameraX + ML Kit 预览区域",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "后续接入真实画面、骨架 Overlay 和低置信度提示",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    icon: ImageVector,
    chips: List<String>,
    lines: List<String>,
    actionText: String,
    onAction: () -> Unit,
    secondaryText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(0.98f),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(chip) },
                    )
                }
            }
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAction) {
                    Text(actionText)
                }
                if (secondaryText != null && onSecondaryAction != null) {
                    FilledTonalButton(onClick = onSecondaryAction) {
                        Text(secondaryText)
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRecord(record: String) {
    Card(
        modifier = Modifier.fillMaxWidth(0.98f),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Assessment, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = record,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = {},
                label = { Text("详情") },
            )
        }
    }
}
