"""生成应用图标与 TV 横幅。

用法（Windows）：
  python scripts/gen-icons.py <源图.png>

源图要求：正方形、人物居中、背景为纯色（当前是红色 #A7321A）。
产出：
  app/src/main/res/mipmap-*/ic_launcher.png          圆角矩形图标（旧版启动器）
  app/src/main/res/mipmap-*/ic_launcher_round.png    圆形图标
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml 自适应图标（Android 8+）
  app/src/main/res/mipmap-*/ic_launcher_foreground.png
  app/src/main/res/drawable-xhdpi/tv_banner.png       TV 启动器横幅 320×180dp（640×360px）
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"

# 与源图背景一致：自适应图标底层用它，前景边缘才不会露出一圈异色
BG = (167, 50, 26)

# 旧版图标各密度边长（px）
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 自适应图标前景边长（108dp 的等效像素）
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

BANNER_W, BANNER_H = 640, 360  # 320×180dp @xhdpi

# 自适应图标前景缩放系数：1.0 会让圆形安全区把头顶的蝴蝶结裁掉，
# 缩到 0.82 再把四周补成同色红（与 background 同色，看不出接缝）
FOREGROUND_SCALE = 0.82
# 圆形旧图标同理，留一点边
ROUND_SCALE = 0.88


def square_icon(src: Image.Image, size: int) -> Image.Image:
    return src.resize((size, size), Image.LANCZOS)


def round_icon(src: Image.Image, size: int) -> Image.Image:
    """圆形图标：先按 ROUND_SCALE 缩小、贴在同色底上，再套圆形蒙版。"""
    base = Image.new("RGB", (size, size), BG)
    inner = int(size * ROUND_SCALE)
    off = (size - inner) // 2
    base.paste(src.resize((inner, inner), Image.LANCZOS), (off, off))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out.paste(base, (0, 0), mask)
    return out


def foreground(src: Image.Image, size: int) -> Image.Image:
    """自适应图标前景：透明底 + 缩小的画面，四周留给 background 层。
    缩放后四周是同色红，与背景层无缝，所以看起来是「人物小一点、蝴蝶结完整」。"""
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    inner = int(size * FOREGROUND_SCALE)
    off = (size - inner) // 2
    out.paste(src.resize((inner, inner), Image.LANCZOS).convert("RGBA"), (off, off))
    return out


def banner(src: Image.Image) -> Image.Image:
    """左半边放人物、右半边放名字：电视启动器的横幅是横条，
    纯头像会被拉成怪比例，带名字才一眼认得出是谁。"""
    out = Image.new("RGB", (BANNER_W, BANNER_H), BG)
    # 人物：从源图裁一个偏上的正方形（头肩），贴到左侧，占 46% 宽度
    face_w = int(BANNER_W * 0.46)
    crop_side = int(src.width * 0.86)
    left = (src.width - crop_side) // 2
    top = int(src.height * 0.02)
    face = src.crop((left, top, left + crop_side, top + crop_side))
    face = face.resize((face_w, BANNER_H), Image.LANCZOS)
    out.paste(face, (0, 0))

    # 右侧文字：底部纯色块 + 12px 渐变过渡，字才压得住背景
    text_x = face_w + 30
    pad = 30
    draw = ImageDraw.Draw(out, "RGBA")
    draw.rectangle((face_w, 0, BANNER_W, BANNER_H), fill=(BG[0], BG[1], BG[2], 240))
    fade = 14
    for i in range(fade):
        alpha = int(240 * i / fade)
        draw.line((face_w - fade + i, 0, face_w - fade + i, BANNER_H),
                  fill=(BG[0], BG[1], BG[2], alpha))

    font_paths = [
        r"C:\Windows\Fonts\msyhbd.ttc",
        r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
    ]
    font_file = None
    for fp in font_paths:
        if Path(fp).exists():
            font_file = fp
            break

    title = "磊毛TV"
    sub = "直播 · 影视"
    avail = BANNER_W - text_x - pad
    # 标题字号自动收缩到放得下：写死字号一旦换文案就会溢出（第一版就是这么被裁掉的）
    size = 96
    while size > 24:
        font = ImageFont.truetype(font_file, size) if font_file else ImageFont.load_default()
        if draw.textbbox((0, 0), title, font=font)[2] <= avail:
            break
        size -= 4
    title_font = ImageFont.truetype(font_file, size) if font_file else ImageFont.load_default()
    sub_font = ImageFont.truetype(font_file, max(24, size // 2)) if font_file else title_font

    tbox = draw.textbbox((0, 0), title, font=title_font)
    sbox = draw.textbbox((0, 0), sub, font=sub_font)
    gap = 18
    total_h = (tbox[3] - tbox[1]) + gap + (sbox[3] - sbox[1])
    y = (BANNER_H - total_h) // 2
    draw.text((text_x, y - tbox[1]), title, font=title_font, fill=(255, 240, 232))
    draw.text((text_x + 4, y + (tbox[3] - tbox[1]) + gap - sbox[1]), sub,
              font=sub_font, fill=(255, 214, 196))
    return out


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(1)
    src = Image.open(sys.argv[1]).convert("RGB")
    if src.width != src.height:
        side = min(src.size)
        src = src.crop(((src.width - side) // 2, (src.height - side) // 2,
                        (src.width + side) // 2, (src.height + side) // 2))

    for density, size in LEGACY.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        square_icon(src, size).save(d / "ic_launcher.png")
        round_icon(src, size).save(d / "ic_launcher_round.png")
        foreground(src, FOREGROUND[density]).save(d / "ic_launcher_foreground.png")
        print(f"mipmap-{density}: {size}px / foreground {FOREGROUND[density]}px")

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
""",
        encoding="utf-8",
    )
    (anydpi / "ic_launcher_round.xml").write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
""",
        encoding="utf-8",
    )

    values = RES / "values"
    values.mkdir(parents=True, exist_ok=True)
    # 单独一个文件名：colors.xml 是别人在用的（tv_background），别覆盖
    (values / "ic_launcher_background.xml").write_text(
        """<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标的底色：与图标源图背景同色，前景边缘不会露出一圈异色 -->
<resources>
    <color name="ic_launcher_background">#A7321A</color>
</resources>
""",
        encoding="utf-8",
    )

    xhdpi = RES / "drawable-xhdpi"
    xhdpi.mkdir(parents=True, exist_ok=True)
    b = banner(src)
    b.save(xhdpi / "tv_banner.png")
    print(f"tv_banner: {b.size}")


if __name__ == "__main__":
    main()
