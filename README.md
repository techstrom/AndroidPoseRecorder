# Android 姿勢レコーダー

Kotlin / CameraX / ONNX Runtime for Android / Ultralytics YOLOv8n-poseを使用するAndroidアプリです。
Android 7.0（API 24）以上、背面カメラを備える実機が必要です。

## 起動

1. Android Studioでこのフォルダーを開きます。
2. JDK 17以上、Android SDK Platform 35を設定してGradle Syncを実行します。
3. 実機でアプリを起動し、カメラ権限を許可します。

コマンドラインでは `./gradlew assembleDebug`（Windowsは `./gradlew.bat assembleDebug`）。
SDKの場所はAndroid Studioで設定するか、git管理外の `local.properties` に
`sdk.dir=C:/Users/yourname/AppData/Local/Android/Sdk` と指定してください。

## 操作

- 起動すると全画面カメラと姿勢の骨格が表示されます。YOLOv8n-poseで人物ごとの17関節を同時に推定します。
- 人物ごとの信頼度しきい値とNMSで重複枠を抑え、複数候補を作ります。姿勢をタップすると選択人物が赤くなり、その人物だけを位置と体幅の近さで追跡・記録します。記録中に別の人をタップすると追跡先を切り替えられます。人が重なって体幅が急変したり見失ったフレームは空の姿勢を記録し、選択解除して再タップを待ちます。
- 下中央の赤丸アイコンで座標の保存を開始します。記録中は赤い四角の停止アイコンに変わり、上部に開始からの経過時間を表示します。
- 同じボタンを押すと停止・保存します。
- 左下のフォルダアイコンで記録一覧を開き、記録を選ぶと「再生」「共有」を選べます。
- 一覧の「動画ファイルから姿勢を記録」で端末上の動画を選択できます。名前とカンマ区切りのタグを入力して解析を開始します。解析画面は最初のフレームで停止し、解析fpsスライダー（5〜30fps、初期値15fps）とシークバーで対象人物と解析間隔を決められます。人物を選ぶと解析を開始し、途中のタップで追跡対象を切り替えます。
- 動画解析前に「解析時に背景の動画を表示」を選ぶと、解析中も元動画のフレームを姿勢の背景に重ねて表示します。再生画面の「背景動画」チェックボックスでも表示を切り替えられます。
- 動画記録には選択した元動画のファイル名とURIを保存します。再生時に同じ動画へアクセスできる場合は姿勢と一緒に表示できます。動画を削除した場合やファイル提供元がアクセスを取り消した場合は、背景を表示できません。
- 動画は選択したfps（5〜30fps）の間隔で、画像の長辺を最大720ピクセルにして端末内で解析します。fpsを上げるほど記録フレーム数が増え、解析に時間がかかります。YOLOv8n-poseで複数人物を一度に推論します。検出人数と追跡状態を表示し、「中止」で破棄できます。対応する動画形式は端末のデコーダーによります。
- 記録のメニューの「名前・タグを編集」で、カメラ記録も動画記録も名前とタグを変更できます。
- 一覧は名前またはタグの部分一致検索、日付の新しい/古い順、名前の昇順/降順に対応します。50件ずつ読み込み、スクロールすると続きを追加します。
- 名前とタグは端末内のSQLiteデータベースで管理します。共有するJSON Linesの先頭のセッション情報にも `name` と `tags` を含めます。内部のデータファイル名は一意なIDのままです。
- 再生画面は黒背景に骨格だけを描画します。上部に経過時間、下部に一時停止/再開と戻るアイコンを表示します。
- 記録の時刻に合わせて再生します。未検出フレームでは骨格を消し、再生終了後は再生アイコンで最初から再生できます。
- バックグラウンド移行時は記録を自動停止します。再開後はもう一度「記録」を押してください。
- 動画・音声は保存しません。推論は端末内で動作します。

## 記録形式

アプリ内部の `files/recordings/pose_<epoch>_<uuid>.jsonl` にUTF-8で保存します。
1行目はセッション情報、以降は処理した各フレームのJSONです。

| フィールド | 内容 |
| --- | --- |
| source_video_name | 動画記録の元動画ファイル名（カメラ記録では出力しません） |
| source_uri | 元動画へのアクセスURI（カメラ記録では出力しません） |
| show_video_on_playback | 再生時に背景動画を初期表示する設定 |
| analysis_fps | 動画解析時に指定したサンプリングfps |
| source_video_start_ms | 選択人物を決めて記録を開始した元動画の位置 |
| selected_pose_index | 保存姿勢のうち選択人物の番号。未検出時は-1 |
| elapsed_ms | 記録開始からの経過時間（ミリ秒） |
| timestamp_monotonic_ms | 端末のuptimeに基づく単調増加時刻 |
| timestamp_video_ms | 動画解析時の動画内時刻（カメラ記録では出力しません） |
| image_width / image_height | 回転・切り取り後の検出画像サイズ |
| poses | 検出した姿勢の配列。未検出フレームは空配列 |
| pose_model | 推論モデル（YOLOv8n-pose ONNX） |
| landmarks[].index | アプリ互換33枠のランドマーク番号（COCO-17関節を対応枠へ格納） |
| x / y | 検出画像の正規化座標 |
| z | YOLO poseでは推定しないため0 |
| visibility / presence | YOLO poseの関節信頼度 |
| world.x / y / z | YOLO poseでは出力しません |

座標は背面カメラの画像を画面と同じ範囲に切り取り、正立方向へ回転したものを基準にします。
左右反転はしません。x/yは画像幅/高さを基準にし、zはメートル単位ではありません。
フレーム間隔は推論速度に応じて変わります。CameraXは古いフレームを破棄するため、
解析時は `elapsed_ms` を時間軸として扱ってください。
書き込みは背景スレッドで逐次行い、30フレームごとおよび停止時にflushします。
記録中・動画解析中のファイルは `.partial` とし、正常終了時に `.jsonl` に確定します。未完了ファイルは一覧に表示しません。
強制終了や電源断では最後の未flushデータが失われることがあります。
アプリを削除すると内部保存データも削除されます。必要な記録は共有して保管してください。

## モデル・参考資料

Ultralytics公式の `yolov8n-pose.pt` から640×640入力、opset 17のONNXモデルをエクスポートして同梱し、ONNX Runtime for AndroidのCPU実行プロバイダーで推論します。出力は`[1, 56, 8400]`で、人物のNMS後にCOCO-17関節を読み取ります。

- [Ultralytics Pose task / export](https://docs.ultralytics.com/tasks/pose/)
- [Ultralytics ONNX export](https://docs.ultralytics.com/integrations/onnx/)
- [ONNX Runtime for Android](https://onnxruntime.ai/docs/get-started/with-java.html)
- [Ultralytics licensing](https://www.ultralytics.com/license)

YOLOv8n-poseの学習済み重みはUltralyticsのライセンス案内上、既定でAGPL-3.0の対象です。非公開または商用の配布を行う場合は、AGPL-3.0の適用条件を確認するかUltralyticsへEnterpriseライセンスを確認してください。ONNX Runtime for AndroidはMaven Centralで配布されるAndroid実行パッケージを使用します。

リアルタイム推論と保存は専用の背景スレッドで順序付けます。
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
6. 動画を選択して解析し、元動画の経過時間で骨格が再生されること。
7. 名前・タグを編集してアプリを再起動しても保持され、検索・ソートに反映されること。

自動テスト: `./gradlew testDebugUnitTest connectedVerificationAndroidTest`。
実機自動テストは通常のアプリとは別ID `jp.example.poserecorder.verification` で実行します。
テストツールによる再インストール・初期化から通常アプリの保存領域を分離します。
実機テストでは専用の一時領域に125件を作り、ページ読み込み・検索・ソート・再編集後の保存を検証します。
