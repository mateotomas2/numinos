#!/usr/bin/env python3
"""Builds app/src/main/assets/books from the public-domain source texts.

Each book becomes a folder holding book.json (its contents and marked passages)
and one file per chapter. Nothing is retyped: marked passages are cut out of the
parsed chapters, and any passage not found where it's claimed to be stops the build.

usage: make_books.py TAO.txt DHAMMAPADA.txt UPANISHADS.txt WEB_VPL.txt OUT_DIR

  TAO         https://www.gutenberg.org/ebooks/216
  DHAMMAPADA  https://www.gutenberg.org/ebooks/2017
  UPANISHADS  https://www.gutenberg.org/ebooks/3283
  WEB_VPL     https://ebible.org/Scriptures/engwebp_vpl.zip (engwebp_vpl.txt)
"""
import json
import re
import shutil
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


BIBLE_BOOKS = {
    "GEN": "Genesis", "EXO": "Exodus", "LEV": "Leviticus", "NUM": "Numbers", "DEU": "Deuteronomy",
    "JOS": "Joshua", "JDG": "Judges", "RUT": "Ruth", "1SA": "1 Samuel", "2SA": "2 Samuel",
    "1KI": "1 Kings", "2KI": "2 Kings", "1CH": "1 Chronicles", "2CH": "2 Chronicles", "EZR": "Ezra",
    "NEH": "Nehemiah", "EST": "Esther", "JOB": "Job", "PSA": "Psalm", "PRO": "Proverbs",
    "ECC": "Ecclesiastes", "SOL": "Song of Solomon", "ISA": "Isaiah", "JER": "Jeremiah",
    "LAM": "Lamentations", "EZE": "Ezekiel", "DAN": "Daniel", "HOS": "Hosea", "JOE": "Joel",
    "AMO": "Amos", "OBA": "Obadiah", "JON": "Jonah", "MIC": "Micah", "NAH": "Nahum", "HAB": "Habakkuk",
    "ZEP": "Zephaniah", "HAG": "Haggai", "ZEC": "Zechariah", "MAL": "Malachi", "MAT": "Matthew",
    "MAR": "Mark", "LUK": "Luke", "JOH": "John", "ACT": "Acts", "ROM": "Romans", "1CO": "1 Corinthians",
    "2CO": "2 Corinthians", "GAL": "Galatians", "EPH": "Ephesians", "PHI": "Philippians",
    "COL": "Colossians", "1TH": "1 Thessalonians", "2TH": "2 Thessalonians", "1TI": "1 Timothy",
    "2TI": "2 Timothy", "TIT": "Titus", "PHM": "Philemon", "HEB": "Hebrews", "JAM": "James",
    "1PE": "1 Peter", "2PE": "2 Peter", "1JO": "1 John", "2JO": "2 John", "3JO": "3 John",
    "JUD": "Jude", "REV": "Revelation",
}

# Where a verse's first words are a heading or belong to the sentence before it.
BIBLE_START_AT = {"PSA 23:1": "The LORD", "LUK 17:21": "God’s Kingdom"}

PART_NUMBERS = {"first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5, "sixth": 6}


def clean(text):
    return re.sub(r"\s+", " ", text).strip()


def read(path):
    source = Path(path).read_text(encoding="utf-8-sig").replace("\r\n", "\n")
    return source.split("*** END OF THE PROJECT GUTENBERG")[0]


def phrase(words):
    """Matches words across the source's line breaks and double spaces."""
    return re.compile(r"\s+".join(re.escape(word) for word in words.split()))


def roman(numeral):
    values = {"I": 1, "V": 5, "X": 10, "L": 50}
    total = 0
    for letter, following in zip(numeral, numeral[1:] + "I"):
        total += -values[letter] if values[letter] < values[following] else values[letter]
    return total


def fail(message):
    sys.exit(f"make_books: {message}")


def tidy(raw):
    """Joins wrapped prose but keeps the line breaks of indented poetry."""
    out = []
    for paragraph in re.split(r"\n\s*\n", raw):
        lines = [line for line in paragraph.split("\n") if line.strip()]
        if not lines:
            continue
        if any(line.startswith("  ") for line in lines):
            out.append("\n".join(clean(line) for line in lines))
        else:
            out.append(clean(paragraph))
    return "\n\n".join(out)


def numbered_passages(body, first):
    """Splits text at the line-start numbers first, first + 1, ... and returns (passages, next number)."""
    passages = []
    expected = first
    position = 0
    label = ""
    while True:
        # Some verses share one paragraph, printed as "58, 59."
        found = re.compile(rf"^{expected}(?:, (\d+))?\.(?:\s|$)", re.M).search(body, position)
        text = tidy(body[position:found.start() if found else len(body)])
        if text:
            passages.append({"l": label, "t": text})
        if not found:
            return passages, expected
        last = int(found.group(1) or expected)
        label = f"{expected}, {last}" if found.group(1) else str(expected)
        position = found.end()
        expected = last + 1


def excerpt(text, start, end, what):
    first = phrase(start).search(text)
    if not first:
        return None
    last = phrase(end).search(text, first.start())
    if not last:
        fail(f"{what}: no ending near: {start} ... {end}")
    return first.start(), last.end()


def as_sentence(text):
    text = clean(text).rstrip(",;")
    return text[0].upper() + text[1:]


def tao(path):
    source = re.sub(r"^PART .*$", "", read(path), flags=re.M)
    source = source[source.index("Ch. 1. 1."):]
    # A chapter normally opens "N. 1." and single-verse chapters (always past 5) "N. Text".
    starts = []
    position = 0
    for chapter in range(1, 82):
        found = re.compile(rf"^(?:Ch\. )?{chapter}\.\s+1\.", re.M).search(source, position)
        single = re.compile(rf"^{chapter}\.\s+[A-Z(]", re.M).search(source, position)
        if chapter > 5 and single and (not found or single.start() < found.start()):
            found = single
        if not found:
            fail(f"Tao: chapter {chapter} heading not found")
        starts.append(found)
        position = found.end()
    chapters = []
    for index, found in enumerate(starts):
        heading_end = re.compile(r"(?:Ch\. )?\d+\.\s*").match(source, found.start()).end()
        stop = starts[index + 1].start() if index < 80 else len(source)
        passages, _ = numbered_passages(source[heading_end:stop], 1)
        # A chapter with verses starts with an empty slot before "1."; drop nothing else.
        chapters.append({"title": f"Chapter {index + 1}", "passages": passages})
    marks = []
    for chapter, start, end in TAO:
        for number, passage in enumerate(chapters[chapter - 1]["passages"]):
            span = excerpt(passage["t"], start, end, "Tao")
            if span:
                text = as_sentence(passage["t"][span[0]:span[1]])
                marks.append({"ref": f"Chapter {chapter}", "text": text, "chapter": chapter - 1,
                              "passage": number, "start": span[0], "end": span[1]})
                break
        else:
            fail(f"Tao: not in chapter {chapter}: {start}")
    return chapters, marks


def dhammapada(path):
    source = read(path)
    headings = list(re.finditer(r"^Chapter ([IVXL]+)\. (.+)$", source, re.M))
    chapters = []
    expected = 1
    for index, heading in enumerate(headings):
        stop = headings[index + 1].start() if index + 1 < len(headings) else len(source)
        passages, expected = numbered_passages(source[heading.end():stop], expected)
        chapters.append({"title": f"Chapter {roman(heading.group(1))}: {clean(heading.group(2))}", "passages": passages})
    if expected != 424:
        fail(f"Dhammapada: stopped at verse {expected}")
    marks = []
    for verse, start, end in DHAMMAPADA:
        for c, chapter in enumerate(chapters):
            for p, passage in enumerate(chapter["passages"]):
                if passage["l"] == str(verse):
                    span = excerpt(passage["t"], start, end, "Dhammapada") or fail(f"Dhammapada: not in verse {verse}: {start}")
                    marks.append({"ref": f"Verse {verse}", "text": as_sentence(passage["t"][span[0]:span[1]]),
                                  "chapter": c, "passage": p, "start": span[0], "end": span[1]})
    if len(marks) != len(DHAMMAPADA):
        fail("Dhammapada: a marked verse is missing")
    return chapters, marks


def upanishad_passages(section, name, chapters):
    """In this source a verse is the paragraph right under its Roman numeral, then commentary follows."""
    current = None
    expect_verse = False
    in_chant = False
    label = ""

    def open_chapter(title):
        chapters.append({"title": title, "passages": []})
        return chapters[-1]["passages"]

    has_parts = re.search(r"^\s*Part first\s*$", section, re.M | re.I)
    if not has_parts:
        current = open_chapter(f"{name} Upanishad")
    pending = []
    for raw in re.split(r"\n\s*\n", section):
        paragraph = clean(raw)
        if not paragraph or paragraph.endswith("-Upanishad") or paragraph == name.upper() + "-UPANISHAD":
            continue
        part = re.fullmatch(r"Part (\w+)", paragraph, re.I)
        if part and part.group(1).lower() in PART_NUMBERS:
            current = open_chapter(f"{name} Upanishad, Part {PART_NUMBERS[part.group(1).lower()]}")
            current[:0] = pending
            pending = []
            continue
        target = current if current is not None else pending
        if paragraph.lower() == "peace chant":
            in_chant = True
            continue
        if in_chant:
            target.append({"l": "Peace Chant", "t": paragraph})
            in_chant = not paragraph.upper().startswith("OM!") or "PEACE" not in paragraph.upper()
            continue
        if re.fullmatch(r"[IVXL]+", paragraph):
            label = str(roman(paragraph))
            expect_verse = True
            continue
        if expect_verse:
            target.append({"l": label, "t": paragraph})
            expect_verse = False
        else:
            target.append({"l": "", "t": paragraph, "c": 1})


def upanishads(path):
    source = read(path)
    chapters = []
    isa = source.index("Isa-Upanishad", source.index("Isa-Upanishad", source.index("Introduction", 3000)) + 20)
    isa_end = source.index("Here ends this Upanishad", isa)
    upanishad_passages(source[isa:isa_end], "Isa", chapters)
    katha_note = source.index("Katha-Upanishad", isa_end)
    katha = source.index("Katha-Upanishad", katha_note + 20)
    katha_end = source.index("Here ends this Upanishad", katha)
    upanishad_passages(source[katha:katha_end], "Katha", chapters)
    kena = source.index("KENA-UPANISHAD", katha_end)
    upanishad_passages(source[kena:source.index("Here ends this Upanishad", kena)], "Kena", chapters)
    marks = []
    for ref, start in UPANISHADS:
        name, *middle, verse_label = ref.split(", ")
        title = f"{name} Upanishad" + (f", Part {middle[0].split()[1]}" if middle else "")
        verse = verse_label.split()[1]
        index = next((i for i, c in enumerate(chapters) if c["title"] == title), None)
        if index is None:
            fail(f"Upanishads: no chapter {title}")
        for p, passage in enumerate(chapters[index]["passages"]):
            if passage["l"] == verse and not passage.get("c"):
                if not phrase(start).match(passage["t"]):
                    fail(f"Upanishads: {ref} doesn't start with: {start}")
                marks.append({"ref": ref, "text": passage["t"], "chapter": index, "passage": p,
                              "start": 0, "end": len(passage["t"])})
                break
        else:
            fail(f"Upanishads: {ref} not found")
    return chapters, marks


def bible(path):
    chapters = []
    index_of = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        parts = line.split(" ", 2)
        if len(parts) != 3 or parts[0] not in BIBLE_BOOKS:
            continue
        code, place, text = parts
        chapter, verse = place.split(":")
        key = f"{code} {chapter}"
        if key not in index_of:
            index_of[key] = len(chapters)
            chapters.append({"title": f"{BIBLE_BOOKS[code]} {chapter}", "passages": []})
        if clean(text):
            chapters[index_of[key]]["passages"].append({"l": verse, "t": clean(text)})
    marks = []
    for code, ref in BIBLE.items():
        key, verse = code.rsplit(":", 1)
        index = index_of[key]
        for p, passage in enumerate(chapters[index]["passages"]):
            if passage["l"] == verse:
                raw = passage["t"]
                start = raw.index(BIBLE_START_AT[code]) if code in BIBLE_START_AT else 0
                # A lone verse shouldn't carry its neighbours' quotation marks or punctuation.
                while raw[start] == "“":
                    start += 1
                end = len(raw)
                while raw[end - 1] in "”:":
                    end -= 1
                text = raw[start:end]
                if text[-1] not in ".!?":
                    text += "."
                marks.append({"ref": ref, "text": text[0].upper() + text[1:], "chapter": index, "passage": p,
                              "start": start, "end": end})
                break
        else:
            fail(f"Bible: {ref} not found")
    return chapters, marks


def write(out_dir, shelf, book_id, title, credit, parsed):
    chapters, marks = parsed
    folder = Path(out_dir, book_id)
    folder.mkdir(parents=True)
    book = {"id": book_id, "title": title, "credit": credit, "shelf": shelf,
            "chapters": [c["title"] for c in chapters], "marks": marks}
    (folder / "book.json").write_text(json.dumps(book, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    for index, chapter in enumerate(chapters):
        if not chapter["passages"]:
            fail(f"{title}: {chapter['title']} is empty")
        (folder / f"c{index}.json").write_text(
            json.dumps(chapter["passages"], ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    passages = sum(len(c["passages"]) for c in chapters)
    print(f"{title}: {len(chapters)} chapters, {passages} passages, {len(marks)} marks")


if __name__ == "__main__":
    tao_path, dham_path, upa_path, web_path, out_dir = sys.argv[1:6]
    out = Path(out_dir)
    if out.parts[-2:] != ("assets", "books"):
        fail("OUT_DIR must end in assets/books, because it's replaced")
    parsed = [tao(tao_path), upanishads(upa_path), dhammapada(dham_path), bible(web_path)]
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    write(out, 0, "tao", "Tao Te Ching", "translated by James Legge (1891), public domain", parsed[0])
    write(out, 1, "upanishads", "Upanishads", "translated by Swami Paramananda (1919), public domain", parsed[1])
    write(out, 2, "dhammapada", "Dhammapada", "translated by F. Max Müller (1881), public domain", parsed[2])
    write(out, 3, "bible", "Bible", "World English Bible, public domain", parsed[3])
