"""pptx.html: PPTXjs, which reports nothing and is polled instead."""

import io
import re
import zipfile

import pytest
from PIL import Image

from helpers import status_text, wait_until_done


def test_a_deck_renders_every_slide(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    assert len(page.query_selector_all("#result .slide")) >= 3


def test_the_spinner_goes_once_the_slides_are_up(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return !e || getComputedStyle(e).display === 'none'; }",
        timeout=20000,
    )


def test_the_slide_titles_are_there(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    # PPTXjs lays every run out with non-breaking spaces between the words
    said = page.text_content("#result").replace("\u00a0", " ")
    assert "Willowmere Kickoff" in said
    assert "What we found" in said
    assert "What happens next" in said


# ---------------------------------------------------------------------------
# PowerPoint's relatives, which FileKind sends here by extension
# ---------------------------------------------------------------------------

SLIDE_RELATIVES = {
    "deck.ppsx": "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
    "deck.pptm": "application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
    "deck.potx": "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
}


@pytest.mark.parametrize("fixture", sorted(SLIDE_RELATIVES))
def test_a_slide_show_a_macro_enabled_deck_and_a_template_render_as_a_pptx_does(
    viewer, page, main_part, fixture
):
    """
    Each is deck.pptx with its main part declared as its own format's. PPTXjs
    opens ppt/presentation.xml by name and finds the slides by their own type,
    which the four formats share, so what the main part was declared as never
    reaches it.
    """
    assert main_part(fixture) == [SLIDE_RELATIVES[fixture]]
    viewer("pptx.html", fixture)
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    wait_until_done(page)
    said = page.text_content("#result").replace(" ", " ")
    assert "Willowmere Kickoff" in said
    assert "What we found" in said
    assert "What happens next" in said


# ---------------------------------------------------------------------------
# Decks PPTXjs could not open: see the foot of pptx.js
# ---------------------------------------------------------------------------

def wait_for_deck(page, slides):
    page.wait_for_function(
        f"() => document.querySelectorAll('#result .slide').length >= {slides}"
        " || document.querySelector('.vw-error')",
        timeout=40000,
    )
    assert page.query_selector(".vw-error") is None, status_text(page)
    wait_until_done(page)


def drawn_paths(page, name):
    """The path data PPTXjs wrote for the shape called [name]."""
    return page.evaluate(
        "(n) => [...document.querySelectorAll('#result svg')]"
        ".filter(s => s.getAttribute('_name') === n)"
        ".flatMap(s => [...s.querySelectorAll('path')].map(p => p.getAttribute('d')))",
        name,
    )


def test_a_deck_with_no_app_properties_renders(viewer, page, fixture_path):
    """Google Slides writes no docProps/app.xml, and PPTXjs read it without looking."""
    with zipfile.ZipFile(fixture_path("deck-no-app-xml.pptx")) as z:
        assert "docProps/app.xml" not in z.namelist()
    viewer("pptx.html", "deck-no-app-xml.pptx")
    wait_for_deck(page, 3)
    said = page.text_content("#result").replace(" ", " ")
    assert "Willowmere Kickoff" in said
    assert "What happens next" in said


def test_a_shape_drawn_in_several_paths_draws_all_of_them(viewer, page):
    """A gate's outline and its two wires, which PPTXjs read as one path and threw on."""
    viewer("pptx.html", "freeforms.pptx")
    wait_for_deck(page, 1)
    paths = drawn_paths(page, "Gate")
    assert len(paths) == 1
    assert paths[0].count("M") == 3, paths[0]
    assert paths[0].count("L") == 6, paths[0]


def test_a_path_on_a_grid_of_its_own_is_drawn_to_its_shape(viewer, page):
    """
    The frame's grid is 1000 square and the diagonal's 500, so the diagonal's far
    end, at 500 on its own grid, is the frame's far corner.
    """
    viewer("pptx.html", "freeforms.pptx")
    wait_for_deck(page, 1)
    paths = drawn_paths(page, "Grid")
    corners = [tuple(round(float(v)) for v in pt.split(","))
               for pt in re.findall(r"[ML]\s*(-?[\d.]+,-?[\d.]+)", paths[0])]
    frame, diagonal_end = corners[2], corners[-1]
    assert diagonal_end == frame, paths[0]


def test_a_path_of_one_straight_segment_is_drawn(viewer, page):
    """A rule under a heading, a path PPTXjs drew as nothing, with no error to say so."""
    viewer("pptx.html", "freeforms.pptx")
    wait_for_deck(page, 1)
    paths = drawn_paths(page, "Rule")
    points = [tuple(round(float(v)) for v in pt.split(","))
              for pt in re.findall(r"[ML]\s*(-?[\d.]+,-?[\d.]+)", paths[0])]
    assert (0, 0) in points and (480, 0) in points, paths[0]


def painted(page, name, rgb):
    """How many pixels in and just around the shape called [name] are near [rgb]."""
    slide = page.query_selector("#result .slide")
    shot = Image.open(io.BytesIO(slide.screenshot())).convert("RGB")
    x, y, w, h = page.evaluate(
        "(n) => { const s = [...document.querySelectorAll('#result svg')]"
        ".find(e => e.getAttribute('_name') === n);"
        "const r = s.getBoundingClientRect(), o = s.closest('.slide').getBoundingClientRect();"
        "return [r.x - o.x, r.y - o.y, r.width, r.height]; }",
        name,
    )
    near = shot.crop((round(x) - 6, round(y) - 6, round(x + w) + 6, round(y + h) + 6))
    want = tuple(int(rgb[i:i + 2], 16) for i in (0, 2, 4))
    px = near.load()
    return sum(
        1 for i in range(near.width) for j in range(near.height)
        if all(abs(a - b) < 48 for a, b in zip(px[i, j], want))
    )


@pytest.mark.parametrize("name, rgb", [("Level", "C02020"), ("Upright", "2060C0"), ("Rule", "208040")])
def test_a_line_that_lies_flat_or_stands_upright_is_drawn(viewer, page, name, rgb):
    """Each is a shape of no height or no width, whose SVG was not drawn at all."""
    viewer("pptx.html", "lines.pptx")
    wait_for_deck(page, 1)
    assert painted(page, name, rgb) > 200


# ---------------------------------------------------------------------------
# Text: see spacesThatBreak in pptx.js
# ---------------------------------------------------------------------------

def text_lines(page, name):
    """The lines the text box called [name] is drawn on, as text, by where each character sits."""
    return page.evaluate(
        """(n) => {
             const lines = [];
             let top = null;
             const shape = document.querySelector(`#result div[_name="${n}"]`);
             for (const block of shape.querySelectorAll('.text-block')) {
               const walk = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
               for (let node; (node = walk.nextNode()); ) {
                 for (let i = 0; i < node.data.length; i++) {
                   const r = document.createRange();
                   r.setStart(node, i); r.setEnd(node, i + 1);
                   const box = r.getClientRects()[0];
                   if (!box) continue;
                   if (top === null || box.top - top > box.height / 2) { lines.push(''); top = box.top; }
                   lines[lines.length - 1] += node.data[i];
                 }
               }
             }
             return lines;
           }""",
        name,
    )


def test_a_line_too_long_for_its_box_breaks_between_words(viewer, page):
    """
    PPTXjs writes every space as a no-break space, so a line had nowhere to break and
    was cut wherever it ran out of room: "Language" on one line and "s" on the next.
    """
    viewer("pptx.html", "wrapping.pptx")
    wait_for_deck(page, 1)
    lines = text_lines(page, "Wrapped")
    assert len(lines) > 2, lines
    words = "Every word of this line stays whole when it wraps inside a narrow box".split()
    assert [w for line in lines for w in line.split()] == words, lines


def test_a_run_of_spaces_keeps_its_width(viewer, page):
    """The spaces PPTXjs wrote as no-break spaces kept their width, and ordinary ones must too."""
    viewer("pptx.html", "wrapping.pptx")
    wait_for_deck(page, 1)
    gap, space = page.evaluate(
        """() => {
             const block = document.querySelector('#result div[_name="Spaced"] .text-block');
             const node = document.createTreeWalker(block, NodeFilter.SHOW_TEXT).nextNode();
             const r = document.createRange();
             const at = (i) => { r.setStart(node, i); r.setEnd(node, i + 1); return r.getBoundingClientRect(); };
             const left = node.data.indexOf('left'), right = node.data.indexOf('right');
             return [at(right).left - at(left + 3).right, at(left + 4).width];
           }"""
    )
    assert space > 0
    assert gap > 6 * space, (gap, space)


# ---------------------------------------------------------------------------
# Weight: see regularWeight in pptx.js
# ---------------------------------------------------------------------------

def test_slide_text_that_is_not_bold_has_normal_weight(viewer, page):
    """
    PPTXjs gave every paragraph of a body or a shape font-weight 100, and its plain runs
    took it, which Android draws in Roboto Thin. A bold run must stay bold.
    """
    viewer("pptx.html", "weights.pptx")
    wait_for_deck(page, 1)
    weights = page.evaluate(
        """() => Object.fromEntries([...document.querySelectorAll('#result .text-block')]
             .map((run) => [run.textContent.trim(), getComputedStyle(run).fontWeight]))"""
    )
    assert weights["Plain body text"] == "400", weights
    assert weights["plain"] == "400", weights
    assert weights["bold"] == "700", weights


# ---------------------------------------------------------------------------
# Bold and italic: see styleTheDesignGives in pptx.js
# ---------------------------------------------------------------------------

def test_text_is_bold_or_italic_where_its_design_says(viewer, page):
    """
    PPTXjs read bold and italic only from the run itself, so a title its master or layout
    makes bold drew regular. What the run says still wins, and the nearer design over the
    farther one, at the paragraph's own level.
    """
    viewer("pptx.html", "inherited-bold.pptx")
    wait_for_deck(page, 2)
    styles = page.evaluate(
        """() => Object.fromEntries([...document.querySelectorAll('#result .text-block')]
             .map((run) => [run.textContent.trim(),
                            getComputedStyle(run).fontWeight + ' ' + getComputedStyle(run).fontStyle]))"""
    )
    assert styles["Bold from the master"] == "700 normal", styles
    assert styles["but not this"] == "400 normal", styles
    assert styles["Bold from the layout"] == "700 normal", styles
    assert styles["Plain at the second level"] == "400 normal", styles
    assert styles["Regular by its layout"] == "400 normal", styles
    assert styles["Italic from the layout"] == "400 italic", styles


# ---------------------------------------------------------------------------
# Line breaks: see breaksPptxjsKeeps in pptx.js
# ---------------------------------------------------------------------------

def test_every_line_break_in_a_paragraph_breaks_it(viewer, page):
    """PPTXjs dropped the first line break of a paragraph that had more than one."""
    viewer("pptx.html", "line-breaks.pptx")
    wait_for_deck(page, 1)
    assert text_lines(page, "Broken") == ["The first line", "the second", "and the third"]
