"""把基准结果（私有工作区）和本引擎的输出逐条对比，打印一致率与差异。

输入题目清单是公开的（`in.txt`），**期望值不是**：`ref_*.tsv` 是从原版实测
出来的数据，放在私有子模块 `work/corpus/`。没有子模块权限时这个脚本会给出
提示并退出，不影响任何构建。

用法（在仓库根目录，先跑完 `run-diff.ps1` 或等价的引擎批跑）：

    python tools/corpus/compare.py
"""
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
REF_DIR = REPO / "work" / "corpus"
OUR_DIR = REPO / "engine" / "build" / "diagnostic"

REF_AUTO = REF_DIR / "ref_auto.tsv"
REF_METHODS = REF_DIR / "ref_methods.tsv"
REF_DRAW = REF_DIR / "ref_draw.tsv"
OUR_AUTO = OUR_DIR / "engine_auto.tsv"
OUR_METHODS = OUR_DIR / "engine_methods.tsv"
OUR_DRAW = OUR_DIR / "engine_draw.tsv"

# 「绘制图像」不是文本结果：界面会丢掉计算出来的那段文本，只把公式串交给绘图页。
# 它走 load_draw 那条通道单独比对。
DRAW_LABEL = "绘制图像"


def unesc(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            nxt = s[i + 1]
            if nxt == "\\":
                out.append("\\")
                i += 2
                continue
            if nxt == "t":
                out.append("\t")
                i += 2
                continue
            if nxt == "n":
                out.append("\n")
                i += 2
                continue
            if nxt == "r":
                out.append("\r")
                i += 2
                continue
        out.append(s[i])
        i += 1
    return "".join(out)


def load_auto(path):
    rows = {}
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            rows[unesc(parts[0])] = unesc(parts[1])
    return rows


def load_methods(path):
    rows = {}
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) >= 5:
            rows.setdefault(unesc(parts[0]), []).append(
                (unesc(parts[2]), int(parts[3]), unesc(parts[4]))
            )
    return rows


def load_draw(path):
    """绘图通道：表达式 -> (原始公式串, 切开后的函数列表)。"""
    rows = {}
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) >= 3:
            rows[unesc(parts[0])] = (unesc(parts[1]), unesc(parts[2]))
    return rows


def main():
    if not REF_AUTO.exists():
        print("找不到基准数据：%s" % REF_AUTO)
        print("这批期望值来自原版实测，只放在私有子模块里。")
        print("如果是第一次克隆：git submodule update --init --recursive")
        return 2
    if not OUR_AUTO.exists():
        print("找不到本引擎的输出：%s" % OUR_AUTO)
        print("先跑一次 tools/corpus/run-diff.ps1（或 :engine:test 的 CorpusRunnerTest）。")
        return 2

    ref_auto, our_auto = load_auto(REF_AUTO), load_auto(OUR_AUTO)
    ref_m, our_m = load_methods(REF_METHODS), load_methods(OUR_METHODS)
    ref_d, our_d = load_draw(REF_DRAW), load_draw(OUR_DRAW)

    auto_ok = auto_bad = 0
    auto_diffs = []
    for expr, ref in ref_auto.items():
        if expr not in our_auto:
            continue
        if our_auto[expr] == ref:
            auto_ok += 1
        else:
            auto_bad += 1
            auto_diffs.append((expr, ref, our_auto[expr]))

    btn_ok = btn_bad = 0
    btn_diffs = []
    res_ok = res_bad = 0
    res_diffs = []
    for expr, ref_list in ref_m.items():
        ours = our_m.get(expr, [])
        ref_sig = [(l, t) for l, t, _ in ref_list]
        our_sig = [(l, t) for l, t, _ in ours]
        if ref_sig == our_sig:
            btn_ok += 1
        else:
            btn_bad += 1
            btn_diffs.append((expr, ref_sig, our_sig))
        for (rl, rt, rr), (ol, ot, orr) in zip(ref_list, ours):
            if rl != ol or rl == DRAW_LABEL:
                continue
            if rr == orr:
                res_ok += 1
            else:
                res_bad += 1
                res_diffs.append((expr, rl, rr, orr))

    draw_ok = draw_bad = 0
    draw_diffs = []
    for expr, ref in ref_d.items():
        if expr not in our_d:
            continue
        if our_d[expr] == ref:
            draw_ok += 1
        else:
            draw_bad += 1
            draw_diffs.append((expr, ref, our_d[expr]))

    print("=" * 78)
    print(f"自动预览     {auto_ok}/{auto_ok + auto_bad}   一致率 {pct(auto_ok, auto_bad)}")
    print(f"按钮集合     {btn_ok}/{btn_ok + btn_bad}   一致率 {pct(btn_ok, btn_bad)}")
    print(f"按钮计算结果 {res_ok}/{res_ok + res_bad}   一致率 {pct(res_ok, res_bad)}")
    print(f"绘图公式     {draw_ok}/{draw_ok + draw_bad}   一致率 {pct(draw_ok, draw_bad)}")
    print("=" * 78)

    show("自动预览不一致", auto_diffs, 12)
    show("按钮集合不一致", [(e, r, o) for e, r, o in btn_diffs], 12)
    show("结果不一致", [(f"{e} [{l}]", r, o) for e, l, r, o in res_diffs], 15)
    show("绘图公式不一致", [(f"{e}", f"{r}", f"{o}") for e, r, o in draw_diffs], 10)
    return 0


def pct(ok, bad):
    total = ok + bad
    return f"{ok / total * 100:.1f}%" if total else "n/a"


def show(title, rows, limit):
    print(f"\n----- {title}（前 {min(len(rows), limit)} / 共 {len(rows)}）-----")
    for expr, ref, ours in rows[:limit]:
        print(f"  {expr}")
        print(f"    基准: {ref!r}")
        print(f"    本实现: {ours!r}")


if __name__ == "__main__":
    sys.exit(main())
