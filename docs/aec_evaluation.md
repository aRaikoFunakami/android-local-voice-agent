# AEC 評価手順（Phase 3）

## 評価用ダンプ（4 系統）

debug ビルド + 明示 opt-in（`--ez dump true`）時のみ生成（開発計画 §18）:

| ファイル | 内容 | 生成箇所 |
|---|---|---|
| `render_reference.pcm` | AEC 参照 = 実再生 PCM | RenderPipeline |
| `raw_capture.pcm` | APM 前の capture | CapturePipeline |
| `aec_output.pcm` | APM 後の clean PCM | CapturePipeline |
| STT 入力 | = `aec_output`（同一。STT 直前の 16k 変換は認識器内部） | - |

## エミュレータでの配管評価（合成 echo）

エミュレータにはスピーカー→マイクの音響経路がないため、合成 echo 注入で
AEC 配管の健全性と ERLE を机上測定する:

```bash
adb shell am start -n com.example.localvoiceagent/.MainActivity \
  --ez dump true --ez tone true --ez render true --ez capture true \
  --ez echosim true --ei echodelay 20 --ef echogain 0.5 --ei delay 20
# 60 秒ほど回して停止後:
adb pull /storage/emulated/0/Android/data/com.example.localvoiceagent/files/raw_capture.pcm .
adb pull /storage/emulated/0/Android/data/com.example.localvoiceagent/files/aec_output.pcm .
python3 scripts/compute_erle.py raw_capture.pcm aec_output.pcm
```

## 実機評価（本評価、要物理端末）

エミュレータでは音響性能は評価できない。実機では `echosim` を使わず、
実スピーカー/マイクで以下を採取する（開発計画 §17 のマトリクス）:

1. ケース: far-end only（TTS/トーンのみ）/ near-end only（人の発話のみ）/
   double-talk / silence
2. 各ケース × スピーカー音量 25/50/75/100% × 端末を机上・手持ち × 静音・背景音あり
3. `--ei delay` を 20/40/60/80/100/150/200ms でスイープし、AEC3 内部推定の
   収束（logcat の `Delay changed to`）と ERLE を記録
4. Bluetooth ヘッドセットは使用しない（まず本体経路を固定、§17）
5. 合格閾値は実測分布を見て決定する（§16 Phase 3）

判定は耳ではなく `compute_erle.py` の数値 + STT 認識結果で行う。

## 既知の制約

- 実機の Audio HAL が強制前処理（AEC/NS）を行う場合は二重 AEC になる。
  `VOICE_RECOGNITION` と `UNPROCESSED` ソースを比較し、端末依存仕様として記録すること（§8）。
