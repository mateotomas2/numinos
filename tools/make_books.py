#!/usr/bin/env python3
"""Builds app/src/main/assets/books from the public-domain source texts.

Every sentence is cut directly out of its source, so nothing here is retyped
and any phrase that isn't found stops the build.

usage: make_books.py TAO.txt DHAMMAPADA.txt UPANISHADS.txt WEB_VPL.txt OUT_DIR

  TAO         https://www.gutenberg.org/ebooks/216
  DHAMMAPADA  https://www.gutenberg.org/ebooks/2017
  UPANISHADS  https://www.gutenberg.org/ebooks/3283
  WEB_VPL     https://ebible.org/Scriptures/engwebp_vpl.zip (engwebp_vpl.txt)
"""
import json
import re
import sys
from pathlib import Path

# (chapter, first words, last words)
TAO = [
    (1, "The Tao that can be trodden", "unchanging name."),
    (8, "The highest excellence is like", "water."),
    (11, "The thirty spokes unite", "the wheel depends."),
    (16, "When things (in the vegetable world)", "return to its root."),
    (33, "He who knows other men", "he who knows himself is intelligent."),
    (33, "He who overcomes others", "overcomes himself is mighty."),
    (33, "He who is satisfied with his lot", "is rich;"),
    (37, "The Tao in its regular course", "which it does not do."),
    (43, "The softest thing in the world", "overcomes the hardest;"),
    (47, "Without going outside his door", "the Tao of Heaven."),
    (48, "He who devotes himself to learning", "diminish (his doing)."),
    (56, "He who knows (the Tao)", "does not know it."),
    (64, "the journey of a thousand li", "single step."),
    (76, "Man at his birth is supple", "firm and strong."),
    (78, "There is nothing in the world more soft", "than water,"),
    (81, "Sincere words are not fine", "fine words are not sincere."),
]

# (verse, first words, last words)
DHAMMAPADA = [
    (1, "All that we are is the result", "made up of our thoughts."),
    (5, "For hatred does not cease by hatred", "this is an old rule."),
    (21, "Earnestness is the path of immortality", "the path of death."),
    (33, "As a fletcher makes straight", "difficult to hold back."),
    (50, "Not the perversities of others", "should a sage take notice of."),
    (81, "As a solid rock is not shaken", "blame and praise."),
    (103, "If one man conquer in battle", "the greatest of conquerors."),
    (160, "Self is the lord of self", "the lord?"),
    (183, "Not to commit any sin", "(all) the Awakened."),
    (197, "Let us live happily then", "those who hate us!"),
    (223, "Let a man overcome anger by love", "by truth!"),
    (276, "You yourself must make an effort", "only preachers."),
    (348, "Give up what is before", "birth and decay."),
]

# (reference, first words of a verse paragraph)
UPANISHADS = [
    ("Isa, verse 1", "All this, whatsoever exists in the universe"),
    ("Isa, verse 6", "He who sees all beings in the Self and the Self"),
    ("Isa, verse 7", "He who perceives all beings as the Self for him"),
    ("Katha, part 2, verse 20", "The Self is subtler than the subtle, greater than the great"),
    ("Katha, part 3, verse 3", "Know the Atman (Self) as the lord of the chariot"),
    ("Katha, part 3, verse 14", "Arise!  Awake!  Having reached the Great Ones"),
    ("Katha, part 4, verse 1", "The Self-existent created the senses out-going"),
    ("Katha, part 4, verse 10", "What is here (in the visible world), that is there"),
    ("Katha, part 5, verse 8", "The Being who remains awake while all sleep"),
    ("Kena, part 1, verse 1", "By whom commanded and directed does the mind"),
    ("Kena, part 2, verse 5", "If one knows It here, that is Truth"),
]

BIBLE = {
    "PSA 23:1": "Psalm 23:1", "PSA 34:18": "Psalm 34:18", "PSA 46:10": "Psalm 46:10",
    "PRO 3:5": "Proverbs 3:5", "PRO 4:23": "Proverbs 4:23", "ECC 3:1": "Ecclesiastes 3:1",
    "ISA 40:31": "Isaiah 40:31", "MIC 6:8": "Micah 6:8", "MAT 5:8": "Matthew 5:8",
    "MAT 6:34": "Matthew 6:34", "MAT 7:7": "Matthew 7:7", "MAT 11:28": "Matthew 11:28",
    "LUK 17:21": "Luke 17:21", "JOH 8:32": "John 8:32", "JOH 14:27": "John 14:27",
    "ROM 12:2": "Romans 12:2", "1CO 13:13": "1 Corinthians 13:13", "2CO 5:7": "2 Corinthians 5:7",
    "PHI 4:7": "Philippians 4:7", "JAM 1:22": "James 1:22", "1JO 4:8": "1 John 4:8",
}


# Where a verse's first words are a heading or belong to the sentence before it.
BIBLE_START_AT = {"PSA 23:1": "The LORD", "LUK 17:21": "God’s Kingdom"}


def clean(text):
    return re.sub(r"\s+", " ", text).strip()


def read(path):
    return Path(path).read_text(encoding="utf-8").replace("\r\n", "\n")


def phrase(words):
    """Matches words across the source's line breaks and double spaces."""
    return re.compile(r"\s+".join(re.escape(word) for word in words.split()))


def span(source, start, end, what):
    first = phrase(start).search(source)
    if not first:
        sys.exit(f"{what}: not found: {start}")
    last = phrase(end).search(source, first.start())
    if not last or last.start() - first.start() > 700:
        sys.exit(f"{what}: no ending near: {start} ... {end}")
    return first.start(), clean(source[first.start():last.end()]).rstrip(",;")


def roman(numeral):
    values = {"I": 1, "V": 5, "X": 10, "L": 50}
    total = 0
    for letter, following in zip(numeral, numeral[1:] + "I"):
        total += -values[letter] if values[letter] < values[following] else values[letter]
    return total


def tao(path):
    source = read(path)
    # Verses inside a chapter are numbered too: a chapter normally opens "N. 1."
    # and single-verse chapters (always past 5) open "N. Text". Used only to
    # confirm that each chapter number claimed above is right.
    starts = {}
    position = 0
    for chapter in range(1, 82):
        found = re.compile(rf"^(?:Ch\. )?{chapter}\.\s+1\.", re.M).search(source, position)
        single = re.compile(rf"^{chapter}\.\s+[A-Z(]", re.M).search(source, position)
        if chapter > 5 and single and (not found or single.start() < found.start()):
            found = single
        if not found:
            print(f"check Tao: chapter {chapter} heading not found")
            continue
        starts[chapter] = found.start()
        position = found.end()
    out = []
    for chapter, start, end in TAO:
        begin, text = span(source, start, end, "Tao")
        actual = max((c for c, at in starts.items() if at <= begin), default=None)
        if actual != chapter:
            print(f"check Tao chapter: {chapter} vs {actual}: {start}")
        out.append({"ref": f"Chapter {chapter}", "text": text[0].upper() + text[1:]})
    # Two sentences from one chapter need distinct references.
    seen = {}
    for item in out:
        seen[item["ref"]] = seen.get(item["ref"], 0) + 1
        if seen[item["ref"]] > 1:
            item["ref"] += f" ({seen[item['ref']]})"
    return out


def dhammapada(path):
    source = read(path)
    out = []
    for verse, start, end in DHAMMAPADA:
        begin, text = span(source, f"{verse}. {start}", end, "Dhammapada")
        out.append({"ref": f"Verse {verse}", "text": text.split(" ", 1)[1]})
    return out


def upanishads(path):
    source = read(path)
    out = []
    for ref, start in UPANISHADS:
        found = phrase(start).search(source)
        if not found:
            sys.exit(f"Upanishads: not found: {start}")
        begin = found.start()
        stop = source.find("\n\n", begin)
        # The source prints each verse number as a Roman numeral above the verse.
        numeral = re.findall(r"^([IVXL]+)$", source[:begin], re.M)[-1]
        if roman(numeral) != int(ref.rsplit(" ", 1)[1]):
            sys.exit(f"Upanishads: {ref} is verse {numeral} in the source")
        out.append({"ref": ref, "text": clean(source[begin:stop])})
    return out


def bible(path):
    verses = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        parts = line.split(" ", 2)
        if len(parts) == 3:
            verses[f"{parts[0]} {parts[1]}"] = parts[2]
    out = []
    for code, name in BIBLE.items():
        text = clean(verses[code])
        if code in BIBLE_START_AT:
            text = text[text.index(BIBLE_START_AT[code]):]
        # A lone verse shouldn't carry its neighbours' quotation marks or punctuation.
        text = text.strip("“”").rstrip(":")
        if text[-1] not in ".!?":
            text += "."
        out.append({"ref": name, "text": text[0].upper() + text[1:]})
    return out


def write(out_dir, file, book_id, title, credit, sentences):
    if len({s["ref"] for s in sentences}) != len(sentences):
        sys.exit(f"{title}: duplicate references")
    book = {"id": book_id, "title": title, "credit": credit, "sentences": sentences}
    Path(out_dir, file).write_text(json.dumps(book, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{title}: {len(sentences)}")


if __name__ == "__main__":
    tao_path, dham_path, upa_path, web_path, out_dir = sys.argv[1:6]
    write(out_dir, "tao.json", "tao", "Tao Te Ching", "translated by James Legge (1891), public domain", tao(tao_path))
    write(out_dir, "upanishads.json", "upanishads", "Upanishads", "translated by Swami Paramananda (1919), public domain", upanishads(upa_path))
    write(out_dir, "dhammapada.json", "dhammapada", "Dhammapada", "translated by F. Max Müller (1881), public domain", dhammapada(dham_path))
    write(out_dir, "bible.json", "bible", "Bible", "World English Bible, public domain", bible(web_path))
