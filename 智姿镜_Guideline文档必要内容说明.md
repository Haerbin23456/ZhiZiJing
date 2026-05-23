# 《智姿镜项目开发 Guideline.md》应包含的必要内容

> 目的：这个文档不是普通项目摘要，而是给后续 AI / Vibe Coding 使用的项目上下文说明书。  
> 每次新建对话时，将该文档提供给 AI，使 AI 能快速理解项目目标、开发边界、模块结构、数据格式和接口约定。

---

## 1. 项目一句话定位

必须用一两句话说明项目是什么。

应包含：
- APP 名称：智姿镜
- 项目类型：安卓原生移动应用
- 核心能力：多手机协同、运动姿态识别、动作计数、动作评分、结果保存、报告导出

作用：让 AI 立即理解项目方向，避免把项目误解成普通健身打卡 APP。

---

## 2. MVP 功能范围

必须明确第一版必须完成什么、不做什么。

应包含：
- 两台安卓手机协同：一台主控端，一台摄像头节点
- CameraX 实时采集
- ML Kit 提取人体关键点
- 自训练动作识别模型识别动作类型
- 至少完成深蹲识别、计数、评分和结果展示
- 历史记录保存
- PDF 报告导出

作用：防止开发范围失控，让 AI 优先生成可落地的最小可行版本。

---

## 3. 技术栈

必须固定项目使用的主要技术。

应包含：
- Android 原生开发
- Kotlin / Java
- CameraX
- ML Kit Pose Detection
- TensorFlow Lite
- Nearby Connections
- Room
- DataStore
- PdfDocument
- 本地文件存储

作用：避免 AI 随意更换技术路线，例如误引入服务器、Web 前端或云数据库。

---

## 4. 系统角色定义

必须说明不同手机在系统中的职责。

应包含：
- 主控端 Host：创建房间、管理设备、分配机位、展示结果、保存记录
- 摄像头节点 Camera Node：加入房间、采集画面、识别姿态、上传数据

作用：让 AI 写代码时清楚哪些功能属于主控端，哪些功能属于节点端。

---

## 5. 页面清单与页面职责

必须列出所有核心页面及其作用。

应包含：
- LoginActivity：登录注册
- MainActivity：训练首页
- RoomActivity：创建 / 加入房间
- DeviceGroupActivity：设备组队与机位分配
- PrepareActivity：训练准备与倒计时
- CameraNodeActivity：摄像头采集
- ActionRecognitionActivity：动作识别
- ActionAnalysisActivity：实时评估
- ResultActivity：训练结果
- HistoryActivity：历史记录

作用：让 AI 生成页面代码时保持流程一致，不遗漏关键页面。

---

## 6. 功能模块划分

必须按模块说明代码结构。

应包含：
- auth：登录注册
- database：Room 数据库
- communication：Nearby 通信
- camera：CameraX 采集
- pose：ML Kit 姿态关键点
- recognition：自训练动作识别模型
- analysis：动作计数与评分
- report：结果展示与 PDF 导出
- ui：页面与交互

作用：避免所有逻辑堆在 Activity 中，方便多人协作和后续维护。

---

## 7. 数据库结构定义

必须给出 Room 数据库的核心表和字段。

应包含：
- User：用户信息
- TrainingSession：训练任务
- DeviceNode：设备节点
- PoseFrame：姿态关键帧
- ActionResult：单次动作分析结果
- ExportRecord：报告导出记录

作用：让 AI 后续生成 Entity、DAO、Repository 时字段统一，避免数据库结构混乱。

---

## 8. DataStore 配置项

必须说明需要保存的轻量级配置。

应包含：
- lastUserId
- minPoseConfidence
- actionRecognitionThreshold
- defaultEvaluationRuleSet
- showSkeletonOverlay
- saveVideoEnabled
- defaultExportDir

作用：区分“结构化训练数据”和“轻量配置数据”，避免把所有内容都塞进 Room。

---

## 9. Nearby 通信接口协议

必须定义主控端和节点端之间传输的数据格式。

应包含：
- DEVICE_STATUS：设备状态
- ASSIGN_ROLE：机位分配
- START_TRAINING：开始训练
- STOP_TRAINING：结束训练
- POSE_FRAME：姿态帧数据
- ACTION_RECOGNITION：动作识别结果
- ACTION_RESULT：单次动作分析结果

作用：这是两个组员并行开发的关键接口。A 负责主控端展示和保存，B 负责节点端识别和上传，双方必须遵守统一 JSON 协议。

---

## 10. 枚举值统一定义

必须统一项目中的关键枚举。

应包含：
- DeviceRole：HOST、FRONT_CAMERA、SIDE_CAMERA、UNKNOWN
- ActionType：SQUAT、JUMPING_JACK、STANDING、UNKNOWN
- TrainingState：IDLE、PREPARING、ANALYZING、FINISHED、ERROR
- ProblemType：NONE、SQUAT_DEPTH_NOT_ENOUGH、KNEE_INWARD、BACK_LEAN_TOO_MUCH、LOW_CONFIDENCE

作用：避免代码中出现 squat、SQUAT、deep_squat 等混乱命名。

---

## 11. 自训练动作识别模型说明

必须明确模型任务边界。

应包含：
- ML Kit 只负责人体现有关键点检测
- 自训练模型负责动作类别识别
- 输入：连续多帧人体关键点、关节角度、身体比例等特征
- 输出：动作类型和置信度
- 部署方式：训练后导出为 TensorFlow Lite，放入 Android assets

作用：避免 AI 把“人体关键点检测”和“动作分类模型”混为一谈。

---

## 12. 姿态关键点数据格式

必须定义人体关键点的标准数据结构。

应包含：
- 关键点名称
- x、y、z 坐标
- confidence 置信度
- 坐标是否归一化
- 低置信度关键点如何处理

作用：保证 ML Kit 输出、模型输入、评分逻辑和数据库保存使用同一套数据格式。

---

## 13. 动作计数与评分规则

必须说明核心动作如何判断和评分。

应包含：
- 深蹲状态机：STANDING → GOING_DOWN → BOTTOM → GOING_UP → COMPLETED
- 深蹲计数条件
- 深蹲评分维度：下蹲深度、膝盖内扣、背部前倾、左右不对称、节奏异常
- 输出字段：score、problemType、suggestion、keyFramePath

作用：让 AI 生成动作分析逻辑时有明确标准，而不是随意写判断条件。

---

## 14. 本地文件存储约定

必须说明图片、视频和报告如何保存。

应包含：
- 关键帧保存目录
- 视频保存目录
- PDF 报告保存目录
- Room 数据库只保存文件路径，不直接保存图片和视频

作用：避免数据库体积过大，也便于历史记录和报告导出。

---

## 15. 推荐代码目录结构

必须给出推荐包结构。

应包含：
- auth/
- database/
- communication/
- camera/
- pose/
- recognition/
- analysis/
- ui/
- report/

作用：让 AI 后续生成代码时保持结构稳定，方便多人协作。

---

## 16. 开发约束

必须列出开发中不能违反的规则。

应包含：
- 不依赖远程服务器
- 第一版优先完成深蹲闭环
- Nearby 消息统一使用 JSON
- 图片和视频不直接存入 Room
- 自训练模型必须以 TFLite 形式集成
- 多机位融合可以作为后续增强，不作为第一版硬性目标

作用：限制 AI 过度设计，保证课程项目能按时完成。

---

## 17. 开发优先级

必须区分 P0、P1、P2。

应包含：
- P0：必须完成，决定项目能否演示
- P1：尽量完成，用于提升展示效果
- P2：时间充足再做，不影响主流程

作用：帮助组长安排 ddl，也帮助 AI 在功能取舍时做正确判断。

---

## 18. 最终验收标准

必须写清楚项目做到什么程度算完成。

应包含完整演示流程：
1. 登录 APP
2. 主控端创建房间
3. 节点端加入房间
4. 主控端分配机位
5. 节点端打开摄像头并识别人体关键点
6. 用户开始训练
7. 系统识别深蹲
8. 系统计数和评分
9. 训练结束后展示结果
10. 历史记录可查看
11. PDF 报告可导出

作用：让开发目标可检查、可验收，避免只完成零散功能但无法完整演示。

---

# 总结

- 项目目标
- MVP 范围
- 技术栈
- 系统角色
- 页面职责
- 模块划分
- 数据库结构
- 通信协议
- 模型输入输出
- 计数评分规则
- 文件存储
- 开发约束
- 优先级
- 验收标准
