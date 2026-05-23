# 智姿镜动作识别模型训练方案

本文档给负责模型训练的同学使用，目标是完成“基于人体关键点序列的动作分类器”，并最终导出 TensorFlow Lite 模型接入 Android APP。

## 1. 模型任务

本项目不训练人体关键点检测模型。人体关键点检测由 Android 端 ML Kit Pose Detection 完成。

模型训练任务是动作分类：
- 输入：连续多帧人体关键点、关节角度、身体比例、时序变化特征。
- 输出：动作类型和置信度。

第一版标签：
- SQUAT
- JUMPING_JACK
- UNKNOWN

可选扩展：
- STANDING
- SQUAT_DOWN
- SQUAT_UP
- JUMPING_JACK_OPEN
- JUMPING_JACK_CLOSED

## 2. 推荐数据集

优先选择包含深蹲和开合跳的数据集，最好已经有关键点或容易从视频提取关键点。

### 2.1 Physical Exercise Recognition Time Series Dataset

来源：[Kaggle - Physical Exercise Recognition Time Series Dataset](https://www.kaggle.com/datasets/muhannadtuameh/exercise-recognition-time-series/versions/1)

特点：
- 已经是时间序列姿态数据，不是原始视频。
- 包含 Push-up、Pull-up、Sit-up、Jumping Jack、Squat。
- 使用 MediaPipe Pose 提取 33 个关键点。
- 数据中还包含角度和距离特征。

推荐程度：最高。  
原因：和本项目的“关键点序列分类”目标最接近，可以最快做出训练结果。

注意：
- Kaggle 数据下载可能需要账号和 API token。
- 许可为 CC BY-NC-SA 4.0，课程展示一般可用，但报告中要注明来源。

### 2.2 Penn Action Dataset

来源：[Penn Action Dataset](https://dreamdragon.github.io/PennAction/)

特点：
- 包含 2326 个视频序列。
- 包含 15 类动作。
- 包含人体关节标注。
- 类别包含 jumping_jacks、squats、sit_ups、bench_press 等。

推荐程度：高。  
原因：有视频和关节标注，动作类别适合本项目。

注意：
- 数据规模比 Kaggle 时间序列数据更麻烦。
- 需要额外处理成统一关键点格式。

### 2.3 UCF101

来源：[UCF101 官方数据集页面](https://www.crcv.ucf.edu/research/data-sets/ucf101/)

特点：
- 经典动作识别视频数据集。
- 包含 101 类动作、超过 13000 个视频片段。
- 类别包含 Body Weight Squats 和 Jumping Jack。

推荐程度：中。  
原因：适合作为补充视频数据，但没有直接关键点，需要先跑姿态估计。

注意：
- 数据较大。
- 从视频到关键点的处理成本较高。
- 课程项目不建议一开始用它作为主数据源。

### 2.4 Qualcomm Exercise Video Dataset

来源：[Qualcomm Exercise Video Dataset](https://www.qualcomm.com/developer/software/qevd-dataset)

特点：
- 面向 fitness coaching 的运动视频数据集。
- 包含大量运动和日常动作视频片段。

推荐程度：备选。  
原因：方向贴合健身场景，但需要确认下载条件、许可和具体类别。

## 3. 推荐训练路线

优先路线：

```text
Kaggle 关键点时间序列数据
-> 清洗标签
-> 统一特征列
-> 按动作片段切窗口
-> 训练小型 MLP
-> 验证集评估
-> 导出 TFLite
-> Android assets 接入
```

备选路线：

```text
Penn Action / UCF101 视频
-> 用 MediaPipe 或 ML Kit 提取关键点
-> 保存关键点 JSON / CSV
-> 切窗口
-> 训练小型 MLP 或 1D CNN
-> 导出 TFLite
```

## 4. 推荐目录结构

```text
model_training/
├── data/
│   ├── raw/
│   ├── labeled/
│   └── processed/
├── notebooks/
├── scripts/
│   ├── prepare_dataset.py
│   ├── extract_features.py
│   ├── train_mlp.py
│   ├── evaluate.py
│   └── export_tflite.py
├── models/
├── exports/
└── README.md
```

## 5. 数据格式

推荐原始样本字段：

```text
sampleId
subjectId
actionType
cameraRole
timestampMs
frameIndex
landmarks
confidence
```

LandmarkPoint：

```text
name
x
y
z
confidence
```

如果使用 Kaggle 时间序列数据，可先保留其已有关键点、角度、距离特征，再映射到本项目标签。

## 6. 特征工程

推荐特征：
- 归一化关键点坐标。
- 膝关节角度。
- 髋关节角度。
- 躯干倾角。
- 双脚距离 / 肩宽。
- 双手高度 / 肩膀高度。
- 左右膝盖相对脚踝偏移。
- 髋部高度变化。
- 膝角变化速度。
- 手腕高度变化。

时序窗口：
- windowSize: 16 到 32 帧。
- stride: 4 到 8 帧。

每个窗口输出一个动作类别。

## 7. 模型选择

建议按难度递进：

1. RuleBasedActionClassifier：不用训练，Android 端 fallback。
2. k-NN：快速验证特征有效性。
3. RandomForest：训练快，解释性较好，但 Android 端部署不如 TFLite 方便。
4. 小型 MLP：推荐最终路线，容易导出 TFLite。
5. 1D CNN / LSTM：时间充足再做。

课程设计推荐最终提交：
- 小型 MLP。
- 输入为扁平化窗口特征。
- 输出为 3 类 softmax。
- 导出 TFLite。

## 8. MLP 输入输出设计

输入：

```text
shape = [windowSize, featureCount]
```

导出 TFLite 前可以展平为：

```text
shape = [windowSize * featureCount]
```

输出：

```text
[p_squat, p_jumping_jack, p_unknown]
```

标签文件：

```text
SQUAT
JUMPING_JACK
UNKNOWN
```

## 9. 训练评估指标

至少输出：
- train accuracy
- validation accuracy
- confusion matrix
- per-class precision / recall / f1-score
- 模型输入维度
- 模型文件大小

课程展示可以截图：
- 训练日志。
- 混淆矩阵。
- 验证集准确率。
- TFLite 导出成功信息。

## 10. Android 接入产物

训练完成后交给 Android 端：

```text
app/src/main/assets/action_classifier.tflite
app/src/main/assets/labels.txt
app/src/main/assets/model_meta.json
```

model_meta.json 示例：

```json
{
  "modelName": "zhizijing_action_classifier",
  "version": "1.0",
  "inputType": "pose_landmark_sequence",
  "windowSize": 24,
  "featureCount": 64,
  "labels": ["SQUAT", "JUMPING_JACK", "UNKNOWN"],
  "confidenceThreshold": 0.7
}
```

## 11. Android 端接口约定

统一接口：

```text
ActionClassifier
输入：List<PoseFrame>
输出：ActionClassificationResult
```

ActionClassificationResult：

```text
actionType: String
confidence: Float
windowStartMs: Long
windowEndMs: Long
source: RULE_BASED / TFLITE
```

注意：
- 分类器只判断动作类型。
- 深蹲计数、评分、问题判断仍由规则模块完成。
- TFLite 加载失败时自动使用规则分类器。

## 12. 第一周可完成目标

1. 下载或整理 Kaggle Physical Exercise Recognition Time Series Dataset。
2. 写 `prepare_dataset.py` 读取数据。
3. 筛选 Squat、Jumping Jack，其余动作合并或采样为 UNKNOWN。
4. 按窗口生成训练样本。
5. 训练一个简单 MLP。
6. 输出验证集准确率和混淆矩阵。
7. 导出 `.tflite`、`labels.txt`、`model_meta.json`。

## 13. 风险和兜底

风险：
- 数据集关键点来自 MediaPipe，Android 端用 ML Kit，关键点命名和数量不完全一致。
- 公开数据集动作场景和实际手机拍摄场景不同，模型泛化可能一般。
- 数据下载和许可可能卡住。

兜底方案：
- Android 主流程永远保留规则识别。
- 模型只作为“动作类型识别加分项”展示。
- 如果模型准确率一般，也可以通过训练日志、TFLite 接入、fallback 机制证明完成模型训练与部署流程。

## 14. 引用来源

- Kaggle Physical Exercise Recognition Time Series Dataset: https://www.kaggle.com/datasets/muhannadtuameh/exercise-recognition-time-series/versions/1
- Penn Action Dataset: https://dreamdragon.github.io/PennAction/
- UCF101 official dataset page: https://www.crcv.ucf.edu/research/data-sets/ucf101/
- Qualcomm Exercise Video Dataset: https://www.qualcomm.com/developer/software/qevd-dataset
