"""xlsx.html: SheetJS into a table, one sheet at a time."""

from helpers import wait_until_done


def wait_for_sheet(page, timeout=25000):
    page.wait_for_function(
        "() => document.querySelector('#sheet') && "
        "document.querySelector('#sheet').textContent.trim().length > 0",
        timeout=timeout,
    )


def test_a_workbook_shows_its_first_sheet(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    assert "Surveying" in page.text_content("#sheet")


def test_the_cells_arrive_as_a_table(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    assert page.query_selector("#sheet table") is not None
    assert len(page.query_selector_all("#sheet tr")) >= 4


def test_every_sheet_gets_a_tab(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    page.wait_for_selector("#tabs button", timeout=10000)
    assert len(page.query_selector_all("#tabs button")) == 3


def test_choosing_a_tab_swaps_the_sheet(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    page.wait_for_selector("#tabs button", timeout=10000)

    page.query_selector_all("#tabs button")[1].click()
    page.wait_for_function(
        "() => document.querySelector('#sheet').textContent.indexOf('detail-sheet') >= 0",
        timeout=10000,
    )
    assert "Surveying" not in page.text_content("#sheet")


def test_a_csv_opens_as_a_single_sheet(viewer, page):
    """A CSV goes to the spreadsheet viewer rather than the text one."""
    viewer("xlsx.html", "budget.csv")
    wait_for_sheet(page)
    assert "Surveying" in page.text_content("#sheet")
    assert page.query_selector("#tabs").is_hidden() or \
        len(page.query_selector_all("#tabs button")) <= 1


def test_a_utf8_csv_with_no_byte_order_mark_reads_as_utf8(viewer, page):
    """Issue #37: handed bytes, SheetJS read this as Latin-1, and Флаг came out as Ð¤Ð»Ð°Ð³."""
    viewer("xlsx.html", "utf8.csv")
    wait_for_sheet(page)
    text = page.text_content("#sheet")
    for word in ("Флаг", "Straße", "東京", "\U0001F1EA\U0001F1FA"):
        assert word in text


def test_a_latin1_csv_still_reads_as_latin1(viewer, page):
    """Not UTF-8, so it goes to SheetJS as bytes, as every CSV did before #37."""
    viewer("xlsx.html", "latin1.csv")
    wait_for_sheet(page)
    text = page.text_content("#sheet")
    assert "Café" in text
    assert "Grüße" in text


def test_a_template_opens_as_a_workbook_does(viewer, page, main_part):
    """
    budget.xlsx with its main part declared as a template's, which is all that
    tells an .xltx apart. SheetJS knows that type for a workbook, and would try
    xl/workbook.xml by name if it did not.
    """
    assert main_part("budget.xltx") == [
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml"
    ]
    viewer("xlsx.html", "budget.xltx")
    wait_for_sheet(page)
    wait_until_done(page)
    assert "Surveying" in page.text_content("#sheet")
    assert len(page.query_selector_all("#tabs button")) == 3
