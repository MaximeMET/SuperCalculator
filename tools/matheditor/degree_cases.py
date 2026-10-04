"""三角函数自动补度数的用例表（探针 + 期望值）。

这段行为**不在** MathQuill 里，而在原版 `bundle.min.js` 的
`Matharea.filterCommand()`：每次按键先过一道过滤，在 sin/cos/tan 的槽位里
敲数字会自动补 `\\degree`。原版二进制没法当标尺（那段是 React 层的代码，
不在 mathquill.min.js 里），所以这里的期望值是从原版源码逐条读出来、
再在真编辑器页上验证过的——改动 editor.js 的 filterCommand 之后要跑一遍。
"""

# 键名 -> [插入内容, 光标左移次数, 是否 typedText]
KEYS = {
    "sin": [r"\sin{}", 1, False],
    "cos": [r"\cos{}", 1, False],
    "arcsin": [r"\arcsin{}", 1, False],
    "0": ["0", 0, True],
    "1": ["1", 0, True],
    "2": ["2", 0, True],
    "3": ["3", 0, True],
    "5": ["5", 0, True],
    "7": ["7", 0, True],
    ".": [".", 0, True],
    "+": ["+", 0, True],
    "-": ["-", 0, True],
    "*": [r"\times", 0, False],
    "/": [r"\slash", 0, False],
    "x": ["x", 0, True],
    "y": ["y", 0, True],
    "c": ["c", 0, True],
    "pi": [r"\pi", 0, False],
    "degree": [r"\degree", 0, False],
    "dms": [r"\dms{}{}{}", 3, False],
    "lparen": ["(", 0, True],
}

# @ 开头的是光标/删除键，直接走 SuperCalcEditor.keystroke
SEQ_CASES = [
    # 名字, 按键, 期望 latex, 期望 symja
    ("sin5", ["sin", "5"], r"\sin{5^\circ}", "sin(5degree)"),
    ("sin53", ["sin", "5", "3"], r"\sin{53^\circ}", "sin(53degree)"),
    ("sin123", ["sin", "1", "2", "3"], r"\sin{123^\circ}", "sin(123degree)"),
    ("sin5点2", ["sin", "5", ".", "2"], r"\sin{5.2^\circ}", "sin(5.2degree)"),
    ("sin5加3", ["sin", "5", "+", "3"], r"\sin{5^\circ+3^\circ}", "sin(5degree+3degree)"),
    ("sin5减3", ["sin", "5", "-", "3"], r"\sin{5^\circ-3^\circ}", "sin(5degree-3degree)"),
    # 乘除把 ° 摘掉，但接着敲的数字又会补一个新的（原版就是这么一步步来的）
    ("sin5乘3", ["sin", "5", "*", "3"], r"\sin{5\times3^\circ}", "sin(5*3degree)"),
    ("sin5除3", ["sin", "5", "/", "3"], r"\sin{5\slash3^\circ}", "sin(5/3degree)"),
    ("sin5x", ["sin", "5", "x"], r"\sin{5x}", "sin(5x)"),
    ("sin5y", ["sin", "5", "y"], r"\sin{5y}", "sin(5y)"),
    ("sin5pi", ["sin", "5", "pi"], r"\sin{5\pi}", "sin(5pi )"),
    # ° 键自己：左右已有 ° 就不再叠一个
    ("sin5再按度", ["sin", "5", "degree"], r"\sin{5^\circ}", "sin(5degree)"),
    ("度键按两次", ["degree", "degree"], r"^\circ", "degree"),
    # 退格：光标左边空了就把右边那个 ° 一起收拾掉
    ("sin5退格", ["sin", "5", "@Backspace"], r"\sin{^\circ}", "sin(degree)"),
    ("sin5退格两次", ["sin", "5", "@Backspace", "@Backspace"], "", ""),
    ("sin53退格", ["sin", "5", "3", "@Backspace"], r"\sin{5^\circ}", "sin(5degree)"),
    # °′″：光标边的数字收进度槽
    (
        "sin5按度分秒",
        ["sin", "5", "dms"],
        r"\sin{{5}^\circ{0}^\prime{0}^\pprime}",
        "sin(DegreeMinuteSecond(5,0,0))",
    ),
    # 只有 sin/cos/tan 补度数；arcsin、括号里的都不补
    ("cos30", ["cos", "3", "0"], r"\cos{30^\circ}", "cos(30degree)"),
    ("arcsin5", ["arcsin", "5"], r"\arcsin{5}", "arcsin(5)"),
    ("sin括号5", ["sin", "lparen", "5"], r"\sin{\left(5\right)}", "sin((5))"),
    # 光标挪走以后就不在三角函数槽位里，补度数的规则不生效
    ("sin5左移1再打7", ["sin", "5", "@Left", "7"], r"\sin{75^\circ}", "sin(75degree)"),
    ("sin5左移2再打7", ["sin", "5", "@Left", "@Left", "7"], r"7\sin{5^\circ}", "7sin(5degree)"),
]
