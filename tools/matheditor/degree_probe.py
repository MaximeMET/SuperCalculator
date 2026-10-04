"""生成「自动补度数」探针页。

和 editor_probe.py 的区别：那边测的是命令层（直接调 MathQuill 的
write/typedText），这边走真按键那条路 ——
`SuperCalcEditor.writeCommand(code, cursorBack, typed, symbol)`，
才会经过 editor.js 里的 filterCommand。

用法：python degree_probe.py <editor.html> <输出 html>
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import degree_cases  # noqa: E402

STUB = """<script>
window.__SUPERCALC_DEBUG__ = true;
window.Android = {
  autoResult: function () { return ''; },
  onHistoryChanged: function () {},
  onEditorReady: function () { window.__editorReady = true; },
  log: function (msg) { (window.__logs = window.__logs || []).push(msg); }
};
</script>
"""

DRIVER = """<script>
var KEYS = __KEYS__;
var SEQS = __SEQS__;

function pressOne(name) {
  if (name.charAt(0) === '@') {
    window.SuperCalcEditor.keystroke(name.slice(1));
    return;
  }
  var k = KEYS[name];
  if (!k) throw new Error('未知键 ' + name);
  window.SuperCalcEditor.writeCommand(k[0], k[1], k[2], name);
}

var out = [];
for (var i = 0; i < SEQS.length; i++) {
  var rec = { name: SEQS[i][0], steps: SEQS[i][1].join(' ') };
  try {
    window.SuperCalcEditor.clear();
    for (var j = 0; j < SEQS[i][1].length; j++) pressOne(SEQS[i][1][j]);
    rec.latex = window.SuperCalcEditor.getLatex();
    rec.symja = window.SuperCalcEditor.getSymja();
  } catch (e) {
    rec.err = String(e && e.message ? e.message : e);
  }
  out.push(rec);
}
window.__seqs = out;
window.__logs = window.__logs || [];
window.__done = true;
</script>
"""


def main():
    editor_path, out_path = sys.argv[1], sys.argv[2]
    html = open(editor_path, encoding="utf-8").read()
    cases = [[name, steps] for name, steps, _latex, _symja in degree_cases.SEQ_CASES]
    driver = DRIVER.replace("__KEYS__", json.dumps(degree_cases.KEYS)).replace(
        "__SEQS__", json.dumps(cases)
    )
    html = html.replace(
        '<script src="editor.js"></script>',
        STUB + '<script src="editor.js"></script>',
    )
    html = html.replace("</body>", driver + "</body>")
    open(out_path, "w", encoding="utf-8").write(html)
    print("wrote", out_path)


if __name__ == "__main__":
    main()
