"""生成「汉字 → 拼音首字母」紧凑表（供频道名拼音简写搜索用）。

数据来源：mozillazg/pinyin-data（MIT 许可）的 `pinyin.txt`
  https://github.com/mozillazg/pinyin-data
格式：`U+4E00: yī,chā  # 一`；同一条里多个读音用逗号分隔，这里只取**第一个**（最常用）。

产出：`app/src/main/assets/pinyin_table.txt`
  一行一个字：`字拼音`（无分隔符，汉字后面直接跟无声调小写拼音），例如
  `一yi`、`湖hu`、`南nan`。首字母由运行时取拼音首字符得到，所以**一份表同时支持
  全拼（hunanweishi）与简写（hnws）**两种输入 — 这是 26 键键盘能打中文拼音的前提。
  只收 BMP 内的汉字，约 2.7 万条 ≈ 240KB（APK 压缩后几十 KB）。

用法：
    python scripts/gen-pinyin.py                    # 自动下载 pinyin.txt
    python scripts/gen-pinyin.py --src /tmp/pinyin.txt
"""

import argparse
import re
import sys
import unicodedata
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "pinyin_table.txt"
URL = "https://raw.githubusercontent.com/mozillazg/pinyin-data/master/pinyin.txt"

LINE = re.compile(r"^U\+([0-9A-Fa-f]{4,6}):\s*([^#]+)")


def tone_less(reading: str) -> str:
    """`zhōng` → `zhong`：去掉声调（组合附加符号）只留字母。"""
    decomposed = unicodedata.normalize("NFD", reading.strip())
    bare = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    letters_only = "".join(ch.lower() for ch in bare if "a" <= ch.lower() <= "z")
    return letters_only


def build(src_path: Path) -> tuple[str, int]:
    pairs: list[str] = []
    seen: set[str] = set()
    for raw in src_path.read_text(encoding="utf-8").splitlines():
        m = LINE.match(raw)
        if not m:
            continue
        code = int(m.group(1), 16)
        if code > 0xFFFF:          # 只收 BMP：运行时按「两字符一组」解析
            continue
        readings = m.group(2).split(",")
        syllable = tone_less(readings[0])
        if not syllable:
            continue
        ch = chr(code)
        if ch in seen:
            continue
        seen.add(ch)
        pairs.append(ch + syllable + "\n")
    return "".join(pairs), len(pairs)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", help="本地 pinyin.txt 路径；不给就联网下载")
    args = ap.parse_args()

    if args.src:
        src = Path(args.src)
    else:
        tmp = Path("/tmp/pinyin_data.txt") if Path("/tmp").exists() else ROOT / "build" / "pinyin_data.txt"
        print(f"下载 {URL} …")
        urllib.request.urlretrieve(URL, tmp)
        src = tmp

    text, count = build(src)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    size = OUT.stat().st_size
    print(f"写出 {OUT.relative_to(ROOT)}：{count} 个汉字，{size / 1024:.0f}KB")
    print("样例：", text[:60].replace("\n", " "))


if __name__ == "__main__":
    sys.exit(main())
