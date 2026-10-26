"""生成「拼音 → 候选词」表（输入法候选，SET-11）。

数据来源（都是 MIT，可进 Apache-2.0 工程；RULES §4）：
  - 词组拼音：mozillazg/phrase-pinyin-data 的 `pinyin.txt`（`词: pīn yīn`）
  - 词频：fxsjy/jieba 的 `dict.txt`（`词 频次 词性`）

为什么要两份：词组拼音给出**正确的多音字读音**（银行 yinhang 而不是 yinxing），
jieba 给出**词频**（决定候选排序，「我」必须排在「窝」前面）。缺一份都不行。

单字候选来自 `app/src/main/assets/pinyin_table.txt`（同一个生成器的产物，一个字可有多个读音）：
词组库里没有单字，而用户打 `wo` 最先要的是「我」—— 没有单字这一步，输入法等于不会打单字。

产出：`app/src/main/assets/pinyin_ime.txt`
  一行一个拼音：`拼音<TAB>候选1,候选2,...`（按词频降序，最多 8 个），例如
  `wo	我,窝,握,卧,沃,喔,蜗,斡`
  拼音是**无声调、无空格**的连写（`nihao`），与用户在小键盘上敲的完全一致。

只收：
  - 长度 1~4 的词（再长的词在电视上没人会打全拼）；
  - 全汉字、且在两份数据里都出现的词（只有词频没有拼音，或只有拼音没有词频的都不要）；
  - 每个拼音最多 8 个候选（电视上候选行最多也就放得下这些）。

用法：
    python scripts/gen-pinyin-ime.py --phrase /tmp/phrase_pinyin.txt --freq /tmp/jieba_dict.txt
"""

import argparse
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "pinyin_ime.txt"

PHRASE_URL = "https://raw.githubusercontent.com/mozillazg/phrase-pinyin-data/master/pinyin.txt"
FREQ_URL = "https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt"

# 每个拼音留几个候选：电视上候选行只放得下这些，多了也只是增加体积
MAX_CANDIDATES = 6
# 全表最多留多少个词：按词频全局取前 N，再按拼音分组。
# 不做这一步的话，38k 个拼音 × 6 候选 ≈ 800KB，其中大半是一辈子打不到的生僻词
MAX_WORDS = 26000
# 词长上限：4 字以上的词全拼太长，用户不会打
MAX_WORD_LEN = 4
# 词频下限：过滤掉 jieba 里那些 freq=0 的脏词
MIN_FREQ = 1

CJK = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff]+$")


def tone_less(reading: str) -> str:
    decomposed = unicodedata.normalize("NFD", reading.strip())
    bare = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    return "".join(ch.lower() for ch in bare if "a" <= ch.lower() <= "z")


def load_phrase_pinyin(path: Path) -> dict[str, str]:
    """`词: pīn yīn` → {词: "pinyin"}（无声调连写）"""
    out: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("#") or ":" not in raw:
            continue
        word, _, reading = raw.partition(":")
        word = word.strip()
        if not word or not CJK.match(word):
            continue
        syllables = [tone_less(part) for part in reading.split()]
        syllables = [s for s in syllables if s]
        if len(syllables) != len(word):
            # 拼音数与字数对不上：说明这条数据本身有问题，丢掉比猜更安全
            continue
        out[word] = "".join(syllables)
    return out


def load_frequency(path: Path) -> dict[str, int]:
    out: dict[str, int] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        parts = raw.split()
        if len(parts) < 2:
            continue
        word, freq = parts[0], parts[1]
        if not CJK.match(word) or not (1 <= len(word) <= MAX_WORD_LEN):
            continue
        try:
            value = int(freq)
        except ValueError:
            continue
        if value >= MIN_FREQ:
            out[word] = value
    return out


def load_single_chars(char_table: Path, freq_of: dict[str, int]) -> list[tuple[str, str, int]]:
    """单字候选：`字r1,r2` → [(r1, 字, freq), (r2, 字, freq)]。

    词组库里没有单字，而用户打 `wo` 最先要的就是「我」—— 少了这一步，输入法不会打单字。
    多音字按每个读音各挂一次（`重` 既在 zhong 也在 chong）。
    """
    out: list[tuple[str, str, int]] = []
    if not char_table.exists():
        return out
    for raw in char_table.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if len(line) < 2:
            continue
        ch = line[0]
        freq = freq_of.get(ch)
        if freq is None:
            continue
        for reading in line[1:].split(","):
            reading = reading.strip()
            if reading:
                out.append((reading, ch, freq))
    return out


def build(phrase_path: Path, freq_path: Path, char_table: Path) -> tuple[str, int, int]:
    pinyin_of = load_phrase_pinyin(phrase_path)
    freq_of = load_frequency(freq_path)

    # (拼音, 词, 词频) 三元组，单字与词组一视同仁，最后统一按词频取前 N
    entries: list[tuple[str, str, int]] = list(load_single_chars(char_table, freq_of))
    for word, pinyin in pinyin_of.items():
        freq = freq_of.get(word)
        if freq is None:
            # 有拼音没词频 → 排序无从谈起；有词频没拼音 → 不知道多音字怎么读。两边都有才收
            continue
        entries.append((pinyin, word, freq))

    # 全局按词频降序取前 N：生僻词在这一步被筛掉，而不是在分组时才砍（分组时砍会留下
    # 「某个拼音只剩一个生僻候选」这种更糟的结果）
    entries.sort(key=lambda e: (-e[2], len(e[1]), e[1]))
    entries = entries[:MAX_WORDS]

    buckets: dict[str, list[str]] = defaultdict(list)
    for pinyin, word, _ in entries:
        if len(buckets[pinyin]) < MAX_CANDIDATES:
            buckets[pinyin].append(word)

    lines = [f"{pinyin}\t{','.join(words)}\n" for pinyin, words in sorted(buckets.items())]
    total_words = sum(len(v) for v in buckets.values())
    return "".join(lines), len(lines), total_words


def main() -> int:
    parser = argparse.ArgumentParser(description="生成拼音输入法候选表")
    parser.add_argument("--phrase", type=Path, help="phrase-pinyin-data 的 pinyin.txt")
    parser.add_argument("--freq", type=Path, help="jieba 的 dict.txt")
    args = parser.parse_args()

    phrase_path = args.phrase
    freq_path = args.freq
    if phrase_path is None or freq_path is None:
        import urllib.request

        if phrase_path is None:
            phrase_path = Path("/tmp/phrase_pinyin.txt")
            urllib.request.urlretrieve(PHRASE_URL, phrase_path)
        if freq_path is None:
            freq_path = Path("/tmp/jieba_dict.txt")
            urllib.request.urlretrieve(FREQ_URL, freq_path)

    char_table = ROOT / "app" / "src" / "main" / "assets" / "pinyin_table.txt"
    text, pinyin_count, word_count = build(phrase_path, freq_path, char_table)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"写出 {OUT.relative_to(ROOT)}：{pinyin_count} 个拼音、{word_count} 个候选词，{len(text.encode()) // 1024}KB")
    for probe in ("wo", "nihao", "zhongguo", "hunan", "yinhang"):
        line = next((l for l in text.splitlines() if l.startswith(probe + "\t")), "(无)")
        print(f"  {probe:10} {line}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
