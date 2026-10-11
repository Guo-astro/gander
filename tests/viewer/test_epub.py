"""
epub.html: a book, read a chapter at a time by a reader of Gander's own (epub.js).

The chapter is drawn in the shadow tree of #book. Playwright's selectors go through an
open shadow root, so "#book h1" below is the chapter's heading.
"""

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
    reach the top of the screen, so on screen with the page moved is what is asked.
    """
    page.wait_for_function(
        """(s) => {
          const top = document.querySelector('#book').shadowRoot.querySelector(s)
            .getBoundingClientRect().top;
          return window.pageYOffset > 0 && top >= 0 && top < window.innerHeight;
        }""",
        arg=selector,
    )


def style_of(page, selector, name):
    return page.evaluate(
        "([s, name]) => getComputedStyle(document.querySelector('#book').shadowRoot"
        ".querySelector(s))[name]",
        [selector, name],
    )


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
    The book's font is obfuscated, which is not DRM: the text reads without it. Its
    @font-face rule goes too, since a rule with no font behind it is one the engine
    complains about, and the heading falls back to the face named after it.
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
