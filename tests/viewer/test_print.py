"""
Print, issue #45: the page side.

A Word or prose page is printed by the WebView, so what it prints is decided here, by the
print rules in the page. A PDF is printed from the file instead, and all its page has to say
is whether it asked for a password, which Android's printing can't open.
"""

import re

import pytest

from helpers import wait_until_done

ZERO = {"top": "0", "bottom": "0", "left": "0", "right": "0"}


def sheets(page, paper):
    """How many sheets the page prints on, on [paper]."""
    data = page.pdf(format=paper, margin=ZERO)
    return len(re.findall(rb"/Type\s*/Page\b", data))


@pytest.mark.parametrize("paper", ["A4", "Letter"])
def test_an_a4_document_prints_a_sheet_per_page_on_either_paper(viewer, page, paper):
    """
    Two A4 pages. Printed on Letter, which is shorter, each page used to run on to a sheet
    of its own; each now has its own paper size, which the print fits to the paper chosen.
    """
    viewer("prose.html", "letter.odt")
    wait_until_done(page)
    assert sheets(page, paper) == 2


@pytest.mark.parametrize("paper", ["A4", "Letter"])
def test_a_letter_word_document_prints_on_one_sheet(viewer, page, paper):
    viewer("docx.html", "report.docx")
    wait_until_done(page)
    assert sheets(page, paper) == 1


@pytest.mark.parametrize("html,fixture,sheet", [
    ("docx.html", "report.docx", ".docx-wrapper > section.docx"),
    ("prose.html", "letter.odt", ".vw-paper > section"),
])
def test_printed_paper_drops_what_the_screen_draws_around_it(viewer, page, html, fixture, sheet):
    """No zoom to the screen's width, no shadow, no ground, and the margins on every sheet."""
    page.emulate_media(color_scheme="dark")
    viewer(html, fixture)
    wait_until_done(page)
    page.emulate_media(media="print")
    style = page.evaluate(
        """(sel) => {
            const s = document.querySelector(sel);
            const cs = getComputedStyle(s);
            return {
                zoom: cs.zoom,
                shadow: cs.boxShadow,
                ground: getComputedStyle(s.parentElement).backgroundColor,
                body: getComputedStyle(document.body).backgroundColor,
                decoration: cs.boxDecorationBreak || cs.webkitBoxDecorationBreak,
                page: s.style.page,
            };
        }""",
        sheet,
    )
    assert style["zoom"] == "1"
    assert style["shadow"] == "none"
    assert style["ground"] in ("rgba(0, 0, 0, 0)", "transparent")
    assert style["body"] in ("rgba(0, 0, 0, 0)", "transparent")
    assert style["decoration"] == "clone"
    assert style["page"].startswith("vw-sheet-")


def test_a_pdf_with_a_password_says_it_is_locked(viewer, page, port):
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", timeout=15000)
    channel = port()
    assert channel.wait_for(r"locked") == "locked"


def test_a_pdf_without_one_never_does(viewer, page, port):
    viewer("pdf.html", "six-pages.pdf")
    channel = port()
    channel.wait_for(r"page \d+ \d+")
    page.wait_for_timeout(300)
    assert "locked" not in channel.messages()
