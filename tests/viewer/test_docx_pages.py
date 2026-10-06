"""
Issue #47: a Word file in pages where Word recorded them, numbered, and counted for the app.

word-pages.docx is six pages in Word and word-unrecorded.docx four, two of them begun where
Word could save no record; make_fixtures.py says where each one ends and why.
"""

import re
import zipfile

from helpers import wait_until_done

FIXTURE = "word-pages.docx"
UNRECORDED = "word-unrecorded.docx"

PAGES = """() => [...document.querySelectorAll('.docx-wrapper > section.docx')].map(s => ({
  articles: [...s.querySelectorAll(':scope > article')]
    .map(a => a.innerText.replace(/\\s+/g, ' ').trim()),
  footer: [...s.querySelectorAll(':scope > footer')]
    .map(f => f.innerText.replace(/\\s+/g, ' ').trim()).join(' | '),
  notes: [...s.querySelectorAll(':scope > ol')].map(o => o.innerText.trim()).join(' | ')
}))"""


def open_word(viewer, page, fixture=FIXTURE, **params):
    viewer("docx.html", fixture, **params)
    wait_until_done(page, timeout=25000)
    return page


def pages(page):
    return page.evaluate(PAGES)


def body(seen):
    return " / ".join(seen["articles"])


def remade(made, fixture_path, fixture, changes):
    """A copy of [fixture] with each part named in [changes] passed through its function."""
    with zipfile.ZipFile(fixture_path(fixture)) as z:
        items = {i.filename: z.read(i.filename) for i in z.infolist()}
    for name, change in changes.items():
        items[name] = change(items[name])
    target = made("remade-" + fixture, b"")
    with zipfile.ZipFile(target, "w") as z:
        for name, data in items.items():
            z.writestr(name, data)
    return target


def swap(old, new):
    def change(data):
        assert data.count(old) == 1
        return data.replace(old, new)
    return change


def counted(pages):
    """docProps/app.xml saying Word counted [pages] pages."""
    return lambda data: re.sub(rb"<Pages>\d+</Pages>", b"<Pages>%d</Pages>" % pages, data)


# ---------------------------------------------------------------------------
# Where the pages end
# ---------------------------------------------------------------------------

def test_a_page_ends_where_word_recorded_it(viewer, page):
    seen = pages(open_word(viewer, page))
    assert len(seen) == 6
    assert body(seen[0]).endswith("Second numbered item,")
    assert body(seen[1]).startswith("carried on to page two.")
    assert body(seen[4]).endswith("It runs on")
    assert body(seen[5]) == "to a second roman page."


def test_the_rest_of_a_cut_list_item_takes_no_number_of_its_own(viewer, page):
    open_word(viewer, page)
    said = page.evaluate("""() => {
      const ps = [...document.querySelectorAll('.docx-wrapper p')];
      const rest = ps.find(p => p.textContent.trim() === 'carried on to page two.');
      const third = ps.find(p => p.textContent.trim() === 'Third numbered item.');
      const look = p => ({
        marker: getComputedStyle(p, '::before').content,
        counts: getComputedStyle(p, '::before').counterIncrement,
        indent: getComputedStyle(p).textIndent
      });
      return { rest: look(rest), third: look(third) };
    }""")
    assert said["rest"]["marker"] == "none"
    assert said["rest"]["counts"] == "none"
    assert said["rest"]["indent"] == "0px"
    # The next item still has its number, which the rest not counting keeps at 3
    assert said["third"]["marker"] != "none"
    assert said["third"]["counts"] != "none"


def test_a_table_a_page_cuts_shows_its_rows_on_both_pages(viewer, page):
    seen = pages(open_word(viewer, page))
    assert body(seen[1]).endswith("Well Reading")
    assert body(seen[2]).startswith("North 4.2 South 3.8")
    assert "Well" not in body(seen[2])


def test_the_record_word_writes_after_a_page_break_adds_no_empty_page(viewer, page):
    seen = pages(open_word(viewer, page))
    assert body(seen[2]).endswith("Before the explicit break.")
    assert body(seen[3]).startswith("After the explicit break.")


def test_a_continuous_section_shares_its_page(viewer, page):
    seen = pages(open_word(viewer, page))
    assert seen[3]["articles"] == [
        "After the explicit break. Section one ends here.",
        "Section two shares page four.",
    ]


def test_a_record_at_the_top_of_a_column_turns_no_page(viewer, page):
    """Word records the top of each column as it does each page; this file is three pages in Word."""
    seen = pages(open_word(viewer, page, "word-columns.docx"))
    assert [p["articles"] for p in seen] == [
        [
            "Willowmere columns Page one begins here.",
            "First column. Second column. Third column.",
            "Back to one column. A long paragraph Word ended page one in,",
        ],
        ["carried on to page two."],
        ["Section four begins page three."],
    ]
    columns = page.evaluate(
        "() => getComputedStyle(document.querySelectorAll('.docx-wrapper article')[1]).columnCount"
    )
    assert columns == "3"


def test_a_footnote_sits_on_the_page_that_cites_it(viewer, page):
    seen = pages(open_word(viewer, page))
    assert [p["notes"] for p in seen] == ["", "Counted on the page that cites it.", "", "", "", ""]


def test_each_page_has_its_own_number(viewer, page):
    """
    The footer's fields saved 1 and 6. The last two sections have no footer of their own
    and show the first section's, as Word does, and the last counts in roman from i.
    """
    seen = pages(open_word(viewer, page))
    assert [p["footer"] for p in seen] == [
        "Page 1 of 6", "Page 2 of 6", "Page 3 of 6", "Page 4 of 6", "Page i of 6", "Page ii of 6",
    ]


def test_a_file_without_words_record_is_drawn_as_before(viewer, page, made, fixture_path):
    """
    The same document as WPS or LibreOffice would save it, with no record of where its pages
    began. docx-preview's own pages, one per break and per section, and the footer's saved
    values on the pages that have it.
    """
    target = remade(made, fixture_path, FIXTURE, {
        "word/document.xml": lambda xml: xml.replace(b"<w:lastRenderedPageBreak/>", b""),
    })
    seen = pages(open_word(viewer, page, target))
    assert len(seen) == 4
    assert [p["footer"] for p in seen if p["footer"]] == ["Page 1 of 6", "Page 1 of 6"]
    assert page.evaluate("vwPages.split") is False


# ---------------------------------------------------------------------------
# Pages Word began where it could save no record, from its own count of them
# ---------------------------------------------------------------------------

def test_a_page_word_began_among_blank_lines_is_found(viewer, page):
    seen = pages(open_word(viewer, page, UNRECORDED))
    assert len(seen) == 4
    assert body(seen[0]).endswith("Ledger line 8 of page one.")
    assert body(seen[1]).startswith("Page two follows the blank lines.")
    assert body(seen[1]).endswith("Ledger line 8 of page two.")
    assert body(seen[2]).startswith("Page three begins where Word recorded it.")
    # Hidden only while the pages were being found
    shown = page.evaluate("getComputedStyle(document.getElementById('container')).visibility")
    assert shown == "visible"


def test_a_page_word_began_on_a_table_row_has_the_header_row_again(viewer, page):
    seen = pages(open_word(viewer, page, UNRECORDED))
    assert body(seen[2]).startswith(
        "Page three begins where Word recorded it. Entry Reading Reading 1 ")
    # The row depends on the font; the table runs on from wherever it is, under its header
    first = int(re.match(r"Entry Reading Reading (\d+) ", body(seen[3])).group(1))
    assert body(seen[2]).endswith(f"Reading {first - 1} Checked {first - 1}")
    assert body(seen[3]).endswith("Reading 18 Checked 18 After the table.")
    # At the row nearest halfway down what the sheet held, so the two pages come out alike
    tall = page.evaluate("""() => [...document.querySelectorAll('.docx-wrapper > section.docx')]
      .slice(2).map(s => s.querySelector(':scope > article').getBoundingClientRect().height)""")
    assert abs(tall[0] - tall[1]) < 0.2 * max(tall)


def test_the_footers_count_the_pages_found(viewer, page):
    seen = pages(open_word(viewer, page, UNRECORDED))
    assert [p["footer"] for p in seen] == ["Page 1 of 4", "Page 2 of 4", "Page 3 of 4", "Page 4 of 4"]


def test_a_count_no_higher_than_the_pages_drawn_changes_nothing(viewer, page, made, fixture_path):
    target = remade(made, fixture_path, UNRECORDED, {"docProps/app.xml": counted(2)})
    seen = pages(open_word(viewer, page, target))
    assert len(seen) == 2
    assert body(seen[0]).endswith("Ledger line 8 of page two.")
    assert page.evaluate("vwPages.turnAt") is None


def test_a_count_past_what_overflows_its_paper_adds_no_more(viewer, page, made, fixture_path):
    """A count can be stale, and a page that fits its paper is no place to look for another."""
    target = remade(made, fixture_path, UNRECORDED, {"docProps/app.xml": counted(9)})
    assert len(pages(open_word(viewer, page, target))) == 4

    target = remade(made, fixture_path, FIXTURE, {"docProps/app.xml": counted(9)})
    seen = pages(open_word(viewer, page, target))
    assert [p["footer"] for p in seen] == [
        "Page 1 of 6", "Page 2 of 6", "Page 3 of 6", "Page 4 of 6", "Page i of 6", "Page ii of 6",
    ]


def test_a_run_of_blank_lines_takes_one_turn_however_long(viewer, page, made, fixture_path):
    """Two turns among the blank lines would make a page with nothing on it."""
    target = remade(made, fixture_path, UNRECORDED, {
        "word/document.xml": swap(b"<w:p/>" * 24, b"<w:p/>" * 120),
        "docProps/app.xml": counted(5),
    })
    seen = pages(open_word(viewer, page, target))
    assert all(body(p) for p in seen)
    assert len(seen) == 4
    assert body(seen[1]).startswith("Page two follows the blank lines.")


PAGE_BREAK = b'<w:p><w:r><w:br w:type="page"/></w:r></w:p>'


def test_blank_lines_just_before_a_page_break_take_no_turn(viewer, page, made, fixture_path):
    """Nothing would follow the turn on its page, so it would make a page of nothing."""
    target = remade(made, fixture_path, UNRECORDED, {
        "word/document.xml": swap(b"<w:p/>" * 24, b"<w:p/>" * 40 + PAGE_BREAK),
    })
    seen = pages(open_word(viewer, page, target))
    assert all(body(p) for p in seen)
    assert len(seen) == 4
    assert body(seen[3]).startswith("Entry Reading Reading ")


def test_blank_lines_just_after_a_page_break_take_no_turn(viewer, page, made, fixture_path):
    """Nothing would be above the turn on its page, so it would make no page, and lose one."""
    target = remade(made, fixture_path, UNRECORDED, {
        "word/document.xml": swap(b"<w:p/>" * 24, PAGE_BREAK + b"<w:p/>" * 40),
    })
    seen = pages(open_word(viewer, page, target))
    assert len(seen) == 4
    assert body(seen[3]).startswith("Entry Reading Reading ")


def test_a_turn_leaves_at_least_half_a_page_above_it(viewer, page, made, fixture_path):
    """
    Word turns at a blank line only once the page above is full. Here the only blank lines
    sit just below page one's first line, and twenty lines of text fill out the sheet.
    """
    filler = b"".join(b"<w:p><w:r><w:t>Filler line %d.</w:t></w:r></w:p>" % n for n in range(1, 21))
    first = b"Ledger line 1 of page one.</w:t></w:r></w:p>"
    target = remade(made, fixture_path, UNRECORDED, {
        "word/document.xml": lambda xml: swap(first, first + b"<w:p/>" * 3)(
            swap(b"<w:p/>" * 24, filler)(xml)),
    })
    seen = pages(open_word(viewer, page, target))
    assert body(seen[0]).endswith("Ledger line 8 of page two.")
    assert len(seen) == 3


# ---------------------------------------------------------------------------
# The page counter and Go to page, over the app's channel
# ---------------------------------------------------------------------------

def test_the_page_on_screen_is_told_to_the_app(viewer, page, port):
    open_word(viewer, page)
    p = port()
    p.wait_for(r"page 1 6")
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    p.wait_for(r"page 6 6")


def test_the_middle_of_the_screen_decides_the_page(viewer, page, port):
    open_word(viewer, page)
    p = port()
    p.wait_for(r"page 1 6")
    # Page 3's top a little above the middle of the screen
    page.evaluate("""() => {
      const third = document.querySelectorAll('.docx-wrapper > section.docx')[2];
      window.scrollBy(0, third.getBoundingClientRect().top - innerHeight / 2 + 40);
    }""")
    p.wait_for(r"page 3 6")


def test_go_to_page_puts_the_page_at_the_top(viewer, page, port):
    open_word(viewer, page)
    p = port()
    p.wait_for(r"page 1 6")
    p.go_to_page(4)
    p.wait_for(r"page 4 6")
    top = page.evaluate(
        "() => document.querySelectorAll('.docx-wrapper > section.docx')[3].getBoundingClientRect().top"
    )
    assert abs(top) <= 2


def test_go_to_page_past_the_end_is_ignored(viewer, page, port):
    open_word(viewer, page)
    p = port()
    p.wait_for(r"page 1 6")
    p.go_to_page(99)
    p.go_to_page("x")
    page.wait_for_timeout(300)
    assert page.evaluate("() => window.scrollY") == 0
    assert p.pages() == ["page 1 6"]
    # Nor does it throw, which the channel answers with a stray search count
    assert p.counts() == []


def test_a_document_of_one_page_says_nothing_about_pages(viewer, page, port):
    open_word(viewer, page, "report.docx")
    p = port()
    page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)")
    page.wait_for_timeout(400)
    assert p.pages() == []
