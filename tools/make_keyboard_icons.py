#!/usr/bin/env python3
"""生成键盘图标的矢量 drawable（app/src/main/res/drawable/ic_keyboard_*.xml）。

来源说明：

- 字形：Noto Sans SC Regular（SIL OFL-1.1），用 uharfbuzz 排版、fontTools 取轮廓，
  只把轮廓写进 drawable，不随仓库分发字体文件本身。字体按需从 noto-cjk 上游下载
  （URL 与 SHA-256 见 FONT_URL / FONT_SHA256）。

  字重是按原版键盘位图定的：拿原版 res/drawable-xhdpi-v4/ic_keyboard_*.png 量
  「4」「7」「x」的竖干宽度，Regular 是 4/4/8px，原版是 4/4/7px，而 Light 只有
  2/3/5px（用户反馈「字都偏细，看着累」）。Medium（5/5/9）又偏粗，所以用 Regular。
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
FONT_URL = "https://github.com/notofonts/noto-cjk/raw/main/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf"
FONT_SHA256 = "FAA6C9DF652116DDE789D351359F3D7E5D2285A2B2A1F04A2D7244DF706D5EA9"
FONT_PATH = os.path.join(REPO, "tools", ".cache", "NotoSansSC-Regular.otf")

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
    return "".join(cmd[0] + " ".join(fmt(p) for p in cmd[1]) for cmd in cmds)


def transform_cmds(cmds, s, dx, dy):
    out = []
    for cmd in cmds:
        c, args = cmd[0], cmd[1]
        if c == "H":
            pts = [args[0] * s + dx]
        elif c == "V":
            pts = [args[0] * s + dy]
        else:
            pts = [args[j] * s + dx if j % 2 == 0 else args[j] * s + dy
                   for j in range(len(args))]
        # 尾部是「笔画补偿」标签（宽度，px），变换时原样带着走
        out.append((c, pts) + tuple(cmd[2:]))
    return out


def transform_group(group, s, dx, dy):
    """group 是一段扁平 cmds（一次排版结果）。"""
    return transform_cmds(group, s, dx, dy)


def polylines(cmds, steps=16):
    polys, cur, pos, start = [], [], (0.0, 0.0), (0.0, 0.0)
    for cmd in cmds:
        c, args = cmd[0], cmd[1]
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

    def run(self, text, size, x=0.0, y=0.0, tracking=0.0, sup_bold=0.0):
        """一段文字，基线在 (x,y)，返回 (cmds, advance)。

        sup_bold > 0 时，字体自带的上下标字符（²³ⁿ 这些）会带上同宽的
        描边标签——它们在字体里的设计字号只有正文的约 0.6，笔画跟着细，
        补偿见 SUP_FONT_FACTOR。
        """
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
            if sup_bold > 0 and info.cluster < len(text) and text[info.cluster] in SUP_CHARS:
                cmds = embolden(cmds, sup_bold)
            if cmds:
                out.extend(cmds)
            penx += pos.x_advance * scale + tracking
        return out, penx


# 节点：("t", text) / ("sub", text) / ("sup", text) / ("seq", [节点...]) / ("frac", 上, 下)
#       / ("grid", [[左上, 右上], [左下, 右下]])


# --------------------------------------------------------------------------
# 笔画补偿：被缩小的字形（上下标、书签里的小字组、字体自带的 ²³ⁿ）不应该是
# 「整个字等比缩小」——字号一小，笔画跟着细，和旁边的正文放一起就显得轻、细。
# 原版位图也是这么处理的：书签里 f（38 高）、a-z（15 高）、f(x) 的括号（23 高）
# 笔画全都是 3px。这里的做法是给缩小的那组轮廓再加一圈同色描边（fill + stroke，
# Android vector drawable 直接支持），等效于把轮廓往外扩 w/2，笔画加粗 w。
#
# STROKE_PER_EM：Noto Sans SC Regular 在 size=100 时的竖干宽度（几何量，
# l/f/i/t/1/7 都是 9.1～9.2 画布 px）。要补的宽度就是
#     w = BOLD_FACTOR × STROKE_PER_EM × (正文号 - 小字号)
# 描边的圆角接合（strokeLineJoin=round）让加粗后的笔端不出现尖刺。
#
# BOLD_FACTOR 是视觉补偿系数：0 = 不补（小字明显发虚），1 = 把小字的绝对笔画
# 宽度补到和正文完全一样。补满以后小字反而显得比正文还重——小字号在视觉上
# 本来就用不着那么粗的绝对笔画（光学尺寸的常规做法只补一部分）。0.6 是实机
# 对照定的：补完笔画约为正文的 0.8，眼睛看过去两边一样重，DevTools 量的绝对
# 像素值仍比正文细一点。
# --------------------------------------------------------------------------
STROKE_PER_EM = 0.091
BOLD_FACTOR = 0.6

#: 字体自带的上下标字符（设计字号约为正文的 0.6，见 LayoutProfile.script_scale）
SUP_CHARS = set("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿ")

#: 字体自带上下标字符的补偿系数（1.0 = 和 SUB/SUP 节点同一条公式）。
#: 这些字形自带笔画比正文重（² 的竖干 7.37/em，³ 甚至 9.89/em），补满会
#: 明显比 SUP 节点上的上标粗——用户对照 y = ax²+bx+c 和 y = aˣ 报过
#: 「² 偏粗、x 刚好」。按画布平均笔宽量（work/tmp/sup2_measure.py，
#: FORMULA_SIZE）：SUP 节点 x = 2.41，字体 ² 不补 = 2.19、0.3 档 = 2.37、
#: 1.0 档 = 2.94。0.3 刚好贴住 SUP 节点，取整存档。
SUP_FONT_FACTOR = 0.3


def embolden(cmds, width):
    """给一组指令打描边标签（宽度单位：画布 px），序列化时按标签分组发。"""
    if not width or width <= 0.05:
        return cmds
    return [(cmd[0], cmd[1], width) for cmd in cmds]


def bold_width(ref_size, small_size):
    """把 small_size 的字排得和 ref_size 一样粗，需要补的描边宽度。"""
    return max(0.0, BOLD_FACTOR * STROKE_PER_EM * (ref_size - small_size))


class LayoutProfile:
    """上下标 / 分式的排版参数（相对基准字号的比例）。

    字号规则只有两条，避免"层层相乘"：
      1. 正文字号 = 基准字号（分式的分子分母也算正文，见 frac_scale）；
      2. 上下标字号 = 基准字号 × script_scale，**不再乘所在层的缩放**——
         所以"分子里的上标"和"单行里的上标"永远同一字号。
    """

    def __init__(self, script_scale, sub_drop, sup_rise, frac_scale,
                 frac_bar_y, frac_gap, frac_bar_h):
        #: 上下标字号比例。0.62 ≈ 字体自带上标字符的设计比例（Noto Sans SC 里
        #: "²" 的墨迹高 45px，全尺寸 "2" 是 74.6px，比值 0.60）。
        self.script_scale = script_scale
        self.sub_drop = sub_drop
        self.sup_rise = sup_rise
        #: 分式分子/分母的字号比例。函数页用 1.0：分子里的 y 必须和单行的 y 一样大。
        self.frac_scale = frac_scale
        #: 分数线相对基线的位置（负值 = 基线以上）。0.369 是等号/加号的中心高度，
        #: 也就是数学排版里的轴线（axis），分数线压在这条线上最规范。
        self.frac_bar_y = frac_bar_y
        self.frac_gap = frac_gap
        self.frac_bar_h = frac_bar_h


# 普通键（log₂、x²、A_P 这些）沿用原来的比例。
SCRIPT_PROFILE = LayoutProfile(
    script_scale=0.62, sub_drop=0.16, sup_rise=0.46,
    frac_scale=0.74, frac_bar_y=-0.30, frac_gap=0.10, frac_bar_h=0.05,
)

# 「函数」页 13 个公式：分子分母和单行正文一个字号（原版这几处是缩小的，
# 用户要求比原版更整齐）。上下标仍按 script_scale 缩小——这是数学排版的惯例，
# 关键是它在 13 个图标里处处一致。
# 全尺寸的分子分母比原来高，分数线要往上挪到轴线（-0.369），缝隙也要比
# 普通键宽一点，否则会和分数线贴住。
UNIFORM_PROFILE = LayoutProfile(
    script_scale=0.62, sub_drop=0.16, sup_rise=0.46,
    frac_scale=1.0, frac_bar_y=-0.369, frac_gap=0.15, frac_bar_h=0.05,
)

# 「函数」页 13 个公式的统一字号（单位：画布里的 px，1px = 0.5dp）。
# 27px ÷（首字母 y 在 size=100 时的墨迹高 77.7px）≈ 34.7。
FORMULA_SIZE = 34.7


def node_layout(sha, node, size, profile=SCRIPT_PROFILE, ctx=1.0):
    """把节点排版到“基线 y=0、起点 x=0”的坐标系，返回 (cmds 列表, advance, bbox)。

    size 是整个图标正文的基准字号；ctx 是当前所在层相对正文的缩放
    （分式的分子/分母会带一个 frac_scale）。上下标只按基准字号算，
    不乘 ctx——这是"分子的上标和单行的上标字号统一"的保证。
    """
    kind = node[0]
    if kind == "t":
        em = size * ctx
        tracking = (node[2] if len(node) > 2 else 0.0) * em
        # 字体自带的 ²³ⁿ 只占约 0.6 em，按 em 的 40% 补笔画，再乘字体字符的
        # 折扣系数（它们自带笔画比正文重，补满会比 SUP 节点上的上标粗）
        cmds, adv = sha.run(node[1], em, tracking=tracking,
                            sup_bold=SUP_FONT_FACTOR * bold_width(em, em * 0.6))
        return cmds, adv, None
    if kind in ("sub", "sup"):
        small = size * profile.script_scale
        dy = size * ctx * (profile.sub_drop if kind == "sub" else -profile.sup_rise)
        cmds, adv = sha.run(node[1], small, y=dy)
        # 上下标的笔画按「所在层正文号」补齐，这样它和旁边的正文一样粗
        return embolden(cmds, bold_width(size * ctx, small)), adv, None
    if kind == "seq":
        all_cmds, x = [], 0.0
        for part in node[1]:
            cmds, adv, _ = node_layout(sha, part, size, profile, ctx)
            all_cmds += transform_group(cmds, 1, x, 0)
            x += adv
        return all_cmds, x, None
    if kind == "frac":
        inner = ctx * profile.frac_scale
        num, den = node[1], node[2]
        num_cmds, _, _ = node_layout(sha, num, size, profile, inner)
        den_cmds, _, _ = node_layout(sha, den, size, profile, inner)
        nb = union_bbox(num_cmds)
        db = union_bbox(den_cmds)
        w = max(nb[2] - nb[0], db[2] - db[0])
        # 分数线、缝隙按**当前层的正文尺寸**走，不跟分子分母的缩放
        em = size * ctx
        bar_y = em * profile.frac_bar_y
        gap = em * profile.frac_gap
        # 分子贴在横线上方，分母贴在下方，整体水平居中
        num_dx = (w - (nb[2] - nb[0])) / 2 - nb[0]
        den_dx = (w - (db[2] - db[0])) / 2 - db[0]
        num_dy = (bar_y - gap) - nb[3]
        den_dy = (bar_y + gap) - db[1]
        cmds = transform_group(num_cmds, 1, num_dx, num_dy)
        cmds += transform_group(den_cmds, 1, den_dx, den_dy)
        # 分数线画成一条**有厚度的矩形**，不能写成 M/L 的单线：
        # 矢量图标最终是按 fillColor 填充的，零面积的线会被光栅化成「什么都没有」
        # （反比例函数、椭圆、双曲线那几个键的分数线就是这么丢的）。
        # 粗细细和 Noto 字形的横画接近，缩放后在图标里大约 1dp。
        bar_h = size * profile.frac_bar_h
        cmds += [("M", [0.0, bar_y - bar_h / 2]), ("L", [w, bar_y - bar_h / 2]),
                 ("L", [w, bar_y + bar_h / 2]), ("L", [0.0, bar_y + bar_h / 2]), ("Z", [])]
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


def place_exact(cmds, box):
    """把已经按统一字号排好的字形搬进 box：只平移、不缩放，墨迹照原样。

    同一族图标（字母、函数名）用同一个 box 和同一个基准字号时，字形之间的
    大小比例、基线、上下伸部都和排版学里的一致——尺子就是这条基线，不是每个
    字形各自的墨迹盒。这就是「每个字母一个字号、一条基线」的做法。
    """
    b = union_bbox(cmds)
    dx = box[0] - b[0]
    dy = box[1] - b[1]
    return transform_group(cmds, 1.0, dx, dy)


def center_in_box(cmds, box):
    """**不缩放**，只把墨迹居中放进 box（原地大小）。"""
    b = union_bbox(cmds)
    cx = box[0] + (box[2] - box[0]) / 2 - (b[0] + (b[2] - b[0]) / 2)
    cy = box[1] + (box[3] - box[1]) / 2 - (b[1] + (b[3] - b[1]) / 2)
    return transform_group(cmds, 1.0, cx, cy)


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
    "e": dict(canvas=(100, 80), glyph=[G(T("e"), (35, 23, 59, 54))]),
    "i": dict(canvas=(14, 36), glyph=[G(T("i"), (0, 0, 14, 36))]),
    "x": dict(canvas=(26, 27), glyph=[G(T("x"), (0, 0, 26, 27))]),
    "y": dict(canvas=(24, 35), glyph=[G(T("y"), (0, 0, 24, 35))]),
    "z": dict(canvas=(21, 26), glyph=[G(T("z"), (0, 0, 21, 26))]),
    "plus": dict(canvas=(30, 30), glyph=[G(T("+"), (0, 1, 30, 30))]),
    "minus": dict(canvas=(30, 3), glyph=[G(T("−"), (0, 0, 30, 3))]),
    "multiply": dict(canvas=(26, 26), glyph=[G(T("×"), (0, 0, 26, 26))]),
    "divide": dict(canvas=(28, 28), glyph=[G(T("÷"), (0, 0, 28, 28))]),
    "equal": dict(canvas=(30, 15), glyph=[G(T("="), (0, 0, 30, 15))]),
    "dot": dict(canvas=(8, 46), glyph=[G(T("."), (1, 39, 7, 45))]),
    "inf": dict(canvas=(47, 22), glyph=[G(T("∞"), (0, 0, 47, 22))]),
    "pi": dict(canvas=(31, 30), glyph=[G(T("π"), (0, 0, 31, 30))]),
    "degree": dict(canvas=(46, 46), glyph=[G(T("°"), (32, 0, 45, 14))]),
    "dms": dict(canvas=(66, 13),
                glyph=[G(SEQ(T("°", 0.10), T("′", 0.10), T("″")), (0, 0, 66, 15))]),
    "factorial": dict(canvas=(42, 36), slots=[(0, 1, 26, 32)],
                      glyph=[G(T("!"), (36, 0, 41, 37))]),
    "frac": dict(canvas=(42, 61), slots=[(11, 0, 22, 24), (11, 37, 22, 24)],
                 paths=[(rect_cmds(0, 29, 42, 3), KEY)]),
    # 根号是自绘几何，不是字形：字形版的横杠会压到右边那个占位方块上（用户反馈
    # 「灰色方块和根号符号重合」）。下面这两段按原版位图 ic_keyboard_sqrt.png 量出来的
    # 形状画：横杠 y≈9..11、从 x=20 拉到 x=52，左边折钩从 (2,43) 下到 (11.5,53.5)
    # 再斜上到横杠左端。
    "sqrt": dict(canvas=(53, 56), slots=[(2, 0, 14, 14), (25, 18, 24, 36)],
                 paths=[
                     ([("M", [20.5, 10.5]), ("L", [51.5, 10.5])], KEY, 3.0, "butt", "miter"),
                     ([("M", [2.5, 42.5]), ("L", [11.5, 53.5]), ("L", [21.5, 10.5])],
                      KEY, 4.0, "butt", "miter"),
                 ]),
    "int": dict(canvas=(42, 53), slots=[(24, 4, 18, 20), (24, 27, 18, 20)],
                glyph=[G(T("∫"), (0, 0, 16, 53))]),
    "abs": dict(canvas=(46, 40), slots=[(11, 6, 24, 28)],
                paths=[(rrect_cmds(0.5, 2, 3, 36, 1.5), KEY),
                       (rrect_cmds(42.5, 2, 3, 36, 1.5), KEY)]),
    # 中文标签键比原版位图放大一号（用户要求「所有中文字符大一号」）：
    # 原版是 59×29、墨迹 28；现在是 64×32、墨迹 ≈30。
    "gcd": dict(canvas=(64, 32), glyph=[G(T("公约"), (1, 0, 63, 32))]),
    "lcm": dict(canvas=(64, 32), glyph=[G(T("公倍"), (1, 0, 63, 32))]),
    "ap": dict(canvas=(52, 43), slots=[(34, 0, 18, 20), (34, 23, 18, 20)],
               glyph=[G(T("A"), (1, 2, 27, 35))]),
    "cp": dict(canvas=(47, 43), slots=[(29, 0, 18, 20), (29, 23, 18, 20)],
               glyph=[G(T("C"), (1, 2, 25, 36))]),
    "exp": dict(canvas=(44, 46), slots=[(0, 14, 28, 32), (30, 0, 14, 14)]),
    # ---- 第 4 页：函数模板 ----
    # 这 13 个公式的画布尺寸照抄原版位图，但**字号必须统一**：原版虽然画布高矮不一
    # （35~72px），正文却是一个字号——量下来每个公式的基准字形（首字母 y）都是 27px
    # 高，分式只是把画布撑高。之前按"墨迹填满各自画布"缩放，反比例函数被放大到 48、
    # 抛物线被压到 33，用户一眼看出大小不一。现在统一在 FORMULA_SIZE 下排版、不缩放，
    # 只把墨迹居中放进原来的画布。
    # （27px ÷ y 在 size=100 时的墨迹高 77.7px ≈ 34.7）
    "f_linear": dict(canvas=(134, 35), noscale=True,
                     glyph=[G(T("y = kx+b"), (0, 0, 134, 35), FORMULA)]),
    "f_inverse": dict(canvas=(91, 59), noscale=True,
                      glyph=[G(SEQ(T("y = "), FRAC("k", "x")), (0, 0, 91, 59), FORMULA)]),
    "f_normalquadratic": dict(canvas=(197, 42), noscale=True,
                              glyph=[G(T("y = ax²+bx+c"), (0, 1, 197, 42), FORMULA)]),
    "f_quadratic": dict(canvas=(198, 45), noscale=True,
                        glyph=[G(T("y = a(x−h)²+k"), (0, 1, 198, 45), FORMULA)]),
    "f_exp": dict(canvas=(86, 36), noscale=True,
                  glyph=[G(SEQ(T("y = a"), SUP("x")), (0, 0, 86, 36), FORMULA)]),
    "f_log": dict(canvas=(139, 35), noscale=True,
                  glyph=[G(SEQ(T("y = log"), SUB("a"), T("x")), (0, 0, 139, 35), FORMULA)]),
    "f_std_cir": dict(canvas=(213, 44), noscale=True,
                      glyph=[G(T("(x−a)²+(y−b)²=r²"), (0, 0, 213, 44), FORMULA)]),
    "f_circle": dict(canvas=(264, 40), noscale=True,
                     glyph=[G(T("x²+y²+ax+by+c=0"), (0, 0, 264, 40), FORMULA)]),
    "f_std_ell": dict(canvas=(139, 72), noscale=True,
                      glyph=[G(SEQ(FRAC("x²", "a²"), T(" + "), FRAC("y²", "b²"), T(" = 1")),
                                (0, 0, 139, 72), FORMULA)]),
    "f_ellipse": dict(canvas=(230, 67), noscale=True,
                      glyph=[G(SEQ(FRAC("(x−k)²", "a²"), T(" + "), FRAC("(y−h)²", "b²"), T(" = 1")),
                                (0, 0, 230, 67), FORMULA)]),
    "f_std_hyper": dict(canvas=(139, 72), noscale=True,
                        glyph=[G(SEQ(FRAC("x²", "a²"), T(" − "), FRAC("y²", "b²"), T(" = 1")),
                                  (0, 0, 139, 72), FORMULA)]),
    "f_hyperbola": dict(canvas=(230, 67), noscale=True,
                        glyph=[G(SEQ(FRAC("(x−k)²", "a²"), T(" − "), FRAC("(y−h)²", "b²"), T(" = 1")),
                                  (0, 0, 230, 67), FORMULA)]),
    "f_parabola": dict(canvas=(113, 38), noscale=True,
                       glyph=[G(T("y²=2px"), (0, 1, 113, 38), FORMULA)]),
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


# --------------------------------------------------------------------------
# 同族字形：数学字母 / 函数名 / 比较号 / 括号
#
# 原版每个键的位图都是「紧贴墨迹裁出来」的：a 是 20×27、b 是 22×37、
# sin 47×33、公倍 59×29……字号由族统一，升降部随字形自然高低。照着做：
# 同族共用一个字号，每个键按自己的墨迹紧裁、居中。早期实现用「并集画布」，
# 短字形被挤到画布一边（sin 偏左、cos / ln 偏上）；按墨迹填满小框则会让
# 同屏字号不一。两种都试过，用户逐条报过，这里回到原版的裁切方式。
# --------------------------------------------------------------------------

#: 数学字母族（a-z 与希腊字母）：字号跟第一页数字同一个 em。
#: 数字图标是「size=100 排版、墨迹装进 34px 高的框」，等效 em≈45.6；
#: 取 46 后 a 的墨迹 26px、b 37px，和原版位图量的 26 / 36 一致。
LETTER_SIZE = 46.0
#: 三角函数族（sin/cos/tan）：原版位图墨迹高 33/24/30 → em≈42，
#: 和同排中文（公倍/公约，em≈30）观感相当，比数字（em≈46）轻一点。
FUNCTION_SIZE = 42.0
#: 反三角（arcsin/arccos/arctan）：原版是 sin 一族的 76%（墨迹 25 → em≈32）。
ARC_SIZE = 32.0
#: 对数族（log/log2/log10/ln）：四个键同字号。原版按 42 排（和 sin 一族同号），
#: 实机上 log/log2/log10 墨迹贴着格子、显得占满，用户要求「小一号」；
#: lim 键的正文原本按框拟合裁出来是 em≈35.2，四键统一到 36 正好两边都接上。
LOG_SIZE = 36.0
#: 对数键的灰色占位方块（原版位图里的槽位）：底数槽 12×13、自变量槽 20×25。
#: 原版 log 位图：基线 y≈27，两块分别是 14..26 和 4..28，即都坐在基线上方。
LOG_BASE_SLOT = (12.0, 13.0)
LOG_ARG_SLOT = (20.0, 25.0)
#: 比较号（< > ≤ ≥）：自绘，笔画宽度按原版位图量（约 4px，≈2dp）。
COMPARE_STROKE = 4.2


def _tight_specs(size, texts, fill=None, extra_slots=None, lock_baseline=False):
    """同族同字号：横向按各自的墨迹紧裁（原版的切图方式）。

    [texts] 是 name -> 排版节点；[extra_slots] 是 name -> [(x,y,w,h), ...]，
    坐标写在「基线 y=0、起点 x=0」的系统里（和 node_layout 的输出同一坐标系）。

    lock_baseline=True 时纵向不再各裁各的，而是整族共用一个高度、共一条基线：
    每个图标都是「居中的一幅图」，各裁各的等于让基线跟着墨迹高度跑——
    sin（i 的点最高）和 cos（只有 x 高）的基线能差 3dp，一排看过去 cos 像被
    抬高了一截（原版也这样，用户对照报过）。锁了基线之后上下伸部照字形自然
    高低，基线永远齐平，和一行文字一样。
    """
    laid = {}
    for name, node in texts.items():
        cmds, _, _ = node_layout(Shaper__shared, node, size)
        b = union_bbox(cmds)
        # 笔画补偿会让墨迹再往外长 bold/2，画布外框要按补偿之后的视觉框算
        bold = max((cmd[2] for cmd in cmds if len(cmd) > 2), default=0.0)
        laid[name] = (cmds, b, bold)
    top = min(b[1] - bold / 2 for _, b, bold in laid.values()) if lock_baseline else None
    bottom = max(b[3] + bold / 2 for _, b, bold in laid.values()) if lock_baseline else None
    specs = {}
    for name, (cmds, b, _bold) in laid.items():
        # 锁基线：整族用同一个上缘；否则按自己的墨迹裁
        dy = -top if lock_baseline else -b[1]
        dx = -b[0]
        w = b[2] - b[0]
        h = bottom - top if lock_baseline else b[3] - b[1]
        specs[name] = dict(
            canvas=(w, h),
            paths=[(transform_group(cmds, 1.0, dx, dy), fill or KEY)],
        )
        slots = (extra_slots or {}).get(name)
        if slots:
            # 墨迹平移了多少，槽位跟着平移多少
            specs[name]["slots"] = [(x + dx, y + dy, sw, sh) for x, y, sw, sh in slots]
    return specs


def _log_slots():
    """对数四键的灰色方块：贴在各自墨迹的右缘，间隔 5（原版的排法）。

    普通 log 多一个底数槽（原版就是 text → 底数槽 → 自变量槽 依次排开，
    所以 log 和 log2 的自变量槽落在同一个 x 上）。
    """
    slots = {}
    for name, node in LOG_TEXTS.items():
        cmds, _, _ = node_layout(Shaper__shared, node, LOG_SIZE)
        b = union_bbox(cmds)
        boxes = []
        cursor = b[2]
        if name == "log":
            w, h = LOG_BASE_SLOT
            boxes.append((cursor + 5.0, -h - 1.0, w, h))
            cursor += 5.0 + w
        w, h = LOG_ARG_SLOT
        boxes.append((cursor + 5.0, 1.0 - h, w, h))
        slots[name] = boxes
    return slots


def _line_intersect(p, d1, q, d2):
    """参数直线 p+s·d1 与 q+t·d2 的交点；平行时退回两点中点。"""
    den = d1[0] * d2[1] - d1[1] * d2[0]
    if abs(den) < 1e-9:
        return ((p[0] + q[0]) / 2, (p[1] + q[1]) / 2)
    t = ((q[0] - p[0]) * d2[1] - (q[1] - p[1]) * d2[0]) / den
    return (p[0] + d1[0] * t, p[1] + d1[1] * t)


def _offset_side(points, width, sign):
    """折线某一侧的偏移边界：端点按法向平移，中间顶点取两条偏移线的交点（真斜接）。

    顶点若按角平分线平移（各点各偏半个笔宽），外侧拐角会少伸、内侧会少凹，
    拐角附近比两条腿窄近一半——实机上就是「笔画粗细不匀、拐角发虚」。
    """
    dirs, off = [], []
    for i in range(len(points) - 1):
        dx = points[i + 1][0] - points[i][0]
        dy = points[i + 1][1] - points[i][1]
        length = (dx * dx + dy * dy) ** 0.5 or 1.0
        dirs.append((dx / length, dy / length))
        off.append((-dy / length * width / 2 * sign, dx / length * width / 2 * sign))
    out = [(points[0][0] + off[0][0], points[0][1] + off[0][1])]
    for i in range(1, len(points) - 1):
        p = (points[i][0] + off[i - 1][0], points[i][1] + off[i - 1][1])
        q = (points[i][0] + off[i][0], points[i][1] + off[i][1])
        out.append(_line_intersect(p, dirs[i - 1], q, dirs[i]))
    out.append((points[-1][0] + off[-1][0], points[-1][1] + off[-1][1]))
    return out


def _thick_polyline(points, width):
    """把折线画成有厚度的多边形（比描边稳：光栅化时不会出现断头）。

    拐角走真斜接：偏移边界在顶点处求交，整条折线处处等宽；端点保持平头。
    """
    return poly_cmds(_offset_side(points, width, 1.0)
                     + _offset_side(points, width, -1.0)[::-1])


def _build_compare_paths(kind):
    """比较号：折线 + 横杠，形状按原版位图来。

    原版 less/greater 是 26×27、le/ge 是 27×29，折线几乎占满画布（半角约 28°），
    ≤/≥ 的横杠压在最底下、通宽，和折线一样粗（4px）。早期版本折线只有 15px 高、
    画布却按 34 高留白，看起来明显偏小（用户对照原版报过）。
    """
    left = kind in ("less", "le")
    if kind in ("less", "greater"):
        w, top, apex, bottom, bar_y = 26.0, (24.0, 2.6), (2.6, 13.5), (24.0, 24.4), None
    else:
        w, top, apex, bottom, bar_y = 27.0, (25.4, 1.85), (3.5, 11.0), (25.4, 20.15), 26.8
    if not left:
        top, apex, bottom = (w - top[0], top[1]), (w - apex[0], apex[1]), (w - bottom[0], bottom[1])
    chevron = _thick_polyline([top, apex, bottom], COMPARE_STROKE)
    paths = [chevron]
    if bar_y is not None:
        # 横杠和折线等宽（原版两段的墨迹盒左右都齐平）——特别是拐角改真斜接之后，
        # 折线左端伸出去的那一截横杠也要跟着，不能还用旧的 0.6 内缩值
        xs = [cmd[1][0] for cmd in chevron if cmd[0] != "Z"]
        paths.append(_thick_polyline([(min(xs), bar_y), (max(xs), bar_y)], COMPARE_STROKE))
    return paths


def compare_icons():
    specs = {}
    for kind in ("less", "greater", "le", "ge"):
        canvas = (26, 27) if kind in ("less", "greater") else (27, 29)
        # 自绘几何直接按填充多边形写（build_icon 里两条元素的项就是纯色填充）
        specs[kind] = dict(
            canvas=canvas,
            # 拐角改成真斜接之后墨迹比 canvas 宽，再和 canvas 并集就会偏心——
            # 这四个键改成只按墨迹裁（原版位图也是紧贴墨迹的），居中由墨迹决定
            tight_hull=True,
            paths=[(path, KEY) for path in _build_compare_paths(kind)],
        )
    return specs


def paren_icons():
    """左右括号：用字体本来的轮廓（形状最像括号），只调整宽高到数字的量级。

    以前把它当一条线去描边，曲线被拉成一根细月牙，反而失真。现在直接用
    Noto 的括号字形：装进原版位图量出来的 11×42 画布（比数字略高，原版就是
    这个比例），笔画粗细随轮廓自然变化。
    """
    return {
        "left_paren": dict(canvas=(11, 42),
                           exact_glyph=[(T("("), 100.0, (0.0, 0.0, 11.0, 42.0), KEY)]),
        "right_paren": dict(canvas=(11, 42),
                            exact_glyph=[(T(")"), 100.0, (0.0, 0.0, 11.0, 42.0), KEY)]),
    }


# 数学字母族：拉丁小写 + 希腊字母。每个都是「同字号、同基线」。
LETTER_TEXTS = {
    "a": T("a"), "b": T("b"), "c": T("c"), "h": T("h"), "k": T("k"),
    "p": T("p"), "s": T("s"), "u": T("u"), "v": T("v"),
    "theta": T("θ"), "phi": T("φ"), "lambda": T("λ"),
    "mu": T("μ"), "sigma": T("σ"), "omega": T("ω"),
}

# 函数名族：三角 / 反三角 / 指数对数。统一字号、统一基线。
FUNCTION_TEXTS = {
    "sin": T("sin"), "cos": T("cos"), "tan": T("tan"),
}

# 反三角：比 sin/cos/tan 再小一档（字母多，原版也是缩小的）
ARC_TEXTS = {
    "arcsin": T("arcsin"), "arccos": T("arccos"), "arctan": T("arctan"),
}

# 对数族：四个键同字号，log2/log10 的底数用下标字形，普通 log 用灰色底数槽，
# 四个键的自变量槽在 _log_slots() 里按各自墨迹右缘排。
LOG_TEXTS = {
    "log": T("log"),
    "log2": SEQ(T("log"), SUB("2")),
    "log10": SEQ(T("log"), SUB("10")),
    "ln": T("ln"),
}

#: lim 键里那个箭头的位置（写死在原版位图上量出来的框里，不跟正文字号走）。
LIM_ARROW_BOX = (14.0, 35.0, 39.0, 51.0)


def _shared_shaper():
    """整个脚本共用一份 Shaper（每次重排字形都要用）。"""
    global Shaper__shared
    if "Shaper__shared" not in globals():
        if not os.path.exists(FONT_PATH):
            raise SystemExit(
                f"缺字体 {FONT_PATH}；先跑 python tools/make_keyboard_icons.py --fetch"
            )
        Shaper__shared = Shaper(FONT_PATH)
    return Shaper__shared


def lim_spec():
    """lim 键：正文和 log 一族同字号，箭头和两侧灰方块照原版。

    `glyph` 走的是 fit_to_box(contain)：缩放取「框宽/墨迹宽」和「框高/墨迹高」
    里的小者。这里把框宽设成 墨迹宽 × LOG_SIZE/100，缩放松紧就正好钉在
    LOG_SIZE 上（框高 34 比 80.9×0.36=29.1 宽松，不会抢）。
    """
    node = T("lim")
    cmds, _, _ = node_layout(_shared_shaper(), node, 100.0)
    b = union_bbox(cmds)
    w = (b[2] - b[0]) * LOG_SIZE / 100.0
    return dict(
        canvas=(53, 51),
        slots=[(0, 35, 14, 16), (39, 35, 14, 16)],
        glyph=[G(node, (4.0, 0.0, 4.0 + w, 34.0)), G(T("→"), LIM_ARROW_BOX)],
    )


def family_icons():
    _shared_shaper()
    icons = {}
    # 四个族都锁基线：同排的字（a/b、sin/cos/tan、log/ln）必须坐在同一条线上
    icons.update(_tight_specs(LETTER_SIZE, LETTER_TEXTS, fill=KEY, lock_baseline=True))
    icons.update(_tight_specs(FUNCTION_SIZE, FUNCTION_TEXTS, fill=KEY, lock_baseline=True))
    icons.update(_tight_specs(ARC_SIZE, ARC_TEXTS, fill=KEY, lock_baseline=True))
    icons.update(_tight_specs(LOG_SIZE, LOG_TEXTS, fill=KEY,
                              extra_slots=_log_slots(), lock_baseline=True))
    icons.update(compare_icons())
    icons.update(paren_icons())
    return icons


ICONS.update(family_icons())
ICONS["lim"] = lim_spec()



# --------------------------------------------------------------------------
# 书签（左栏四个圆底按钮）：圆 + 字形
#
# 字号只有两档，是用户报「左侧图标字体大小不一」之后按原版位图定下来的：
#
#   * BOOK_F_SIZE 给 f：书签 2 的单字 f 和书签 4 里 f(x) 的 f 是同一个字号 ——
#     原版 ic_keyboard_book2/4.png 里这两个 f 都是 15×38（同一张字的两次使用）。
#     之前 f(x) 整串按墨迹高度缩，f 被括号拖小到 23，和单字 f 的 33 差一档。
#   * BOOK_S_SIZE 给 a、z、x：书签 3 的 a/z 和书签 4 里的 x 同高
#     （原版量出来 a 15、z 14、x 14，画布 px）。小括号再小一档
#     （BOOK_PAREN_SIZE）：原版括号 23 高，约是 x 的 1.6 倍。
#
# 排法也照原版：书签 4 = 大 f + 小 "(x)"，(x) 组垂直居中在 f 的 x 高带上；
# a-z 三个字形摊开 40 宽（原版 a 12..25、- 29..35、z 38..51），Noto 默认只有
# 34，加 0.12em 字距补齐。小字组的笔画按 f 的粗细补齐（原版位图里 f、a-z、
# (x) 的笔画都是 3px）——只缩字号不加粗，笔画会明显偏细（用户报过）。
# --------------------------------------------------------------------------
BOOK_F_SIZE = 47.0
BOOK_S_SIZE = 26.0
BOOK_PAREN_SIZE = 21.0
BOOK_AZ_TRACKING = 0.12

#: 小字组要补的描边宽度（画布 px）
BOLD_SMALL = bold_width(BOOK_F_SIZE, BOOK_S_SIZE)
BOLD_PAREN = bold_width(BOOK_F_SIZE, BOOK_PAREN_SIZE)

#: 书签 1（+ − × ÷）的笔画宽度。原来四个符号是直接拿字体的 +−×÷ 塞进四宫格，
#: 可 Noto 里这四个字符各自笔宽不同（实测 2.05 / 1.92 / 1.2 / 1.16），摆在大 f
#: （干宽 4.3）旁边明显发轻，用户报「视觉重心不稳」。改成几何自绘、共用一条笔宽：
#: 2.9 ≈ f 的 0.68 —— 原版位图里四则符号 2px、f 3px，比例正是 0.67。
BOOK_OP_STROKE = 2.9
BOOK_OP_DOT = 3.4
BOOK_OP_ARM = 13.5
# × 的墨迹盒比 + 的臂长短一档（原版 11 vs 12）；同笔宽下盒子越小越显重，
# 这里留 12.2（原版比例 11/12 = 0.92，我们 12.2/13.5 = 0.90），四个符号才一样重
BOOK_OP_CROSS = 12.2


def _bar_cmds(p0, p1, width):
    """两端平头、宽 width 的直杆（填充多边形）。"""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    length = (dx * dx + dy * dy) ** 0.5 or 1.0
    nx, ny = -dy / length * width / 2, dx / length * width / 2
    return poly_cmds([
        (p0[0] + nx, p0[1] + ny), (p1[0] + nx, p1[1] + ny),
        (p1[0] - nx, p1[1] - ny), (p0[0] - nx, p0[1] - ny),
    ])


def _book_op_paths():
    """书签 1 的四则运算符号：四宫格中心照原版位图（24.5/41 × 26.25/43.25）。"""
    w, arm, box = BOOK_OP_STROKE, BOOK_OP_ARM, BOOK_OP_CROSS
    half = arm / 2
    # × 的两条 45° 斜杆：端点还要算上平头在角上占掉的 w/2·cos45°
    d = box / 2 - w / 2 * (0.5 ** 0.5)
    out = []
    for cx, cy in ((24.5, 26.25), (41.0, 26.25), (24.5, 43.25), (41.0, 43.25)):
        if cx < 32:  # + 与 ×
            if cy < 35:
                out.append(_bar_cmds((cx - half, cy), (cx + half, cy), w))
                out.append(_bar_cmds((cx, cy - half), (cx, cy + half), w))
            else:
                out.append(_bar_cmds((cx - d, cy + d), (cx + d, cy - d), w))
                out.append(_bar_cmds((cx - d, cy - d), (cx + d, cy + d), w))
        else:       # − 与 ÷
            out.append(_bar_cmds((cx - half, cy), (cx + half, cy), w))
            if cy > 35:
                r = BOOK_OP_DOT / 2
                out.append(circle_cmds(cx, cy - 4.27, r))
                out.append(circle_cmds(cx, cy + 4.27, r))
    return out


def _ink_of(sha, node, size):
    """按 size 排版一段字，返回 (cmds, 墨迹 bbox)。"""
    cmds, _, _ = node_layout(sha, node, size)
    return cmds, union_bbox(cmds)


def _center_ink(cmds, b, cx=32.0, cy=32.0):
    """只平移：把墨迹中心搬到 (cx, cy)。"""
    return transform_group(cmds, 1.0,
                           cx - (b[0] + b[2]) / 2, cy - (b[1] + b[3]) / 2)


def book_glyphs(sha, kind, dx=0.0, dy=0.0):
    """书签里的字形路径（64×64 画布坐标；dx/dy 是按下态那 1px 的偏移）。"""
    out = []
    if kind == 1:
        # 四则运算符号：几何自绘，四宫格位置和原版位图一致（见 _book_op_paths）
        out.extend(_book_op_paths())
    elif kind == 2:
        cmds, b = _ink_of(sha, T("f"), BOOK_F_SIZE)
        out.append(_center_ink(cmds, b))
    elif kind == 3:
        cmds, b = _ink_of(sha, T("a-z", BOOK_AZ_TRACKING), BOOK_S_SIZE)
        out.append(_center_ink(embolden(cmds, BOLD_SMALL), b))
    else:
        # f(x)：大 f 的 x 高带（基线往上一整个 x 高）是小字组的对齐基准。
        f_cmds, f_b = _ink_of(sha, T("f"), BOOK_F_SIZE)
        band_cmds, band_b = _ink_of(sha, T("x"), BOOK_F_SIZE)
        lp_cmds, lp_b = _ink_of(sha, T("("), BOOK_PAREN_SIZE)
        x_cmds, x_b = _ink_of(sha, T("x"), BOOK_S_SIZE)
        rp_cmds, rp_b = _ink_of(sha, T(")"), BOOK_PAREN_SIZE)
        f_h = f_b[3] - f_b[1]
        band_h = band_b[3] - band_b[1]
        band_top = f_h - band_h          # 相对 f 的墨迹顶
        x_top = band_top + (band_h - (x_b[3] - x_b[1])) / 2
        paren_top = band_top + (band_h - (lp_b[3] - lp_b[1])) / 2
        gap = 2.0
        # f 的墨迹左上角定在 (0,0)，其它三件贴着 x 高带排开。描边会让每件
        # 各往外长 bold/2，间距按「视觉间隙 = gap」反推，免得 (x) 挤成一团。
        pieces = [
            (f_cmds, f_b, 0.0, 0.0, 0.0),
            (lp_cmds, lp_b, None, paren_top, BOLD_PAREN),
            (x_cmds, x_b, None, x_top, BOLD_SMALL),
            (rp_cmds, rp_b, None, paren_top, BOLD_PAREN),
        ]
        out = []
        union = None
        cursor, prev_bold = None, 0.0
        for cmds, b, px, py, bold in pieces:
            if cursor is None:
                px = 0.0
            else:
                px = cursor + gap + (prev_bold + bold) / 2
            placed = transform_group(cmds, 1.0, px - b[0], py - b[1])
            if bold > 0:
                placed = embolden(placed, bold)
            # 参与居中的是「描边之后」的视觉外框
            bb = (px - bold / 2, py - bold / 2,
                  px + (b[2] - b[0]) + bold / 2, py + (b[3] - b[1]) + bold / 2)
            union = bb if union is None else (
                min(union[0], bb[0]), min(union[1], bb[1]),
                max(union[2], bb[2]), max(union[3], bb[3]))
            cursor = px + (b[2] - b[0])
            prev_bold = bold
            out.append(placed)
        sx = 32.0 - (union[0] + union[2]) / 2
        sy = 32.0 - (union[1] + union[3]) / 2
        out = [transform_group(c, 1.0, sx, sy) for c in out]
    if dx or dy:
        out = [transform_group(c, 1.0, dx, dy) for c in out]
    return out


for i in range(1, 5):
    ICONS[f"book{i}"] = dict(
        canvas=(64, 64),
        paths=[(circle_cmds(32, 32, 32), BOOK)] +
              [(cmds, KEY) for cmds in book_glyphs(Shaper__shared, i)],
    )


# --------------------------------------------------------------------------
# 生成 XML
# --------------------------------------------------------------------------

VECTOR_HEAD = """<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/make_keyboard_icons.py 生成：字形取自 Noto Sans SC Regular（SIL OFL-1.1），
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


def split_bold(cmds):
    """按笔画补偿标签把指令分组：[(None, 普通指令), (宽度, 加粗指令), ...]。"""
    groups = []
    index = {}
    for cmd in cmds:
        width = cmd[2] if len(cmd) > 2 else None
        if width not in index:
            index[width] = len(groups)
            groups.append((width, []))
        groups[index[width]][1].append(cmd)
    return groups


def emit_vector(name, canvas, solids, strokes):
    w, h = canvas
    out = [VECTOR_HEAD.format(w=fmt_dp(w), h=fmt_dp(h), vw=int(w), vh=int(h))]
    for cmds, color in solids:
        # 指令里带笔画补偿标签的分成单独一条 path：填充之外再描一圈同色边，
        # 等效于把轮廓外扩 width/2（见 STROKE_PER_EM 那段说明）。
        for width, part in split_bold(cmds):
            if width is None:
                out.append(f'  <path\n      android:fillColor="{color}"\n'
                           f'      android:pathData="{serialize(part)}" />')
            else:
                out.append(f'  <path\n      android:fillColor="{color}"\n'
                           f'      android:strokeColor="{color}"\n'
                           f'      android:strokeWidth="{fmt(width)}"\n'
                           f'      android:strokeLineCap="round"\n'
                           f'      android:strokeLineJoin="round"\n'
                           f'      android:pathData="{serialize(part)}" />')
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
    noscale = bool(spec.get("noscale"))
    # 「函数」页 13 个公式（目前唯一用 noscale 的一组）走统一字号排版。
    profile = UNIFORM_PROFILE if noscale else SCRIPT_PROFILE
    for node, box, color in spec.get("glyph", []):
        if color_override:
            color = color_override
        if noscale:
            # 统一字号：按 FORMULA_SIZE 排版，只居中不缩放
            cmds, _, _ = node_layout(sha, node, FORMULA_SIZE, profile)
            solids.append((center_in_box(cmds, box), color))
        else:
            # 同族图标（数学字母、函数名）走统一字号：spec 指定统一 box，排版后
            # 只平移、不缩放，字形之间的基线、x 高度、上下伸部都保持字体本来的关系。
            uniform = spec.get("uniform_size")
            if uniform is not None:
                cmds, _, _ = node_layout(sha, node, uniform)
                bb = union_bbox(cmds)
                box = (box[0], box[1],
                       box[0] + (bb[2] - bb[0]), box[1] + (bb[3] - bb[1]))
                solids.append((place_exact(cmds, box), color))
            else:
                cmds, _, _ = node_layout(sha, node, 100.0)
                solids.append((fit_to_box(cmds, box, "contain" if contain else "height"), color))
    # 指定字号的字形，独立缩放 x/y 装进 box（括号需要比其它族更宽的横向压缩）
    for node, size, box, color in spec.get("exact_glyph", []):
        if color_override:
            color = color_override
        cmds, _, _ = node_layout(sha, node, size)
        b = union_bbox(cmds)
        sx = (box[2] - box[0]) / max(b[2] - b[0], 1e-6)
        sy = (box[3] - box[1]) / max(b[3] - b[1], 1e-6)
        transformed = transform_cmds(cmds, 1.0, 0.0, 0.0)
        transformed = [
            (cmd[0], [cmd[1][j] * sx - b[0] * sx + box[0] if j % 2 == 0
                      else cmd[1][j] * sy - b[1] * sy + box[1] for j in range(len(cmd[1]))])
            + tuple(cmd[2:])
            for cmd in transformed
        ]
        solids.append((transformed, color))
    # 画布至少包住 canvas 矩形；内容超出就往两边长（tight_hull 时只按内容裁）
    hull = None if spec.get("tight_hull") else (0.0, 0.0, float(canvas[0]), float(canvas[1]))

    def merge(a, b):
        return (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))

    for group, _ in solids:
        if not group:
            continue
        gb = cmds_bbox(group)
        # 带笔画补偿的字形，轮廓本身还会往外长半个描边宽
        bold = max((cmd[2] for cmd in group if len(cmd) > 2), default=0.0)
        if bold > 0:
            gb = (gb[0] - bold / 2, gb[1] - bold / 2,
                  gb[2] + bold / 2, gb[3] + bold / 2)
        hull = gb if hull is None else merge(hull, gb)
    if hull is None:
        hull = (0.0, 0.0, float(canvas[0]), float(canvas[1]))
    for stroke in strokes:
        hull = union_bbox(stroke[0], hull)
    # 描边是以中心线画的，边缘要留出半个笔宽，否则圆头会被画布裁掉。
    # 只有新加的比较号/括号打开这个开关，老图标的尺寸保持不变。
    pad = 0.75
    if spec.get("stroke_pad"):
        for stroke in strokes:
            if len(stroke) > 2:
                pad = max(pad, float(stroke[2]) / 2.0 + 0.5)
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
    # 书签按下态的白色字形（和常态同一份字形，整体右下 1px，照原版按下态）
    for i in range(1, 5):
        solids = [(cmds, WHITE) for cmds in book_glyphs(sha, i, dx=1.0, dy=1.0)]
        xml = emit_vector(f"dart{i}", (66, 66), solids, [])
        with open(os.path.join(args.out, f"ic_dart_glyph_{i}.xml"), "w", encoding="utf-8") as f:
            f.write(xml)
        n += 1
    print(f"生成 {n} 个 drawable -> {args.out}")


if __name__ == "__main__":
    main()
