"""pptx.html: PPTXjs, which reports nothing and is polled instead."""

import re
import zipfile

import pytest

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
