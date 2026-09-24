# Dial Record Labs

用于改造混合 App 通话功能的技术预研，不是录音管理 App，也不复刻线上业务。

## 验证三个场景

1. **系统通话 · 录音**：准备麦克风前台服务 → 拨号 → OFFHOOK 开始录音 → IDLE 停止录音 → 查询本次系统通话记录 → 返回结果。
2. **系统通话 · 不录音**：同样拨号、监听和查询记录，不绑定录音服务，不申请麦克风权限。
3. **SDK 事件模拟 · 手动录音**：开始请求模拟开始录音，按钮模拟 SDK 通话结束，可输入 SDK 返回的通话时长。不监听系统电话，不查询系统记录。

**没有接入 Infobip。第三种场景只验证录音生命周期和结果传递，不能证明能采集 Infobip 的双向音频。**

页面分别展示 H5 回调 JSON 与 Android 本地录音/上传诊断，**不把文件地址回调给 H5**。`MainActivity.complete()` 是未来接 H5 回调的位置。预研页面生成 UUID 模拟 callId；移植时换成 H5 传入的原值。SDK 结果里的 `simulated` 等字段只是演示，不是完整线上协议。录音前填写关联用 userId（默认实验值 `lab`，线上应来自登录信息）。

## 代码结构

- `ui/MainActivity.kt`：权限、单请求协程、流程编排、结果展示；不保存或恢复请求。
- `calls/SystemCallController.kt`：一次系统拨号，等待 OFFHOOK / IDLE；在 finally 注销广播。
- `calls/CallLogMatcher.kt`：独立查询通话记录，完全不依赖录音服务或文件。
- `recording/RecordingService.kt`：绑定式麦克风前台服务，只有准备、开始、结束和资源清理；不负责电话状态或业务回调。
- `recording/RecordingFiles.kt`：临时目录、文件名协议、重命名发布到待上传目录，无元数据存储。
- `upload/RecordingUploader.kt`：独立串行轮询、失败重试、收到成功确认后删除文件。
- `LabApplication.kt`：启动唯一的进程级上传协程；`uploadRecording()` 是替换真实网络请求的位置。
- `accessibility/CallAccessibilityService.kt`：保留原有系统录音实验的无障碍服务前置检查。

删除了 RecordingEntry、RecordingStore、录音列表、播放器、手动修改历史时长、元数据及文件名恢复机制。使用已有依赖提供的协程和 lifecycleScope，没有新增依赖。

## 一次请求的生命周期

- 同时只处理一次请求，callId 保存在不可变请求中，不会被后续点击覆盖。
- `lifecycleScope` 跟随页面销毁取消。进入后台或打开系统电话界面不会取消请求。
- 取消时注销电话监听、停止录音并解绑；不恢复会话、不向重建的页面补发回调。页面销毁不会挂断系统电话。
- 录音服务在可见页面中准备，再发起系统拨号，以满足麦克风前台服务的启动限制。
- 录音服务不自动重启。正常取消会尽力封装已经录制的音频；进程被直接杀死时不保证 finally/onDestroy 执行。
- 拨号或 SDK 结果与录音结果分开。录音启动/封装/发布失败显示在 Android 本地诊断中，不提前结束通话等待，也不替换通话业务结果。准备服务失败则本次请求失败，不拨号。
- 系统通话等待 OFFHOOK 最多 90 秒；通话进行中不强制超时。等待期间出现 RINGING 会结束本次请求。
- OFFHOOK **不代表对方接听**，不能用于计算真实通话时长。SDK 模拟不以录音长度替代通话时长，留空为未知，0 为明确的零秒。

代码直接使用挂起流程，不建立通用事件总线、持久化状态机或会话恢复层。`withRecorder` 的 finally 保证调用者取消时释放录音资源。

## 系统通话记录匹配

拨号前查询最大记录 ID 作为水位。挂断后仅考虑：

- ID 大于拨号前水位；
- 呼出记录；
- 号码与本次号码匹配（PhoneNumberUtils.compare）；
- 记录时间位于本次拨号到 OFFHOOK 的窗口内，前后容差 2 秒。

每 500 毫秒查询一次，最多等待约 10 秒（系统 Provider 单次查询耗时另计）。仅返回唯一候选；超时或多候选不猜测，不拿“最新一条”兜底。匹配失败返回明确错误，已完成的录音仍保留。

这仍是设备相关的启发式匹配，不是 Android 提供的通话 ID 关联保证。多 SIM、其他应用同时拨号、厂商记录时间差异需要真机测试。通话记录中的 date 为毫秒、duration 为秒。

## 文件名就是关联协议

线上参考代码使用 `<userId>_<phoneNumber>_<callTime秒>_<duration秒>.mp3`。本项目沿用字段顺序，但实际编码为 AAC/MPEG-4，因此使用 **`.m4a`**，不能只修改扩展名伪装成 MP3。后端是否接受 m4a 仍需联调确认。

- `userId`：一次请求开始时固定，不能在上传时用新的登录用户覆盖。
- `phoneNumber`：与业务回调里的 number 使用同一份标准化号码。
- `callTime`：沿用线上有录音时的约定，取录音开始时间；无录音开始时间时取系统记录时间或模拟 SDK 开始时间。业务回调 `date` 为秒精度的毫秒时间戳，文件名为 `date / 1000`。
- 系统记录原始时间另放在 `callLog.systemDate`，不能误用它代替关联用的 `date`。
- `duration`：系统记录/SDK 结果的通话时长，不是音频长度；明确的 0 可以发布，未知不伪造为 0。这里不沿用旧 App 静默删除零秒音频的策略。

例如：回调 number 为 `+244123456`、date 为 `1700000000000`、duration 为 `42`，userId 为 `123`，则文件名为 `123_+244123456_1700000000_42.m4a`。UUID 只用于临时文件与模拟 callId，不能擅自加入最终协议文件名。

## 文件发布与独立轮询

```text
recordings/staging/<UUID>.part       录制中，不能上传
recordings/staging/<UUID>.m4a        已封装，但尚未发布，不能上传
recordings/pending/<协议文件名>.m4a   关联字段完整，等待上传
```

- 挂断后立即封装；拿到关联字段后，先按协议命名并移入 pending，再返回通话结果。回调不等待网络上传。
- staging 与 pending 在同一文件系统，通过一次 rename 发布，轮询不会看到半成品。
- 记录匹配失败、时长未知、页面中途销毁：保留 staging 文件，不上传、不恢复旧会话。发布失败也保留原文件。同名冲突报错，不覆盖、不自行加后缀。
- pending 文件名从发布到重试始终不变；目录本身就是队列，没有 entry.json 或数据库。
- Application 启动时扫描一次，之后每轮结束等待 60 秒；逐个上传。一个文件失败不阻塞其他文件。
- 只有网络适配器返回业务成功确认才删除文件。失败、超时、取消都保留文件。进程退出后轮询停止，下次启动重新扫描 pending；与 Activity/H5 生命周期无关。
- 这是“至少一次”尝试，不是“恰好一次”。服务器收到文件但响应丢失，或本地删除失败，都会导致同名重试；服务端需要按约定处理幂等，客户端不能单方面保证。
- **当前没有接入真实服务器。** `LabApplication.uploadRecording()` 只打印待上传文件名并返回 false，绝不模拟成功删除文件。实现真实上传时，保留 multipart 的 `filename = file.name`，补充认证、网络超时与取消支持；返回 true 必须代表业务确认成功，不只是 HTTP 请求发出了。
- 不使用 WorkManager、不另建上传前台服务、不为上传延长录音服务生命周期。后台协程不保证精确定时或抵抗系统休眠/杀进程。
- 不迁移或恢复旧版录音。staging 中遗留文件不自动补关联，实验后可清除 App 数据。

可通过 Android Studio Device Explorer 导出 staging/pending 中的 `.m4a` 试听。不再提供 App 内播放列表。

## 权限和音频限制

系统通话需要 CALL_PHONE、READ_PHONE_STATE、READ_CALL_LOG；录音另外需要 RECORD_AUDIO。SDK 模拟仅需要麦克风。Android 13+ 录音时可申请通知权限，拒绝不阻止操作。

系统录音保留无障碍服务前置检查，不录音和 SDK 模拟不要求开启。无障碍服务本身不等于获得系统双向音频权限。

音源仍为 `MediaRecorder.AudioSource.VOICE_RECOGNITION`。录出有效 m4a 不等于录到了对方声音；需要在目标设备、系统版本、音频路由下实际试听。不要求 Google Play 上架不代表可以绕过 Android 音频和后台运行限制。

## 验证

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

单元测试覆盖通话记录水位/时间窗口、协议命名/发布、重名保护、串行上传、失败重试、取消和重建上传器后重新扫描。上传测试注入假传输函数，不会访问网络。设备测试覆盖真实麦克风采集、重复结束、页面销毁取消后封装及清理，不会拨打电话。设备需解锁并允许测试页面前台运行。

真机手动验证：

- 系统录音：接通、未接、拒接、主动挂断；试听双方声音，检查回调号码/时间/时长。
- 系统不录音：无麦克风授权也能回调记录，无录音通知和新文件。
- 连续呼叫同一号码：不能拿上次记录作为本次结果。
- 记录延迟、缺权限、拨号失败：明确返回错误，不错误回调历史记录。
- SDK 模拟：未知时长留在 staging，零秒/指定时长发布到 pending；核对文件名与 JSON 的 number/date/duration 一致，JSON 不含本地文件路径。
- 查看 Logcat 的 `RecordingUploader`：启动时及每轮间隔扫描；当前未接网络，文件应保留。重启 App 后应再次看到同名待上传文件。
- 回到桌面再返回：请求继续；销毁页面再进入：没有恢复的旧请求或旧回调。
- 录音失败时：系统电话继续等待终态和通话记录，Android 本地诊断单独报告录音错误。
