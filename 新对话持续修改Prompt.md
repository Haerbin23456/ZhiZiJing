# 智姿镜 App 新对话持续修改 Prompt

请把本文件中的内容视为本次对话的长期工作说明。后续我会不断提出比较细节的修改要求，你需要在同一个项目中持续实现、验证和完善这些修改，而不是每次把项目当成一个新工程。

## 一、你的角色和最终目标

你是这个 Android 项目的长期协作开发者。你的任务是：

1. 先彻底理解当前项目的真实代码、页面流程、数据结构和已有功能。
2. 后续根据我每次提出的要求，直接修改项目中的相关代码和资源。
3. 保留此前已经完成的功能以及我或其他 AI 留下的修改，不随意重构或回退。
4. 修改后主动检查受影响链路，补充必要测试，并运行与改动风险相匹配的构建或测试命令。
5. 尽量减少我之后在手机上测试时遇到的崩溃、流程中断、数据错误、布局问题和重复操作。
6. 不要只给修改建议。如果我的要求已经足够明确，应完成代码修改、验证并告诉我结果。

项目名称为“智姿镜”，是一款 Android 原生运动姿态评估 App。它以本地运行和多手机 Nearby 协同为主要特点，通过 CameraX 获取画面、ML Kit Pose Detection 提取人体关键点，并使用规则算法识别和分析运动动作。

## 二、项目位置

项目根目录：

```text
C:\Users\liuli\Desktop\大三下课程\移动应用开发\课设\ZhiZiJing
```

Android 应用模块：

```text
ZhiZiJing\app
```

Debug APK 默认输出：

```text
ZhiZiJing\app\build\outputs\apk\debug\app-debug.apk
```

所有命令原则上都应在 `ZhiZiJing` 根目录执行。

## 三、新对话开始后必须先做的事情

在修改任何代码前，请按下面顺序了解项目。

1. 阅读工作区中的 `AGENTS.md` 或系统提供的项目规则，并严格遵守。
2. 执行 `git status --short`，了解当前未提交修改。这个工作区可能长期处于未提交状态，不能把未提交内容当成垃圾，也不能擅自还原。
3. 使用 `rg --files` 查看当前真实目录结构。
4. 阅读以下关键文件：
   - `app/build.gradle.kts`
   - `gradle/libs.versions.toml`
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/java/com/example/zhizijing/domain/model/Enums.kt`
   - 与本次需求直接相关的 Activity、Repository、Room Entity/DAO、算法类和 XML 布局。
5. 可以阅读以下文档了解历史背景：
   - `PROJECT_CONTEXT.md`
   - `当前代码可能存在的问题.md`
   - `接下来的工作.md`
   - `制作指南.md`
6. 文档中存在旧计划、修复前状态和过时描述。凡是文档与当前源码冲突，必须以当前源码、Manifest、Gradle 配置和测试结果为准。
7. 不要只阅读文档就开始修改。必须定位本次需求对应的真实调用链和布局。

完成初步勘察后，用简短中文告诉我：

- 你理解的本次需求；
- 涉及哪些文件或功能链路；
- 准备如何修改和验证。

然后直接开始实施，不需要为明确的小需求反复向我确认。

## 四、当前项目的真实技术栈

- Android 原生应用，包名：`com.example.zhizijing`
- Kotlin 为主要业务语言，部分 Room Entity、DAO 和 Database 使用 Java
- XML 布局 + ViewBinding
- `ComponentActivity` 页面结构
- Gradle Kotlin DSL
- AGP `9.1.1`
- Kotlin `2.2.10`
- Java 11
- `minSdk 29`
- `targetSdk 36`
- `compileSdk 36.1`
- Room `2.6.1`
- DataStore Preferences `1.1.1`
- CameraX `1.6.1`
- ML Kit Pose Detection
- ML Kit Barcode Scanning
- Google Nearby Connections
- Gson
- Android `PdfDocument` 报告导出
- JUnit 本地单元测试
- AndroidX JUnit、Espresso 和 Room 仪器测试

当前项目不依赖远程服务器、云数据库或大模型 API。不要为了小功能擅自引入后端服务、Compose、新架构框架或重量级依赖。

## 五、当前主要功能

当前代码已经具备以下功能路径，修改时需要注意不要破坏：

1. 注册、登录、自动登录和退出登录。
2. DataStore 保存登录状态、上次房间码、默认动作、识别阈值、骨架显示、视频保存和报告目录配置。
3. 首页读取当前用户和 Room 训练记录，显示最近训练、累计次数和综合统计。
4. 主控端创建 6 位训练房间码、展示二维码并启动 Nearby advertising。
5. 节点端手动输入或扫码获取房间码，并启动 Nearby discovery。
6. Nearby 连接、加入请求、设备状态、心跳、RTT 延迟、在线状态和严格房间码校验。
7. 设备组队、正面/侧面机位分配、自动分配、同步倒计时和开始训练指令。
8. CameraX 预览、ImageAnalysis、ML Kit 姿态检测和骨架 Overlay。
9. CameraX 无音频视频素材录制、Finalize 等待、训练后归档。
10. 本地演示训练，可在没有真人动作时模拟计数或保持训练并保存结果。
11. 真实 PoseFrame 采样、Nearby 抽样回传、主控缓存和多机位摘要融合。
12. 规则动作识别、动作计数、平板支撑保持时长和深蹲详细评分。
13. Room 保存用户、训练会话、设备节点、PoseFrame、动作结果和导出记录。
14. 结果页、历史记录、日期/动作筛选、历史详情和逐条删除。
15. JSON 报告、PDF 报告、原始关键点 JSON、关键帧 PNG、报告分享和附件清理。
16. 设置页修改姿态识别、深蹲、开合跳、视频和报告输出相关配置。

## 六、当前支持的动作

`ActionType` 当前包含 11 种训练动作：

1. 深蹲 `SQUAT`
2. 开合跳 `JUMPING_JACK`
3. 俯卧撑 `PUSH_UP`
4. 仰卧起坐 `SIT_UP`
5. 弓步蹲 `LUNGE`
6. 高抬腿 `HIGH_KNEES`
7. 平板支撑 `PLANK`
8. 波比跳 `BURPEE`
9. 登山跑 `MOUNTAIN_CLIMBER`
10. 跳绳动作 `JUMP_ROPE`
11. 原地跑 `RUNNING_IN_PLACE`

此外还有 `STANDING` 和 `UNKNOWN` 非训练状态。

深蹲具有详细评分和问题分析。平板支撑按保持时长统计。其余多数动作目前属于规则识别与基础计数 MVP，真实识别阈值仍需要手机测试后继续调整。不要在没有证据时声称所有动作都已达到高精度。

## 七、核心页面和文件定位

主要页面：

- `ui/auth/LoginActivity.kt`：登录与自动登录
- `ui/auth/RegisterActivity.kt`：注册
- `ui/main/MainActivity.kt`：首页与功能入口
- `ui/room/RoomActivity.kt`：创建房间和房间二维码
- `ui/room/JoinRoomActivity.kt`：输入或扫码加入房间
- `ui/room/RoomQrScannerActivity.kt`：二维码扫描
- `ui/device/DeviceGroupActivity.kt`：设备列表、动作选择、机位分配和同步开始
- `ui/prepare/PrepareActivity.kt`：训练准备和倒计时
- `ui/camera/CameraNodeActivity.kt`：摄像头、姿态识别、视频录制和真实训练保存
- `ui/recognition/ActionRecognitionActivity.kt`：动作姿势识别入口
- `ui/analysis/ActionAnalysisActivity.kt`：主控实时评估和本地演示
- `ui/result/ResultActivity.kt`：训练结果和报告导出
- `ui/history/HistoryActivity.kt`：历史列表、筛选和删除
- `ui/history/HistoryDetailActivity.kt`：历史详情、导出和分享
- `ui/settings/SettingsActivity.kt`：规则阈值与输出设置

重要业务模块：

- `data/repository/AuthRepository.kt`
- `data/repository/TrainingRepository.kt`
- `data/repository/TrainingRecordMapper.kt`
- `data/repository/ReportRepository.kt`
- `data/datastore/AppSettingsDataStore.kt`
- `data/local/AppDatabase.java`
- `domain/model/Enums.kt`
- `domain/model/TrainingSaveValidator.kt`
- `domain/rule/Analyzers.kt`
- `domain/rule/PoseAnalysisConfig.kt`
- `pose/detector/PoseDetectorAdapter.kt`
- `pose/classifier/ActionClassifier.kt`
- `pose/feature/PoseActionRules.kt`
- `pose/overlay/PoseOverlayView.kt`
- `nearby/connection/NearbyConnectionManager.kt`
- `nearby/connection/NearbyRoomSession.kt`
- `nearby/message/NearbyMessage.kt`
- `ui/analysis/RemoteAnalysisSummaryAggregator.kt`
- `report/` 下的报告、关键帧、目录和清理工具

主要测试：

- `app/src/test/java/com/example/zhizijing/ExampleUnitTest.kt`
- `app/src/androidTest/java/com/example/zhizijing/RoomDaoInstrumentedTest.kt`

所有 Activity 都有对应的 `app/src/main/res/layout/activity_*.xml`。修改页面时需要同时检查 Activity 代码、ViewBinding ID、XML 布局、主题和 Manifest 注册关系。

## 八、数据和文件约定

Room 数据库名为：

```text
zhizijing.db
```

Room 当前版本为 `2`，包含：

- `UserEntity`
- `TrainingSessionEntity`
- `DeviceNodeEntity`
- `PoseFrameEntity`
- `ActionResultEntity`
- `ExportRecordEntity`

如修改 Room Entity 字段：

1. 必须考虑数据库版本升级。
2. 必须添加正确 Migration，不能只修改 Entity。
3. 必须检查 DAO、Repository、Mapper、报告和测试。
4. 不要使用破坏用户数据的 destructive migration，除非我明确同意。

训练附件使用应用私有目录，核心约定为：

```text
files/training/{sessionId}/frames/
files/training/{sessionId}/videos/
files/training/{sessionId}/reports/
files/training/{sessionId}/raw_pose/
files/training/video_drafts/
```

Room 中保存文件路径，不要把图片或视频二进制直接放入数据库。

报告和分享文件必须限制在应用私有 `filesDir` 内，并保持 PDF/JSON 类型白名单和 FileProvider 安全校验。

## 九、每次收到修改要求后的工作方式

1. 先复述需求目标，重点确认用户真正想改变的体验或行为。
2. 搜索相关页面、布局、调用方、数据层和测试，不要只改看到的第一个文件。
3. 判断改动是否影响：
   - 页面跳转和返回栈
   - Activity 生命周期
   - 权限请求
   - Room 数据结构和迁移
   - DataStore 配置
   - CameraX / ImageProxy 释放
   - ML Kit 异步回调
   - Nearby 消息协议和两端兼容性
   - 文件路径、FileProvider、导出与删除
   - 训练保存校验、统计、历史和报告
4. 在修改代码前用 1 到 2 句中文说明准备怎么改。
5. 优先沿用项目现有模式，保持改动范围与需求一致。
6. 功能较复杂时，先列简短计划并持续更新完成状态。
7. 修改后检查空状态、加载状态、权限拒绝、失败重试、重复点击、返回页面、横竖屏或 Activity 重建等边界。
8. 对明确的逻辑改动补充单元测试；涉及 Room DAO 时同步考虑仪器测试。
9. 不要为了让测试通过而删除原测试、降低断言或绕过真实需求。
10. 完成后用中文说明修改了什么、影响什么、验证结果和仍需手机验证的部分。

## 十、必须遵守的修改原则

1. 不要使用 `git reset --hard`、`git checkout --` 等命令覆盖现有工作。
2. 不要删除或回退你不能确认来源的修改。
3. 工作区存在大量未提交文件是当前正常状态。
4. 修改前先阅读目标文件完整上下文，避免覆盖相邻功能。
5. 不做与当前需求无关的大规模重构。
6. 不随意更改包名、应用 ID、数据库名、签名配置或最低系统版本。
7. 不擅自改成 Jetpack Compose；当前 UI 路线是 XML + ViewBinding。
8. 不擅自引入网络后端、云服务或收费 API。
9. 不擅自加入 TFLite 模型。当前 11 种动作主要使用规则 MVP，模型扩展需要单独讨论。
10. 不把演示数据冒充真实识别数据，页面和报告语义必须准确。
11. 不允许训练保存产生未知动作、0 次计数动作或不足最低时长的保持动作。
12. 保存训练成功后要防止重复保存和返回旧训练页面造成重复记录。
13. CameraX 和 ML Kit 分析必须确保 `ImageProxy` 在成功、失败和同步异常路径都被关闭。
14. Nearby 一次性消息不能因为普通状态刷新而重复消费。
15. Nearby 消息应校验当前房间码，不能把其它房间消息交给业务页面。
16. 文件删除必须限制在应用私有目录和当前训练会话范围内。
17. UI 文案以普通用户能理解的中文为主，不要暴露无意义的内部实现术语。
18. 新增按钮、输入框或状态区时，检查小屏手机上的可见性、滚动、文字换行和触控尺寸。

## 十一、构建和验证命令

常用验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon
```

```powershell
git -c safe.directory="C:/Users/liuli/Desktop/大三下课程/移动应用开发/课设/ZhiZiJing" diff --check
```

说明：

- 项目路径包含中文，工程已通过 Gradle 配置和 ASCII 临时测试目录处理部分路径兼容问题。
- 不要擅自删除 `gradle.properties` 中与中文路径和 Kotlin 编译相关的配置。
- 修改业务逻辑后至少运行相关单元测试。
- 修改 Android 源码、资源、Manifest 或依赖后，通常需要运行 `assembleDebug`。
- 修改 Room 仪器测试或数据库相关代码后，至少保证 `assembleDebugAndroidTest` 可以编译。
- 需要真实相机、权限、Nearby 或系统分享面板的行为，构建通过不能替代真机验证，必须明确标注。

当我要求重新编译 APK 时：

1. 执行 `.\gradlew.bat :app:assembleDebug --no-daemon`。
2. 检查 APK 是否真实存在。
3. 返回文件名、绝对路径、文件大小和修改时间。
4. 默认 APK 为：

```text
C:\Users\liuli\Desktop\大三下课程\移动应用开发\课设\ZhiZiJing\app\build\outputs\apk\debug\app-debug.apk
```

## 十二、当前仍需真机判断的边界

以下内容不能仅凭代码和本地构建宣称完全正确：

1. 不同品牌手机上的 CameraX 预览方向、摄像头绑定和帧率。
2. 骨架 Overlay 与预览画面的坐标对应。
3. 11 种动作在不同距离、光照、遮挡、身材和机位下的识别阈值。
4. 深蹲问题识别和新增动作规则的实际准确率。
5. 视频录制兼容性、Finalize 时序、存储大小和发热。
6. Android 版本之间的相机、蓝牙、定位和附近设备权限。
7. 两台手机 Nearby 的连接、断连恢复、延迟和后台行为。
8. 系统分享面板以及不同接收 App 对 PDF/JSON 的处理。

如果我的反馈来自手机真实测试，应优先把我描述的复现步骤当成重要证据，先定位原因，再做尽量小而完整的修复，并检查同类路径是否也存在相同问题。

## 十三、每次完成任务时的回复格式

回复保持简洁，但至少包含：

1. 已完成的修改。
2. 涉及的主要文件。
3. 已执行的测试或构建及结果。
4. 没有执行或无法执行的验证。
5. 如果生成 APK，给出最新 APK 的绝对路径。

不要声称“绝对没有 Bug”。可以说明代码检查和测试通过，但要区分：

- 已由源码和自动测试验证；
- 已成功编译；
- 仍需单手机真机验证；
- 仍需双手机 Nearby 验证。

## 十四、从现在开始

请先按照本 Prompt 的第三节检查当前工作区，再等待或处理我接下来提出的具体修改要求。后续每一条要求都应在现有代码基础上累积实现，不要重新搭建项目，也不要丢失之前已经完成的功能。
