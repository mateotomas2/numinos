#!/usr/bin/env python3
"""Writes a small EPUB with an image, a table and styled text, for testing the reader.

usage: make_sample_epub.py OUT.epub
"""
import math
import struct
import sys
import zipfile
import zlib

MARKED = "The thirty spokes unite in the one nave; but it is on the empty space (for the axle), that the use of the wheel depends."


def png(size=320):
    """Draws a yin-yang, so the image needs no outside source or license."""
    rows = []
    r = size / 2 - 4
    c = size / 2
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            dx, dy = x - c, y - c
            shade = (246, 241, 231)
            if math.hypot(dx, dy) <= r:
                dark = dx > 0
                if math.hypot(dx, dy - r / 2) <= r / 2:
                    dark = False
                if math.hypot(dx, dy + r / 2) <= r / 2:
                    dark = True
                if math.hypot(dx, dy - r / 2) <= r * 0.13:
                    dark = True
                if math.hypot(dx, dy + r / 2) <= r * 0.13:
                    dark = False
                shade = (42, 38, 32) if dark else (255, 253, 248)
            row.extend(shade)
        rows.append(bytes(row))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


def page(title, body):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title>
<link rel="stylesheet" type="text/css" href="style.css"/></head>
<body><h1>{title}</h1>{body}</body></html>"""


CHAPTERS = [
    ("An image", """<p>The picture below comes from inside this EPUB. It should fit the card and keep its shape.</p>
<figure><img src="symbol.png" alt="A yin-yang symbol"/><figcaption>A drawn symbol with a caption.</figcaption></figure>
<p>Try scrolling, and try long-pressing a few words to select them. The menu should offer Like and Fix.</p>"""),
    ("A table", """<p>This table should keep its rows and columns.</p>
<table><thead><tr><th>Book</th><th>Tradition</th><th>Translator</th></tr></thead><tbody>
<tr><td>Tao Te Ching</td><td>Taoism</td><td>James Legge</td></tr>
<tr><td>Upanishads</td><td>Vedanta</td><td>Swami Paramananda</td></tr>
<tr><td>Dhammapada</td><td>Buddhism</td><td>F. Max Müller</td></tr>
<tr><td>Bible</td><td>Christianity</td><td>World English Bible</td></tr>
</tbody></table>
<p class="note">This paragraph uses the book's own <em>italic</em>, <strong>bold</strong> and color styling.</p>"""),
    ("A pencil mark", f"""<p>The next sentence should be underlined, because the app marks it.</p>
<p>{MARKED}</p>
<p>The bookmark button in the header should jump straight to it.</p>"""),
]

STYLE = """figure { margin: 1em 0; text-align: center; }
img { max-width: 70%; height: auto; }
figcaption { font-style: italic; font-size: 0.85em; }
table { border-collapse: collapse; width: 100%; font-size: 0.85em; }
th, td { border: 1px solid #998f7d; padding: 0.4em; text-align: left; }
th { background: #e3dbcb; color: #2a2620; }
.note { color: #3f7d68; border-left: 3px solid #3f7d68; padding-left: 0.6em; }
"""


def main(out):
    items = "".join(
        f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(len(CHAPTERS)))
    spine = "".join(f'<itemref idref="c{i}"/>' for i in range(len(CHAPTERS)))
    nav = "".join(f'<li><a href="c{i}.xhtml">{c[0]}</a></li>' for i, c in enumerate(CHAPTERS))
    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">numinos-reader-test</dc:identifier><dc:title>NuminOS reader test</dc:title>
<dc:language>en</dc:language><meta property="dcterms:modified">2026-11-03T00:00:00Z</meta></metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="css" href="style.css" media-type="text/css"/><item id="img" href="symbol.png" media-type="image/png"/>{items}</manifest>
<spine>{spine}</spine></package>"""
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as epub:
        epub.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", zipfile.ZIP_STORED)
        epub.writestr("META-INF/container.xml", """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        epub.writestr("OEBPS/book.opf", opf)
        epub.writestr("OEBPS/style.css", STYLE)
        epub.writestr("OEBPS/symbol.png", png())
        epub.writestr("OEBPS/nav.xhtml", page("Contents", f'<nav xmlns:epub="http://www.idpf.org/2007/ops" epub:type="toc"><ol>{nav}</ol></nav>'))
        for i, (title, body) in enumerate(CHAPTERS):
            epub.writestr(f"OEBPS/c{i}.xhtml", page(title, body))


if __name__ == "__main__":
    main(sys.argv[1])
