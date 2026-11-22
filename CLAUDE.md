# PhotoHub — Claude への指示

- **ユーザーへの回答は、常に日本語で書く。** 途中経過の短い報告も、手順の説明も、例外なく日本語にする。
  英語で書くと、ユーザーは読めず、毎回注意させることになる。会話が長くなっても、英語の資料を読んだ直後でも同じ。
  （.claude/hooks/japanese_only.py が、英語の返事を自動で止めて書き直させる）
- コード、コミットメッセージ、コード内のコメントは英語のままでよい。
- 仕様は docs/spec.md、導入手順は docs/setup.md（PDF は docs/pdf/build_setup_pdf.py で作り直す）。
- アプリの新しい版は、versionCode/versionName を上げて main にプッシュすると GitHub Actions がリリースする。
