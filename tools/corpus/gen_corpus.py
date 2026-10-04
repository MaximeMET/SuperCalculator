"""生成送给插桩探针/引擎的语料（`in.txt`）。

`legacy-216.txt` 是最早那批 216 条，保留；其余按类别补到千条量级——
跑一条几乎不要钱，语料越厚，后面改动引擎时越不容易「修好一个、弄坏三个」。

用法（在仓库根目录）：

    python tools/corpus/gen_corpus.py

输出就写在同目录的 `in.txt`，运行时由 `run-diff.ps1` 拷进
`engine/build/corpus/in.txt` 交给 `CorpusRunnerTest`。
"""
from pathlib import Path

HERE = Path(__file__).resolve().parent
LEGACY = HERE / "legacy-216.txt"
OUT = HERE / "in.txt"


def build():
    items = []

    def add(*exprs):
        items.extend(e for e in exprs if e)

    # ---- 四则运算：多位数、负数、小数、括号 ----
    nums = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16, 25, 36, 49, 64, 100, 121, 1000]
    for a in nums:
        for b in nums[:8]:
            add(f"{a}+{b}", f"{a}-{b}", f"{a}*{b}", f"{a}/{b}")
    add(
        "12.5+3.25", "0.1+0.2", "1.5*2", "7.5/2.5", "-3+5", "-3*4", "-(3+4)",
        "2*(3+4)", "(2+3)*(4-1)", "100/3", "1/6", "5/8", "22/7", "355/113",
        "1-2-3", "2^3^2", "-2^2", "(-2)^2", "2^-3", "10^-5", "0.001*1000",
    )

    # ---- 幂、根、阶乘、组合数 ----
    for b in range(2, 11):
        add(f"2^{b}", f"3^{b}", f"{b}^2", f"{b}^3")
    for n in [2, 3, 5, 8, 9, 12, 16, 25, 27, 32, 36, 49, 64, 81, 100, 144, 1000, 10000]:
        add(f"sqrt({n})", f"sqrt({n})+1", f"sqrt({n})*2")
    for n in [3, 4, 5, 6, 7, 8, 9, 10, 12]:
        add(f"{n}!", f"({n}+1)!")
    add("cbrt(8)", "cbrt(27)", "8^(1/3)", "27^(1/3)", "16^(1/2)", "16^(1/4)", "2^(1/2)")

    # ---- 取余、最大公约数、最小公倍数、绝对值 ----
    for a, b in [(12, 18), (7, 3), (100, 30), (17, 5), (48, 36), (9, 6)]:
        add(f"gcd({a},{b})", f"lcm({a},{b})", f"mod({a},{b})")
    for n in [-100, -7, -1, 0, 1, 7, 100]:
        add(f"abs({n})")

    # ---- 三角函数与反三角 ----
    for a in [0, 1, 2, 30, 45, 60, 90, 180, 360]:
        add(f"sin({a})", f"cos({a})", f"tan({a})")
    add(
        "sin(pi)", "sin(pi/2)", "sin(pi/3)", "sin(pi/4)", "sin(pi/6)",
        "cos(pi)", "cos(pi/2)", "cos(pi/3)", "cos(pi/4)", "cos(pi/6)",
        "tan(pi/4)", "tan(pi/3)", "tan(pi/6)",
        "arcsin(0.5)", "arccos(0.5)", "arctan(1)", "arcsin(1)", "arccos(0)",
        "sin(1)", "cos(1)", "tan(1)", "sinh(1)", "cosh(1)", "tanh(1)",
        "cot(1)", "sec(1)", "csc(1)",
    )

    # ---- 对数与指数 ----
    for n in [2, 3, 5, 8, 10, 16, 100, 1000, 1024]:
        add(f"ln({n})", f"log2({n})", f"log10({n})", f"exp({n})")
    add("log(2,8)", "log(3,9)", "ln(e)", "log10(1)", "ln(1)", "exp(0)", "e^2", "e^-1")

    # ---- 常量与精度 ----
    add(
        "pi", "e", "pi^2", "pi/4", "2*pi", "3*pi", "e^pi", "pi^e",
        "999999999999*999999999999", "99999^3", "2^64", "2^100", "1/998001",
        "0.1+0.2==0.3", "1/3+1/6", "1/3*3",
    )

    # ---- 多项式、化简 ----
    add(
        "x", "x^2", "x^3", "x^4", "x^5",
        "x+1", "x-1", "2*x+3", "3*x-9", "x/2", "x^2-1", "x^2+1", "x^2+2*x+1",
        "x^2-5*x+6", "x^2+5*x+6", "x^3-1", "x^3+1", "x^3-8", "x^4-1",
        "1/x", "1/x^2", "1/(x+1)", "(x+1)/(x-1)", "(x^2-1)/(x-1)",
        "(x-1)*(x+1)", "(x+1)^2", "(x+1)^3", "(2*x+1)*(3*x-2)",
        "a*x^2+b*x+c", "a*x+b", "x*y", "x^2*y",
        "sqrt(x)", "sqrt(x^2)", "abs(x)", "1/(x^2+1)",
    )

    # ---- 函数式表达式 ----
    add(
        "sin(x)", "cos(x)", "tan(x)", "cot(x)", "sec(x)", "csc(x)",
        "arcsin(x)", "arccos(x)", "arctan(x)",
        "sinh(x)", "cosh(x)", "tanh(x)",
        "exp(x)", "ln(x)", "log2(x)", "log10(x)", "log(x)",
        "sin(x)*cos(x)", "sin(x)/x", "x*sin(x)", "x^2*sin(x)", "exp(x)/x",
        "exp(x)*x", "ln(x)/x", "1/sin(x)", "1/(1+x^2)", "sqrt(1+x^2)",
        "e^(-x)", "e^(x^2)", "sin(x^2)", "sin(x)^2", "cos(x)^2",
    )

    # ---- 方程 ----
    add(
        "x==1", "x+1==0", "2*x+3==7", "3*x-9==0", "5*x==1", "x/2==3",
        "x^2==1", "x^2==4", "x^2==2", "x^2==-1", "x^2==0",
        "x^2-1==0", "x^2+2*x+1==0", "x^2-5*x+6==0", "x^2+5*x+6==0",
        "x^3==8", "x^3==1", "x^3==-8", "x^4==16", "x^4==1",
        "2*x^2+3*x-5==0", "x^2-a==0", "a*x+b==0",
        "e^x==1", "ln(x)==1", "sin(x)==0",
    )

    # ---- 不等式 ----
    add(
        "x>3", "x<3", "x>=3", "x<=3", "2*x+1<=7", "3*x-2>4",
        "x^2>4", "x^2<9", "x^2>=1", "x^2<=16",
        "x^2+2*x+1>0", "x^2-1<0", "1/x>1", "abs(x)>1",
    )

    # ---- 边界与错误 ----
    add(
        "1/0", "0/0", "sqrt(-1)", "ln(0)", "log2(0)", "0^0", "0^-1",
        "foo(x)", "sin()", "(((", "1+", "*", "x==", "sin(x",
        "unknownfunc(2,3)", "123abc",
    )

    # ---- 特殊写法 ----
    add(
        "10%", "50%*200", "25%+25%", "1e3", "1.5e-3", "2.5E6",
        "|−3|", "3!", "nCr(5,2)", "nPr(5,2)",
    )

    return items


def main():
    old = [l.strip() for l in LEGACY.read_text(encoding="utf-8").splitlines() if l.strip()]
    new = build()
    seen, merged = set(), []
    for e in old + new:
        e = e.strip()
        if e and e not in seen:
            seen.add(e)
            merged.append(e)
    OUT.write_text("\n".join(merged) + "\n", encoding="utf-8")
    print(f"旧 {len(old)} 条 + 新增 {len(new)} 条 -> 去重后 {len(merged)} 条")
    print(f"写出：{OUT}")


if __name__ == "__main__":
    main()
