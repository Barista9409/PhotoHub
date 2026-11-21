"""Builds docs/PhotoHub導入ガイド.pdf from docs/setup.md (+ docker-compose.yml as an appendix).
Run:  python docs/pdf/build_setup_pdf.py   (needs: markdown, playwright with Chromium)"""
import html
import re
from pathlib import Path

import markdown
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[2]
md = (root / "docs/setup.md").read_text(encoding="utf-8")
md = md.replace("リポジトリの [`docker-compose.yml`](../docker-compose.yml) の中身", "**付録A** の docker-compose.yml")
# Python-Markdown needs 4-space indents for nested lists and a blank line before a list
lines, out = md.split("\n"), []
in_code = False
for ln in lines:
    if ln.strip().startswith("```"):
        in_code = not in_code
    if not in_code:
        m = re.match(r"^( {2,3})(- |\d+\. |```|[^ ])", ln)
        if m and ln.startswith("   ") and not ln.startswith("    "):
            ln = " " + ln
        is_item = re.match(r"^\s*(- |\d+\. )", ln)
        if is_item and out and out[-1].strip() and not re.match(r"^\s*(- |\d+\. )", out[-1]) \
                and not out[-1].startswith("    ") and not out[-1].startswith("|"):
            out.append("")
    out.append(ln)
md = "\n".join(out)
body = markdown.markdown(md, extensions=["tables", "fenced_code", "sane_lists"])
# step headings get a check box to tick off while following along
body = re.sub(r"<h2>(\d+\. )", r'<h2><span class="box"></span>\1', body)
compose = html.escape((root / "docker-compose.yml").read_text(encoding="utf-8"))

checklist = """
<section class="check">
<h2>チェックリスト（終わったら印を付けてください）</h2>
<table>
<tr><th></th><th>やること</th><th>誰が</th></tr>
<tr><td class="b"></td><td>1. NASの準備（タイムゾーン、ユーザーホーム、photohubフォルダとユーザー、uid/gid、パッケージ）</td><td>管理者</td></tr>
<tr><td class="b"></td><td>2. Container Managerで PhotoHub を起動し、/api/health を確認</td><td>管理者</td></tr>
<tr><td class="b"></td><td>3. アプリを入れて Synology のユーザー名でログイン、置き場所を選ぶ</td><td>家族それぞれ</td></tr>
<tr><td class="b"></td><td>4-1. Google Cloud で Drive の利用を許可（1回だけ）</td><td>管理者</td></tr>
<tr><td class="b"></td><td>4-2. アプリでバックアップをオン（NAS・ドライブ）、Googleフォトのバックアップはオフ</td><td>家族それぞれ</td></tr>
<tr><td class="b"></td><td>4-3. Cloud Sync（ドライブ /PhotoHub → inbox/drive/名前）</td><td>管理者</td></tr>
<tr><td class="b"></td><td>4-5. Googleフォトを Takeout で一度だけ取り込む</td><td>家族それぞれ</td></tr>
<tr><td class="b"></td><td>5. 取り込み履歴で件数を確認 → PHOTOHUB_APPLY を "true" に</td><td>管理者</td></tr>
<tr><td class="b"></td><td>6. Tailscale を入れて外出先から確認</td><td>家族それぞれ</td></tr>
<tr><td class="b"></td><td>（Amazonなしの場合）Hyper Backup で USB などへ定期バックアップ</td><td>管理者</td></tr>
</table>
<p class="memo">メモ：uid:gid ＿＿＿＿＿＿＿　NASのIP ＿＿＿＿＿＿＿＿＿　管理者名 ＿＿＿＿＿＿</p>
</section>"""

page = f"""<!doctype html><html lang="ja"><head><meta charset="utf-8"><style>
@page {{ size: A4; margin: 16mm 14mm 18mm 14mm; }}
body {{ font-family: "IPAPGothic","IPAGothic",sans-serif; font-size: 11pt; line-height: 1.65; color: #1b1f24; }}
h1 {{ font-size: 21pt; margin: 0 0 4mm; color: #1e3a5f; border-bottom: 3px solid #3d7bd9; padding-bottom: 2mm; }}
h2 {{ font-size: 15pt; color: #fff; background: #2f64b0; padding: 2mm 3mm; border-radius: 2mm; margin: 8mm 0 3mm;
      break-after: avoid; }}
h3 {{ font-size: 12.5pt; color: #1e3a5f; margin: 5mm 0 2mm; break-after: avoid; }}
p, li {{ break-inside: avoid; }}
ol, ul {{ padding-left: 7mm; }}
li {{ margin: 1.2mm 0; }}
ol > li::marker {{ font-weight: bold; color: #2f64b0; }}
table {{ border-collapse: collapse; width: 100%; margin: 3mm 0; font-size: 10pt; break-inside: avoid; }}
th, td {{ border: 1px solid #b8c4d2; padding: 1.6mm 2mm; vertical-align: top; }}
th {{ background: #e8eef6; }}
code {{ font-family: "IPAGothic",monospace; background: #f0f3f7; padding: 0 1mm; border-radius: 1mm; font-size: 9.5pt; }}
pre {{ background: #f4f6f9; border: 1px solid #d5dce5; border-radius: 2mm; padding: 3mm; font-size: 9pt;
       white-space: pre-wrap; break-inside: avoid; }}
pre code {{ background: none; padding: 0; }}
blockquote {{ border-left: 4px solid #f2b33d; background: #fff8e8; margin: 3mm 0; padding: 2mm 4mm; }}
hr {{ border: none; border-top: 1px dashed #b8c4d2; margin: 6mm 0; }}
strong {{ color: #b3261e; }}
.box {{ display: inline-block; width: 4.2mm; height: 4.2mm; border: 2px solid #fff; border-radius: 1mm; margin-right: 2mm;
        vertical-align: -0.5mm; }}
.check {{ break-after: page; }}
.check td.b {{ width: 7mm; }}
.check td.b::before {{ content: ""; display: inline-block; width: 4.5mm; height: 4.5mm; border: 1.5px solid #2f64b0; border-radius: 1mm; }}
.memo {{ margin-top: 6mm; font-size: 11pt; }}
.appendix pre {{ font-size: 8.6pt; break-inside: auto; }}
a {{ color: #2f64b0; text-decoration: none; }}
</style></head><body>
{checklist}
{body}
<section class="appendix" style="break-before: page">
<h2>付録A　docker-compose.yml（Container Manager に貼り付けるもの）</h2>
<p>手順2で、この内容をそのまま貼り付け、<code>&lt;-- CHANGE</code> の行だけ直します。
スマホでは https://github.com/Barista9409/PhotoHub/blob/main/docker-compose.yml を開き、右上の「Raw」から全部コピーすると確実です。</p>
<pre><code>{compose}</code></pre>
</section>
</body></html>"""

out_html = root / "docs/pdf/setup.html"
out_html.write_text(page, encoding="utf-8")
out_pdf = root / "docs" / "PhotoHub導入ガイド.pdf"
with sync_playwright() as p:
    import os
    exe = os.environ.get("CHROMIUM_PATH") or ("/opt/pw-browsers/chromium" if Path("/opt/pw-browsers/chromium").exists() else None)
    b = p.chromium.launch(executable_path=exe) if exe else p.chromium.launch()
    pg = b.new_page()
    pg.goto(out_html.as_uri())
    pg.pdf(path=str(out_pdf), format="A4", print_background=True, display_header_footer=True,
           header_template="<span></span>",
           footer_template='<div style="font-size:8pt;width:100%;text-align:center;color:#888">写真ハブ 導入ガイド　'
                           '<span class="pageNumber"></span> / <span class="totalPages"></span></div>',
           margin={"top": "16mm", "bottom": "18mm", "left": "14mm", "right": "14mm"})
    b.close()
print(out_pdf)
