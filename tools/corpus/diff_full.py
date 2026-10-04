"""把基准和本实现的差异全量列出来，按方法分组，方便逐类定位。

用法（在仓库根目录，先跑完 `run-diff.ps1`）：

    python tools/corpus/diff_full.py > diff.txt
"""
import collections
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
REF_DIR = REPO / "work" / "corpus"
OUR_DIR = REPO / "engine" / "build" / "diagnostic"

REF_M = REF_DIR / "ref_methods.tsv"
OUR_M = OUR_DIR / "engine_methods.tsv"
REF_A = REF_DIR / "ref_auto.tsv"
OUR_A = OUR_DIR / "engine_auto.tsv"

sys.path.insert(0, str(Path(__file__).parent))
from compare import load_auto, load_methods  # noqa: E402


def main():
    ref_m, our_m = load_methods(REF_M), load_methods(OUR_M)
    ref_a, our_a = load_auto(REF_A), load_auto(OUR_A)

    diffs = []
    for expr, ref_list in ref_m.items():
        ours = our_m.get(expr, [])
        by_label = {l: (t, r) for l, t, r in ours}
        for label, _type, ref_res in ref_list:
            if label not in by_label:
                diffs.append((expr, label, ref_res, "<缺失>"))
                continue
            _ot, our_res = by_label[label]
            if our_res != ref_res:
                diffs.append((expr, label, ref_res, our_res))

    auto_diffs = [(e, r, our_a.get(e)) for e, r in ref_a.items() if our_a.get(e) != r]

    print(f"自动预览差异 {len(auto_diffs)} 条；按钮结果差异 {len(diffs)} 条")
    for e, r, o in auto_diffs:
        print(f"  [预览] {e}\n    基准: {r!r}\n    本实现: {o!r}")

    groups = collections.defaultdict(list)
    for d in diffs:
        groups[d[1]].append(d)
    for label, rows in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        print(f"\n=========== {label}（{len(rows)} 条）===========")
        for expr, _l, ref_res, our_res in rows:
            print(f"  {expr}\n    基准: {ref_res!r}\n    本实现: {our_res!r}")


if __name__ == "__main__":
    main()
