"""把原版和我们的逐键结果并排 diff 出来。

用法：python diff_keys.py <orig.json> <ours.json> <报告输出路径>
"""

import io
import json
import sys


def load(path):
    raw = io.open(path, encoding="utf-8-sig").read().strip()
    # playwright-cli --raw 打出来的是一层 JSON 字符串
    if raw.startswith('"'):
        raw = json.loads(raw)
    return json.loads(raw)


orig = load(sys.argv[1])
ours = load(sys.argv[2])
out_path = sys.argv[3]

o_keys = {r["key"]: r for r in orig["keys"]}
u_keys = {r["key"]: r for r in ours["keys"]}

lines = []
lines.append("=== 单键插入结果（latex） ===")
same = 0
diff = []
for key in o_keys:
    o, u = o_keys.get(key), u_keys.get(key)
    if u is None:
        lines.append("!! %-14s 我方缺这一项" % key)
        continue
    ok = (o["latex"] == u["latex"]) and (o["afterBack"] == u["afterBack"])
    if ok:
        same += 1
    else:
        diff.append(key)
        lines.append("!! %-14s [%s]" % (key, u["type"]))
        lines.append("     原版 latex = %s" % json.dumps(o["latex"], ensure_ascii=False))
        lines.append("     我方 latex = %s" % json.dumps(u["latex"], ensure_ascii=False))
        lines.append("     原版 左移后 = %s" % json.dumps(o["afterBack"], ensure_ascii=False))
        lines.append("     我方 左移后 = %s" % json.dumps(u["afterBack"], ensure_ascii=False))
lines.append("")
lines.append("逐键：一致 %d / %d，不一致 %d" % (same, len(o_keys), len(diff)))

lines.append("")
lines.append("=== 渲染结构（html）差异 ===")
html_same = 0
for key in o_keys:
    o, u = o_keys[key], u_keys.get(key)
    if u is None:
        continue
    if o.get("html") == u.get("html"):
        html_same += 1
    else:
        lines.append("!! %-14s [%s]" % (key, u["type"]))
        lines.append("     原版 = %s" % o.get("html"))
        lines.append("     我方 = %s" % u.get("html"))
lines.append("")
lines.append("渲染结构：一致 %d / %d" % (html_same, len(o_keys)))

lines.append("")
lines.append("=== 连打序列 ===")
o_seqs = {r["name"]: r for r in orig["seqs"]}
u_seqs = {r["name"]: r for r in ours["seqs"]}
seq_bad = []
for name in o_seqs:
    o, u = o_seqs[name], u_seqs.get(name)
    if u is None:
        continue
    same_latex = o["latex"] == u["latex"]
    same_symja = o["symja"] == u["symja"]
    if not (same_latex and same_symja):
        seq_bad.append(name)
    mark = "  " if (same_latex and same_symja) else "!!"
    lines.append("%s %-18s 原版 %-36s 我方 %s"
                 % (mark, name, json.dumps(o["latex"], ensure_ascii=False),
                    json.dumps(u["latex"], ensure_ascii=False)))
    if not same_symja:
        lines.append("   symja 原版 %s" % json.dumps(o["symja"], ensure_ascii=False))
        lines.append("   symja 我方 %s" % json.dumps(u["symja"], ensure_ascii=False))
lines.append("")
lines.append("连打序列不一致 %d / %d：%s" % (len(seq_bad), len(o_seqs), ", ".join(seq_bad)))

lines.append("")
lines.append("=== 原版 symja()（引擎输入，逐键） ===")
for r in orig["keys"]:
    lines.append("%-14s %-8s latex=%-26s symja=%s"
                 % (r["key"], r["type"], json.dumps(r["latex"], ensure_ascii=False),
                    json.dumps(r["symja"], ensure_ascii=False)))

lines.append("")
lines.append("=== 原版 symja()（连打序列） ===")
for r in orig["seqs"]:
    lines.append("%-18s latex=%-40s symja=%s"
                 % (r["name"], json.dumps(r["latex"], ensure_ascii=False),
                    json.dumps(r["symja"], ensure_ascii=False)))

io.open(out_path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("一致 %d / %d" % (same, len(o_keys)))
print("wrote", out_path)
