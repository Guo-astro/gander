"""
epub.html against a book written to do harm.

A book is the one kind of file whose own markup and stylesheets Gander draws nearly as they
are written, so each test here is a book that tries something: to run script, to ask a host
for something, to take the reader off the page, to draw over Gander's own parts, or to keep
the page busy until the reader gives up. conftest fails any test where the page's policy
had to refuse something, so each also checks that the reader itself never tried.
"""

from pathlib import Path

import pytest

from test_epub import go_to, little_book, open_book, style_of, wait_for_book, wait_for_heading

XHTML = 'xmlns="http://www.w3.org/1999/xhtml"'
SVG = 'xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"'


def in_book(page, script, arg=None):
    """Runs [script] with the chapter's shadow tree as `root`."""
    return page.evaluate(
        "(arg) => { const root = document.querySelector('#book').shadowRoot; " + script + " }", arg
    )


def tap_all(page, selector):
    """A real tap on everything in the chapter that [selector] finds and that has a box."""
    spots = in_book(
        page,
        """return [...root.querySelectorAll(arg)].map(el => {
             el.scrollIntoView({ block: 'center' });
             const r = el.getBoundingClientRect();
             return r.width && r.height ? [r.left + r.width / 2, r.top + r.height / 2] : null;
           }).filter(Boolean).length;""",
        selector,
    )
    for n in range(spots):
        at = in_book(
            page,
            """const el = [...root.querySelectorAll(arg[0])].filter(el => {
                 const r = el.getBoundingClientRect();
                 return r.width && r.height;
               })[arg[1]];
               if (!el) return null;
               el.scrollIntoView({ block: 'center' });
               const r = el.getBoundingClientRect();
               return [r.left + Math.min(r.width / 2, 4), r.top + Math.min(r.height / 2, 4)];""",
            [selector, n],
        )
        if at:
            page.mouse.click(at[0], at[1])
            page.wait_for_timeout(60)
    return spots


def asked_of(page):
    """Every address the page asks for from here on, but for bytes it already holds."""
    asked = []
    page.on("request", lambda r: asked.append(r.url) if not r.url.startswith(("blob:", "data:")) else None)
    return asked


# ---------------------------------------------------------------------------
# Script
# ---------------------------------------------------------------------------

SOUND_SCRIPTS = f"""<body onload="window.__xss='body onload'">
<h1>Sound and scripted</h1>
<script>window.__xss = 'script in the body'</script>
<script src="evil.js"></script>
<p><img src="nowhere.png" onerror="window.__xss='onerror'" alt=""/>
<img src="pix.png" onload="window.__xss='onload'" alt=""/>
<span class="tap" onclick="window.__xss='onclick'" onmouseover="window.__xss='over'">tap me</span>
<video autoplay="autoplay" onloadstart="window.__xss='loadstart'" src="nowhere.mp4"></video></p>
<details open="open" ontoggle="window.__xss='toggle'"><summary class="tap">more</summary>less</details>
<p><a class="tap" href="javascript:window.__xss='a'">one</a>
<a class="tap" href="JaVaScRiPt:window.__xss='a2'">two</a>
<a class="tap" href="&#106;avascript:window.__xss='a3'">three</a>
<a class="tap" href=" &#9;java&#10;script:window.__xss='a4'">four</a>
<a class="tap" href="vbscript:window.__xss='a5'">five</a>
<a class="tap" href="data:text/html,&lt;script&gt;parent.__xss='a6'&lt;/script&gt;">six</a></p>
<iframe srcdoc="&lt;script&gt;parent.__xss='srcdoc'&lt;/script&gt;"></iframe>
<iframe src="javascript:parent.__xss='frame'"></iframe>
<object data="evil.html"></object><embed src="evil.html"/>
<form action="javascript:window.__xss='form'"><button class="tap" formaction="javascript:window.__xss='button'">go</button>
<input type="image" src="pix.png" formaction="javascript:window.__xss='input'"/></form>
<meta http-equiv="refresh" content="0;url=javascript:window.__xss='meta'"/>
<svg {SVG} width="300" height="40" onload="window.__xss='svg onload'">
  <script>window.__xss = 'script in a drawing'</script>
  <script xlink:href="evil.js"></script>
  <a class="tap" xlink:href="javascript:window.__xss='svg a'"><text x="0" y="20">drawn link</text></a>
  <a class="tap"><animate attributeName="href" values="javascript:window.__xss='animate'" dur="1s" repeatCount="indefinite"/>
    <set attributeName="xlink:href" to="javascript:window.__xss='set'"/><text x="120" y="20">moving link</text></a>
  <foreignObject width="10" height="10"><body {XHTML} onload="window.__xss='foreign'"><script>window.__xss = 'foreign script'</script></body></foreignObject>
  <use xlink:href="data:image/svg+xml,&lt;svg xmlns='http://www.w3.org/2000/svg' id='x' onload='parent.__xss=1'/&gt;#x"/>
</svg>
<math xmlns="http://www.w3.org/1998/Math/MathML"><maction actiontype="statusline#javascript:window.__xss='maction'" xlink:href="javascript:window.__xss='math'" {SVG.split(' ')[1]}><mi class="tap">x</mi></maction></math>
<p class="after">Text beside the scripts.</p>
</body>"""

# Not sound XML, for the &nbsp; in it, so this one is read as HTML: the ways markup has been
# got past a cleaner by being read one way and written back another
UNSOUND_SCRIPTS = """<body><h1>Unsound&nbsp;and scripted</h1>
<p class="after">Text beside the scripts. <a class=tap href=javascript:window.__xss='a7'>seven</a></p>
<img src=x onerror="window.__xss='plain'">
<script>window.__xss = 'script, as html'</script>
<noscript><p title="</noscript><img src=x onerror=window.__xss='noscript'>"></noscript>
<svg><style><img src=x onerror="window.__xss='svg style'"></style></svg>
<math><mtext><table><mglyph><style><!--</style><img title="--&gt;&lt;/mglyph&gt;&lt;img src=1 onerror=window.__xss='mglyph'&gt;"></table></mtext></math>
<form><math><mtext></form><form><mglyph><style></math><img src onerror="window.__xss='form'"></form>
<svg></p><style><a id="</style><img src=1 onerror=window.__xss='svg p'>"></svg>
<svg onload="window.__xss='svg onload, as html'"></svg>
<iframe src="javascript:parent.__xss='frame, as html'"></iframe>
<xmp><img src=x onerror="window.__xss='xmp'"></xmp>
</body>"""

DRAWN_SCRIPTS = f"""<?xml version="1.0" encoding="UTF-8"?>
<svg {SVG} viewBox="0 0 200 100" onload="window.__xss='drawing onload'">
  <script>window.__xss = 'script in a drawing of its own'</script>
  <a class="tap" xlink:href="javascript:window.__xss='drawing link'"><text x="10" y="40">A drawn page</text></a>
  <text class="after" x="10" y="80">Text beside the scripts.</text>
  <foreignObject width="100" height="40" y="50"><div {XHTML}><img src="x" onerror="window.__xss='drawing foreign'" alt=""/></div></foreignObject>
</svg>"""

DANGEROUS = "script, iframe, frame, object, embed, form, button, input, meta, base, link, style"


@pytest.mark.parametrize("chapter", [0, 1, 2])
def test_no_script_in_a_book_runs_however_it_is_written(viewer, page, made, fixture_path, chapter):
    """
    A chapter that is sound XHTML, one that is read as HTML, and a drawing in the reading
    order, each with script in every place markup has for it. None of it may run, none of
    it may reach the page, and a tap on any of it must leave the reader where they were.
    """
    book = little_book(
        made, "scripted.epub",
        [("", "<script>window.__xss = 'script in the head'</script>", SOUND_SCRIPTS),
         ("", "", UNSOUND_SCRIPTS)],
        listed='<item id="pix" href="pix.png" media-type="image/png"/>'
               '<item id="js" href="evil.js" media-type="text/javascript"/>'
               '<item id="page" href="evil.html" media-type="text/html"/>'
               '<item id="draw" href="draw.svg" media-type="image/svg+xml"/>',
        order=["c0", "c1", "draw"],
        extra={
            "pix.png": fixture_path("tiny.png").read_bytes(),
            "evil.js": "window.__xss = 'a script file in the book'",
            "evil.html": "<script>parent.__xss = 'a page in the book'</script>",
            "draw.svg": DRAWN_SCRIPTS,
        },
    )
    open_book(viewer, page, book)
    if chapter:
        go_to(page, chapter)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('.after')"
    )
    here = page.url
    assert tap_all(page, ".tap, a, summary, text") > 0
    page.wait_for_timeout(400)

    assert page.evaluate("() => window.__xss") is None
    assert page.url == here
    assert len(page.frames) == 1
    assert in_book(page, "return root.querySelector('.vw-page').querySelectorAll(arg).length", DANGEROUS) == 0
    assert in_book(
        page,
        """return [...root.querySelector('.vw-page').querySelectorAll('*')].flatMap(el =>
             [...el.attributes].filter(a => /^on/i.test(a.name) ||
               /^[\\s\\u0000-\\u0020]*(javascript|vbscript|data:text)/i.test(a.value.replace(/[\\t\\n\\r]/g, '')))
               .map(a => el.localName + ' ' + a.name));""",
    ) == []
    assert "Text beside the scripts." in in_book(page, "return root.querySelector('.after').textContent")


# ---------------------------------------------------------------------------
# A tap that would take the reader somewhere else
# ---------------------------------------------------------------------------

def leaving(made, fixture_path, target):
    """A book whose one chapter offers [target] in each way a tap can be given somewhere to go."""
    body = f"""<body><h1>Ways out</h1>
<p><img id="mapped" src="pix.png" usemap="#m" width="200" height="100" alt=""/></p>
<map name="m"><area shape="rect" coords="0,0,200,100" href="{target}" alt="a part of the picture"/></map>
<p><a id="plain" href="{target}" target="_blank" rel="opener">a link that asks for a window of its own</a></p>
<svg {SVG} width="300" height="60">
  <a id="drawn" xlink:href="{target}"><text x="0" y="50">a drawn link</text></a>
</svg>
</body>"""
    return little_book(
        made, "ways-out.epub", [("", "", body)],
        listed='<item id="pix" href="pix.png" media-type="image/png"/>',
        extra={"pix.png": fixture_path("tiny.png").read_bytes()},
    )


@pytest.mark.parametrize("target,shown", [
    ("https://example.invalid/away", "https://example.invalid/away"),
    ("//example.invalid/away", "https://example.invalid/away"),
    # Gander's own pages, which the app lets a viewer go to: see isViewerPage()
    ("text.html?name=spoof", None),
    ("/assets/viewer/unsupported.html?name=spoof&amp;ext=apk", None),
    ("../../doc/book", None),
])
@pytest.mark.parametrize("way", ["#mapped", "#plain", "#drawn text"])
def test_no_tap_in_a_book_leaves_the_page(viewer, page, made, fixture_path, target, shown, way):
    """
    A link, a part of a picture that is one, and a link in a drawing. None may take the
    page anywhere: an address on the web is shown for the reader to copy, as test_epub.py
    has it for a plain link, and any other does nothing.
    """
    asked = asked_of(page)
    book = leaving(made, fixture_path, target)
    open_book(viewer, page, book)
    page.wait_for_function(
        "() => document.querySelector('#book').shadowRoot.querySelector('#mapped').naturalWidth > 0"
    )
    here = page.url
    del asked[:]
    assert tap_all(page, way) == 1
    page.wait_for_timeout(500)

    assert page.url == here
    assert len(page.context.pages) == 1
    assert asked == []
    assert "Ways out" in page.text_content("#book h1")
    if shown:
        assert page.is_visible("#epub-link")
        assert page.text_content("#epub-link-address") == shown
    else:
        assert not page.is_visible("#epub-link")


# ---------------------------------------------------------------------------
# Asking a host for something
# ---------------------------------------------------------------------------

REACHING_CSS = """@import "https://example.invalid/i1.css";
@import/**/"https://example.invalid/i2.css";
@\\69mport "https://example.invalid/i3.css";
@import url(https://example.invalid/i4.css);
@import "leak-i5.css";
@IMPORT/**/'leak-i6.css';
@import url("\\68ttps://example.invalid/i7.css");
@font-face { font-family: "Far"; src: url("https://example.invalid/f1.woff2"), url(leak-f2.woff2); }
h1 { color: rgb(1, 2, 3); font-family: "Far", serif; }
.a { background-image: url(https://example.invalid/u1.png); }
.b { background-image: u\\72l(https://example.invalid/u2.png); }
.c { background-image: \\75 rl(https://example.invalid/u3.png); }
.d { background-image: image-set("https://example.invalid/s1.png" 1x); }
.e { background-image: -webkit-image-set(url("https://example.invalid/s2.png") 1x); }
.f { background-image: image-set("leak-s3.png" 1x, "leak-s3b.png" 2x); }
.g { --far: "https://example.invalid/s4.png"; background-image: image-set(var(--far) 1x); }
.h { background: URL(  'https://example.invalid/u5.png'  ); }
.i { list-style-image: url("leak-u6.png"); cursor: url(leak-u7.cur), auto; }
.i::before { content: url(leak-u8.png); }
.j { border-image: url(//example.invalid/u9.png) 1; -webkit-mask-image: url(https://example.invalid/u10.png); }
.k { background-image: image-\\73 et("https://example.invalid/s5.png" 1x); }
.l { background-image: IMAGE-SET(/**/"leak-s6.png"/**/1x); }
.m { background-image: \\55RL(leak-u11.png); }
.n { background-image: url(https\\3a //example.invalid/u12.png); }
p { margin-top: 3px; }
"""

REACHING = f"""<body background="leak-b1.png"><h1>Reaching out</h1>
<div class="a">a</div><div class="b">b</div><div class="c">c</div><div class="d">d</div>
<div class="e">e</div><div class="f">f</div><div class="g">g</div><div class="h">h</div>
<ul><li class="i">i</li></ul><div class="j">j</div><div class="k">k</div><div class="l">l</div>
<div class="m">m</div><div class="n">n</div>
<div style="background-image: url(https://example.invalid/n1.png)">n1</div>
<div style="background-image: u\\72l(https://example.invalid/n2.png)">n2</div>
<div style="background-image: image-set('https://example.invalid/n3.png' 1x)">n3</div>
<div style="background-image: image-set('leak-n4.png' 1x)">n4</div>
<div style="background-image:/**/url(leak-n5.png)">n5</div>
<div style="background-image: \\75 rl(leak-n6.png)">n6</div>
<svg {SVG} width="60" height="20">
  <rect width="10" height="10" mask="url(https://example.invalid/m1.svg#m)" filter="url(https://example.invalid/m2.svg#f)"
        fill="url(leak-m3.svg#p)" clip-path="url(leak-m4.svg#c)"/>
  <rect x="12" width="10" height="10" style="mask: url(leak-m8.svg#m)" marker-start="url(leak-m9.svg#k)"/>
  <image xlink:href="https://example.invalid/m5.png" width="5" height="5"/>
  <image href="leak-m5b.png" width="5" height="5"/>
  <use xlink:href="https://example.invalid/m6.svg#u"/><use href="leak-m7.svg#u"/>
  <filter id="f"><feImage xlink:href="https://example.invalid/m10.png"/></filter>
</svg>
<p><img src="//example.invalid/p1.png" srcset="https://example.invalid/p2.png 2x, leak-p2b.png 3x" alt=""/>
<picture><source srcset="https://example.invalid/p3.png"/><img src="leak-p4.png" alt=""/></picture>
<img src="leak-p4b.png" lowsrc="leak-p4c.png" longdesc="leak-p4d.html" alt=""/></p>
<video poster="https://example.invalid/p5.png" src="https://example.invalid/p6.mp4" preload="auto">
  <source src="leak-p7.mp4"/><track src="leak-p8.vtt" default="default"/></video>
<audio src="https://example.invalid/p9.mp3" preload="auto"></audio>
<p><a id="pinged" href="#top" ping="https://example.invalid/ping leak-ping">a link that tells on its reader</a></p>
<table background="https://example.invalid/t1.png"><tr><td background="leak-t2.png">t</td></tr></table>
<style>@import "https://example.invalid/h3.css"; .o {{ background: url(leak-h4.png); }}</style>
<div class="o">o</div>
</body>"""

REACHING_HEAD = """<link rel="stylesheet" href="reach.css"/>
<link rel="stylesheet" href="https://example.invalid/l1.css"/>
<link rel="prefetch" href="https://example.invalid/l2"/>
<link rel="preload" as="image" href="leak-l3.png"/>
<link rel="dns-prefetch" href="//example.invalid"/>
<link rel="preconnect" href="https://example.invalid"/>
<link rel="icon" href="leak-l4.png"/>
<link rel="manifest" href="leak-l5.json"/>
<style>@import "https://example.invalid/h1.css"; @import 'leak-h2.css';</style>
"""


def test_nothing_is_asked_of_any_host_however_a_book_names_it(viewer, page, made):
    """
    Every way a chapter or a stylesheet has of naming a file: on another host, which the
    policy would refuse, and beside the page itself, which it would not, so that a book
    could ask Gander's own host for whatever it liked. Neither may be asked for, and the
    rules written beside them must still hold.
    """
    asked = asked_of(page)
    book = little_book(
        made, "reaching.epub", [("", REACHING_HEAD, REACHING)],
        listed='<item id="css" href="reach.css" media-type="text/css"/>',
        extra={"reach.css": REACHING_CSS},
    )
    open_book(viewer, page, book)
    wait_for_heading(page, "Reaching out")
    page.click("#book #pinged")
    page.wait_for_timeout(700)

    assert [u.rsplit("/", 1)[-1] for u in asked if "example.invalid" in u or "leak-" in u] == []
    assert style_of(page, "h1", "color") == "rgb(1, 2, 3)"
    assert style_of(page, "p", "marginTop") == "3px"


# ---------------------------------------------------------------------------
# Gander's own parts of the page
# ---------------------------------------------------------------------------

OVER_CSS = """:host, :host(#book), :host(main#book#book#book) { position: fixed !important; top: 0 !important;
  left: 0 !important; width: 100vw !important; height: 100vh !important; overflow: auto !important;
  z-index: 2147483647 !important; background: rgb(200, 0, 0) !important; }
html, body, #epub-bar, .epub-bar, #epub-turn, #epub-contents, #epub-link, #vw-status { display: none !important; }
:host-context(body) h1 { color: rgb(1, 2, 3); }
"""


def test_a_book_cannot_draw_over_the_contents_or_a_links_address(viewer, page, made, port):
    """
    A stylesheet in the shadow tree can reach the element the tree hangs on, and nothing
    outranks what it says there with !important. So a book can lay itself over the whole
    screen, and the two things of Gander's own that open over a book must still open over
    this one: its contents, and the address a link leaves for.
    """
    book = little_book(
        made, "over.epub",
        [("", "<style>" + OVER_CSS + "</style>",
          '<body><h1>Over everything</h1><p><a id="out" href="https://example.invalid/">out</a></p></body>')],
    )
    open_book(viewer, page, book)
    wait_for_heading(page, "Over everything")
    on_top = """(id) => {
      const el = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
      return !!(el && el.closest('#' + id));
    }"""
    # The book did get over the page, or this test shows nothing
    assert page.evaluate(on_top, "book")
    assert page.evaluate("() => getComputedStyle(document.querySelector('#epub-bar')).display") != "none"

    app = port()
    app.contents()
    page.wait_for_function("() => !document.querySelector('#epub-contents').hidden")
    assert page.evaluate(on_top, "epub-contents")
    app.close_over()
    page.wait_for_function("() => document.querySelector('#epub-contents').hidden")

    page.click("#book #out")
    page.wait_for_function("() => !document.querySelector('#epub-link').hidden")
    assert page.evaluate(on_top, "epub-link")


FACES = ["Roboto", "sans-serif", "-apple-system", "Segoe UI", "system-ui", "serif", "monospace",
         "Arial", "Helvetica", "Times New Roman", "Courier New"]


def test_a_books_font_is_not_what_gander_writes_in(viewer, page, made):
    """
    A book's fonts are named in the page's own head, where a shadow tree's would do nothing,
    so a book that names one of its fonts after the font Gander writes in would have the
    address of a link, and everything else Gander says on the page, set in a font of the
    book's choosing: one whose letters need not be the letters they stand for.
    """
    from reportlab.pdfbase import pdfmetrics
    face = (Path(pdfmetrics.__file__).parent.parent / "fonts" / "VeraBd.ttf").read_bytes()
    css = "".join(
        f'@font-face {{ font-family: "{name}"; src: url("face.ttf"); }}\n'
        f'@font-face {{ font-family: {name}; src: url("face.ttf"); }}\n'
        for name in FACES if " " not in name
    ) + "".join(f'@font-face {{ font-family: "{name}"; src: url("face.ttf"); }}\n' for name in FACES if " " in name)
    book = little_book(
        made, "faces.epub",
        [("", '<link rel="stylesheet" href="book.css"/>',
          '<body><h1 style="font-family: \'Other Face\', cursive">Faces</h1>'
          '<p style="font-family: cursive"><a id="out" href="https://example.invalid/">out</a></p></body>')],
        listed='<item id="css" href="book.css" media-type="text/css"/><item id="face" href="face.ttf" media-type="font/ttf"/>',
        extra={"book.css": css, "face.ttf": face},
    )
    open_book(viewer, page, book)
    wait_for_heading(page, "Faces")
    page.click("#book #out")
    page.wait_for_function("() => !document.querySelector('#epub-link').hidden")
    page.click("#epub-link-close")
    page.click("#epub-contents-open")
    page.wait_for_function("() => !document.querySelector('#epub-contents').hidden")
    page.evaluate("() => document.fonts.ready")
    page.wait_for_timeout(300)

    # The book did name them, or this test shows nothing
    assert page.evaluate("() => [...document.fonts].length") >= len(FACES)
    # A font is read only when something on the page is to be written in it, and nothing in
    # the chapter asks for one of these
    assert page.evaluate(
        "() => [...document.fonts].filter(f => f.status !== 'unloaded').map(f => f.family)"
    ) == []


# ---------------------------------------------------------------------------
# Keeping the page busy
# ---------------------------------------------------------------------------

def fanned_out():
    """Eight stylesheets, each bringing in the next ten times over: ten million readings."""
    sheets = {f"s{n}.css": "".join(f'@import "s{n + 1}.css";\n' for _ in range(10)) for n in range(7)}
    sheets["s7.css"] = "h1 { color: rgb(1, 2, 3); }\n"
    return sheets


BUSY = {
    "imports that fan out": dict(head='<link rel="stylesheet" href="s0.css"/>', extra=fanned_out()),
    "imports with no end": dict(
        head='<link rel="stylesheet" href="b.css"/>',
        extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + "@import x " * 60000}),
    "font rules with no end": dict(
        head='<link rel="stylesheet" href="b.css"/>',
        extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + "@font-face { src: local(x) " * 60000}),
    "addresses with no end": dict(
        head='<link rel="stylesheet" href="b.css"/>',
        extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + "url(a " * 60000 + "\n" + 'url("a ' * 60000}),
    "sets of pictures with no end": dict(
        head='<link rel="stylesheet" href="b.css"/>',
        extra={"b.css": "h1 { color: rgb(1, 2, 3); }\n" + "p { background: image-set( " * 60000}),
    # Read as HTML, for the &nbsp;, which is where a chapter's empty elements are spelled out
    "a tag with a long gap in it": dict(tail="&nbsp;<a" + " " * 500000 + "x<p>after</p>"),
}


@pytest.mark.parametrize("how", sorted(BUSY))
def test_a_book_written_to_keep_the_page_busy_still_opens(viewer, page, made, how):
    """
    Files a few hundred kilobytes long, which is nothing to read, shaped so that reading
    them the simple way takes minutes: the same stretch gone over once for every place in
    it that looks like the start of something. Each must open in the time any book does.
    """
    case = BUSY[how]
    contents = "".join(f'<li><a href="c0.xhtml">Part {n}</a></li>' for n in range(3))
    book = little_book(
        made, "busy.epub",
        [("", case.get("head", ""),
          f'<body><h1>Busy</h1>{case.get("tail", "")}</body>')],
        contents=contents, extra=case.get("extra"),
    )
    viewer("epub.html", book)
    wait_for_book(page, timeout=15000)
    assert "Busy" in page.text_content("#book h1")
    if "head" in case:
        assert style_of(page, "h1", "color") == "rgb(1, 2, 3)"
