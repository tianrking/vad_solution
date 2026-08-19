# Audio Silence Service

独立的 CPU 音频处理服务：上传音频，识别并删除长静音段，返回处理后的 M4A 或 WAV。

首版使用 WebRTC VAD，不依赖 GPU 或神经网络模型。VAD 使用 16 kHz 单声道 PCM 做分析；最终输出使用输入音频的采样率和声道数，避免无必要地降低录音质量。

## 本地运行

需要 Python 3.11+、FFmpeg 和 FFprobe：

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

健康检查：

```powershell
curl http://127.0.0.1:8000/healthz
```

处理音频：

```powershell
curl -X POST http://127.0.0.1:8000/v1/audio/remove-long-silence `
  -F "file=@input.m4a" `
  -F "min_silence_ms=800" `
  -F "keep_silence_ms=250" `
  -F "padding_ms=80" `
  -F "output_format=m4a" `
  --output output.m4a
```

## Docker 部署

```bash
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:8080/healthz
```

Compose 默认只绑定到 `127.0.0.1:8080`，推荐放到 Nginx/Caddy/现有 HTTPS 反向代理后面，不直接暴露公网。

生产环境请在 `.env` 设置一个长随机 `API_KEY`。处理接口支持 `X-API-Key` 或 `Authorization: Bearer <API_KEY>`；`/healthz` 保持公开，方便反向代理或监控检查。

默认资源限制：

```text
CPU：2 cores
内存：2 GB
最大上传：200 MB
最大音频时长：3600 秒
最大并行任务：2
FFmpeg 每个任务：1 thread
```

处理中间 PCM 放在 Docker named volume `audio-work`，不是内存 tmpfs。VPS 至少预留 2 GB 可用磁盘；如果允许 48 kHz 双声道长录音，建议预留 5 GB 以上。任务结束后中间文件会自动清理。

如果 VPS 只有 1 vCPU，将 `MAX_CONCURRENT_JOBS=1`、`FFMPEG_THREADS=1`，并把 Compose 的 `cpus` 改成 `1.0`。如果 VPS 有 4 vCPU，可以先把并行任务提高到 3，再用压测确认。

## 参数

| 参数 | 默认值 | 作用 |
| --- | ---: | --- |
| `min_silence_ms` | 800 | 连续静音达到这个长度才切除 |
| `keep_silence_ms` | 250 | 被切除的长静音两端保留少量自然停顿 |
| `padding_ms` | 80 | 保护语音词头、词尾 |
| `min_speech_ms` | 160 | 忽略过短的噪声或点击声 |
| `aggressiveness` | 2 | WebRTC VAD 激进程度，0 到 3 |
| `frame_ms` | 20 | VAD 帧长度，10、20 或 30 |

## 生产注意事项

- API 目前是同步处理，适合短音频；长音频建议后续增加任务队列和对象存储。
- 上传文件会写入临时目录，响应完成后自动清理。
- 不要把音频内容写入日志。
- 生产环境应在反向代理和应用层增加认证、限流、文件大小限制。
- 不要把用户传入的字符串拼接进 shell 命令；服务内部使用参数数组调用 FFmpeg。
