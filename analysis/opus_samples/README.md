# Opus 片段诊断样本目录

请将同事提供的 **裸 Opus 片段**（如 `seg_000.opus`）放在本目录下，然后运行分析脚本。

## 使用方法

```bash
# 需要 Python 3.8+；可选安装 ffmpeg 以做完整解码验证
python3 analysis/opus_samples/analyze_opus.py analysis/opus_samples/

# 或分析单个文件
python3 analysis/opus_samples/analyze_opus.py analysis/opus_samples/seg_000.opus
```

## 请一并提供（写在同目录 `notes.txt` 即可）

- 出问题片段的文件名 + 是否正常片段的文件名（各至少 1 个）
- 录制环境：手机型号、Android 版本、是否锁屏/后台、BLE 连接是否稳定
- App 日志里是否有 `[reassembler] frame missing chunk` 等字样
- 错误出现的工具（本 App VAD / 你们自己的解码脚本 / ffplay 等）

## 说明

- 本目录下的 `*.opus` 已在 `.gitignore` 中忽略，不会误提交到 Git。
- 分析脚本输出会保存为 `analysis/opus_samples/_reports/<文件名>.txt`
