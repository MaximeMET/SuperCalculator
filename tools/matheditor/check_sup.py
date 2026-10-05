"""把 sup_probe 的结果和 sup_cases 里的期望值逐条比对。

用法：python check_sup.py <探针 dump 出来的 json>
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sup_cases  # noqa: E402


def load(path):
    raw = open(path, encoding="utf-8-sig").read().strip()
    if raw.startswith('"'):
        raw = json.loads(raw)
    return json.loads(raw)


def main():
    path = sys.argv[1]
    got = {rec["name"]: rec for rec in load(path)}
    bad = 0
    for name, _steps, latex, symja in sup_cases.SEQ_CASES:
        rec = got.get(name)
        if rec is None:
            print("缺失: %s" % name)
            bad += 1
            continue
        if rec.get("err"):
            print("异常 %s: %s" % (name, rec["err"]))
            bad += 1
            continue
        if rec.get("latex") == latex and rec.get("symja") == symja:
            print("OK   %s" % name)
        else:
            bad += 1
            print("FAIL %s" % name)
            print("     期望 latex=%r symja=%r" % (latex, symja))
            print("     实际 latex=%r symja=%r" % (rec.get("latex"), rec.get("symja")))
    print("---")
    print("%d/%d 通过" % (len(sup_cases.SEQ_CASES) - bad, len(sup_cases.SEQ_CASES)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
