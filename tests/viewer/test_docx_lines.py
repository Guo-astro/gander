"""
Lines as Word sets them (docx-lines.js). word-lines.docx is in the theme's Cambria at 11pt with
Word's 1.15 lines, and each paragraph after the first sets its spacing another way;
make_fixtures.py lists them.
"""

import pytest

from helpers import wait_until_done

FIXTURE = "word-lines.docx"

PT = 4 / 3  # CSS px to a point
SIZE = 11 * PT
# Word's single line over the size, as docx-lines.js has them
CAMBRIA, ARIAL = 1.1720, 1.1499

ANDROID = ("Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) "
           "Version/4.0 Chrome/153.0.0.0 Mobile Safari/537.36")

# The paragraph beginning [prefix]: the gaps between its lines, its box and its margins, at the
# page's own size rather than the zoom that fits it to the screen
PARAGRAPH = """(prefix) => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  const p = [...document.querySelectorAll('.docx-wrapper section.docx p')]
    .find(p => p.textContent.startsWith(prefix));
  const walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
  let text = null, node;
  while ((node = walker.nextNode())) if (!text || node.length > text.length) text = node;
  const range = document.createRange();
  if (text) range.selectNodeContents(text);
  const tops = text ? [...new Set([...range.getClientRects()].map(r => r.top))].sort((a, b) => a - b) : [];
  const box = p.getBoundingClientRect();
  return { gaps: tops.slice(1).map((t, i) => t - tops[i]), top: box.top, bottom: box.bottom, height: box.height };
}"""

BLANKS = """() => {
  document.querySelector('.docx-wrapper').style.setProperty('--vw-page-zoom', '1');
  return [...document.querySelectorAll('.docx-wrapper section.docx > article > p')]
    .filter(p => !p.textContent).map(p => p.getBoundingClientRect().height);
}"""


def open_lines(viewer, page):
    viewer("docx.html", FIXTURE)
    wait_until_done(page, timeout=25000)
    return page


def line_gaps(page, prefix):
    gaps = page.evaluate(PARAGRAPH, prefix)["gaps"]
    assert len(gaps) >= 2, f"{prefix!r} should run to several lines"
    return gaps


def test_auto_spacing_multiplies_the_fonts_own_line(viewer, page):
    """docx-preview multiplied the size alone: 1.15 times 11pt, where Word's line is 1.15 Cambria lines."""
    for gap in line_gaps(open_lines(viewer, page), "Auto."):
        assert gap == pytest.approx(1.15 * CAMBRIA * SIZE, abs=0.05)


def test_a_blank_line_is_as_tall_as_a_line(viewer, page):
    plain, tall = open_lines(viewer, page).evaluate(BLANKS)
    assert plain == pytest.approx(1.15 * CAMBRIA * SIZE, abs=0.05)
    # As tall as a line at the size Word gave its paragraph mark
    assert tall == pytest.approx(1.15 * CAMBRIA * 20 * PT, abs=0.05)


def test_at_least_spacing_is_a_floor_under_the_fonts_own_line(viewer, page):
    """docx-preview added the minimum to the size, so 11pt lines meant to be at least 14pt apart stood 25pt apart."""
    open_lines(viewer, page)
    for gap in line_gaps(page, "At least fourteen."):
        assert gap == pytest.approx(14 * PT, abs=0.05)
    for gap in line_gaps(page, "At least ten."):
        assert gap == pytest.approx(CAMBRIA * SIZE, abs=0.05)


def test_exact_spacing_stays_exact(viewer, page):
    for gap in line_gaps(open_lines(viewer, page), "Exactly twelve."):
        assert gap == pytest.approx(12 * PT, abs=0.05)


def test_a_run_in_another_font_spaces_lines_by_its_own(viewer, page):
    """The paragraph holds Arial runs, and its own font would otherwise stretch each line to Cambria's."""
    for gap in line_gaps(open_lines(viewer, page), "Arial."):
        assert gap == pytest.approx(1.15 * ARIAL * SIZE, abs=0.05)


def test_list_items_of_a_style_asking_for_no_space_have_none_between_them(viewer, page):
    open_lines(viewer, page)
    items = [page.evaluate(PARAGRAPH, f"List item {n}.") for n in (1, 2, 3)]
    after = page.evaluate(PARAGRAPH, "After the list.")
    assert items[1]["top"] - items[0]["bottom"] == pytest.approx(0, abs=0.05)
    assert items[2]["top"] - items[1]["bottom"] == pytest.approx(0, abs=0.05)
    # The last item keeps the 10pt after it, as the next paragraph is of another style
    assert after["top"] - items[2]["bottom"] == pytest.approx(10 * PT, abs=0.05)


STAND_INS = """() => [...document.fonts].filter(f => f.family === 'calibri' || f.family === '"calibri"')
  .map(f => ({ style: f.style, size: f.sizeAdjust, range: f.unicodeRange, axes: f.variationSettings }))"""


def test_on_android_word_fonts_are_drawn_in_its_own_at_their_width(viewer, page):
    page.context.new_cdp_session(page).send("Emulation.setUserAgentOverride", {"userAgent": ANDROID})
    faces = open_lines(viewer, page).evaluate(STAND_INS)
    latin = [f for f in faces if "U+400" not in f["range"]]
    cyrillic = [f for f in faces if "U+400" in f["range"]]
    assert {f["size"] for f in latin} == {"92%"}
    assert {f["size"] for f in cyrillic} == {"89.5%"}
    # Roboto's italic is an axis of the one font Android has
    assert [f["axes"] for f in latin if f["style"] == "italic"] == ['"ital" 1']


def test_elsewhere_a_word_font_is_the_systems_own(viewer, page):
    assert open_lines(viewer, page).evaluate(STAND_INS) == []
