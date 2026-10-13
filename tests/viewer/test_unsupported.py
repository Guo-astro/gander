"""unsupported.html: the last resort, and the way out of it."""


def test_the_file_is_named(viewer, page):
    viewer("unsupported.html", "unknown.xyz")
    page.wait_for_selector("#fname", timeout=10000)
    assert page.text_content("#fname").strip() == "unknown.xyz"


def test_legacy_powerpoint_gets_an_explanation_of_its_own(viewer, page):
    """
    .ppt is the one Office format Gander deliberately does not open, now that
    .doc has a reader of its own, so it is told why rather than just refused.
    """
    viewer("unsupported.html", "unknown.xyz", name="slides.ppt", ext="ppt")
    page.wait_for_selector("#hint", timeout=10000)
    assert ".pptx" in page.text_content("#hint")


def test_reading_it_as_text_is_offered_with_a_caveat(viewer, page):
    viewer("unsupported.html", "unknown.xyz")
    page.wait_for_selector("#asText", timeout=10000)
    assert page.get_attribute("#asText", "aria-describedby") == "caveat"
    assert "random characters" in page.text_content("#caveat")


def test_the_way_to_skip_the_card_is_named_where_the_menu_offers_it(viewer, page):
    """
    astext=1 is the app saying its menu has "View unknown files as text", issue #51,
    and the card then points at it by the name the menu gives it.
    """
    viewer("unsupported.html", "unknown.xyz", astext=1)
    page.wait_for_selector("#skip", state="visible", timeout=10000)
    assert '"View unknown files as text"' in page.text_content("#skip")


def test_the_card_does_not_point_at_a_menu_item_that_is_not_there(viewer, page):
    """A .ppt, or a track the player gave up on, gets the card without the menu item."""
    viewer("unsupported.html", "unknown.xyz")
    # The name is written by the script that would show the line, so it has run
    page.wait_for_function("document.getElementById('fname').textContent !== ''", timeout=10000)
    assert not page.is_visible("#skip")


def test_reading_it_as_text_opens_the_text_viewer(viewer, page):
    viewer("unsupported.html", "unknown.xyz")
    page.wait_for_selector("#asText", timeout=10000)

    page.click("#asText")
    page.wait_for_url("**/text.html*", timeout=15000)

    assert "name=unknown.xyz" in page.url
    assert "ext=xyz" in page.url
    page.wait_for_function(
        "() => document.querySelector('#content').textContent.length > 0", timeout=20000
    )
