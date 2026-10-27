"""The page's half of a double tap's zoom. The app's half is DoubleTapZoom.kt."""

import pytest

TOUCH_ACTION = "() => getComputedStyle(document.documentElement).touchAction"


@pytest.mark.parametrize("html,fixture", [
    ("pdf.html", "six-pages.pdf"),
    ("docx.html", "report.docx"),
    ("prose.html", "letter.odt"),
    ("pptx.html", "deck.pptx"),
    ("imgweb.html", "icon.svg"),
    ("xlsx.html", "budget.xlsx"),
])
def test_a_page_the_app_zooms_turns_chromiums_own_double_tap_zoom_off(viewer, page, html, fixture):
    """Without it Chromium zooms the same double tap as well."""
    viewer(html, fixture)
    assert page.evaluate(TOUCH_ACTION) == "manipulation"
