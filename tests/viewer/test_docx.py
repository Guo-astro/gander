"""docx.html: docx-preview, plus the private-use bullet fix."""

import pytest

from helpers import status_text, status_visible, wait_until_done


def wait_for_document(page, timeout=25000):
    page.wait_for_function(
        "() => document.querySelector('#container') && "
        "document.querySelector('#container').textContent.trim().length > 0",
        timeout=timeout,
    )


def test_a_document_renders_its_text(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    assert "Field Survey, Willowmere" in page.text_content("#container")


def test_paragraphs_keep_their_order(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    text = page.text_content("#container")
    assert text.index("A short report") < text.index("The paragraph after it")


def test_a_wingdings_bullet_becomes_a_real_character(viewer, page):
    """
    Word writes its bullets as private use codepoints in a font that is not on
    the phone, so they arrive as blank boxes. fixSymbolChars swaps the range
    U+F000 to U+F0FF for the Unicode characters they stand for.
    """
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    page.wait_for_timeout(500)

    leftover = page.evaluate(
        "() => { const t = document.querySelector('#container').textContent;"
        "return [...t].filter(c => c >= '\\uF000' && c <= '\\uF0FF').length; }"
    )
    assert leftover == 0, f"{leftover} private use characters left in the document"


def test_the_text_around_the_bullet_is_untouched(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    assert "A bullet that arrives as a private use codepoint." \
        in page.text_content("#container")


# ---------------------------------------------------------------------------
# Word's relatives, which FileKind sends here by extension
# ---------------------------------------------------------------------------

WORD_RELATIVES = {
    "report.docm": "application/vnd.ms-word.document.macroEnabled.main+xml",
    "report.dotx": "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
}


@pytest.mark.parametrize("fixture", sorted(WORD_RELATIVES))
def test_a_macro_enabled_document_and_a_template_render_as_a_docx_does(
    viewer, page, main_part, fixture
):
    """
    Each is report.docx with its main part declared as its own format's, which
    is all that tells them apart. docx-preview reaches that part through the
    package's relationships and never asks what it was declared as.
    """
    assert main_part(fixture) == [WORD_RELATIVES[fixture]]
    viewer("docx.html", fixture)
    wait_for_document(page)
    wait_until_done(page)
    text = page.text_content("#container")
    assert "Field Survey, Willowmere" in text
    assert text.index("A short report") < text.index("The paragraph after it")


# ---------------------------------------------------------------------------
# The too-old-WebView card, which Kotlin decides and app.js words
# ---------------------------------------------------------------------------

def test_an_engine_too_old_for_docx_preview_is_told_to_update_it(viewer, page, server):
    viewer("docx.html", "report.docx", webview=64, needs=80)
    page.wait_for_selector(".vw-error", timeout=15000)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Word documents" in said
    assert "64" in said and "80" in said
    assert "updating android system webview" in said.lower()
    # The card is the whole page: the document is never read
    assert server.full_requests() == []


def test_the_card_is_all_that_shows_when_docx_preview_cannot_parse(viewer, page):
    """
    Issue #31, WebView 64. docx-preview could not parse, the page rendered anyway,
    and the reader was told "docx is not defined". The browser here parses it, so
    the file is swapped for one that no engine can.
    """
    page.route(
        "**/assets/viewer/lib/docx-preview.min.js",
        lambda route: route.fulfill(content_type="text/javascript", body="var x = ;"),
    )
    with page.expect_event("pageerror"):
        viewer("docx.html", "report.docx", webview=64, needs=80)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Word documents" in said
    assert "not defined" not in said


def test_a_locked_engine_is_not_told_to_update_what_it_cannot(viewer, page):
    viewer("docx.html", "report.docx", webview=64, needs=80, locked=1)
    page.wait_for_selector(".vw-error", timeout=15000)
    said = status_text(page)
    assert "Word documents cannot be shown" in said
    assert "Updating Android System WebView" not in said


# ---------------------------------------------------------------------------
# Runs set as subscript or superscript, which docx-preview draws twice
# ---------------------------------------------------------------------------

def wait_for_the_tab_pass(page):
    """
    docx-preview lines each tab up against its paragraph's stops half a second
    after the document is up. The last tab is the last it reaches, so the pass is
    over once that one is spaced, or once the card is up because the pass threw.
    """
    wait_for_document(page)
    page.wait_for_function(
        "() => { const t = document.querySelectorAll('.docx-tab-stop');"
        "return (t.length && t[t.length - 1].style.wordSpacing) ||"
        " document.querySelector('.vw-error'); }",
        timeout=15000,
    )


def test_a_tab_in_a_subscript_run_leaves_the_document_up(viewer, page):
    """
    The run's first drawing queued its tab for the pass in no paragraph, and the
    pass threw on it, "Cannot read properties of null (reading
    'getBoundingClientRect')", which put the card up over a document that had drawn.
    """
    thrown = []
    page.on("pageerror", lambda e: thrown.append(str(e)))
    viewer("docx.html", "raised-runs.docx")
    wait_for_the_tab_pass(page)
    assert thrown == []
    assert not status_visible(page), status_text(page)


def test_every_tab_after_it_is_lined_up(viewer, page):
    viewer("docx.html", "raised-runs.docx")
    wait_for_the_tab_pass(page)
    spacing = page.evaluate(
        "() => [...document.querySelectorAll('.docx-tab-stop')].map(t => t.style.wordSpacing)"
    )
    assert len(spacing) == 7
    assert all(spacing), f"tabs left where they fell: {spacing}"


def test_a_footnote_raised_by_its_run_is_numbered_once(viewer, page):
    viewer("docx.html", "raised-runs.docx")
    wait_for_the_tab_pass(page)
    marks = page.evaluate(
        "() => [...document.querySelectorAll('#container p sup')]"
        ".filter(s => !s.querySelector('sup')).map(s => s.textContent)"
    )
    notes = page.evaluate(
        "() => [...document.querySelectorAll('#container ol li')].map(l => l.textContent)"
    )
    assert marks == ["1"]
    assert notes == ["Sampling began on the first dry day."]


def test_the_runs_are_still_lowered_and_raised(viewer, page):
    """Drawn once, but into the sub and sup the library would have kept."""
    viewer("docx.html", "raised-runs.docx")
    wait_for_the_tab_pass(page)
    assert page.evaluate("() => !!document.querySelector('#container sub .docx-tab-stop')")
    assert page.evaluate("() => document.querySelector('#container sub').textContent") \
        .startswith("3")
    assert page.evaluate("() => document.querySelector('#container sup sup').textContent") == "1"
