# Android 姿勢レコーダー

Kotlin / CameraX / MediaPipe Pose Landmarkerを使用するAndroidアプリです。
Android 7.0（API 24）以上、背面カメラを備える実機が必要です。

## 起動

1. Android Studioでこのフォルダーを開きます。
2. JDK 17以上、Android SDK Platform 35を設定してGradle Syncを実行します。
3. 実機でアプリを起動し、カメラ権限を許可します。

コマンドラインでは `./gradlew assembleDebug`（Windowsは `./gradlew.bat assembleDebug`）。
SDKの場所はAndroid Studioで設定するか、git管理外の `local.properties` に
`sdk.dir=C:/Users/yourname/AppData/Local/Android/Sdk` と指定してください。

## 操作

- 起動すると全画面カメラと姿勢の骨格が表示されます。縦画面・背面カメラ・1人の検出に対応します。
- 下中央の赤丸アイコンで座標の保存を開始します。記録中は赤い四角の停止アイコンに変わり、上部に開始からの経過時間を表示します。
- 同じボタンを押すと停止・保存します。
- 左下のフォルダアイコンで記録一覧を開き、記録を選ぶと「再生」「共有」を選べます。
- 再生画面は黒背景に骨格だけを描画します。上部に経過時間、下部に一時停止/再開と戻るアイコンを表示します。
- 記録の時刻に合わせて再生します。未検出フレームでは骨格を消し、再生終了後は再生アイコンで最初から再生できます。
- バックグラウンド移行時は記録を自動停止します。再開後はもう一度「記録」を押してください。
- 動画・音声は保存しません。推論は端末内で動作します。

## 記録形式

アプリ内部の `files/recordings/pose_<epoch>_<uuid>.jsonl` にUTF-8で保存します。
1行目はセッション情報、以降は処理した各フレームのJSONです。

| フィールド | 内容 |
| --- | --- |
| elapsed_ms | 記録開始からの経過時間（ミリ秒） |
| timestamp_monotonic_ms | 端末のuptimeに基づく単調増加時刻 |
| image_width / image_height | 回転・切り取り後の検出画像サイズ |
| poses | 検出した姿勢の配列。未検出フレームは空配列 |
| landmarks[].index | MediaPipeの33点のランドマーク番号（0–32） |
| x / y / z | 検出画像の正規化座標と相対奥行き |
| visibility / presence | 可視性・存在の信頼度 |
| world.x / y / z | 腰の中央を原点とするメートル単位の3次元座標 |

座標は背面カメラの画像を画面と同じ範囲に切り取り、正立方向へ回転したものを基準にします。
左右反転はしません。x/yは画像幅/高さを基準にし、zはメートル単位ではありません。
フレーム間隔は推論速度に応じて変わります。CameraXは古いフレームを破棄するため、
解析時は `elapsed_ms` を時間軸として扱ってください。
書き込みは背景スレッドで逐次行い、30フレームごとおよび停止時にflushします。
強制終了や電源断では最後の未flushデータが失われることがあります。
アプリを削除すると内部保存データも削除されます。必要な記録は共有して保管してください。

## モデル・参考資料

同梱モデル: Googleの `pose_landmarker_lite` float16 version 1。
モデル取得元:
https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task

- [MediaPipe Androidガイド](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker/android)
- [Pose Landmarker概要・モデル](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)

リアルタイムのカメラ画像を専用の背景スレッドでVIDEOモードに渡します。
これにより推論・保存・開始/停止が同じキューで順序付けられます。
PreviewとImageAnalysisは共通のViewPortを使用します。

## 実機での確認項目

このPCにはAndroid SDK 35、Build Tools 35.0.0、Platform Toolsを
`C:/Users/fujisawa/AppData/Local/Android/Sdk` にインストールしています。
ユーザー環境変数のANDROID_HOMEとJAVA_HOME（JDK 21）も設定済みです。
環境変数の変更は新しく起動したターミナルに反映されます。
2026-10-03に `assembleDebug` の成功を確認しました。
APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。
実機でのカメラ動作は未検証です。

1. 権限許可後に映像が表示され、全身の骨格が人物に重なること。
2. 記録→停止→共有したファイルをJSONとして各行解析できること。
3. 経過時間が単調増加し、人が画面から離れたフレームではposesが空になること。
4. 記録中にホームへ移動すると保存が終了し、復帰後に新しい記録を開始できること。
5. 権限を拒否してもクラッシュせず、設定から許可して復帰できること。
