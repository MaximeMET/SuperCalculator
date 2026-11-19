#!/usr/bin/env python3
"""生成启动图标 PNG 兜底（本项目自己的素材，M9）。

标记由白色圆头加号和品牌橙上升曲线组成，表达算术与函数计算。
几何和两个 VectorDrawable 一一对应。

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

# ---- 素材参数（512 画布坐标系，与两个 VectorDrawable 相同）----
CANVAS = 512
INK_TOP = (0x3C, 0x4A, 0x70)
INK_BOTTOM = (0x1E, 0x27, 0x40)
ACCENT = (0xFF, 0xB5, 0x60)
WHITE = (0xFF, 0xFF, 0xFF)

AXIS_WIDTH = 30
CURVE_WIDTH = 28
AXIS_VERTICAL = ((256, 108), (256, 380))
AXIS_HORIZONTAL = ((122, 244), (390, 244))
CURVE = ((140, 364), (170, 302), (350, 180), (374, 120))

# 加号和曲线整体放大，并在自适应图标安全区内居中。
MARK_SCALE = 0.98
MARK_OFFSET = (5.12, 4.08)

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


def cubic_points(curve, segments=256):
    """把三次贝塞尔曲线采样成 Pillow 可绘制的折线。"""
    p0, p1, p2, p3 = curve
    points = []
    for i in range(segments + 1):
        t = i / segments
        u = 1 - t
        points.append((
            u ** 3 * p0[0] + 3 * u ** 2 * t * p1[0] + 3 * u * t ** 2 * p2[0] + t ** 3 * p3[0],
            u ** 3 * p0[1] + 3 * u ** 2 * t * p1[1] + 3 * u * t ** 2 * p2[1] + t ** 3 * p3[1],
        ))
    return points


def scaled_point(point, scale):
    x, y = mark_point(*point)
    return (x * scale, y * scale)


def draw_round_line(draw, points, width, scale, color=WHITE):
    points = [scaled_point(point, scale) for point in points]
    stroke = round(width * MARK_SCALE * scale)
    draw.line(points, fill=color, width=stroke)
    radius = stroke / 2
    for x, y in (points[0], points[-1]):
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=color)


def draw_round_curve(draw, curve, width, scale, color):
    radius = width * MARK_SCALE * scale / 2
    for x, y in (scaled_point(point, scale) for point in cubic_points(curve, segments=512)):
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=color)


def draw_mark(draw, scale):
    """在画布上画圆头加号和一条上升曲线。"""
    draw_round_line(draw, AXIS_VERTICAL, AXIS_WIDTH, scale)
    draw_round_line(draw, AXIS_HORIZONTAL, AXIS_WIDTH, scale)
    draw_round_curve(draw, CURVE, CURVE_WIDTH, scale, ACCENT)


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
