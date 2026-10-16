"""
The simple view, pdfsimple.html: a PDF on a phone whose WebView pdf.js can't run on, each page a
picture drawn by Gander's isolated renderer and served under /render/. Here the stand-in in
server.py draws the pictures with Pillow, so what is tested is the page: which pictures it asks
for and when, where it lays them out, and what it says over the channel. SimplePdf.kt and the
renderer are tested in ViewerActivityTest and on a device.
"""

import pytest

A4 = (595, 842)
LANDSCAPE = (842, 595)

# The column of pages runs from the spacer above them to the one below, the first and last of
# #pages but for the note a document too long for it ends with
LAYOUT = """() => {
  const pages = document.getElementById('pages');
  const column = [...pages.children].filter(k => !k.classList.contains('vw-cut'));
  const boxes = [...pages.querySelectorAll('.pg')];
  return {
    width: pages.clientWidth,
    inner: column[column.length - 1].getBoundingClientRect().bottom
      - column[0].getBoundingClientRect().top,
    boxes: boxes.length,
    heights: boxes.map(b => b.getBoundingClientRect().height),
    drawn: boxes.filter(b => b.querySelector('img')).length,
  };
}"""


def opened(page, server, sizes, wait=True, pages=0, error=None, broken=(), delay=0.0, **params):
    """The simple view of a document whose pages are [sizes], with its first picture in place."""
    simple = server.show_simple(sizes=sizes, pages=pages, error=error, broken=broken, delay=delay)
    page.goto(server.url("pdfsimple.html", name="report.pdf", ext="pdf", **params))
    if wait:
        page.wait_for_function("() => document.querySelector('#pages .pg img')", timeout=15000)
    return simple


def layout(page):
    return page.evaluate(LAYOUT)


def drawn(page, n, night=None, timeout=15000):
    """Waits for page n, one based, to be showing its picture, of [night] if that is given."""
    want = "" if night is None else f"&night={1 if night else 0}"
    page.wait_for_function(
        """([n, want]) => { const img = document.querySelector(`img[alt="Page ${n}"]`);
           return !!img && img.getAttribute('src').endsWith(want) && img.complete; }""",
        arg=[n, want], timeout=timeout)


def top_of(page, n):
    """Where page n's picture is, from the top of the screen."""
    return page.evaluate(
        "(n) => document.querySelector(`img[alt=\"Page ${n}\"]`).getBoundingClientRect().top", n)


# ---------------------------------------------------------------
# Laying out
# ---------------------------------------------------------------

def test_a_long_document_stands_at_its_full_height_with_only_the_pages_near_the_screen_laid_out(
        page, server):
    opened(page, server, [A4] * 300)
    lay = layout(page)
    pitch = lay["width"] * 842 / 595 + 8
    assert lay["inner"] == pytest.approx(300 * pitch, abs=2)
    # A page and a bit on a 1600 px screen, and five more below it kept laid out
    assert lay["boxes"] == 7
    assert all(h == pytest.approx(lay["width"] * 842 / 595, abs=1) for h in lay["heights"])


def test_pages_come_and_go_as_the_reader_scrolls_and_nothing_on_screen_moves(page, server):
    opened(page, server, [A4] * 300)
    page.evaluate("() => window.scrollTo(0, 100 * 1395)")
    drawn(page, 101)
    before = top_of(page, 101)
    lay = layout(page)
    assert lay["boxes"] <= 2 * 5 + 2
    # Page 1 is long gone, and its column is the same height without it
    assert page.evaluate("() => document.querySelector('img[alt=\"Page 1\"]')") is None
    assert lay["inner"] == pytest.approx(300 * (lay["width"] * 842 / 595 + 8), abs=2)
    page.evaluate("() => window.scrollBy(0, 600)")
    page.wait_for_timeout(300)
    assert top_of(page, 101) == pytest.approx(before - 600, abs=1)


def test_a_page_of_another_shape_takes_its_own_once_its_picture_comes(page, server):
    opened(page, server, [A4, LANDSCAPE, A4])
    drawn(page, 2)
    lay = layout(page)
    assert lay["heights"][1] == pytest.approx(lay["width"] * 595 / 842, abs=1)
    assert lay["heights"][0] == pytest.approx(lay["width"] * 842 / 595, abs=1)


def test_a_document_of_a_million_pages_lays_out_a_few_and_says_where_it_stops(page, server, port):
    opened(page, server, [A4], pages=1_000_000)
    lay = layout(page)
    assert lay["boxes"] <= 7
    note = page.text_content(".vw-cut")
    shown = int(note.split("first ")[1].split(" ")[0])
    assert note == f"The simple view shows the first {shown} of this PDF's 1000000 pages."
    assert lay["inner"] == pytest.approx(shown * (lay["width"] * 842 / 595 + 8), abs=2)
    assert lay["inner"] < 25e6
    channel = port()
    channel.wait_for(rf"page 1 {shown}")


def test_a_short_document_sits_in_the_middle_of_the_screen(page, server):
    opened(page, server, [LANDSCAPE])
    box = page.evaluate("() => document.querySelector('.pg').getBoundingClientRect().toJSON()")
    below = page.evaluate("() => innerHeight") - box["bottom"]
    assert box["top"] == pytest.approx(below - 8, abs=2)


# ---------------------------------------------------------------
# Asking for pictures
# ---------------------------------------------------------------

def test_pictures_are_asked_for_nearest_the_middle_first_and_two_at_a_time(page, server):
    simple = opened(page, server, [A4] * 300, delay=0.15)
    page.wait_for_function("() => document.querySelectorAll('#pages .pg img').length === 5",
                           timeout=15000)
    asked = [n for n, _ in server.pages_asked()]
    # Two go at once, so either of a pair may reach the server first
    assert sorted(asked[:2]) == [1, 2]
    assert sorted(asked[2:4]) == [3, 4]
    assert asked[4:] == [5]
    assert simple.most == 2


def test_a_picture_is_asked_for_at_the_screens_own_resolution(page, server):
    opened(page, server, [A4] * 3)
    asked = [r["query"] for r in server.state.requests if r["path"] == "/render/page"]
    dpr = page.evaluate("() => devicePixelRatio")
    width = page.evaluate("() => document.getElementById('pages').clientWidth")
    assert int(asked[0]["w"]) == round(min(width * dpr * 1.1, width * min(dpr, 2)))


def test_a_page_the_renderer_cannot_draw_says_so(page, server):
    opened(page, server, [A4] * 3, wait=False, broken={1})
    page.wait_for_selector(".pg .pg-note", timeout=15000)
    assert page.text_content(".pg .pg-note") == "This page could not be drawn"
    # Asked for once, not again and again
    page.wait_for_timeout(300)
    assert [n for n, _ in server.pages_asked()].count(1) == 1


# ---------------------------------------------------------------
# The channel
# ---------------------------------------------------------------

def test_the_readout_names_the_page_on_screen_and_the_last_at_the_end(page, server, port):
    """Pages shorter than half the screen, whose last is never under the middle of it."""
    opened(page, server, [LANDSCAPE] * 30)
    channel = port()
    channel.wait_for("page 1 30")
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    channel.wait_for("page 30 30")
    drawn(page, 30)


def test_go_to_page_puts_the_page_at_the_top_and_draws_it_first(page, server, port):
    opened(page, server, [A4] * 300)
    channel = port()
    channel.wait_for("page 1 300")
    page.wait_for_function("() => document.querySelectorAll('#pages .pg img').length === 5",
                           timeout=15000)
    asked = len(server.pages_asked())
    channel.go_to_page(120)
    channel.wait_for("page 120 300")
    drawn(page, 120)
    assert top_of(page, 120) == pytest.approx(0, abs=2)
    assert server.pages_asked()[asked][0] == 120


def test_go_to_a_short_page_puts_it_in_the_middle_so_the_readout_names_it(page, server, port):
    opened(page, server, [LANDSCAPE] * 40)
    channel = port()
    channel.wait_for("page 1 40")
    channel.go_to_page(20)
    channel.wait_for("page 20 40")
    drawn(page, 20)
    middle = page.evaluate("() => innerHeight / 2")
    box = page.evaluate(
        "() => document.querySelector('img[alt=\"Page 20\"]').getBoundingClientRect().toJSON()")
    assert box["top"] < middle < box["bottom"]


def test_a_jump_shapes_the_page_and_the_one_before_it_first(page, server, port):
    """
    Until it is drawn a page stands at page 1's shape, which in a deck after a portrait cover is
    not its own. Placed at that, a landscape page went to the top as if it were tall and the
    readout named the one after it: pdf.mjs's place() says so, and so does this page's.
    """
    opened(page, server, [A4] + [LANDSCAPE] * 39)
    channel = port()
    channel.wait_for("page 1 40")
    channel.go_to_page(20)
    channel.wait_for("page 20 40")
    drawn(page, 20)
    page.wait_for_timeout(300)
    middle = page.evaluate("() => innerHeight / 2")
    box = page.evaluate(
        "() => document.querySelector('img[alt=\"Page 20\"]').getBoundingClientRect().toJSON()")
    assert box["top"] < middle < box["bottom"]
    assert channel.pages()[-1] == "page 20 40"


def test_a_page_out_of_range_is_not_gone_to(page, server, port):
    opened(page, server, [A4] * 10)
    channel = port()
    channel.wait_for("page 1 10")
    before = layout(page)
    channel.go_to_page(11)
    channel.go_to_page(0)
    channel.send("gx")
    page.wait_for_timeout(300)
    assert channel.pages() == ["page 1 10"]
    # Nothing laid out moved, and a page that is there is still gone to
    assert layout(page)["inner"] == pytest.approx(before["inner"], abs=1)
    assert layout(page)["boxes"] == before["boxes"]
    channel.go_to_page(5)
    channel.wait_for("page 5 10")


def test_a_reopened_document_opens_at_its_page_and_draws_that_first(page, server, port):
    opened(page, server, [A4] * 300, resume=40)
    assert server.pages_asked()[0] == (40, False)
    drawn(page, 40)
    assert top_of(page, 40) == pytest.approx(0, abs=2)
    channel = port()
    channel.wait_for("page 40 300")


def test_a_resume_past_the_end_opens_at_the_top(page, server):
    opened(page, server, [A4] * 10, resume=11)
    assert server.pages_asked()[0] == (1, False)


def test_night_mode_asks_for_every_page_turned_over_and_back(page, server, port):
    opened(page, server, [A4] * 10, night=1)
    assert page.evaluate("() => document.documentElement.classList.contains('vw-night')")
    assert all(night for _, night in server.pages_asked())
    channel = port()
    channel.night_mode(False)
    drawn(page, 1, night=False)
    assert not page.evaluate("() => document.documentElement.classList.contains('vw-night')")
    # Each page shows one picture or the other while it is turned, never none
    assert page.evaluate("() => [...document.querySelectorAll('.pg')].slice(0, 2)"
                         ".every(b => b.querySelectorAll('img').length === 1)")


def test_the_title_bar_has_room_at_the_top(page, server):
    opened(page, server, [A4] * 10, top=56)
    first = page.evaluate("() => document.querySelector('.pg').getBoundingClientRect().top + scrollY")
    assert first == pytest.approx(56 + 8, abs=1)


# ---------------------------------------------------------------
# What it says
# ---------------------------------------------------------------

@pytest.mark.parametrize("word, title", [
    ("password", "This PDF needs a password"),
    ("not-pdf", "This file is not a PDF that can be read"),
    ("damaged", "This PDF could not be read"),
    ("unreadable", "This PDF could not be read"),
    ("gone", "Could not render this PDF"),
])
def test_a_document_that_wont_open_is_said_why(page, server, word, title):
    opened(page, server, [], wait=False, error=word)
    page.wait_for_selector(".vw-error", timeout=15000)
    assert page.text_content(".vw-error-title") == title
    assert server.pages_asked() == []


def test_the_card_goes_once_the_first_page_is_drawn(page, server):
    opened(page, server, [A4] * 3)
    page.wait_for_function(
        "() => getComputedStyle(document.getElementById('vw-status')).display === 'none'",
        timeout=5000)


def test_its_ground_is_pdf_htmls_in_both_schemes_and_black_at_night(page, server):
    for scheme in ("light", "dark"):
        page.emulate_media(color_scheme=scheme)
        opened(page, server, [A4] * 3)
        assert page.evaluate("() => getComputedStyle(document.body).backgroundColor") == "rgb(72, 68, 61)"
    opened(page, server, [A4] * 3, night=1)
    assert page.evaluate("() => getComputedStyle(document.body).backgroundColor") == "rgb(0, 0, 0)"


def test_the_app_zooms_its_double_tap(page, server):
    opened(page, server, [A4] * 3)
    assert page.evaluate("() => getComputedStyle(document.documentElement).touchAction") == "manipulation"
