#!/usr/bin/env python3
"""生成启动图标（本项目自己的素材，M6）。

标记本身是「一条根号 + 一个等号」，几何和 app/src/main/res/drawable/ic_about_logo_mark.xml
一一对应，改一边就要改另一边。

产出：
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png        圆角方块版
    app/src/main/res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png  圆形版

API 26+ 走的是 mipmap-anydpi-v26/ 里的自适应图标（矢量前景 + 渐变底色），
这里的 PNG 是给 API 21-25 的兜底。

用法：
    python tools/make_launcher_icon.py            # 写入 res
    python tools/make_launcher_icon.py -p out.png # 额外存一张 432px 预览
"""

import argparse
import os

from PIL import Image, ImageDraw

# ---- 素材参数（512 画布坐标系，与 ic_about_logo_mark.xml 相同）----
CANVAS = 512
INK_TOP = (0x3C, 0x4A, 0x70)
INK_BOTTOM = (0x1E, 0x27, 0x40)
ACCENT = (0xFF, 0xB5, 0x60)
WHITE = (0xFF, 0xFF, 0xFF)

RADICAL = [(56, 272), (114, 334), (190, 156), (322, 156)]
RADICAL_WIDTH = 30
EQUALS_BARS = [(346, 208, 448, 240), (346, 280, 448, 312)]

# 标记在整块画布里的缩放与平移：自适应图标的前景用的是同一组数，
# 保证两种图标看起来是同一个大小。0.8 是算出来的：标记横着铺开，缩完之后
# 两端离中心最远的地方约 35.6 格，落在启动器圆形蒙版的 36 格半径以内
# （自适应图标的外圈 18dp 有可能被裁掉，内容不能顶到边）。
MARK_SCALE = 0.80
MARK_OFFSET = (60.0, 60.0)

# 圆角方块：留 16 的边距，半径 100（老式启动图标习惯留一点点透明边）
SHAPE_INSET = 16
SHAPE_RADIUS = 100

SUPERSAMPLE = 4
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def mark_point(x, y):
    return (x * MARK_SCALE + MARK_OFFSET[0], y * MARK_SCALE + MARK_OFFSET[1])


def draw_mark(draw, scale):
    """在已经按 scale 放大的画布上画根号和等号。"""
    pts = [mark_point(x, y) for x, y in RADICAL]
    pts = [(x * scale, y * scale) for x, y in pts]
    width = RADICAL_WIDTH * MARK_SCALE * scale
    draw.line(pts, fill=WHITE, width=round(width), joint="curve")
    # 圆头：两端补一个半径 = 线宽一半 的圆点
    r = width / 2
    for x, y in (pts[0], pts[-1]):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=WHITE)

    for x0, y0, x1, y1 in EQUALS_BARS:
        box = [mark_point(x0, y0), mark_point(x1, y1)]
        box = [
            box[0][0] * scale, box[0][1] * scale,
            box[1][0] * scale, box[1][1] * scale,
        ]
        radius = (box[3] - box[1]) / 2
        draw.rounded_rectangle(box, radius=radius, fill=ACCENT)


def background(size, shape):
    """渐变底：竖向渐变 + 圆角/圆形蒙版。"""
    grad = Image.new("RGB", (1, size), INK_TOP)
    for y in range(size):
        t = y / max(size - 1, 1)
        grad.putpixel(
            (0, y),
            tuple(round(a + (b - a) * t) for a, b in zip(INK_TOP, INK_BOTTOM)),
        )
    grad = grad.resize((size, size), Image.NEAREST)

    mask = Image.new("L", (size, size), 0)
    mdraw = ImageDraw.Draw(mask)
    inset = SHAPE_INSET / CANVAS * size
    box = [inset, inset, size - inset, size - inset]
    if shape == "round":
        mdraw.ellipse(box, fill=255)
    else:
        mdraw.rounded_rectangle(box, radius=SHAPE_RADIUS / CANVAS * size, fill=255)

    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out


def render(shape):
    size = CANVAS * SUPERSAMPLE
    img = background(size, shape)
    draw = ImageDraw.Draw(img)
    draw_mark(draw, SUPERSAMPLE)
    return img


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--preview", "-p",
        help="额外输出一张 432px 的预览图（放大看边缘用）",
    )
    parser.add_argument(
        "--res", default=os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res"),
        help="res 目录，默认取仓库里的 app/src/main/res",
    )
    args = parser.parse_args()

    for shape, name in (("square", "ic_launcher"), ("round", "ic_launcher_round")):
        master = render(shape)
        for density, px in DENSITIES.items():
            out_dir = os.path.join(args.res, f"mipmap-{density}")
            os.makedirs(out_dir, exist_ok=True)
            out_path = os.path.join(out_dir, f"{name}.png")
            master.resize((px, px), Image.LANCZOS).save(out_path)
            print(f"写好了 {os.path.relpath(out_path, args.res)}  {px}x{px}")
        if args.preview and shape == "square":
            master.resize((432, 432), Image.LANCZOS).save(args.preview)
            print(f"预览图 {args.preview}")


if __name__ == "__main__":
    main()
