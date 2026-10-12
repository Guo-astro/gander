"""
epub.html: a book, read a chapter at a time by a reader of Gander's own (epub.js).

The chapter is drawn in the shadow tree of #book. Playwright's selectors go through an
open shadow root, so "#book h1" below is the chapter's heading.
"""

import hashlib
import re
import struct
from pathlib import Path

import pytest

from helpers import highlight_count, status_text

BOOK = "harbour.epub"

# Pages per chapter of harbour.epub at 2048 bytes a page: 1, 1, 6 and 1
TOTAL = 9


def wait_for_book(page, timeout=20000):
    """The strip above a chapter appears once the first one is dressed."""
    page.wait_for_function(
        "() => !document.querySelector('#epub-bar').hidden", timeout=timeout
    )


def wait_for_heading(page, words, timeout=10000):
    page.wait_for_function(
        """(words) => {
          const h = document.querySelector('#book').shadowRoot.querySelector('h1');
          const page = document.querySelector('#book').shadowRoot.querySelector('.vw-page');
          return h && h.textContent.includes(words) && page.style.opacity !== '0';
        }""",
        arg=words,
        timeout=timeout,
    )


def open_book(viewer, page, fixture=BOOK, **params):
    viewer("epub.html", fixture, **params)
    wait_for_book(page)


def turn_to(page, heading):
    """Next, until the chapter under [heading] is the one on the page."""
    for _ in range(6):
        if heading in (page.text_content("#book h1") or ""):
            break
        page.click("#epub-next")
        page.wait_for_timeout(150)
    wait_for_heading(page, heading)


def top_of(page, selector):
    """How far down the screen an element in the chapter is."""
    return page.evaluate(
        "(s) => document.querySelector('#book').shadowRoot.querySelector(s)"
        ".getBoundingClientRect().top",
        selector,
    )


def wait_until_on_screen(page, selector):
    """
    Scrolled to, as far as the chapter allows: a place near a short chapter's foot cannot
    reach the top of the screen, so on screen with the page moved is what is asked. One that
    does reach the top may sit a fraction of a pixel above it, since the page scrolls by
    whole pixels and a line of text is not a whole number of them tall.
    """
    page.wait_for_function(
        """(s) => {
          const top = document.querySelector('#book').shadowRoot.querySelector(s)
            .getBoundingClientRect().top;
          return window.pageYOffset > 0 && top > -1 && top < window.innerHeight;
        }""",
        arg=selector,
    )


def style_of(page, selector, name):
    return page.evaluate(
        "([s, name]) => getComputedStyle(document.querySelector('#book').shadowRoot"
        ".querySelector(s))[name]",
        [selector, name],
    )


def go_to(page, index):
    """Chapter [index], by its place in the book, for a chapter with no heading to wait for."""
    page.evaluate("(i) => { vwEpub.go(i, null); }", index)
    page.wait_for_function(
        """(i) => vwFindParts.shown() === i && document.querySelector('#book').shadowRoot
          .querySelector('.vw-page').style.opacity !== '0'""",
        arg=index,
    )


def box_of(page, selector):
    """Where an element of the chapter is on the screen, and how much of it there is to scroll."""
    return page.evaluate(
        """(s) => {
          const el = document.querySelector('#book').shadowRoot.querySelector(s);
          const r = el.getBoundingClientRect();
          return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width,
                   height: r.height, inside: el.clientWidth, across: el.scrollWidth };
        }""",
        selector,
    )


def little_book(made, name, chapters, metadata="", contents="", extra=None,
                identifier="urn:uuid:00000000-0000-4000-8000-000000000054", listed="", order=None):
    """
    A book written for one test, for what no committed one has: [chapters] is a list of
    (what its place in the reading order says of it, what goes in its head, its body), and
    [extra] any other files by name. [listed] is what the package lists beside the chapters,
    and [order] the reading order by id where it is not simply the chapters, c0 onward.
    """
    import io
    import zipfile

    def chapter(head, body):
        return (
            '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n'
            '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
            f'<head><title>A chapter</title>{head}</head>{body}</html>\n'
        )

    items = "".join(
        f'<item id="c{n}" href="c{n}.xhtml" media-type="application/xhtml+xml"/>'
        for n in range(len(chapters))
    )
    refs = "".join(f'<itemref idref="c{n}" {says}/>' for n, (says, _, _) in enumerate(chapters))
    if order is not None:
        refs = "".join(f'<itemref idref="{i}"/>' for i in order)
    package = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">'
        '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
        '<dc:identifier>not the one the package calls its own</dc:identifier>'
        f'<dc:identifier id="uid">{identifier}</dc:identifier>'
        f'<dc:title>A Little Book</dc:title><dc:language>en</dc:language>{metadata}</metadata>'
        '<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
        f'{items}{listed}</manifest><spine>{refs}</spine></package>\n'
    )
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip")
        z.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?><container version="1.0" '
            'xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles>'
            '<rootfile full-path="package.opf" media-type="application/oebps-package+xml"/>'
            '</rootfiles></container>',
        )
        z.writestr("package.opf", package)
        z.writestr("nav.xhtml", chapter("", f'<body><nav epub:type="toc"><ol>{contents}</ol></nav></body>'))
        for n, (_, head, body) in enumerate(chapters):
            z.writestr(f"c{n}.xhtml", chapter(head, body))
        for path, data in (extra or {}).items():
            z.writestr(path, data)
    return made(name, buf.getvalue())


# ---------------------------------------------------------------------------
# Opening, and what a chapter takes from the zip
# ---------------------------------------------------------------------------

def test_a_book_opens_at_its_first_chapter(viewer, page):
    open_book(viewer, page)
    assert "The Harbour at Low Tide" in page.text_content("#book h1")
    assert page.text_content("#epub-where") == "Title page"
    assert page.is_disabled("#epub-prev")
    assert not page.is_disabled("#epub-next")


def test_a_picture_comes_out_of_the_zip(viewer, page):
    open_book(viewer, page)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#mark').naturalWidth === 48"
    )
    src = page.get_attribute("#book #mark", "src")
    assert src.startswith("blob:")


def test_the_books_stylesheet_applies_with_the_one_it_imports(viewer, page):
    open_book(viewer, page)
    # book.css, and the centring it gives the body it names by class
    assert style_of(page, "h1", "color") == "rgb(120, 40, 20)"
    assert style_of(page, "body", "textAlign") == "center"
    # extra.css, imported from book.css, with its picture found from its own folder
    assert style_of(page, ".byline", "backgroundImage").startswith('url("blob:')
    turn_to(page, "The Lamp Room")
    assert style_of(page, ".note", "fontStyle") == "italic"


def test_a_stylesheet_in_the_chapter_itself_applies(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Tide Table")
    assert style_of(page, "h2", "letterSpacing") == "1px"


def test_the_books_stylesheet_does_not_reach_the_page_around_it(viewer, page):
    """The contents list has an h1 of its own, which the book's h1 rule must not colour."""
    open_book(viewer, page)
    colour = page.evaluate(
        "() => getComputedStyle(document.querySelector('#epub-contents-title')).color"
    )
    assert colour != "rgb(120, 40, 20)"


def test_a_picture_named_in_an_inline_style_and_in_a_drawing(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Lamp Room")
    assert style_of(page, "#crest", "backgroundImage").startswith('url("blob:')
    turn_to(page, "The Tide Table")
    href = page.evaluate(
        "() => document.querySelector('#book').shadowRoot.querySelector('#drawn')"
        ".getAttributeNS('http://www.w3.org/1999/xlink', 'href')"
    )
    assert href.startswith("blob:")


def test_a_scrambled_font_is_left_out_and_the_book_still_opens(viewer, page):
    """
    The book's font is scrambled, which is not DRM, and what comes of unscrambling it is
    not a font: the file is noise. The text reads without it. Its @font-face rule goes too,
    since a rule with no font behind it is one the engine complains about, and the heading
    falls back to the face named after it.
    """
    said = []
    page.on("console", lambda m: said.append(m.text))
    open_book(viewer, page)
    faces = page.evaluate(
        "() => [...document.querySelectorAll('style')].map(s => s.textContent).join('')"
    )
    assert "Keeper Hand" not in faces
    assert "Keeper Hand" in style_of(page, "h1", "fontFamily")
    assert not [line for line in said if "font" in line.lower()]


def head_at(font, where):
    """
    The same font with its tables in another order, so that the one named head begins at
    byte [where]. That table holds a number every font must carry, twelve bytes in, and the
    engine takes no font without it. So with head at 1024 the number sits in the sixteen
    bytes that one way of scrambling covers and the other does not, and unscrambling too
    little of the file, or too much, leaves no font.
    """
    count = struct.unpack(">H", font[4:6])[0]
    tables = {}
    for n in range(count):
        tag, checksum, offset, length = struct.unpack(">4sIII", font[12 + 16 * n:28 + 16 * n])
        tables[tag] = (checksum, font[offset:offset + length])
    at = 12 + 16 * count
    placed, body, after = {}, b"", []
    for tag in sorted((t for t in tables if t != b"head"), key=lambda t: len(tables[t][1])):
        size = (len(tables[tag][1]) + 3) & ~3
        if at + size > where:
            after.append(tag)
            continue
        placed[tag] = at
        body += tables[tag][1].ljust(size, b"\0")
        at += size
    body += b"\0" * (where - at)
    at = where
    for tag in [b"head"] + after:
        size = (len(tables[tag][1]) + 3) & ~3
        placed[tag] = at
        body += tables[tag][1].ljust(size, b"\0")
        at += size
    directory = b"".join(
        struct.pack(">4sIII", tag, tables[tag][0], placed[tag], len(tables[tag][1]))
        for tag in sorted(tables)
    )
    made = font[:12] + directory + body
    assert made[where + 12:where + 16] == b"\x5f\x0f\x3c\xf5"
    return made


def scramble(font, algorithm, identifier):
    """A font as a book scrambles it, worked here from the two specifications' own words."""
    if algorithm.endswith("embedding"):
        key = hashlib.sha1(re.sub(r"[ \t\r\n]", "", identifier).encode("utf-8")).digest()
        span = 1040
    else:
        key = bytes.fromhex(re.search(r"[0-9a-f-]{36}", identifier).group().replace("-", ""))
        span = 1024
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(font[:span])) + font[span:]


@pytest.mark.parametrize("algorithm,identifier", [
    # The identifier has spaces and a line end in it, which come out before it is hashed
    ("http://www.idpf.org/2008/embedding", " heronsgate press : a little book \n"),
    ("http://ns.adobe.com/pdf/enc#RC", "urn:uuid:1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"),
])
def test_a_scrambled_font_is_put_right(viewer, page, made, algorithm, identifier):
    """
    A publisher's book scrambles its fonts, one of two published ways, with a key made from
    the book's own identifier. Put right, the font loads, which a font still scrambled, or
    unscrambled with the wrong key, does not: the engine takes nothing that is not sound.
    """
    from reportlab.pdfbase import pdfmetrics
    vera = head_at((Path(pdfmetrics.__file__).parent.parent / "fonts" / "Vera.ttf").read_bytes(), 1024)
    encryption = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" '
        'xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData>'
        f'<enc:EncryptionMethod Algorithm="{algorithm}"/>'
        '<enc:CipherData><enc:CipherReference URI="face.ttf"/></enc:CipherData>'
        '</enc:EncryptedData></encryption>'
    )
    book = little_book(
        made, "typeset.epub",
        [("", '<link rel="stylesheet" href="book.css"/>', "<body><h1>Set in the book's own face</h1></body>")],
        identifier=identifier,
        extra={
            "META-INF/encryption.xml": encryption,
            "face.ttf": scramble(vera, algorithm, identifier),
            "book.css": '@font-face { font-family: "Book Face"; src: url("face.ttf"); }\n'
                        'h1 { font-family: "Book Face", monospace; }\n',
        },
    )
    said = []
    page.on("console", lambda m: said.append(m.text))
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => [...document.fonts].some(f => f.family.includes('Book Face') && f.status === 'loaded')",
        timeout=10000,
    )
    assert not [line for line in said if "font" in line.lower()]


def test_a_chapter_that_is_not_sound_xml_is_read_as_html(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Long")
    assert page.text_content("#book h1") == "Two: The Long Watch"
    assert len(page.query_selector_all("#book p")) == 60
    # An empty anchor written <a id="halfway"/> must not swallow what follows it
    assert page.evaluate(
        "() => document.querySelector('#book').shadowRoot.querySelector('#halfway').textContent"
    ) == ""


# ---------------------------------------------------------------------------
# A chapter is somebody else's markup
# ---------------------------------------------------------------------------

def test_script_in_a_chapter_does_not_run(viewer, page):
    open_book(viewer, page)
    turn_to(page, "The Lamp Room")
    page.click("#book #tapped")
    page.click("#book #bad-link")
    page.wait_for_timeout(400)

    assert page.evaluate("() => window.__xss") is None
    assert page.query_selector("#book script") is None
    assert page.get_attribute("#book #tapped", "onclick") is None
    assert page.get_attribute("#book #missing", "onerror") is None
    assert page.get_attribute("#book #bad-link", "href") is None
    assert "Text after the injected markup" in page.text_content("#book .note")


def test_nothing_outside_the_book_is_asked_for(viewer, page):
    """
    A picture on another host, a picture the book does not hold and a web link that is
    tapped: none of them may become a request. The policy would refuse the first, and
    conftest fails a test where anything was refused, so this also checks that the page
    never tried.
    """
    asked = []
    page.on("request", lambda r: asked.append(r.url))
    open_book(viewer, page)
    turn_to(page, "The Lamp Room")
    page.click("#book #web-link")
    page.wait_for_timeout(400)

    assert page.get_attribute("#book #remote", "src") is None
    assert page.get_attribute("#book #missing", "src") is None
    assert not [u for u in asked if "example.invalid" in u or "nowhere.png" in u]
    assert "The Lamp Room" in page.text_content("#book h1")


# ---------------------------------------------------------------------------
# Getting about: the two buttons, the contents and links
# ---------------------------------------------------------------------------

def test_next_and_previous_turn_the_chapter(viewer, page):
    open_book(viewer, page)
    page.click("#epub-next")
    wait_for_heading(page, "The Lamp Room")
    assert page.text_content("#epub-where") == "One: The Lamp Room"
    page.click("#epub-prev")
    wait_for_heading(page, "The Harbour at Low Tide")

    turn_to(page, "The Tide Table")
    assert page.is_disabled("#epub-next")


def test_the_contents_list_is_the_books_own(viewer, page):
    open_book(viewer, page)
    page.click("#epub-contents-open")
    assert page.is_visible("#epub-contents")
    assert page.text_content("#epub-title") == "The Harbour at Low Tide"
    labels = page.eval_on_selector_all(
        "#epub-contents-list li", "els => els.map(e => e.textContent)"
    )
    assert labels == [
        "Title page", "One: The Lamp Room", "The ledger", "Two: The Long Watch",
        "Three: The Tide Table", "Notes, in the printed edition only",
    ]
    # The entry that goes nowhere is words and not a button
    assert len(page.query_selector_all("#epub-contents-list button")) == 5
    assert page.get_attribute("#epub-contents-list button[aria-current]", "data-n") == "0"

    page.click("#epub-contents-list button >> text=Three: The Tide Table")
    wait_for_heading(page, "The Tide Table")
    assert not page.is_visible("#epub-contents")


def test_a_contents_entry_goes_to_its_place_in_a_chapter(viewer, page):
    open_book(viewer, page)
    page.set_viewport_size({"width": 390, "height": 300})
    page.click("#epub-contents-open")
    page.click("#epub-contents-list button >> text=The ledger")
    wait_for_heading(page, "The Lamp Room")
    wait_until_on_screen(page, "#ledger")


def test_opening_and_closing_the_contents_does_not_move_the_page(viewer, page):
    """
    Clicked from script, because Playwright scrolls a button into view before it clicks it
    and the button is at the top of the chapter. What is checked is that the list coming and
    going, and the focus it hands back, leave the chapter where it was.
    """
    open_book(viewer, page)
    turn_to(page, "The Long")
    page.evaluate("() => window.scrollTo(0, 900)")
    page.evaluate("() => document.querySelector('#epub-contents-open').click()")
    assert page.is_visible("#epub-contents")
    page.evaluate("() => document.querySelector('#epub-contents-close').click()")
    assert not page.is_visible("#epub-contents")
    assert page.evaluate("() => window.pageYOffset") == 900
    assert "The Long" in page.text_content("#book h1")


def test_a_link_goes_to_its_place_in_another_chapter(viewer, page):
    open_book(viewer, page)
    page.set_viewport_size({"width": 390, "height": 160})
    turn_to(page, "The Lamp Room")
    page.click("#book #to-tide")
    wait_for_heading(page, "The Tide Table")
    wait_until_on_screen(page, "#tide-table")


# ---------------------------------------------------------------------------
# Pages: the counter, Go to page and the place a book reopens at
# ---------------------------------------------------------------------------

def test_the_page_is_reported_and_counts_the_whole_book(viewer, page, port):
    open_book(viewer, page)
    channel = port()
    channel.wait_for(rf"page 1 {TOTAL}")

    # Chapter two starts on page 3 and runs to page 8
    turn_to(page, "The Long")
    channel.wait_for(rf"page 3 {TOTAL}")
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    channel.wait_for(rf"page 8 {TOTAL}")


def test_go_to_page_lands_in_the_right_chapter_and_part_of_it(viewer, page, port):
    open_book(viewer, page)
    channel = port()
    channel.wait_for(rf"page 1 {TOTAL}")

    channel.go_to_page(5)
    wait_for_heading(page, "The Long")
    channel.wait_for(rf"page 5 {TOTAL}")
    assert page.evaluate("() => window.pageYOffset") > 0

    channel.go_to_page(TOTAL)
    wait_for_heading(page, "The Tide Table")
    channel.wait_for(rf"page {TOTAL} {TOTAL}")

    # Out of range is not a page, and the book stays where it is
    channel.go_to_page(TOTAL + 1)
    channel.go_to_page(0)
    page.wait_for_timeout(300)
    assert "The Tide Table" in page.text_content("#book h1")


def test_a_book_reopens_at_the_page_it_was_left_on(viewer, page, port):
    open_book(viewer, page, resume=6)
    wait_for_heading(page, "The Long")
    channel = port()
    channel.wait_for(rf"page 6 {TOTAL}")


def test_a_page_past_the_end_opens_the_book_at_the_start(viewer, page):
    open_book(viewer, page, resume=TOTAL + 5)
    assert "The Harbour at Low Tide" in page.text_content("#book h1")


# ---------------------------------------------------------------------------
# Find in page, which has to cross chapters
# ---------------------------------------------------------------------------

def test_a_search_counts_matches_in_chapters_not_on_the_page(viewer, page, port):
    """Once in chapter one and twice in chapter three, and the page is showing the title."""
    open_book(viewer, page)
    channel = port()
    channel.query("heronsgate")
    channel.wait_for(r"1 3 1")
    # Until every chapter had been read, the count was a not-yet and never a firm nought
    assert "0 0 1" not in channel.counts()
    wait_for_heading(page, "The Lamp Room")
    page.wait_for_function("() => CSS.highlights.has('vw-find-active')")

    channel.clear_inbox()
    channel.next()
    channel.wait_for(r"2 3 1")
    wait_for_heading(page, "The Tide Table")
    page.wait_for_function("() => CSS.highlights.has('vw-find')")
    assert highlight_count(page) == 1


def test_a_search_for_words_the_book_does_not_have_finds_nothing(viewer, page, port):
    open_book(viewer, page)
    channel = port()
    channel.query("zeppelin")
    page.wait_for_timeout(600)
    assert channel.counts()[-1] == "0 0 1"


# ---------------------------------------------------------------------------
# The older kind of book, and the ones that cannot be opened
# ---------------------------------------------------------------------------

def test_an_epub_2_reads_with_its_ncx_contents(viewer, page):
    open_book(viewer, page, fixture="almanac.epub")
    assert page.text_content("#book h1") == "First Light"
    assert style_of(page, "h1", "fontVariantCaps") == "small-caps"

    page.click("#epub-contents-open")
    labels = page.eval_on_selector_all(
        "#epub-contents-list li", "els => els.map(e => e.textContent)"
    )
    assert labels == ["First Light", "Second Watch", "The watch bell"]
    page.click("#epub-contents-list button >> text=The watch bell")
    wait_for_heading(page, "Second Watch")


def test_a_book_under_drm_says_so(viewer, page):
    viewer("epub.html", "locked.epub")
    page.wait_for_selector(".vw-error", timeout=15000)
    said = status_text(page)
    assert "This book is locked" in said
    assert "DRM" in said
    assert page.is_hidden("#epub-bar")


def test_a_file_that_is_not_a_book_says_so(viewer, page, made):
    viewer("epub.html", made("notes.epub", b"Plain words in a file named like a book."))
    page.wait_for_selector(".vw-error", timeout=15000)
    assert "It is not an EPUB, or the file is damaged." in status_text(page)


# ---------------------------------------------------------------------------
# What real books do: each of these is the shape of something in a book from Project
# Gutenberg, Standard Ebooks or the EPUB 3 samples
# ---------------------------------------------------------------------------

GAZETTEER = "gazetteer.epub"


def test_margins_set_for_a_desk_do_not_squeeze_the_words(viewer, page):
    """
    The book gives its body six ems on one side and sixteen on the other, which on a phone
    is fifteen pixels for the words. The body is the width of the page, less the page's own
    margins, and the paper colour the book gives it goes out to the edges.
    """
    open_book(viewer, page, fixture=GAZETTEER)
    column = box_of(page, ".vw-page")
    assert abs(box_of(page, "body")["width"] - column["width"]) < 1
    assert abs(box_of(page, "#first")["width"] - (column["width"] - 40)) < 1
    assert style_of(page, "body", "backgroundColor") == "rgb(255, 255, 240)"


def test_a_box_wider_than_the_screen_is_held_to_the_page(viewer, page):
    """A box 1400 pixels wide, as Project Gutenberg gives its plates, and the picture in it."""
    open_book(viewer, page, fixture=GAZETTEER)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#chart').naturalWidth === 1400"
    )
    edge = box_of(page, "#first")["right"]
    page.wait_for_function(
        """(edge) => document.querySelector('#book').shadowRoot.querySelector('#plate')
          .getBoundingClientRect().right <= edge + 1""",
        arg=edge,
    )
    assert box_of(page, "#chart")["right"] <= edge + 1
    assert box_of(page, "#chart")["width"] > 300


@pytest.mark.parametrize("selector,last", [
    ("#fares-table", "td:last-child"),
    ("#log", None),
    # Set in a line of words, which cannot scroll, so the paragraph the line is in does
    ("#sum", None),
    # A formula, no part of which can scroll either
    ("#eq", None),
])
def test_what_cannot_be_narrowed_scrolls_sideways_where_it_stands(viewer, page, selector, last):
    """
    A table of fourteen columns and a line of code the book says must not wrap, both in a
    section that is itself no wider than the page, then a run of code and a formula. The
    page never moves sideways, so each scrolls by itself, where it stands and not as part of
    something larger, and its far end can be brought into view.
    """
    open_book(viewer, page, fixture=GAZETTEER)
    edge = box_of(page, "#first")["right"]
    page.wait_for_function(
        """([s, edge]) => document.querySelector('#book').shadowRoot.querySelector(s)
          .getBoundingClientRect().right <= edge + 1""",
        arg=[selector, edge],
    )
    box = box_of(page, selector)
    assert box["across"] > box["inside"] + 100
    assert style_of(page, selector, "overflowX") == "auto"
    assert style_of(page, "#tables", "overflowX") == "visible"
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
    if last:
        page.evaluate(
            "(s) => { const el = document.querySelector('#book').shadowRoot.querySelector(s);"
            " el.scrollLeft = el.scrollWidth; }",
            selector,
        )
        assert box_of(page, f"{selector} {last}")["right"] <= edge + 1


def test_what_the_book_already_dealt_with_is_left_alone(viewer, page):
    """
    A table in a box the book made scroll sideways can be reached as it is, so nothing in
    the box is touched. Nor is a note the book put far off to the right, out of sight.
    """
    open_book(viewer, page, fixture=GAZETTEER)
    edge = box_of(page, "#first")["right"]
    page.wait_for_function(
        """(edge) => document.querySelector('#book').shadowRoot.querySelector('#fares-table')
          .getBoundingClientRect().right <= edge + 1""",
        arg=edge,
    )
    assert style_of(page, "#kept", "display") == "table"
    assert page.get_attribute("#book #kept", "style") is None
    assert page.get_attribute("#book #far", "style") == "position: absolute; left: 2000px; width: 300px"


def test_pictures_too_wide_together_once_they_have_loaded(viewer, page, made):
    """
    Two plates the book keeps on one line. Each is held to the width of the page, so the
    pair is twice too wide, which nobody knows until they have loaded and have a width at
    all. So what is too wide is looked for again then.
    """
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", (1500, 30), (30, 90, 140)).save(buf, format="PNG")
    plate = '<img src="wide.png" alt="A wide plate"/>'
    book = little_book(made, "plates.epub", [(
        "", "",
        '<body><p id="p">' + "Words enough to fill a line of the page. " * 4 + '</p>'
        f'<p id="row" style="white-space: nowrap">{plate}{plate}</p></body>',
    )], extra={"wide.png": buf.getvalue()})
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        """() => { const row = document.querySelector('#book').shadowRoot.querySelector('#row');
          return row.scrollWidth > row.clientWidth + 100 && getComputedStyle(row).overflowX === 'auto'; }"""
    )
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")


def test_what_fits_is_left_as_the_book_set_it(viewer, page):
    """Only what is too wide is touched: a table of two columns stays a table."""
    open_book(viewer, page)
    turn_to(page, "The Tide Table")
    page.wait_for_timeout(300)
    assert style_of(page, "table", "display") == "table"
    assert style_of(page, "table", "overflowX") == "visible"
    # Nor the chapter itself, which would then scroll sideways with nothing to scroll to
    assert style_of(page, "body", "overflowX") == "visible"


def beside_the_text(made, name, head, picture, size, para=""):
    """A chapter with one picture in a paragraph, set beside its words however [head] and [picture] say."""
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", size, (140, 60, 30)).save(buf, format="PNG")
    return little_book(made, name, [(
        "", head,
        f'<body><p id="p" {para}>{picture}<span id="words">To the youth</span>, the calm river seems to '
        'lead straight to the castle in the sky.</p></body>',
    )], extra={"plate.png": buf.getvalue()})


@pytest.mark.parametrize("head, picture, size", [
    ('<style>img.detail { float: right; margin: 0 0 1em 2em; }</style>',
     '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (600, 200)),
    ('<style>@media screen { img.detail { float: left; } }</style>',
     '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (600, 200)),
    ('', '<img id="plate" style="float: right" src="plate.png" alt="A detail"/>', (600, 200)),
    ('', '<img id="plate" align="right" src="plate.png" alt="A detail"/>', (600, 200)),
    ('<style>.side { float: right; width: 600px; }</style>',
     '<span id="plate" class="side"><img src="plate.png" alt="A detail"/></span>', (600, 200)),
    # Narrower, and it is the margin the book gives it that takes the room
    ('<style>img.detail { float: right; margin-left: 6em; }</style>',
     '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (500, 200)),
], ids=["rule", "media", "inline", "align", "box", "margin"])
def test_a_picture_that_leaves_the_text_no_room_beside_it_is_set_alone(viewer, page, made, head, picture, size):
    """
    A plate nearly as wide as the page, set beside the text as Cole's Voyage of Life sets its
    details: the column left down its side takes a letter to a line. It goes in the middle of
    the page instead, and the words start under it on one line.
    """
    book = beside_the_text(made, "detail.epub", head, picture, size)
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => getComputedStyle(document.querySelector('#book').shadowRoot.querySelector('#plate')).cssFloat === 'none'"
    )
    plate, words, para = box_of(page, "#plate"), box_of(page, "#words"), box_of(page, "#p")
    assert words["top"] >= plate["bottom"] - 1
    assert words["height"] < 40
    assert abs((plate["left"] - para["left"]) - (para["right"] - plate["right"])) <= 1


def test_a_picture_that_fills_a_narrow_box_is_set_alone_in_it(viewer, page, made):
    """The room is what the box it stands in has, which may be far less than the page."""
    book = beside_the_text(
        made, "boxed.epub", '<style>img.detail { float: left; }</style>',
        '<img id="plate" class="detail" src="plate.png" alt="A detail"/>', (200, 100), para='style="width: 240px"')
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => getComputedStyle(document.querySelector('#book').shadowRoot.querySelector('#plate')).cssFloat === 'none'"
    )
    assert box_of(page, "#words")["top"] >= box_of(page, "#plate")["bottom"] - 1


def test_a_heading_set_across_its_whole_box_stays_as_the_book_set_it(viewer, page, made):
    """
    Pro Git sets the heading of a note this way, so that it sits on the note's upper edge. It
    takes the whole width, so there is no text beside it to squeeze.
    """
    book = little_book(made, "note.epub", [(
        "", '<style>aside > h2 { float: left; width: 100%; margin-top: -1.25em; } aside { padding: 1em 2em; }</style>',
        '<body><aside><h2 id="plate">Note</h2><p id="p">Words enough to fill a line of the page.</p></aside></body>',
    )])
    open_book(viewer, page, fixture=book)
    page.wait_for_timeout(300)
    assert style_of(page, "#plate", "cssFloat") == "left"
    assert page.get_attribute("#book #plate", "style") is None


def test_a_picture_that_leaves_the_text_room_stays_beside_it(viewer, page, made):
    """A drop capital, as Project Gutenberg sets them: the words run down its side."""
    book = beside_the_text(
        made, "capital.epub", '<style>img.letra { float: left; margin-right: 4px; }</style>',
        '<img id="plate" class="letra" src="plate.png" alt="T"/>', (90, 90))
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#plate').naturalWidth > 0"
    )
    page.wait_for_timeout(300)
    assert style_of(page, "#plate", "cssFloat") == "left"
    assert page.get_attribute("#book #plate", "style") is None
    plate, words = box_of(page, "#plate"), box_of(page, "#words")
    assert words["left"] >= plate["right"]
    assert words["top"] < plate["bottom"]


def test_a_small_picture_in_a_narrow_box_stays_beside_its_text(viewer, page, made):
    """The box is what is narrow there, and setting the picture alone would not widen it."""
    book = beside_the_text(
        made, "narrow.epub", '<style>img.mark { float: left; margin-right: 4px; }</style>',
        '<img id="plate" class="mark" src="plate.png" alt="A mark"/>', (40, 40), para='style="width: 150px"')
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#plate').naturalWidth > 0"
    )
    page.wait_for_timeout(300)
    assert style_of(page, "#plate", "cssFloat") == "left"


def a_plate(size=(300, 200)):
    import io
    from PIL import Image
    buf = io.BytesIO()
    Image.new("RGB", size, (140, 60, 30)).save(buf, format="PNG")
    return buf.getvalue()


def chapter_text(page):
    return " ".join(page.evaluate(
        "document.querySelector('#book').shadowRoot.querySelector('.vw-page body').textContent"
    ).split())


def test_a_picture_in_the_reading_order_is_drawn(viewer, page, made, port):
    """
    A book of plates may list the plates themselves to be read, each with a page of words to
    show where a picture cannot be. Here it can, so the plate is what is shown, as one page
    however large its file is.
    """
    import os
    plate = a_plate() + os.urandom(9000)
    book = little_book(
        made, "plates-in-order.epub",
        [("", "", "<body><h1>Words for the plate</h1></body>"), ("", "", "<body><h1>After the plate</h1></body>")],
        listed='<item id="plate" href="plate.png" media-type="image/png" fallback="c0"/>',
        order=["plate", "c1"], extra={"plate.png": plate})
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        """() => { const im = document.querySelector('#book').shadowRoot.querySelector('img');
          return im && im.naturalWidth === 300; }"""
    )
    assert chapter_text(page) == ""
    port().wait_for(r"page 1 2")
    page.click("#epub-next")
    wait_for_heading(page, "After the plate")


@pytest.mark.parametrize("kind", ["application/json", "application/xml", "application/octet-stream"])
def test_a_file_that_is_no_page_gives_way_to_the_page_named_in_its_stead(viewer, page, made, kind):
    book = little_book(
        made, "stead.epub", [("", "", "<body><h1>The page in its stead</h1></body>")],
        listed=f'<item id="data" href="data.bin" media-type="{kind}" fallback="c0"/>',
        order=["data"], extra={"data.bin": "<novel><para>Not these words.</para></novel>"})
    open_book(viewer, page, fixture=book)
    assert chapter_text(page) == "The page in its stead"


def test_a_file_that_is_no_page_with_nothing_in_its_stead_is_not_read_as_one(viewer, page, made):
    """A disk image in the reading order, say: its bytes are not words, and the book goes on after it."""
    book = little_book(
        made, "bytes.epub", [("", "", "<body><h1>After the file</h1></body>")],
        listed='<item id="blob" href="blob.bin" media-type="application/octet-stream"/>',
        order=["blob", "c0"], extra={"blob.bin": b"\x00\x01EFI PART\xff\xfe words that are not words"})
    open_book(viewer, page, fixture=book)
    assert chapter_text(page) == "This part of the book could not be read."
    page.click("#epub-next")
    wait_for_heading(page, "After the file")


def test_a_chapter_the_package_gives_the_wrong_type_is_still_read(viewer, page, made):
    """Only a type that says a file is something else is believed: text of any kind is a chapter."""
    odd = ('<?xml version="1.0" encoding="UTF-8"?>\n<html xmlns="http://www.w3.org/1999/xhtml">'
           '<head><title>Odd</title></head><body><h1>Called plain text</h1></body></html>')
    book = little_book(
        made, "mislabelled.epub", [("", "", "<body><h1>Unused</h1></body>")],
        listed='<item id="odd" href="odd.xhtml" media-type="text/html"/>',
        order=["odd"], extra={"odd.xhtml": odd})
    open_book(viewer, page, fixture=book)
    assert chapter_text(page) == "Called plain text"


def test_a_picture_the_engine_cannot_draw_gives_way_to_the_one_named_in_its_stead(viewer, page, made):
    book = little_book(
        made, "photoshop.epub",
        [("", "", '<body><p><img id="plate" src="plate.psd" alt="A plate"/></p></body>')],
        listed='<item id="psd" href="plate.psd" media-type="image/vnd.adobe.photoshop" fallback="png"/>'
               '<item id="png" href="plate.png" media-type="image/png"/>',
        extra={"plate.psd": b"8BPS not a picture a browser draws", "plate.png": a_plate()})
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#plate').naturalWidth === 300"
    )


def test_a_contents_entry_that_is_a_picture_goes_by_what_the_picture_is_called(viewer, page, made):
    book = little_book(
        made, "pictured-contents.epub", [("", "", "<body><h1>The Light</h1></body>")],
        contents='<li><a href="c0.xhtml">Start</a></li>'
                 '<li><a href="c0.xhtml"><img src="light.png" alt="The harbour light"/></a></li>',
        extra={"light.png": a_plate()})
    open_book(viewer, page, fixture=book)
    labels = page.eval_on_selector_all("#epub-contents-list li", "els => els.map(e => e.textContent)")
    assert labels == ["Start", "The harbour light"]


def test_properties_under_epubs_old_names_apply(viewer, page, made):
    """The first EPUB 3 books hyphenate by -epub-hyphens, which the engine knows as hyphens only."""
    book = little_book(made, "prefixed.epub", [(
        "", "<style>#p { -epub-hyphens: auto; -epub-text-align-last: center; }</style>",
        '<body><p id="p">Words enough to fill a line of the page.</p></body>',
    )])
    open_book(viewer, page, fixture=book)
    page.wait_for_function(
        "() => getComputedStyle(document.querySelector('#book').shadowRoot.querySelector('#p')).hyphens === 'auto'"
    )
    assert style_of(page, "#p", "textAlignLast") == "center"


def test_an_id_the_document_has_a_property_by_is_kept(viewer, page):
    """
    "title" is the id of the heading, the name its stylesheet styles it by, and where the
    contents point. It is also a property of every document, for which DOMPurify drops it.
    """
    # A screen short enough that the heading has somewhere to be scrolled to
    page.set_viewport_size({"width": 412, "height": 500})
    open_book(viewer, page, fixture=GAZETTEER)
    assert style_of(page, "#title", "letterSpacing") == "3px"
    page.evaluate("document.querySelector('#epub-contents-open').click()")
    page.click("#epub-contents-list button >> text=Fares")
    wait_until_on_screen(page, "#title")


def test_a_switch_shows_its_default(viewer, page):
    open_book(viewer, page, fixture=GAZETTEER)
    said = page.text_content("#book body")
    assert "Salt and water, written out in words." in said
    assert "markup no reader here knows" not in said
    # The one written with a prefix, whose other branch would show beside its default
    assert "A second default, in words." in said
    assert "more of what no reader here knows" not in said
    # And the chapter goes on after it
    assert "The last line of the chapter." in said


def test_a_book_set_in_columns_is_laid_out_in_rows(viewer, page):
    """
    The chapter brings a stylesheet for columns that run down the page and offers one for
    rows as its alternative. The one for rows is used, the other is not, and what the
    chapter itself sets sideways is laid out in rows too.
    """
    open_book(viewer, page, fixture=GAZETTEER)
    go_to(page, 1)
    assert style_of(page, ".main", "writingMode") == "horizontal-tb"
    assert style_of(page, ".main", "letterSpacing") == "2px"
    assert style_of(page, ".main", "marginLeft") == "0px"
    assert style_of(page, ".aside", "writingMode") == "horizontal-tb"


def fixed_page_is_scaled(page):
    """The plate, 600 by 800, is as wide as the page and its caption where the book put it."""
    column = box_of(page, ".vw-page")
    by = column["width"] / 600
    sheet = box_of(page, "html")
    assert abs(sheet["width"] - column["width"]) < 1
    assert abs(sheet["height"] - 800 * by) < 1
    assert abs(column["height"] - 800 * by) <= 1
    caption = box_of(page, "#caption")
    assert abs(caption["left"] - (column["left"] + 300 * by)) < 1
    assert abs(caption["top"] - (column["top"] + 400 * by)) < 1
    # The buttons under the chapter are under it, and not beneath the page's picture
    turn = page.evaluate("document.querySelector('#epub-turn').getBoundingClientRect().top")
    assert turn >= column["bottom"] - 1


def test_a_page_of_a_fixed_size_is_scaled_to_the_screen(viewer, page):
    open_book(viewer, page, fixture=GAZETTEER)
    # The two chapters before it say they reflow, in a book whose pages are fixed
    assert "vw-flow" in page.get_attribute("#book .vw-page", "class")
    go_to(page, 2)
    assert "vw-fixed" in page.get_attribute("#book .vw-page", "class")
    fixed_page_is_scaled(page)
    assert style_of(page, "body", "backgroundColor") == "rgb(20, 40, 60)"

    # And again on a phone held upright, which the page learns of as a change of size
    page.set_viewport_size({"width": 412, "height": 915})
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('html')"
        ".getBoundingClientRect().width < 420"
    )
    fixed_page_is_scaled(page)

    # A chapter that reflows, after one that does not, has its own height back
    go_to(page, 0)
    assert "vw-flow" in page.get_attribute("#book .vw-page", "class")
    assert abs(box_of(page, ".vw-page")["height"] - box_of(page, "html")["height"]) < 1
    assert box_of(page, "#after")["bottom"] <= box_of(page, ".vw-page")["bottom"]


def test_a_fixed_page_keeps_its_colours_on_a_dark_phone(viewer, page):
    """It is a drawing more than it is text, and white words on the book's own dark blue."""
    page.emulate_media(color_scheme="dark")
    open_book(viewer, page, fixture=GAZETTEER)
    go_to(page, 2)
    assert style_of(page, "#caption", "color") == "rgb(255, 255, 255)"
    assert style_of(page, "body", "backgroundColor") == "rgb(20, 40, 60)"


def test_one_fixed_page_in_a_book_that_reflows(viewer, page, made):
    """
    The page says it is fixed in its own place in the reading order, as a painting between
    two chapters of text does. A size alone makes no chapter a fixed page: the second has
    one and the book does not call it fixed.
    """
    size = '<meta name="viewport" content="width=400,height=300"/>'
    bleed = '<div id="bleed" style="position: absolute; left: 300px; top: 100px; width: 300px; height: 60px"></div>'
    book = little_book(made, "painting.epub", [
        ('properties="rendition:layout-pre-paginated"', size, f'<body><p id="p">A painting.</p>{bleed}</body>'),
        ("", size, '<body><p id="p">Words about it.</p></body>'),
    ])
    open_book(viewer, page, fixture=book)
    assert "vw-fixed" in page.get_attribute("#book .vw-page", "class")
    column = box_of(page, ".vw-page")
    assert abs(box_of(page, "html")["height"] - 300 * column["width"] / 400) < 1

    # What the page draws past its own edge is cut off there, as at the edge of a sheet
    def under(x):
        return page.evaluate(
            "([x, y]) => { const el = document.querySelector('#book').shadowRoot.elementFromPoint(x, y);"
            " return el ? el.id : ''; }",
            [x, column["top"] + 130 * column["width"] / 400],
        )
    assert under(column["right"] - 10) == "bleed"
    assert under(column["right"] + 10) != "bleed"
    go_to(page, 1)
    assert "vw-flow" in page.get_attribute("#book .vw-page", "class")


def test_a_fixed_book_says_which_of_its_chapters_are_not(viewer, page, made):
    """
    In a book of fixed pages a chapter may say that it reflows, and does then, whatever size
    it gives. One that gives no size cannot be scaled to it and reflows too: a width alone
    is not a size, nor is one in words.
    """
    fixed = '<meta property="rendition:layout">pre-paginated</meta>'
    size = '<meta name="viewport" content="width=400,height=300"/>'
    book = little_book(made, "plates.epub", [
        ("", size, '<body><p>A plate.</p></body>'),
        ('properties="rendition:layout-reflowable"', size, '<body><p>The words between the plates.</p></body>'),
        ("", '<meta name="viewport" content="width=400"/>', '<body><p>A width and no height.</p></body>'),
        ("", '<meta name="viewport" content="width=device-width, height=device-height"/>',
         '<body><p>A size in words.</p></body>'),
    ], metadata=fixed)
    open_book(viewer, page, fixture=book)
    assert "vw-fixed" in page.get_attribute("#book .vw-page", "class")
    for chapter in (1, 2, 3):
        go_to(page, chapter)
        assert "vw-flow" in page.get_attribute("#book .vw-page", "class")


def test_a_fixed_page_with_no_colours_of_its_own_is_black_on_white(viewer, page, made):
    """
    Its sheet is white and its words black unless the book says otherwise, on a dark phone
    too: the dark ground of the page around it would leave black words unread.
    """
    page.emulate_media(color_scheme="dark")
    book = little_book(made, "plain-plate.epub", [(
        'properties="rendition:layout-pre-paginated"',
        '<meta name="viewport" content="width=400,height=300"/>',
        '<body><p id="p">Words on a plate that names no colour.</p></body>',
    )])
    open_book(viewer, page, fixture=book)
    assert style_of(page, "html", "backgroundColor") == "rgb(255, 255, 255)"
    assert style_of(page, "#p", "color") == "rgb(0, 0, 0)"


def test_a_long_contents_list_is_shown_whole(viewer, page, made):
    """An epic in 2,014 chapters lists every one, so a list of 2,100 is shown to its end."""
    entries = "".join(f'<li><a href="c0.xhtml">Canto {n}</a></li>' for n in range(1, 2101))
    book = little_book(made, "epic.epub", [("", "", "<body><p>Sing.</p></body>")], contents=entries)
    open_book(viewer, page, fixture=book)
    assert page.evaluate("document.querySelectorAll('#epub-contents-list button').length") == 2100


def test_a_table_too_wide_in_a_book_read_right_to_left(viewer, page, made):
    """It runs over to the left there, and scrolls from the right."""
    cells = "".join(f"<td>{'م' * 20}</td>" for _ in range(30))
    book = little_book(made, "rtl.epub", [(
        "", "<style>td { white-space: nowrap; padding: 0 12px; }</style>",
        f'<body dir="rtl"><p id="p">{"كلمة " * 40}</p><table id="wide"><tr>{cells}</tr></table></body>',
    )])
    open_book(viewer, page, fixture=book)
    edge = box_of(page, "#p")["left"]
    page.wait_for_function(
        """(edge) => document.querySelector('#book').shadowRoot.querySelector('#wide')
          .getBoundingClientRect().left >= edge - 1""",
        arg=edge,
    )
    box = box_of(page, "#wide")
    assert box["across"] > box["inside"] + 100


# ---------------------------------------------------------------------------
# A dark phone
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("scheme,dark", [("light", False), ("dark", True)])
def test_the_books_own_colours_give_way_on_a_dark_phone(viewer, page, scheme, dark):
    """
    The book sets its headings in a dark red, which is unreadable on a dark ground. By day
    it is the book's; on a dark phone the ink is the page's, and the picture is untouched.
    """
    page.emulate_media(color_scheme=scheme)
    open_book(viewer, page)
    colour = style_of(page, "h1", "color")
    assert (colour == "rgb(231, 226, 217)") is dark
    assert (colour == "rgb(120, 40, 20)") is not dark
    assert page.get_attribute("#book #mark", "src").startswith("blob:")


def test_ink_a_book_insists_on_still_gives_way_on_a_dark_phone(viewer, page, made):
    """
    A converted book often marks its colours important, black on white. The page's own rules
    come after the book's and are no less particular, so on a dark phone they still win.
    """
    page.emulate_media(color_scheme="dark")
    book = little_book(made, "insistent.epub", [(
        "",
        "<style>p.calibre1 { color: rgb(0, 0, 0) !important; background-color: rgb(255, 255, 255) !important; }"
        "</style>",
        '<body><p class="calibre1">Black on white, and marked important.</p></body>',
    )])
    open_book(viewer, page, fixture=book)
    assert style_of(page, "p", "color") == "rgb(231, 226, 217)"
    assert style_of(page, "p", "backgroundColor") == "rgba(0, 0, 0, 0)"
