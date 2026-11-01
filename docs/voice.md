# 语音回复

系统发送的是 QQ 语音条，不是音频文件。文本回答仍然生成，语音作为同一条回复的附加媒体发送。

## 1. 链路

| 阶段 | 组件 | 行为 |
|---|---|---|
| 触发 | `VoiceTriggerService` | 关键词命中后要求这条回复带语音 |
| 生成 | `ChatService` + `VoiceTool` | 模型调用 `play_voice` 选素材，或调用 `say` 现场合成 |
| 暂存 | `MediaOutbox` | 只记录发送意图，不立即发送 |
| 兜底 | `VoiceReplyPlanner` | 必须语音但模型没安排时，把文字回答的前 80 字合成语音 |
| 加工 | `VoiceSender` | 用 ffmpeg 转成 24 kHz mp3，量时长、限大小 |
| 发送 | `OutboundSender` | 与文字一起排序发送，NapCat 再转 SILK |

不要直接发送 SILK。协议端会跳过转码，客户端可能显示 0 秒。

## 2. 两种来源

| 来源 | 工具 | 适用场景 |
|---|---|---|
| 素材库 | `play_voice` | 点歌、猫叫、音效、固定台词 |
| 现场合成 | `say` | 没有现成素材，但一句话用说的更自然 |

`play_voice` 只接受曲名、别名或标签，不接受文件路径。模型无法借此读取服务器上的任意文件。

## 3. 规则

- 一条回复最多两段语音。
- `say` 最多 80 字，不要用它朗读整段回答。
- 必须语音但模型没有安排时，兜底只合成回答前 80 字。
- 同一句话、同一套 TTS 参数只合成一次，缓存复用。
- 普通问答不主动发语音；只有用户明确想听声音时才调用语音工具。
- 工具预算与检索工具共用，单次请求最多 3 次工具调用。

## 4. 配置

| 键 | 默认 | 作用 |
|---|---|---|
| `app.voice.enabled` | `true` | 总开关；关闭后不碰 ffmpeg 与协议端 |
| `app.voice.max-kb` | `1024` | 加工后音频大小上限 |
| `app.voice.max-seconds` | `300` | 单条语音时长上限 |
| `app.voice.ffmpeg` | `ffmpeg` | ffmpeg 路径或命令名 |
| `app.voice.ffprobe` | `ffprobe` | ffprobe 路径或命令名 |
| `app.voice.timeout-seconds` | `60` | 单次转码超时 |
| `app.voice.dir` | `./data/voices` | 素材与 TTS 缓存目录 |
| `app.voice.tts.enabled` | `true` | 是否允许现场合成 |
| `app.voice.tts.provider` | `edge-tts` | 合成器 |
| `app.voice.tts.edge-tts-path` | `edge-tts` | 可执行文件路径，可由 `EDGE_TTS` 覆盖 |
| `app.voice.tts.voice` | `zh-CN-XiaoxiaoNeural` | 默认声线 |
| `app.voice.tts.rate` | `+0%` | 语速 |
| `app.voice.tts.pitch` | `+0Hz` | 音高 |
| `app.voice.tts.volume` | `+0%` | 音量 |
| `app.voice.tts.max-chars` | `200` | 单次合成字数上限 |
| `app.voice.tts.timeout-seconds` | `30` | 合成超时 |
| `app.voice.tts.cache-dir` | `./data/voices/tts` | 合成缓存目录 |

服务器需要安装 ffmpeg、ffprobe 与 edge-tts。只写命令名时从 `PATH` 查找；Windows 上通常指向虚拟环境里的 `edge-tts.exe`。

## 5. 触发规则

触发词保存在 `voice_trigger`，可在管理后台「语音」面板增删。每条规则包含：

- 关键词
- 冷却秒数
- 生效群范围
- 备注

默认触发词包括“唱首歌”“唱歌”“来一首”“喵”“叫一声”“晚安”。触发只决定要不要语音，不决定说什么；内容仍由模型生成。

## 6. 管理端

「语音」面板提供：

- 上传、试听、改名、改别名与标签、启用、删除素材
- 把素材直接发到测试群
- 维护触发词与冷却
- 查看 edge-tts 可用声线、试听合成、发送合成结果

素材存放在 `voice_asset` 与 `app.voice.dir`，触发规则存放在 `voice_trigger`。上传后立即可用，不需要重启。

## 7. 失败与限制

| 情况 | 行为 |
|---|---|
| TTS 不可用 | 返回文字，不阻塞回答 |
| ffmpeg 失败或超时 | 放弃语音，文字照常发送 |
| 音频超过大小或时长 | 拒绝发送并记录原因 |
| 工具预算用完 | 返回文字，提示模型不要再调用 |
| 素材库没有匹配 | 提示模型改用 `say` 或文字 |

## 8. 版权与隐私

语音是可直接传播的内容，版权与隐私风险高于文字。只上传有授权的音频；商用语音包、影视片段、他人语音条不要进仓库，也不要发到群里。
