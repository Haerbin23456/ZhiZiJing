# 智姿镜项目概要上下文

本文档用于后续 AI / Vibe Coding 新对话时快速理解项目。每次新开对话时优先提供本文档，再说明当前阶段。

## 1. 项目定位

智姿镜是一款安卓原生移动应用，用多台手机协同完成运动姿态评估。系统通过 CameraX 采集画面，使用 ML Kit Pose Detection 提取人体关键点，再基于规则和可选 TFLite 动作分类模型完成动作识别、动作计数、动作评分、结果保存和报告导出。

第一版重点不是做普通健身打卡，而是展示“多手机协同 + 人体姿态识别 + 深蹲评估”的完整闭环。

## 2. 目标优先级

P0 必须完成：
- 登录注册和自动登录。
- 训练首页和页面跳转。
- 主控端创建房间，节点端加入房间。
- 多设备组队和机位分配。
- CameraX 摄像头预览。
- ML Kit Pose Detection 人体关键点识别。
- 骨架 Overlay 绘制。
- 深蹲实时计数。
- 深蹲评分和问题提示：下蹲深度不足、膝盖内扣、背部前倾、左右不对称。
- 训练结果页。
- Room 保存训练记录，历史记录页可查看。

P1 尽量完成：
- 开合跳计数。
- PDF / JSON 报告导出。
- Nearby 节点状态上报：设备名、电量、延迟、在线状态。
- 同步倒计时。
- 单机位识别失败时的提示和降级。

P2 加分增强：
- 基于关键点序列的动作分类模型训练。
- TFLite 模型接入 Android assets。
- 自动识别动作类型：SQUAT、JUMPING_JACK、UNKNOWN。
- 多机位结果融合。

## 3. 技术栈

- Android 原生开发。
- Kotlin。
- XML 布局。
- ViewBinding。
- Activity + ViewModel / Repository。
- Room：保存用户、训练记录、动作结果、设备节点、导出记录。
- DataStore Preferences：保存登录状态和轻量配置。
- CameraX：摄像头预览和帧分析。
- ML Kit Pose Detection：人体关键点检测。
- Nearby Connections：多手机近场组队和消息传输。
- Gson 或 Moshi：Nearby JSON 消息序列化。
- Android PdfDocument：生成 PDF 报告。
- TensorFlow Lite：后期接入动作分类模型。

不要引入远程服务器、云数据库或大模型 API 作为核心功能。

## 4. 系统角色

HOST / 主控端：
- 创建训练房间。
- 开启 Nearby advertising。
- 管理已连接设备。
- 分配 FRONT_CAMERA / SIDE_CAMERA。
- 下发倒计时和训练指令。
- 展示实时次数、评分和问题。
- 汇总结果并保存历史记录。

CAMERA_NODE / 摄像头节点：
- 加入房间。
- 开启 Nearby discovery。
- 接收机位角色。
- 打开 CameraX 预览。
- 使用 ML Kit 识别关键点。
- 绘制骨架。
- 上传分析摘要或抽样 PoseFrame。

## 5. 核心页面

- LoginActivity：登录。
- RegisterActivity：注册。
- MainActivity：训练首页、最近训练、功能入口。
- RoomActivity：创建房间、展示房间码和二维码。
- JoinRoomActivity：输入房间码或扫码加入。
- DeviceGroupActivity：设备列表、机位分配、在线状态、同步倒计时。
- PrepareActivity：训练前准备、机位摆放提示、倒计时。
- CameraNodeActivity：节点端摄像头预览、姿态识别、骨架绘制。
- SquatAnalysisActivity：主控端深蹲实时评估。
- JumpingJackActivity：开合跳计数。
- ResultActivity：训练结果和建议。
- HistoryActivity：历史记录列表。
- HistoryDetailActivity：历史详情和报告导出入口。

## 6. 推荐包结构

```text
com.example.zhizijing
├── ui
│   ├── auth
│   ├── main
│   ├── room
│   ├── device
│   ├── prepare
│   ├── camera
│   ├── analysis
│   ├── result
│   └── history
├── data
│   ├── local
│   ├── datastore
│   ├── entity
│   ├── dao
│   ├── repository
│   └── mapper
├── domain
│   ├── model
│   ├── usecase
│   └── rule
├── pose
│   ├── detector
│   ├── model
│   ├── overlay
│   ├── feature
│   └── classifier
├── nearby
│   ├── connection
│   ├── message
│   ├── room
│   └── heartbeat
├── report
│   ├── json
│   └── pdf
└── utils
```

## 7. Room 数据表

User：
- userId: Long
- username: String
- passwordHash: String
- nickname: String?
- createdAt: Long
- lastLoginAt: Long?

TrainingSession：
- sessionId: Long
- userId: Long
- actionType: String
- startTime: Long
- endTime: Long?
- deviceCount: Int
- totalCount: Int
- averageScore: Float?
- mainProblems: String?
- reportPath: String?
- status: String

DeviceNode：
- nodeId: Long
- sessionId: Long
- deviceName: String
- endpointId: String?
- role: String
- batteryLevel: Int?
- networkDelayMs: Int?
- isOnline: Boolean
- lastHeartbeatAt: Long?

PoseFrame：
- frameId: Long
- sessionId: Long
- nodeId: Long
- timestampMs: Long
- cameraRole: String
- landmarksJson: String
- confidence: Float?
- frameImagePath: String?
- frameType: String

ActionResult：
- resultId: Long
- sessionId: Long
- actionIndex: Int
- actionType: String
- startTimeMs: Long
- endTimeMs: Long
- score: Float?
- kneeAngle: Float?
- trunkAngle: Float?
- depthLevel: String?
- problemType: String?
- suggestion: String?
- keyFramePath: String?

ExportRecord：
- exportId: Long
- sessionId: Long
- exportType: String
- filePath: String
- createdAt: Long
- fileSize: Long?

## 8. DataStore 配置

- isLoggedIn: Boolean
- lastUserId: Long
- defaultActionType: String
- lastRoomCode: String
- minPoseConfidence: Float
- squatDepthThreshold: Float
- backLeanThreshold: Float
- actionRecognitionThreshold: Float
- showSkeletonOverlay: Boolean
- saveVideoEnabled: Boolean
- defaultExportDir: String

## 9. Nearby 消息协议

Nearby 消息统一使用 JSON 字符串，Payload.fromBytes() 发送。

通用结构：

```json
{
  "type": "MESSAGE_TYPE",
  "sessionId": 1001,
  "senderEndpointId": "endpoint_x",
  "timestampMs": 1710000000000,
  "payload": {}
}
```

消息类型：
- JOIN_REQUEST
- JOIN_ACCEPTED
- DEVICE_STATUS
- ASSIGN_ROLE
- START_COUNTDOWN
- START_ANALYSIS
- POSE_FRAME
- ANALYSIS_SUMMARY
- END_TRAINING
- HEARTBEAT
- ERROR

MVP 优先上传 ANALYSIS_SUMMARY，避免每帧传完整图片或完整关键点导致 Nearby 压力过大。

## 10. 枚举值

DeviceRole：
- HOST
- FRONT_CAMERA
- SIDE_CAMERA
- BACKUP_CAMERA
- UNKNOWN

ActionType：
- SQUAT
- JUMPING_JACK
- STANDING
- UNKNOWN

TrainingState：
- IDLE
- PREPARING
- ANALYZING
- FINISHED
- ERROR

ProblemType：
- NONE
- SQUAT_DEPTH_NOT_ENOUGH
- KNEE_INWARD
- BACK_LEAN_TOO_MUCH
- ASYMMETRY
- LOW_CONFIDENCE
- RHYTHM_ABNORMAL

ClassifierSource：
- RULE_BASED
- TFLITE

## 11. 姿态数据结构

PoseFrame：
- sessionId: Long
- nodeId: Long
- timestampMs: Long
- cameraRole: FRONT_CAMERA / SIDE_CAMERA / SINGLE_CAMERA
- landmarks: Map<String, LandmarkPoint>
- overallConfidence: Float
- imageWidth: Int
- imageHeight: Int

LandmarkPoint：
- name: String
- x: Float
- y: Float
- z: Float?
- confidence: Float

常用关键点：
- LEFT_SHOULDER
- RIGHT_SHOULDER
- LEFT_ELBOW
- RIGHT_ELBOW
- LEFT_WRIST
- RIGHT_WRIST
- LEFT_HIP
- RIGHT_HIP
- LEFT_KNEE
- RIGHT_KNEE
- LEFT_ANKLE
- RIGHT_ANKLE
- LEFT_FOOT_INDEX
- RIGHT_FOOT_INDEX

低置信度关键点不参与评分。连续人体丢失超过 1 秒时，页面提示用户保持全身入镜。

## 12. 深蹲分析规则

状态机：

```text
STANDING -> DESCENDING -> SQUATTING -> RISING -> STANDING
```

计数条件：
- 站立状态开始。
- 膝角变小、髋部下降，进入 DESCENDING。
- 到达最低点或膝角低于阈值，进入 SQUATTING。
- 膝角变大、髋部上升，进入 RISING。
- 回到接近站立姿态，计数加一。

评分规则第一版采用扣分制：
- 下蹲深度不足：-15
- 膝盖内扣：-20
- 背部前倾明显：-15
- 左右不对称：-10
- 人体识别不稳定：-10
- 动作节奏异常：-5

输出：
- totalCount
- score
- postureLevel
- problemType
- suggestion
- kneeAngle
- trunkAngle
- depthLevel

## 13. 动作分类模型边界

ML Kit 负责人体现有关键点检测，不训练人体关键点模型。

自训练模型只负责动作类型识别：
- 输入：连续 16 到 32 帧人体关键点特征、角度、距离比例、时序变化。
- 输出：SQUAT、JUMPING_JACK、UNKNOWN 及置信度。
- 部署：导出 action_classifier.tflite、labels.txt、model_meta.json，放入 app/src/main/assets/。

分类器不直接输出深蹲评分。评分仍由规则模块完成，方便解释和验收。

模型加载失败、labels 缺失、输入维度不匹配或置信度过低时，自动 fallback 到 RuleBasedActionClassifier。

## 14. 本地文件存储

不要把图片、视频直接存入 Room。Room 只保存文件路径。

推荐目录：

```text
files/training/{sessionId}/frames/
files/training/{sessionId}/videos/
files/training/{sessionId}/reports/
files/training/{sessionId}/raw_pose/
```

## 15. 开发路线

1. 创建 Android 项目和基础依赖。
2. 登录、首页、Room、DataStore。
3. 单机 CameraX 预览。
4. ML Kit 姿态识别和骨架绘制。
5. 深蹲状态机、计数、评分、建议。
6. 开合跳基础计数。
7. Nearby 创建房间、加入房间、设备列表、机位分配。
8. 主控和节点联动，同步倒计时。
9. 结果页、历史记录、JSON/PDF 导出。
10. 独立训练动作分类器。
11. 接入 TFLite，并保留规则 fallback。

## 16. 课程验收演示脚本

1. 打开 APP，展示登录和首页。
2. 主控端创建房间，展示房间码。
3. 第二台手机加入房间。
4. 主控端展示设备列表、电量、延迟。
5. 分配正面或侧面机位。
6. 点击同步倒计时。
7. 节点手机进入摄像头预览并显示骨架。
8. 用户做 3 到 5 个深蹲。
9. 主控端展示次数、角度、评分和问题提示。
10. 故意做一个下蹲太浅、膝盖内扣或背部前倾动作，展示问题识别。
11. 结束训练，进入结果页。
12. 展示历史记录。
13. 导出 PDF 或 JSON 报告。
14. 如果分类器已接入，展示动作识别类型、置信度和 TFLite 来源。

## 17. 关键约束

- 不依赖远程服务器。
- 不把大模型 API 当核心功能。
- 不训练人体关键点检测模型。
- 第一版优先完成深蹲闭环。
- Nearby 消息统一用 JSON。
- 图片、视频、报告保存到本地文件，Room 保存路径。
- 模型训练是加分项，但为了达到 85 分目标，应保留完整训练文档、代码和 TFLite 接入路径。
