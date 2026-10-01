#!/usr/bin/env python3
"""生成键盘图标的矢量 drawable（app/src/main/res/drawable/ic_keyboard_*.xml）。

来源说明：

- 字形：Noto Sans SC Light（SIL OFL-1.1），用 uharfbuzz 排版、fontTools 取轮廓，
  只把轮廓写进 drawable，不随仓库分发字体文件本身。字体按需从 noto-cjk 上游下载
  （URL 与 SHA-256 见 FONT_URL / FONT_SHA256）。
- 几何（工具行垃圾桶/回车/箭头/退格、书签圆底、占位方块）：本项目自绘。
- 画布尺寸和占位方块位置沿用键盘的版式规格（和原参考实现一致），改布局时同步改
  这里的 ICONS 表。

用法：

    python tools/make_keyboard_icons.py                  # 直接写进 app/src/main/res/drawable
    python tools/make_keyboard_icons.py --out 临时目录    # 先看效果

依赖：fonttools、uharfbuzz（pip install fonttools uharfbuzz）。
"""

import argparse
import hashlib
import os
import re
import sys
import urllib.request

import uharfbuzz as hb
from fontTools.misc.transform import Transform
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_URL = "https://github.com/notofonts/noto-cjk/raw/main/Sans/SubsetOTF/SC/NotoSansSC-Light.otf"
FONT_SHA256 = "35CCA31CEA56B2720C096EFAEA2CFDFFDF1B523BF5DE0A80552D16EDCCFA9C70"
FONT_PATH = os.path.join(REPO, "tools", ".cache", "NotoSansSC-Light.otf")

# 颜色（沿用现有配色）
KEY = "#FF505050"       # 功能键字形
TOOL = "#FF53595E"      # 工具行
FORMULA = "#FF515B64"   # 「函数」页公式
SLOT = "#FFEAEAEA"      # 模板占位方块
BOOK = "#FFE4E4E4"      # 书签圆底
WHITE = "#FFFFFFFF"     # 书签按下态字形


# --------------------------------------------------------------------------
# 路径工具：解析 / 变换 / 序列化（只处理 SVGPathPen 会输出的绝对指令）
# --------------------------------------------------------------------------

CMD_ARGS = {"M": 2, "L": 2, "H": 1, "V": 1, "Q": 4, "C": 6, "Z": 0}


def parse_path(d):
    toks = re.findall(r"[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", d)
    out, i, cmd = [], 0, None
    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t.upper()
            i += 1
            if cmd == "Z":
                out.append(("Z", []))
                cmd = None
                continue
        if cmd is None:
            raise ValueError("pathData 里命令缺位：" + d[:60])
        n = CMD_ARGS[cmd]
        out.append((cmd, [float(v) for v in toks[i:i + n]]))
        i += n
        if cmd == "M":
            cmd = "L"  # M 后面跟多组坐标 = 隐式连线
    return out


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def serialize(cmds):
    return "".join(c + " ".join(fmt(p) for p in pts) for c, pts in cmds)


def transform_cmds(cmds, s, dx, dy):
    out = []
    for c, args in cmds:
        if c == "H":
            pts = [args[0] * s + dx]
        elif c == "V":
            pts = [args[0] * s + dy]
        else:
            pts = [args[j] * s + dx if j % 2 == 0 else args[j] * s + dy
                   for j in range(len(args))]
        out.append((c, pts))
    return out


def transform_group(group, s, dx, dy):
    """group 是一段扁平 cmds（一次排版结果）。"""
    return transform_cmds(group, s, dx, dy)


def polylines(cmds, steps=16):
    polys, cur, pos, start = [], [], (0.0, 0.0), (0.0, 0.0)
    for c, args in cmds:
        if c == "M":
            if cur:
                polys.append(cur)
            cur = [(args[0], args[1])]
            pos = start = (args[0], args[1])
        elif c == "L":
            cur.append((args[0], args[1]))
            pos = (args[0], args[1])
        elif c == "H":
            cur.append((args[0], pos[1]))
            pos = (args[0], pos[1])
        elif c == "V":
            cur.append((pos[0], args[0]))
            pos = (pos[0], args[0])
        elif c == "Q":
            x1, y1, x2, y2 = args
            for k in range(1, steps + 1):
                t = k / steps
                a, b, cc = (1 - t) ** 2, 2 * (1 - t) * t, t ** 2
                cur.append((a * pos[0] + b * x1 + cc * x2, a * pos[1] + b * y1 + cc * y2))
            pos = (x2, y2)
        elif c == "C":
            x1, y1, x2, y2, x3, y3 = args
            for k in range(1, steps + 1):
                t = k / steps
                a, b, cc, e = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t ** 2, t ** 3
                cur.append((
                    a * pos[0] + b * x1 + cc * x2 + e * x3,
                    a * pos[1] + b * y1 + cc * y2 + e * y3,
                ))
            pos = (x3, y3)
        elif c == "Z":
            if cur:
                cur.append(start)
                polys.append(cur)
                cur = []
            pos = start
    if cur:
        polys.append(cur)
    return polys


def cmds_bbox(cmds):
    xs, ys = [], []
    for poly in polylines(cmds):
        for x, y in poly:
            xs.append(x)
            ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


# --------------------------------------------------------------------------
# 排版：文字 -> 轮廓
# --------------------------------------------------------------------------


class Shaper:
    def __init__(self, path):
        self.font = TTFont(path, fontNumber=0)
        self.upem = self.font["head"].unitsPerEm
        self.glyphset = self.font.getGlyphSet()
        self.order = self.font.getGlyphOrder()
        blob = hb.Blob.from_file_path(path)
        self.hbfont = hb.Font(hb.Face(blob))
        self.hbfont.scale = (self.upem, self.upem)

    def run(self, text, size, x=0.0, y=0.0, tracking=0.0):
        """一段文字，基线在 (x,y)，返回 (cmds, advance)。"""
        buf = hb.Buffer()
        buf.add_str(text)
        buf.guess_segment_properties()
        hb.shape(self.hbfont, buf)
        scale = size / self.upem
        penx, out = 0.0, []
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            gname = self.order[info.codepoint]
            spen = SVGPathPen(self.glyphset)
            tpen = TransformPen(
                spen,
                Transform(scale, 0, 0, -scale,
                          x + penx + pos.x_offset * scale,
                          y - pos.y_offset * scale),
            )
            self.glyphset[gname].draw(tpen)
            cmds = parse_path(spen.getCommands())
            if cmds:
                out.extend(cmds)
            penx += pos.x_advance * scale + tracking
        return out, penx


# 节点：("t", text) / ("sub", text) / ("sup", text) / ("seq", [节点...]) / ("frac", 上, 下)
#       / ("grid", [[左上, 右上], [左下, 右下]])

SUB_SCALE = 0.62
SUB_DROP = 0.16
SUP_RISE = 0.46
FRAC_SCALE = 0.74


def node_layout(sha, node, size):
    """把节点排版到“基线 y=0、起点 x=0”的坐标系，返回 (cmds 列表, advance, bbox)。"""
    kind = node[0]
    if kind == "t":
        tracking = (node[2] if len(node) > 2 else 0.0) * size
        cmds, adv = sha.run(node[1], size, tracking=tracking)
        return cmds, adv, None
    if kind in ("sub", "sup"):
        small = size * SUB_SCALE
        dy = size * (SUB_DROP if kind == "sub" else -SUP_RISE)
        cmds, adv = sha.run(node[1], small, y=dy)
        return cmds, adv, None
    if kind == "seq":
        all_cmds, x = [], 0.0
        for part in node[1]:
            cmds, adv, _ = node_layout(sha, part, size)
            all_cmds += transform_group(cmds, 1, x, 0)
            x += adv
        return all_cmds, x, None
    if kind == "frac":
        small = size * FRAC_SCALE
        num, den = node[1], node[2]
        num_cmds, _, _ = node_layout(sha, num, small)
        den_cmds, _, _ = node_layout(sha, den, small)
        nb = union_bbox(num_cmds)
        db = union_bbox(den_cmds)
        w = max(nb[2] - nb[0], db[2] - db[0])
        bar_y = -size * 0.30
        gap = size * 0.10
        # 分子贴在横线上方，分母贴在下方，整体水平居中
        num_dx = (w - (nb[2] - nb[0])) / 2 - nb[0]
        den_dx = (w - (db[2] - db[0])) / 2 - db[0]
        num_dy = (bar_y - gap) - nb[3]
        den_dy = (bar_y + gap) - db[1]
        cmds = transform_group(num_cmds, 1, num_dx, num_dy)
        cmds += transform_group(den_cmds, 1, den_dx, den_dy)
        cmds += [("M", [0.0, bar_y]), ("L", [w, bar_y])]
        return cmds, w, None
    if kind == "grid":
        rows = node[1]
        col_gap = size * 0.55
        row_gap = size * 0.62
        col_w = [max(sha.run(rows[r][c], size)[1] for r in range(len(rows))) for c in range(len(rows[0]))]
        cmds = []
        y = 0.0
        for r, row in enumerate(rows):
            x = 0.0
            for c, text in enumerate(row):
                part, adv = sha.run(text, size, x=x, y=y)
                cmds += part
                x += col_w[c] + col_gap
            y += size + row_gap
        return cmds, sum(col_w) + col_gap * (len(col_w) - 1), None
    raise ValueError(kind)


def union_bbox(cmds, box=None):
    xs, ys = [], []
    for poly in polylines(cmds):
        for x, y in poly:
            xs.append(x)
            ys.append(y)
    if not xs:
        return box or (0.0, 0.0, 0.0, 0.0)
    b = (min(xs), min(ys), max(xs), max(ys))
    if box is None:
        return b
    return (min(box[0], b[0]), min(box[1], b[1]), max(box[2], b[2]), max(box[3], b[3]))


def fit_to_box(cmds, box, mode="height"):
    """把一组路径缩放到 box。mode=height 时按高度定尺寸（宽度可以超出，画布会跟着长）。"""
    b = union_bbox(cmds)
    bw, bh = max(b[2] - b[0], 1e-6), max(b[3] - b[1], 1e-6)
    tw, th = box[2] - box[0], box[3] - box[1]
    s = th / bh if mode == "height" else min(tw / bw, th / bh)
    cx = box[0] + tw / 2 - (b[0] + (b[2] - b[0]) / 2) * s
    cy = box[1] + th / 2 - (b[1] + (b[3] - b[1]) / 2) * s
    return transform_group(cmds, s, cx, cy)


def rect_cmds(x, y, w, h):
    return [("M", [x, y]), ("L", [x + w, y]), ("L", [x + w, y + h]), ("L", [x, y + h]), ("Z", [])]


def circle_cmds(cx, cy, r):
    return [
        ("M", [cx, cy - r]),
        ("C", [cx + r * 0.552, cy - r, cx + r, cy - r * 0.552, cx + r, cy]),
        ("C", [cx + r, cy + r * 0.552, cx + r * 0.552, cy + r, cx, cy + r]),
        ("C", [cx - r * 0.552, cy + r, cx - r, cy + r * 0.552, cx - r, cy]),
        ("C", [cx - r, cy - r * 0.552, cx - r * 0.552, cy - r, cx, cy - r]),
        ("Z", []),
    ]


def rrect_cmds(x, y, w, h, r):
    k = r * 0.552
    return [
        ("M", [x + r, y]),
        ("L", [x + w - r, y]),
        ("C", [x + w - r + k, y, x + w, y + r - k, x + w, y + r]),
        ("L", [x + w, y + h - r]),
        ("C", [x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h]),
        ("L", [x + r, y + h]),
        ("C", [x + r - k, y + h, x, y + h - r + k, x, y + h - r]),
        ("L", [x, y + r]),
        ("C", [x, y + r - k, x + r - k, y, x + r, y]),
        ("Z", []),
    ]


def poly_cmds(points):
    cmds = [("M", list(points[0]))]
    cmds += [("L", list(p)) for p in points[1:]]
    cmds.append(("Z", []))
    return cmds


# --------------------------------------------------------------------------
# 图标规格
# --------------------------------------------------------------------------

T = lambda s, tr=0.0: ("t", s, tr)          # noqa: E731
SUB = lambda b: ("sub", b)                  # noqa: E731
SUP = lambda b: ("sup", b)                  # noqa: E731
SEQ = lambda *parts: ("seq", list(parts))   # noqa: E731
FRAC = lambda n, d: ("frac", T(n), T(d))    # noqa: E731


def G(text, box, color=KEY):
    """一个文字元素：(节点, 目标框, 颜色)。"""
    return (text, box, color)


# 每个图标：canvas=画布（像素），slots=占位方块 (x,y,w,h)，glyph=文字元素，paths=自绘几何
ICONS = {
    # ---- 第 1 页：数字、字母、基础运算 ----
    "0": dict(canvas=(23, 35), glyph=[G(T("0"), (0, 0, 22, 34))]),
    "1": dict(canvas=(12, 34), glyph=[G(T("1"), (1, 0, 11, 34))]),
    "2": dict(canvas=(21, 34), glyph=[G(T("2"), (0, 0, 21, 34))]),
    "3": dict(canvas=(21, 35), glyph=[G(T("3"), (0, 0, 21, 34))]),
    "4": dict(canvas=(25, 34), glyph=[G(T("4"), (0, 0, 25, 34))]),
    "5": dict(canvas=(20, 35), glyph=[G(T("5"), (0, 0, 20, 34))]),
    "6": dict(canvas=(23, 35), glyph=[G(T("6"), (0, 0, 23, 34))]),
    "7": dict(canvas=(22, 34), glyph=[G(T("7"), (1, 0, 21, 34))]),
    "8": dict(canvas=(23, 35), glyph=[G(T("8"), (0, 0, 23, 34))]),
    "9": dict(canvas=(23, 35), glyph=[G(T("9"), (0, 0, 23, 34))]),
    "a": dict(canvas=(20, 27), glyph=[G(T("a"), (0, 0, 20, 27))]),
    "b": dict(canvas=(22, 37), glyph=[G(T("b"), (0, 0, 22, 37))]),
    "c": dict(canvas=(19, 27), glyph=[G(T("c"), (0, 0, 19, 27))]),
    "e": dict(canvas=(100, 80), glyph=[G(T("e"), (35, 23, 59, 54))]),
    "h": dict(canvas=(20, 36), glyph=[G(T("h"), (0, 0, 20, 36))]),
    "i": dict(canvas=(14, 36), glyph=[G(T("i"), (0, 0, 14, 36))]),
    "k": dict(canvas=(20, 36), glyph=[G(T("k"), (0, 0, 20, 36))]),
    "p": dict(canvas=(22, 37), glyph=[G(T("p"), (0, 0, 22, 37))]),
    "x": dict(canvas=(26, 27), glyph=[G(T("x"), (0, 0, 26, 27))]),
    "y": dict(canvas=(24, 35), glyph=[G(T("y"), (0, 0, 24, 35))]),
    "z": dict(canvas=(21, 26), glyph=[G(T("z"), (0, 0, 21, 26))]),
    "plus": dict(canvas=(30, 30), glyph=[G(T("+"), (0, 1, 30, 30))]),
    "minus": dict(canvas=(30, 3), glyph=[G(T("−"), (0, 0, 30, 3))]),
    "multiply": dict(canvas=(26, 26), glyph=[G(T("×"), (0, 0, 26, 26))]),
    "divide": dict(canvas=(28, 28), glyph=[G(T("÷"), (0, 0, 28, 28))]),
    "equal": dict(canvas=(30, 15), glyph=[G(T("="), (0, 0, 30, 15))]),
    "dot": dict(canvas=(8, 46), glyph=[G(T("."), (1, 39, 7, 45))]),
    "left_paren": dict(canvas=(11, 42), glyph=[G(T("("), (0, 0, 11, 42))]),
    "right_paren": dict(canvas=(11, 42), glyph=[G(T(")"), (0, 0, 11, 42))]),
    "less": dict(canvas=(26, 27), glyph=[G(T("<"), (0, 0, 26, 27))]),
    "le": dict(canvas=(27, 29), glyph=[G(T("≤"), (0, 0, 27, 28))]),
    "greater": dict(canvas=(26, 27), glyph=[G(T(">"), (0, 0, 26, 27))]),
    "ge": dict(canvas=(27, 29), glyph=[G(T("≥"), (0, 0, 27, 28))]),
    "inf": dict(canvas=(47, 22), glyph=[G(T("∞"), (0, 0, 47, 22))]),
    "pi": dict(canvas=(31, 30), glyph=[G(T("π"), (0, 0, 31, 30))]),
    "degree": dict(canvas=(46, 46), glyph=[G(T("°"), (32, 0, 45, 14))]),
    "dms": dict(canvas=(66, 13),
                glyph=[G(SEQ(T("°", 0.10), T("′", 0.10), T("″")), (0, 0, 66, 15))]),
    "factorial": dict(canvas=(42, 36), slots=[(0, 1, 26, 32)],
                      glyph=[G(T("!"), (36, 0, 41, 37))]),
    "frac": dict(canvas=(42, 61), slots=[(11, 0, 22, 24), (11, 37, 22, 24)],
                 paths=[(rect_cmds(0, 29, 42, 3), KEY)]),
    "sqrt": dict(canvas=(53, 56), slots=[(2, 0, 14, 14), (25, 18, 24, 36)],
                 glyph=[G(T("√"), (0, 9, 53, 55))]),
    "int": dict(canvas=(42, 53), slots=[(24, 4, 18, 20), (24, 27, 18, 20)],
                glyph=[G(T("∫"), (0, 0, 16, 53))]),
    "log": dict(canvas=(85, 38), slots=[(49, 14, 12, 14), (65, 3, 20, 25)],
                glyph=[G(T("log"), (0, 1, 44, 38))]),
    "log2": dict(canvas=(85, 38), slots=[(65, 4, 20, 25)],
                 glyph=[G(SEQ(T("log"), SUB("2")), (0, 1, 58, 38))]),
    "log10": dict(canvas=(87, 38), slots=[(67, 4, 20, 25)],
                  glyph=[G(SEQ(T("log"), SUB("10")), (0, 1, 64, 38))]),
    "ln": dict(canvas=(55, 36), slots=[(35, 10, 20, 25)],
               glyph=[G(T("ln"), (0, 0, 30, 34))]),
    "abs": dict(canvas=(46, 40), slots=[(11, 6, 24, 28)],
                paths=[(rrect_cmds(0.5, 2, 3, 36, 1.5), KEY),
                       (rrect_cmds(42.5, 2, 3, 36, 1.5), KEY)]),
    # ---- 第 2 页：函数与高级运算 ----
    "sin": dict(canvas=(47, 33), glyph=[G(T("sin"), (0, 1, 47, 32))]),
    "cos": dict(canvas=(59, 24), glyph=[G(T("cos"), (0, 0, 59, 24))]),
    "tan": dict(canvas=(58, 30), glyph=[G(T("tan"), (0, 0, 58, 30))]),
    "arcsin": dict(canvas=(80, 26), glyph=[G(T("arcsin"), (0, 1, 79, 28))]),
    "arccos": dict(canvas=(89, 19), glyph=[G(T("arccos"), (0, 0, 89, 21))]),
    "arctan": dict(canvas=(87, 24), glyph=[G(T("arctan"), (0, 1, 86, 26))]),
    "gcd": dict(canvas=(59, 28), glyph=[G(T("公约"), (1, 0, 58, 29))]),
    "lcm": dict(canvas=(59, 29), glyph=[G(T("公倍"), (1, 0, 58, 29))]),
    "lim": dict(canvas=(53, 51), slots=[(0, 35, 14, 16), (39, 35, 14, 16)],
                glyph=[G(T("lim"), (4, 0, 50, 34)), G(T("→"), (14, 35, 39, 51))]),
    "ap": dict(canvas=(52, 43), slots=[(34, 0, 18, 20), (34, 23, 18, 20)],
               glyph=[G(T("A"), (1, 2, 27, 35))]),
    "cp": dict(canvas=(47, 43), slots=[(29, 0, 18, 20), (29, 23, 18, 20)],
               glyph=[G(T("C"), (1, 2, 25, 36))]),
    "exp": dict(canvas=(44, 46), slots=[(0, 14, 28, 32), (30, 0, 14, 14)]),
    # ---- 第 4 页：函数模板 ----
    "f_linear": dict(canvas=(134, 35), glyph=[G(T("y = kx+b"), (0, 0, 134, 35), FORMULA)]),
    "f_inverse": dict(canvas=(91, 59), glyph=[G(SEQ(T("y = "), FRAC("k", "x")), (0, 0, 91, 59), FORMULA)]),
    "f_normalquadratic": dict(canvas=(197, 42),
                              glyph=[G(T("y = ax²+bx+c"), (0, 1, 197, 42), FORMULA)]),
    "f_quadratic": dict(canvas=(198, 45),
                        glyph=[G(T("y = a(x−h)²+k"), (0, 1, 198, 45), FORMULA)]),
    "f_exp": dict(canvas=(86, 36), glyph=[G(SEQ(T("y = a"), SUP("x")), (0, 0, 86, 36), FORMULA)]),
    "f_log": dict(canvas=(139, 35),
                  glyph=[G(SEQ(T("y = log"), SUB("a"), T("x")), (0, 0, 139, 35), FORMULA)]),
    "f_std_cir": dict(canvas=(213, 44),
                      glyph=[G(T("(x−a)²+(y−b)²=r²"), (0, 0, 213, 44), FORMULA)]),
    "f_circle": dict(canvas=(264, 40),
                     glyph=[G(T("x²+y²+ax+by+c=0"), (0, 0, 264, 40), FORMULA)]),
    "f_std_ell": dict(canvas=(139, 72),
                      glyph=[G(SEQ(FRAC("x²", "a²"), T(" + "), FRAC("y²", "b²"), T(" = 1")),
                                (0, 0, 139, 72), FORMULA)]),
    "f_ellipse": dict(canvas=(230, 67),
                      glyph=[G(SEQ(FRAC("(x−k)²", "a²"), T(" + "), FRAC("(y−h)²", "b²"), T(" = 1")),
                                (0, 0, 230, 67), FORMULA)]),
    "f_std_hyper": dict(canvas=(139, 72),
                        glyph=[G(SEQ(FRAC("x²", "a²"), T(" − "), FRAC("y²", "b²"), T(" = 1")),
                                  (0, 0, 139, 72), FORMULA)]),
    "f_hyperbola": dict(canvas=(230, 67),
                        glyph=[G(SEQ(FRAC("(x−k)²", "a²"), T(" − "), FRAC("(y−h)²", "b²"), T(" = 1")),
                                  (0, 0, 230, 67), FORMULA)]),
    "f_parabola": dict(canvas=(113, 38), glyph=[G(T("y²=2px"), (0, 1, 113, 38), FORMULA)]),
}


# 工具行 5 个：自绘几何
def tool_icons():
    trash = [
        (rrect_cmds(10.8, 0.6, 8.4, 3.4, 1.2), TOOL),
        (rrect_cmds(1.0, 5.0, 28.0, 3.6, 1.6), TOOL),
        (poly_cmds([(4.8, 9.4), (25.2, 9.4), (23.4, 27.8),
                    (22.2, 30.4), (7.8, 30.4), (6.6, 27.8)]), TOOL),
    ]
    newline = [
        ([("M", [45.2, 0.0]), ("L", [45.2, 16.4]), ("Q", [45.2, 19.4, 42.2, 19.4]), ("L", [13.6, 19.4])],
         TOOL, 6.4, "butt", "miter"),
        (poly_cmds([(1.2, 19.4), (13.8, 9.6), (13.8, 29.2)]), TOOL),
    ]
    left = [(poly_cmds([(19.0, 1.0), (19.0, 25.0), (1.0, 13.0)]), TOOL)]
    right = [(poly_cmds([(1.0, 1.0), (1.0, 25.0), (19.0, 13.0)]), TOOL)]
    backspace = [
        ([("M", [13.0, 1.4]), ("L", [33.2, 1.4]),
          ("Q", [36.6, 1.4, 36.6, 4.8]), ("L", [36.6, 21.2]),
          ("Q", [36.6, 24.6, 33.2, 24.6]), ("L", [13.0, 24.6]),
          ("L", [1.6, 13.0]), ("Z", [])], TOOL),
    ]
    backspace_x = [
        ([("M", [15.4, 8.4]), ("L", [24.6, 17.6]), ("M", [24.6, 8.4]), ("L", [15.4, 17.6])], WHITE, 2.8),
    ]
    return {
        "clear": dict(canvas=(30, 31), stroked=[], paths=trash),
        "newline": dict(canvas=(50, 30), paths=newline),
        "left": dict(canvas=(20, 26), paths=left),
        "right": dict(canvas=(20, 26), paths=right),
        "backspace": dict(canvas=(38, 26), paths=backspace, extra_strokes=backspace_x),
    }


ICONS.update(tool_icons())


# 书签（左栏四个圆底按钮）：圆 + 字形
def book_glyphs(kind, box, dx=0.0, dy=0.0):
    x0, y0, x1, y1 = box
    b = (x0 + dx, y0 + dy, x1 + dx, y1 + dy)
    if kind == 1:
        row_h = (b[3] - b[1] - 3) / 2
        mid = b[1] + row_h + 3
        col_w = (b[2] - b[0] - 3) / 2
        midx = b[0] + col_w + 3
        return [
            G(T("+"), (b[0], b[1], midx - 3, mid), KEY),
            G(T("−"), (midx, b[1], b[2], mid), KEY),
            G(T("×"), (b[0], mid + 3, midx - 3, b[3]), KEY),
            G(T("÷"), (midx, mid + 3, b[2], b[3]), KEY),
        ]
    text = {2: "f", 3: "a-z", 4: "f(x)"}[kind]
    return [G(T(text), b, KEY)]


for i in range(1, 5):
    ICONS[f"book{i}"] = dict(canvas=(64, 64), paths=[(circle_cmds(32, 32, 32), BOOK)],
                             glyph=book_glyphs(i, (17, 17, 47, 48)))


# --------------------------------------------------------------------------
# 生成 XML
# --------------------------------------------------------------------------

VECTOR_HEAD = """<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/make_keyboard_icons.py 生成：字形取自 Noto Sans SC Light（SIL OFL-1.1），
     几何图形为本项目自绘。改图标请改生成脚本，不要手改本文件。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w}dp"
    android:height="{h}dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}">
"""


def fmt_dp(v):
    s = f"{v / 2:.2f}".rstrip("0").rstrip(".")
    return s


def emit_vector(name, canvas, solids, strokes):
    w, h = canvas
    out = [VECTOR_HEAD.format(w=fmt_dp(w), h=fmt_dp(h), vw=int(w), vh=int(h))]
    for cmds, color in solids:
        out.append(f'  <path\n      android:fillColor="{color}"\n'
                   f'      android:pathData="{serialize(cmds)}" />')
    for stroke in strokes:
        cmds, color, width = stroke[0], stroke[1], stroke[2]
        cap = stroke[3] if len(stroke) > 3 else "round"
        join = stroke[4] if len(stroke) > 4 else "round"
        out.append(f'  <path\n      android:fillColor="@android:color/transparent"\n'
                   f'      android:strokeColor="{color}"\n      android:strokeWidth="{fmt(width)}"\n'
                   f'      android:strokeLineCap="{cap}"\n      android:strokeLineJoin="{join}"\n'
                   f'      android:pathData="{serialize(cmds)}" />')
    out.append("</vector>\n")
    return "\n".join(out)


def build_icon(sha, name, spec, color_override=None):
    canvas = spec["canvas"]
    solids, strokes = [], []
    for x, y, w, h in spec.get("slots", []):
        solids.append((rect_cmds(x, y, w, h), SLOT))
    for item in spec.get("paths", []):
        if len(item) == 2:
            solids.append(item)
        else:
            strokes.append(item)
    strokes += spec.get("extra_strokes", [])
    # 不带槽位、只有一个文字元素时按高度适配（窄字形不会被压小）；其余保持 contain
    contain = (bool(spec.get("slots")) or bool(spec.get("paths"))
               or len(spec.get("glyph", [])) > 1)
    for node, box, color in spec.get("glyph", []):
        if color_override:
            color = color_override
        cmds, _, _ = node_layout(sha, node, 100.0)
        solids.append((fit_to_box(cmds, box, "contain" if contain else "height"), color))
    # 画布至少包住 canvas 矩形；内容超出就往两边长
    hull = (0.0, 0.0, float(canvas[0]), float(canvas[1]))
    for group, _ in solids:
        hull = union_bbox(group, hull)
    for stroke in strokes:
        hull = union_bbox(stroke[0], hull)
    pad = 0.75
    x0, y0 = hull[0] - pad, hull[1] - pad
    new_w = int(round(hull[2] - hull[0] + pad * 2))
    new_h = int(round(hull[3] - hull[1] + pad * 2))
    solids = [(transform_cmds(g, 1, -x0, -y0), c) for g, c in solids]
    strokes = [((transform_cmds(s[0], 1, -x0, -y0),) + tuple(s[1:])) for s in strokes]
    return emit_vector(name, (new_w, new_h), solids, strokes)


def fetch_font(path):
    if os.path.exists(path):
        with open(path, "rb") as f:
            if hashlib.sha256(f.read()).hexdigest().upper() == FONT_SHA256:
                return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    print(f"下载字体 {FONT_URL}")
    urllib.request.urlretrieve(FONT_URL, path)
    with open(path, "rb") as f:
        got = hashlib.sha256(f.read()).hexdigest().upper()
    if got != FONT_SHA256:
        raise SystemExit(f"字体 SHA-256 不符：\n  期望 {FONT_SHA256}\n  实际 {got}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", default=FONT_PATH)
    ap.add_argument("--out", default=os.path.join("app", "src", "main", "res", "drawable"))
    ap.add_argument("--fetch", action="store_true", help="字体缺失时先下载并校验")
    args = ap.parse_args()

    if args.fetch:
        fetch_font(args.font)
    sha = Shaper(args.font)
    os.makedirs(args.out, exist_ok=True)

    n = 0
    for name, spec in sorted(ICONS.items()):
        xml = build_icon(sha, name, spec)
        with open(os.path.join(args.out, f"ic_keyboard_{name}.xml"), "w", encoding="utf-8") as f:
            f.write(xml)
        n += 1
    # 书签按下态的白色字形
    for i in range(1, 5):
        glyphs = book_glyphs(i, (17, 17, 47, 48), dx=1.0, dy=1.0)
        solids = []
        for node, box, _ in glyphs:
            cmds, _, _ = node_layout(sha, node, 100.0)
            solids.append((fit_to_box(cmds, box, "contain"), WHITE))
        xml = emit_vector(f"dart{i}", (66, 66), solids, [])
        with open(os.path.join(args.out, f"ic_dart_glyph_{i}.xml"), "w", encoding="utf-8") as f:
            f.write(xml)
        n += 1
    print(f"生成 {n} 个 drawable -> {args.out}")


if __name__ == "__main__":
    main()
