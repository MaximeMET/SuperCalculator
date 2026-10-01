#!/usr/bin/env python3
"""生成结果页与工具条上的自绘图标。

**这些不是原版素材的描摹。** 需求只有「图标要表达什么意思」——继续编辑、
清空、用结果继续运算、返回、分享——几何形状由本项目自己设计，风格和其它
自绘素材统一：

* 线稿色 #53595E（和键盘上的工具/字形图标同色）。
* 线条粗细 4/152 ≈ 2dp，圆头线帽 + 圆角接合，读起来和键盘那批细线图标是一套。
* 强调色用品牌橙 #FFB560（见 values/colors.xml 的 brand_accent）。
* 结果页三个按钮的底衬是浅橙圆形，和品牌色同源，不描边。

生成的 5 个文件都在 res/drawable/ 下，只用到纯色，没有 API 24 才支持的渐变，
所以 minSdk 21 上也能正常渲染。

用法：
    python tools/make_result_icons.py            # 写文件
    python tools/make_result_icons.py --check    # 只校验文件是否已是最新
"""

from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

INK = "53595E"        # 线稿（同键盘图标）
ACCENT = "FFB560"     # 品牌橙（values/colors.xml brand_accent）
ACCENT_SOFT = "FFD9A6"
TILE = "FFF4EA"       # 圆形底衬：品牌橙的极浅色
WHITE = "FFFFFF"

STROKE = 4.0          # 152 画布上的线宽（76dp 图标上约 2dp）


# ---------------------------------------------------------------- 路径拼装

def n(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-0") else "0"


def circle(cx: float, cy: float, r: float) -> str:
    return (f"M{n(cx - r)},{n(cy)}"
            f"A{n(r)},{n(r)} 0 1 1 {n(cx + r)},{n(cy)}"
            f"A{n(r)},{n(r)} 0 1 1 {n(cx - r)},{n(cy)}Z")


def round_rect(x: float, y: float, w: float, h: float, r: float) -> str:
    r = min(r, w / 2, h / 2)
    return (f"M{n(x + r)},{n(y)}H{n(x + w - r)}"
            f"Q{n(x + w)},{n(y)} {n(x + w)},{n(y + r)}"
            f"V{n(y + h - r)}"
            f"Q{n(x + w)},{n(y + h)} {n(x + w - r)},{n(y + h)}"
            f"H{n(x + r)}"
            f"Q{n(x)},{n(y + h)} {n(x)},{n(y + h - r)}"
            f"V{n(y + r)}"
            f"Q{n(x)},{n(y)} {n(x + r)},{n(y)}Z")


def poly(points, r=0.0) -> str:
    """多边形；r>0 时每个顶点用二次曲线倒角（r 可给列表逐顶点指定）。"""
    pts = [(float(x), float(y)) for x, y in points]
    radii = list(r) if isinstance(r, (list, tuple)) else [r] * len(pts)

    def toward(a, b, dist):
        dx, dy = b[0] - a[0], b[1] - a[1]
        length = (dx * dx + dy * dy) ** 0.5 or 1.0
        return (a[0] + dx / length * dist, a[1] + dy / length * dist)

    if not any(radii):
        return "M" + "L".join(f"{n(x)},{n(y)}" for x, y in pts) + "Z"

    out = []
    for i, v in enumerate(pts):
        prev, nxt = pts[i - 1], pts[(i + 1) % len(pts)]
        d = min(radii[i],
                0.5 * ((v[0] - prev[0]) ** 2 + (v[1] - prev[1]) ** 2) ** 0.5,
                0.5 * ((nxt[0] - v[0]) ** 2 + (nxt[1] - v[1]) ** 2) ** 0.5)
        enter = toward(v, prev, d)
        leave = toward(v, nxt, d)
        out.append(f'{"M" if i == 0 else "L"}{n(enter[0])},{n(enter[1])}')
        if d > 0:
            out.append(f"Q{n(v[0])},{n(v[1])} {n(leave[0])},{n(leave[1])}")
    return "".join(out) + "Z"


def seg(a, b) -> str:
    return f"M{n(a[0])},{n(a[1])}L{n(b[0])},{n(b[1])}"


def offset(p, d, dist):
    """沿方向 d（单位向量）从 p 走 dist。"""
    return (p[0] + d[0] * dist, p[1] + d[1] * dist)


def unit(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    length = (dx * dx + dy * dy) ** 0.5 or 1.0
    return (dx / length, dy / length)


def perp(d):
    return (-d[1], d[0])


def layer(path, fill=None, stroke=None, width=STROKE, cap="round", join="round",
          even_odd=False) -> dict:
    d = {"path": path}
    if fill:
        d["fill"] = fill
    if stroke:
        d["stroke"] = stroke
        d["strokeWidth"] = width
        d["strokeLineCap"] = cap
        d["strokeLineJoin"] = join
    if even_odd:
        d["evenOdd"] = True
    return d


def emit(size, viewport, layers, comment: str) -> str:
    head = ["<!--"]
    head += [f"  {line}" for line in comment.strip().splitlines()]
    head += ["-->", "<vector",
             '    xmlns:android="http://schemas.android.com/apk/res/android"',
             f'    android:width="{n(size[0])}dp"',
             f'    android:height="{n(size[1])}dp"',
             f'    android:viewportWidth="{n(viewport[0])}"',
             f'    android:viewportHeight="{n(viewport[1])}">']
    body = []
    for ly in layers:
        attrs = [f'android:pathData="{ly["path"]}"']
        if ly.get("fill"):
            attrs.append(f'android:fillColor="#FF{ly["fill"]}"')
        if ly.get("evenOdd"):
            attrs.append('android:fillType="evenOdd"')
        if ly.get("stroke"):
            attrs.append(f'android:strokeColor="#FF{ly["stroke"]}"')
            attrs.append(f'android:strokeWidth="{n(ly["strokeWidth"])}"')
            attrs.append(f'android:strokeLineCap="{ly["strokeLineCap"]}"')
            attrs.append(f'android:strokeLineJoin="{ly["strokeLineJoin"]}"')
        body.append("    <path")
        body.extend(f"        {a}" for a in attrs[:-1])
        body.append(f"        {attrs[-1]} />")
    return "\n".join(head + body + ["</vector>", ""])


# ---------------------------------------------------------------- 共用底衬

TILE_LAYER = layer(circle(76, 76, 60), fill=TILE)


# ---------------------------------------------------------------- 三个结果页图标

def icon_result_resume():
    """继续编辑：一张稿纸 + 一支铅笔（铅笔压在稿纸右下角上）。"""
    page = round_rect(44, 43, 46, 66, 7)
    # 铅笔：笔尖在左下，笔杆指向右上
    tip = (63, 113)
    back = (107, 69)
    axis = unit(tip, back)
    side = perp(axis)
    half = 6.5
    shoulder = offset(tip, axis, 15)
    return [
        TILE_LAYER,
        layer(page, fill=WHITE, stroke=INK),
        layer(seg((57, 62), (78, 62)), stroke=INK),
        layer(seg((57, 76), (70, 76)), stroke=INK),
        # 笔杆
        layer(poly([offset(shoulder, side, half), offset(back, side, half),
                    offset(back, side, -half), offset(shoulder, side, -half)], 3),
              fill=WHITE, stroke=INK),
        # 笔尖
        layer(poly([tip, offset(shoulder, side, half), offset(shoulder, side, -half)], 2),
              fill=ACCENT, stroke=INK),
    ]


def icon_result_clear():
    """清空：一块斜放的橡皮，擦头是品牌橙，下面留一道擦痕。"""
    center = (77, 76)
    axis = (0.7071, -0.7071)      # 沿橡皮长边，指向右上
    side = perp(axis)
    half_len, half_wid = 29, 15
    # 四个角
    a = offset(offset(center, axis, -half_len), side, -half_wid)   # 左下
    b = offset(offset(center, axis, half_len), side, -half_wid)    # 左上
    c = offset(offset(center, axis, half_len), side, half_wid)     # 右上
    d = offset(offset(center, axis, -half_len), side, half_wid)    # 右下
    cut = 19                        # 擦头长度
    e = offset(a, axis, cut)
    f = offset(d, axis, cut)
    return [
        TILE_LAYER,
        layer(seg((38, 118), (60, 118)), stroke=INK),
        layer(poly([a, d, c, b], 4.5), fill=WHITE, stroke=INK),
        layer(poly([a, d, f, e], 4.5), fill=ACCENT, stroke=INK),
    ]


def icon_result_new():
    """用结果继续运算：一台计算器（显示屏 + 六个按键）。"""
    keys = [(50 + 20 * col, 74 + 20 * row) for row in range(2) for col in range(3)]
    return [
        TILE_LAYER,
        layer(round_rect(46, 38, 60, 76, 12), fill=WHITE, stroke=INK),
        layer(round_rect(56, 48, 40, 18, 5), fill=ACCENT_SOFT, stroke=INK),
    ] + [layer(round_rect(x, y, 12, 12, 3), fill=INK) for x, y in keys]


# ---------------------------------------------------------------- 工具条图标

def icon_back():
    """返回：一个箭头（24dp 网格，圆头线帽）。"""
    return [
        layer("M14.5,5.5L8,12L14.5,18.5", stroke=WHITE, width=2),
        layer(seg((10.5, 12), (19.5, 12)), stroke=WHITE, width=2),
    ]


def icon_share():
    """分享：开口方框 + 右上方箭头（画布沿用布局里的 37x35）。"""
    return [
        layer("M12,9.4H6.4Q3.4,9.4 3.4,12.4V28.6Q3.4,31.6 6.4,31.6H29.6"
              "Q32.6,31.6 32.6,28.6V21.4", stroke=WHITE, width=2.6),
        layer(seg((13.6, 25.4), (30.6, 8.4)), stroke=WHITE, width=2.6),
        layer(poly([(34.8, 4.2), (25.6, 4.2), (34.8, 13.4)], 1.4), fill=WHITE),
    ]


RESULT_COMMENT = """\
  结果页按钮图标，本项目自绘：形状只按「继续编辑 / 清空 / 用结果继续运算」
  这三个含义重新设计，没有描摹任何原版素材。风格与其它自绘素材一致：
  线稿 #53595E（4/152 ≈ 2dp、圆头线帽）、强调色用品牌橙 #FFB560、
  底衬是品牌橙的极浅色圆。尺寸沿用布局里的 76dp。"""


def build() -> dict:
    files = {}
    for name, fn in (("ic_result_resume", icon_result_resume),
                     ("ic_result_clear", icon_result_clear),
                     ("ic_result_new", icon_result_new)):
        files[f"drawable/{name}.xml"] = emit((76, 76), (152, 152), fn(),
                                             RESULT_COMMENT)
    files["drawable/ic_back.xml"] = emit(
        (24, 24), (24, 24), icon_back(),
        """\
  工具条返回箭头，本项目自绘：就是通用的「箭头 + 横杆」两笔，
  白色、2dp 线宽、圆头线帽，和工具条上其它图标一个风格。""")
    files["drawable/ic_share.xml"] = emit(
        (18.5, 17.5), (37, 35), icon_share(),
        """\
  工具条分享图标，本项目自绘：开口方框 + 右上方箭头，白色圆头线条。
  画布沿用布局里原来的 37x35，换掉图形不会挪动工具条排版。""")
    return files


def main() -> int:
    check = "--check" in sys.argv[1:]
    files = build()
    dirty = []
    for rel, text in files.items():
        path = os.path.join(RES, rel.replace("/", os.sep))
        old = None
        if os.path.exists(path):
            with open(path, encoding="utf-8") as fh:
                old = fh.read()
        if old == text:
            continue
        dirty.append(rel)
        if not check:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text)
    if check:
        if dirty:
            print("以下文件与生成结果不一致：")
            for rel in dirty:
                print("   ", rel)
            return 1
        print("全部图标都已是最新。")
        return 0
    print(f"写入 {len(dirty)} 个文件：" if dirty else "没有文件需要更新。")
    for rel in dirty:
        print("   ", rel)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
