"""md.html: Markdown through marked, then DOMPurify, then into the DOM."""

import pytest

from helpers import status_text


def wait_for_render(page, timeout=20000):
    page.wait_for_function(
        "() => document.querySelector('#content').children.length > 0", timeout=timeout
    )


def test_markdown_becomes_html(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert page.query_selector("#content h1") is not None
    assert "Willowmere site notes" in page.text_content("#content h1")


def test_lists_and_links_render(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert len(page.query_selector_all("#content li")) >= 2
    assert page.query_selector("#content a") is not None


@pytest.mark.parametrize("heading", [
    "ملاحظات القراءة",
    # The first letter is Latin, but most of the prose under it is Arabic.
    "Reading notes",
])
def test_prose_sets_direction_without_turning_code(viewer, page, made, port, heading):
    direction = "rtl"
    text = f'''```js
const count = 1;
```

# {heading}

(2026) العربية من اليمين إلى اليسار.

- العنصر الأول
- العنصر الثاني

> اقتباس عربي.

نص مع `value += 1`.
'''
    viewer("md.html", made("direction.md", text))
    wait_for_render(page)
    assert page.locator("#content h1, #content p, #content li").evaluate_all(
        "(els, direction) => els.every(el => getComputedStyle(el).direction === direction)", direction
    )
    assert page.locator("#content pre, #content code").evaluate_all(
        "els => els.every(el => getComputedStyle(el).direction === 'ltr')"
    )
    assert page.locator("#content > p code").evaluate("el => getComputedStyle(el).unicodeBidi") == "isolate"
    assert page.text_content("#content pre code") == "const count = 1;\n"
    borders = page.locator("#content blockquote").evaluate(
        "el => [getComputedStyle(el).borderLeftWidth, getComputedStyle(el).borderRightWidth]"
    )
    assert borders == ["0px", "4px"]
    channel = port()
    channel.query("العربية")
    channel.wait_for_count(1, 1)


ARABIC_PROSE = "العربية من اليمين إلى اليسار، وهذا سطر آخر بالعربية.\n\nوهذه فقرة ثانية بالعربية أيضا.\n"


@pytest.mark.parametrize("opening", [
    "English | [العربية](README.ar.md) | [فارسی](README.fa.md)",
    "# NewPipe",
    "---\ntitle: Notes\nlayout: post\n---",
])
def test_arabic_prose_reads_right_to_left_below_an_opening_in_english(viewer, page, made, opening):
    """Real Arabic files often open with a language menu, the project's English name or front matter."""
    viewer("md.html", made("opening.md", f"{opening}\n\n{ARABIC_PROSE}"))
    wait_for_render(page)
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "rtl"


def test_english_prose_reads_left_to_right_below_a_link_to_its_arabic_version(viewer, page, made):
    text = "[العربية](README.ar.md)\n\n# Quick start\n\nInstall the app, then open any file from the share sheet.\n"
    viewer("md.html", made("english.md", text))
    wait_for_render(page)
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "ltr"


def test_a_part_the_author_gave_a_direction_does_not_decide_the_rest(viewer, page, made):
    text = f'''Install the app, then open any file from the share sheet.

<div dir="rtl">

{ARABIC_PROSE * 4}
</div>
'''
    viewer("md.html", made("authored-part.md", text))
    wait_for_render(page)
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "ltr"
    assert page.locator("#content div").evaluate("el => getComputedStyle(el).direction") == "rtl"


def test_only_the_opening_of_a_long_document_is_counted(viewer, page, made):
    """Counting megabytes at once holds hundreds of megabytes of matches."""
    english = "Install the app, then open any file from the share sheet. " * 1500  # about 88K characters
    viewer("md.html", made("long.md", f"{english}\n\n{ARABIC_PROSE * 3000}"))
    wait_for_render(page)
    assert page.locator("#content").evaluate("el => getComputedStyle(el).direction") == "ltr"


@pytest.mark.parametrize("direction", ["rtl", "ltr", "auto"])
def test_code_reads_left_to_right_inside_an_authored_direction_unless_it_has_its_own(viewer, page, made, direction):
    """A wrapper's direction would put a leading `.` or `--` on the wrong end of the code in it."""
    text = f'''<div dir="{direction}">
<pre><code>مقطع عربي</code></pre>
<p><code>مقطع عربي</code></p>
</div>
<code dir="{direction}">مقطع عربي</code>
'''
    viewer("md.html", made("authored-dir.md", text))
    wait_for_render(page)
    assert page.get_attribute("#content div", "dir") == direction
    assert page.get_attribute("#content > code", "dir") == direction
    inside = page.locator("#content div pre, #content div code")
    assert inside.count() == 3
    assert inside.evaluate_all("els => els.every(el => getComputedStyle(el).direction === 'ltr')")
    assert page.locator("#content > code").evaluate("el => getComputedStyle(el).direction") == (
        "ltr" if direction == "ltr" else "rtl"
    )


def test_a_script_in_the_document_is_removed(viewer, page):
    """
    A Markdown file is untrusted input and marked will happily pass raw HTML
    through. DOMPurify is what stands between the two.
    """
    viewer("md.html", "notes.md")
    wait_for_render(page)
    page.wait_for_timeout(500)

    assert page.query_selector("#content script") is None
    assert page.evaluate("() => window.__xss") is None


def test_an_event_handler_attribute_is_removed(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    page.wait_for_timeout(500)

    handlers = page.evaluate(
        "() => [...document.querySelectorAll('#content *')]"
        ".filter(e => e.getAttribute('onerror') || e.getAttribute('onload')).length"
    )
    assert handlers == 0
    assert page.evaluate("() => window.__xss") is None


def test_the_prose_after_the_injected_markup_survives(viewer, page):
    """Sanitising is not the same as discarding the document."""
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert "Text after the injected markup" in page.text_content("#content")


# ---------------------------------------------------------------------------
# The too-old-WebView card, which Kotlin decides and app.js words
# ---------------------------------------------------------------------------

def test_an_engine_too_old_for_marked_is_told_to_update_it(viewer, page, server):
    viewer("md.html", "notes.md", webview=64, needs=92)
    page.wait_for_selector(".vw-error", timeout=15000)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Markdown files" in said
    assert "64" in said and "92" in said
    assert "updating android system webview" in said.lower()
    # The card is the whole page: the document is never read
    assert server.full_requests() == []


def test_the_card_is_all_that_shows_when_marked_cannot_parse(viewer, page):
    """
    Below Chromium 80, which is issue #31's WebView 64: marked could not parse and
    the reader was told "marked is not defined". The browser here parses it, so the
    file is swapped for one that no engine can.
    """
    page.route(
        "**/assets/viewer/lib/marked.min.js",
        lambda route: route.fulfill(content_type="text/javascript", body="var x = ;"),
    )
    with page.expect_event("pageerror"):
        viewer("md.html", "notes.md", webview=64, needs=92)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Markdown files" in said
    assert "not defined" not in said


def test_the_card_is_all_that_shows_where_marked_parses_but_cannot_run(viewer, page):
    """
    From Chromium 80 to 91 marked parses, and then fails on the first document it
    is given, because its lexer calls Array.prototype.at, which arrived in 92. So
    the floor is 92, not the 80 its syntax alone would suggest. Taking .at away
    here is that engine.
    """
    page.add_init_script("delete Array.prototype.at; delete String.prototype.at;")
    viewer("md.html", "notes.md", webview=91, needs=92)
    page.wait_for_selector(".vw-error", timeout=15000)
    page.wait_for_load_state("networkidle")
    said = status_text(page)
    assert "too old to show Markdown files" in said
    assert "is not a function" not in said
