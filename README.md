# Dial Record Labs

实验性 Android 通话录音 App。录音会话可以由系统电话事件、SDK 接入代码或手动操作驱动，不要求存在系统通话记录。

## 代码结构

- `recording/RecordingService.kt`：前台服务、录音生命周期、命令及结果。状态与事件放在同一文件，所有命令在主线程串行处理。
- `calls/SystemCallController.kt`：系统电话的 OFFHOOK / IDLE 监听、拨号等待和超时。
- `calls/CallLogMatcher.kt`：系统通话结束后的可选信息补全。
- `data/RecordingEntry.kt`、`RecordingStore.kt`：录音信息、文件保存、时长更新与重命名恢复。
- `ui/`：模式选择、系统拨号、手动录音验证、播放和时长编辑。

没有旧数据迁移逻辑，也没有 DurationSource。来源仅包含 SYSTEM 和 SDK，不代表已经集成了对应 SDK 的音频接口。

所有拨打方式都必须提供 phoneNumber，不能使用空值或 SDK 用户标识。页面统一标准化号码，Service 和录音数据统一校验为可选的前导 `+` 加 1–32 位数字。

## 生命周期与接入

为每个请求创建一个 UUID，作为整个录音生命周期和后续更新的稳定 ID：

```kotlin
val id = UUID.randomUUID().toString()

RecordingService.prepare(context, id, CallSource.SDK, "13800138000", receiver)
// receiver 收到 READY 后，前台服务已建立。
// 在需要开始采集的时机（例如 SDK 的通话事件）调用：
RecordingService.start(context, id)

// SDK 通话结束；不知道通话时长时省略第三个参数。
RecordingService.finish(context, id, durationSeconds = 56)

// 保存完成之后，也可以独立修正时长与文件名。
val updated = RecordingStore.updateDuration(context, id, 60)
val audio = RecordingStore.audioFile(context, updated.recordingId)
```

`ResultReceiver` 使用主线程 Handler；通过 `RecordingResult.fromBundle(bundle)` 解析事件：

- `READY`：服务就绪，还没有开始录音。
- `STARTED`：录音器成功启动。
- `COMPLETED`：音频已封装并保存，结果包含录音信息；不表示电话一定接通过。
- `CANCELLED`：准备被取消，或未开始录音便结束。
- `FAILED`：准备、录音或保存失败，附错误信息。异常关闭时若成功保存音频，结果仍会携带该录音。

同一时间只允许一个录音会话；已有会话时拒绝新的准备请求，不替换当前会话。重复开始、结束，以及其他 ID 的过期命令不会重复录音或中断当前录音。

准备阶段可调用 `cancelPreparation(context, id)`。录音开始后取消准备不会停止录音，必须调用 `finish`。录音期间可用 `setDuration(context, id, seconds)` 暂存时长；已完成录音使用 Store 更新。事件只在内存中投递，页面销毁后通过 Store 重读已保存录音；服务不会在进程被杀后自动恢复采集。

## 不同来源

### 系统电话

选择 SYSTEM。页面准备服务后发起 ACTION_CALL；Controller 收到 OFFHOOK 时开始录音，IDLE 时停止。等待 OFFHOOK 最多 90 秒，等待时收到来电则取消。

文件保存后立即结束录音会话，通话记录匹配最多继续尝试 10 秒。匹配只补充未知时长，不覆盖已经传入或手动编辑的时长。不允许两条录音关联同一条通话记录。匹配是进程内的可选补全，进程退出、匹配失败或权限在会话开始后被撤销时可以保留未知时长。

系统模式保留原有无障碍服务前置检查。

### SDK 通话

选择 SDK，使用显式 start / finish，不注册系统电话监听，也不查询系统通话记录。

当前页面的 SDK 模式是通用录音的手动验证入口，项目未接入 Infobip SDK。实际集成时，由 SDK 接入层根据业务需要把通话事件转换成录音命令，并提供真实通话时长。

手动验证时通过页面的“结束录音”按钮停止录音。前台通知仅显示录音状态并提供返回 App 的入口，不提供结束录音操作。

两种模式使用同一套权限要求。每次发起拨号或录音前，统一检查并申请尚未授权的电话、电话状态、通话记录及麦克风权限，全部获得后才能继续。Android 13 及以上同时申请尚未授权的通知权限，但拒绝通知权限不阻止本次操作。Service 准备会话时也统一检查必需权限，直接调用 Service 不能绕过要求。

目前两种模式都使用 MediaRecorder 的 VOICE_RECOGNITION 音源。支持 SDK 录音生命周期不等于已经验证能采集其双向通话音频；实际收音效果需要针对 SDK 和设备单独验证。

## 数据与文件

- `durationSeconds == null`：通话时长未知；文件名不带 duration。
- `durationSeconds == 0`：明确的零秒；文件名包含 `_0`。
- 通话时长不是音频时长，不用录音长度填补未知通话时长。
- 每条录音保存在 `files/recordings/<recordingId>/`。文件名为 `<phoneNumber>_<recordingStartedAtMs>[_<durationSeconds>].m4a`，直接使用校验后的电话号码。
- 录制时写入 `audio.part`；成功停止并释放录音器后才发布元数据和最终文件名。未完成的录音不出现在列表中。
- 每条录音的 `entry.json` 使用 AtomicFile 原子写入，随后重命名音频。进程在两步之间中断时，下次读取按已提交元数据完成重命名。
- 所有时长修改、系统记录关联和文件重命名均由 Store 串行处理。普通重命名失败会尝试恢复旧元数据，保留音频；重复提交相同时长是幂等操作。
- 文件名不是标识。调用方保存 recordingId，播放或导出前使用 `RecordingStore.audioFile` 取得当前文件。

## 验证

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

设备测试涵盖实际麦克风采集与音频封装、SDK 会话、系统准备取消、过期命令、时长更新、重命名失败回滚和中断恢复。设备测试会申请全部必需权限，不会拨打电话。
