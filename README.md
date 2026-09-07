# 物件レポート（House Manager）

住所を入力すると、国土交通省 不動産情報ライブラリの災害リスク・建築条件・周辺施設・成約価格をまとめて表示する Android アプリです。地図表示と、端末内で動く AI（Gemma 4 E2B）による講評・質問・住所補正に対応しています。

## APK のダウンロード

最新版: **https://github.com/mooman0222/House-Manager/releases/latest/download/house-report.apk**

`main` への push ごとに GitHub Actions が release 署名付き APK をビルドし、[Releases](https://github.com/mooman0222/House-Manager/releases) に `build-<番号>` として公開します。

インストール手順:
1. Android 端末で上のリンクを開いて APK を保存する
2. 「提供元不明のアプリ」の許可を求められたらブラウザに許可する
3. 起動後、設定画面で不動産情報ライブラリの API キーを入力する（[reinfolib.mlit.go.jp](https://www.reinfolib.mlit.go.jp/) で無料申請）
4. AI 機能を使う場合は設定画面からモデル（約2.6GB、Wi-Fi 推奨）をダウンロードする

## 開発

- `android/local.properties` に `MAPS_API_KEY=<Maps SDK for Android のキー>` を追記すると地図タブが表示されます
- release 署名は `android/keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）から読みます。無ければ debug 署名になります
- CI は Secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` / `MAPS_API_KEY` を使います

```sh
cd android && ./gradlew :app:assembleDebug
```
