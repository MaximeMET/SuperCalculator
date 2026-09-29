"""我方探针页：加载真正的 editor.html / editor.js，用同一张命令表跑。

探针挂在真页面上，所以测到的就是将来打进 APK 的那套东西。

用法：python editor_probe.py <editor.html> <输出 html>
"""

import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
import keylist  # noqa: E402

editor_path, out_path = sys.argv[1], sys.argv[2]
html = open(editor_path, encoding="utf-8").read()

STUB = """<script>
window.__SUPERCALC_DEBUG__ = true;
window.Android = {
  autoResult: function (latex) { return ''; },
  onHistoryChanged: function () {},
  onEditorReady: function () { window.__editorReady = true; },
  log: function (msg) { (window.__logs = window.__logs || []).push(msg); }
};
</script>
"""

DRIVER = "<script>\n" + keylist.page_js(True) + "\n</script>\n"

html = html.replace(
    '<script src="editor.js"></script>',
    STUB + '<script src="editor.js"></script>',
)
html = html.replace("</body>", DRIVER + "</body>")
open(out_path, "w", encoding="utf-8").write(html)
print("wrote", out_path)
