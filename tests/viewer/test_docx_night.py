"""
Issue #47: night mode for Word.

word-colours.docx is painted in known values, and each must come out where the matrix
pdf.html uses puts it; test_pdf_invert.py says why these numbers and not a plain invert.
The colours are read off the elements rather than the screen, because here the page is
laid out rather than drawn as a picture.
"""

from helpers import wait_until_done

FIXTURE = "word-colours.docx"

# What each thing is painted in, and what night makes of it.
HEADING, HEADING_OVER = "rgb(0, 119, 199)", "rgb(56, 175, 255)"
INK, INK_OVER = "rgb(20, 20, 20)", "rgb(235, 235, 235)"
DEFAULT, DEFAULT_OVER = "rgb(0, 0, 0)", "rgb(255, 255, 255)"
THEMED, THEMED_OVER = "rgb(79, 129, 189)", "rgb(89, 139, 199)"   # accent1 of the template's theme
SHADED, SHADED_OVER = "rgb(30, 150, 60)", "rgb(49, 169, 79)"
RULE, RULE_OVER = "rgb(200, 30, 30)", "rgb(255, 153, 153)"
PAPER, PAPER_OVER = "rgb(255, 255, 255)", "rgb(0, 0, 0)"

INVERT = "invert(1) hue-rotate(180deg)"

LOOK = """() => {
  const run = t => [...document.querySelectorAll('#container span')]
    .filter(e => e.textContent.trim() === t).pop();
  const colour = t => getComputedStyle(run(t)).color;
  const rule = [...document.querySelectorAll('#container p')]
    .find(p => p.textContent.startsWith('A paragraph with a rule'));
  const cell = [...document.querySelectorAll('#container td')]
    .find(t => t.textContent.trim() === 'SHADED');
  return {
    heading: colour('Willowmere at night'),
    ink: colour('Ink set in a near black.'),
    deflt: colour('Ink left to the default.'),
    themed: colour('Coloured by the theme alone.'),
    rule: getComputedStyle(rule).borderBottomColor,
    cell: getComputedStyle(cell).backgroundColor,
    paper: getComputedStyle(document.querySelector('.docx-wrapper > section.docx')).backgroundColor,
    ground: getComputedStyle(document.querySelector('.docx-wrapper')).backgroundColor,
    pictures: [...document.querySelectorAll('#container img')].map(i => getComputedStyle(i).filter)
  };
}"""


def open_word(viewer, page, night=True):
    viewer("docx.html", FIXTURE, night="1" if night else "0")
    wait_until_done(page, timeout=25000)
    return page


def look(page):
    return page.evaluate(LOOK)


def wait_for_pictures(page):
    """The paper test reads each picture once it has loaded."""
    page.wait_for_function(
        "() => [...document.querySelectorAll('#container img')].every(i => i.complete)"
    )
    page.wait_for_timeout(100)


def test_the_paper_turns_black_and_the_ink_turns_white(viewer, page):
    seen = look(open_word(viewer, page))
    assert seen["paper"] == PAPER_OVER
    assert seen["deflt"] == DEFAULT_OVER
    assert seen["ink"] == INK_OVER


def test_a_colour_keeps_its_hue(viewer, page):
    assert look(open_word(viewer, page))["heading"] == HEADING_OVER


def test_a_colour_given_only_by_the_theme_turns_over_too(viewer, page):
    assert look(open_word(viewer, page))["themed"] == THEMED_OVER


def test_shading_and_rules_turn_over(viewer, page):
    seen = look(open_word(viewer, page))
    assert seen["cell"] == SHADED_OVER
    assert seen["rule"] == RULE_OVER


def test_the_ground_is_black_with_a_line_at_each_page_edge(viewer, page):
    """A line along the top of every page, and along the foot of the last."""
    viewer("docx.html", "word-pages.docx", night="1")
    wait_until_done(page, timeout=25000)
    assert page.evaluate(
        "() => getComputedStyle(document.querySelector('.docx-wrapper')).backgroundColor"
    ) == "rgb(0, 0, 0)"
    edges = page.evaluate(
        "() => [...document.querySelectorAll('.docx-wrapper > section.docx')]"
        ".map(s => getComputedStyle(s).boxShadow.split('rgb(71, 71, 71)').length - 1)"
    )
    assert edges == [1, 1, 1, 1, 1, 2]


def test_a_photograph_is_left_as_it_was(viewer, page):
    open_word(viewer, page)
    wait_for_pictures(page)
    assert look(page)["pictures"][0] == "none"


def test_a_picture_that_reads_as_paper_turns_over(viewer, page):
    """A white-backed figure, and ink drawn on nothing, which shows the page through it."""
    open_word(viewer, page)
    wait_for_pictures(page)
    assert look(page)["pictures"][1:] == [INVERT, INVERT]


def test_nothing_turns_over_unless_night_mode_is_asked_for(viewer, page):
    seen = look(open_word(viewer, page, night=False))
    assert (seen["heading"], seen["ink"], seen["deflt"], seen["themed"]) == (HEADING, INK, DEFAULT, THEMED)
    assert (seen["cell"], seen["rule"], seen["paper"]) == (SHADED, RULE, PAPER)
    assert seen["pictures"] == ["none", "none", "none"]


def test_a_printout_keeps_the_documents_colours(viewer, page):
    open_word(viewer, page)
    wait_for_pictures(page)
    page.emulate_media(media="print")
    seen = look(page)
    assert (seen["heading"], seen["ink"], seen["deflt"], seen["themed"]) == (HEADING, INK, DEFAULT, THEMED)
    assert (seen["cell"], seen["rule"], seen["paper"]) == (SHADED, RULE, PAPER)
    assert seen["pictures"] == ["none", "none", "none"]


def test_the_port_turns_it_on_and_off(viewer, page, port):
    open_word(viewer, page, night=False)
    wait_for_pictures(page)
    p = port()
    p.night_mode(True)
    page.wait_for_function("() => document.documentElement.classList.contains('vw-night')")
    seen = look(page)
    assert (seen["heading"], seen["paper"], seen["cell"]) == (HEADING_OVER, PAPER_OVER, SHADED_OVER)
    page.wait_for_timeout(100)
    assert look(page)["pictures"] == ["none", INVERT, INVERT]

    p.night_mode(False)
    page.wait_for_function("() => !document.documentElement.classList.contains('vw-night')")
    seen = look(page)
    assert (seen["heading"], seen["paper"], seen["cell"]) == (HEADING, PAPER, SHADED)
    assert seen["pictures"] == ["none", "none", "none"]


def test_a_document_opened_at_night_shows_once_it_is_dark(viewer, page):
    """Hidden until its colours are turned, so it never shows white first."""
    open_word(viewer, page)
    assert page.evaluate(
        "() => getComputedStyle(document.getElementById('container')).visibility"
    ) == "visible"
    assert page.evaluate("() => document.documentElement.classList.contains('vw-night-ready')")
