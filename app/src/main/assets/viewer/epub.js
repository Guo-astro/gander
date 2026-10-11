"use strict";

/*
 * epub.html: an EPUB, the format most books that are not PDFs come in. Issue #50.
 *
 * A book is a zip of web pages. Each chapter is an XHTML file with its stylesheets, pictures
 * and fonts beside it, and a package file lists the chapters in reading order. So this is a
 * reader of Gander's own over two libraries the app already carried, JSZip to open the zip
 * and DOMPurify to clean a chapter. The libraries written for the job were looked at first:
 * epub.js last had a release in 2022 and is 224 KB before the JSZip it needs, and foliate-js
 * supports only the newest engines and has no stable release.
 *
 * A chapter is somebody else's markup, and three things stand between it and the page.
 * DOMPurify takes out script, handlers, forms and frames. Nothing in a chapter keeps an
 * address: a picture, font or stylesheet is read out of the zip here and handed over as an
 * object URL, and anything pointing outside the book is dropped. And the page's policy
 * (ViewerPolicy.kt) runs no script that is not Gander's own, whatever got past the first two.
 *
 * One chapter is on the page at a time, in a shadow tree, so the book's stylesheets cannot
 * reach the page around it and the page's cannot reach the book. Inside it the chapter gets
 * an html and a body element of its own, which is what its stylesheets expect to find.
 *
 * A book has no pages, since its text is laid out afresh on every screen, so it is counted
 * in equal stretches of its files instead: see VW_EPUB_PAGE_BYTES. Those are what the page
 * counter, Go to page and the place a book reopens at all speak in.
 */

/*
 * How much of a chapter's file counts as a page. A novel's files come to about this much for
 * each page of the printed book, so the count lands near the number a reader expects, and it
 * does not change with the size of the screen or of the type.
 */
var VW_EPUB_PAGE_BYTES = 2048;

var vwEpub = (function () {
  var XLINK = "http://www.w3.org/1999/xlink";

  /* The two ways a book's fonts are scrambled so they cannot be lifted out of it. Not a lock on
     the book: the text reads without them, in the phone's own faces. */
  var FONT_SCRAMBLES = /^(http:\/\/www\.idpf\.org\/2008\/embedding|http:\/\/ns\.adobe\.com\/pdf\/enc#RC)$/;

  /* Elements with no end tag in HTML, which is what tells them from one written as <p/> */
  var VOID = /^(area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)$/i;

  /*
   * What DOMPurify takes out beyond its own list. A chapter's stylesheets are read and
   * rewritten on their own, see sheet(). Forms and frames have no place in a book. The
   * attributes are the ones that fetch something or act by themselves.
   */
  var PURIFY = {
    RETURN_DOM_FRAGMENT: true,
    FORBID_TAGS: ["style", "link", "meta", "base", "form", "input", "button", "select",
      "option", "textarea", "iframe", "frame", "object", "embed", "dialog"],
    FORBID_ATTR: ["srcset", "sizes", "background", "autoplay", "ping", "action", "formaction"],
    ADD_ATTR: ["epub:type", "xml:lang"]
  };

  /* By extension, for a file the package gives no type or a wrong one */
  var TYPES = {
    png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif",
    webp: "image/webp", svg: "image/svg+xml", avif: "image/avif", bmp: "image/bmp",
    mp3: "audio/mpeg", m4a: "audio/mp4", ogg: "audio/ogg", wav: "audio/wav",
    mp4: "video/mp4", webm: "video/webm",
    ttf: "font/ttf", otf: "font/otf", woff: "font/woff", woff2: "font/woff2"
  };

  var URL_IN_CSS = /url\(\s*(?:"([^"]*)"|'([^']*)'|([^'")\s][^)]*?))\s*\)/gi;
  var IMPORT_IN_CSS = /@import\s*(?:url\(\s*)?(["']?)([^"')\s;]+)\1\s*\)?[^;]*;/gi;

  /*
   * The chapter's surroundings inside the shadow tree. The plain rules come before the
   * book's stylesheets, which may overrule them; the ones marked important are what keeps a
   * chapter inside the screen and readable on a dark one. A dark phone gets the book's words
   * in light ink on the page's dark ground, whatever colours the book set, and its pictures
   * as they are.
   */
  var BOOK_CSS = [
    ":host { display: block; }",
    ".vw-page { contain: layout; box-sizing: border-box; max-width: 42em; margin: 0 auto;",
    "  padding: 8px 20px 24px; font-family: serif; font-size: 17px; line-height: 1.55;",
    "  overflow-wrap: break-word; word-wrap: break-word; }",
    "html, body { display: block; margin: 0; padding: 0; }",
    "img, svg, video { max-width: 100% !important; }",
    "img, video { height: auto; }",
    "svg { max-height: 100vh; }",
    "pre { white-space: pre-wrap; }",
    "table { max-width: 100%; }",
    "::highlight(vw-find) { background-color: rgba(255, 213, 79, 0.45); }",
    "::highlight(vw-find-active) { background-color: rgba(255, 145, 0, 0.62); }",
    "@media (prefers-color-scheme: dark) {",
    "  html, body, body * { color: #E7E2D9 !important; background-color: transparent !important; }",
    "  a, a * { color: #FFB39E !important; }",
    "  svg text, svg tspan { fill: #E7E2D9 !important; }",
    "}"
  ].join("\n");

  var zip = null;
  /* Every file in the zip by its name in lower case, for a book that spells one differently */
  var names = {};
  /* The type the package gives each file, by path */
  var types = {};
  /* Files that are encrypted or scrambled, which nothing here can read */
  var sealed = {};
  /* Of those, the ones under a real lock, which is what DRM is */
  var locked = {};
  /* The chapters in reading order: { path, pages, start, wait, ready } */
  var spine = [];
  /* A chapter's place in the spine, by path */
  var index = {};
  /* The contents list: { label, depth, at, frag }, at being -1 for a heading that goes nowhere */
  var contents = [];
  var bookTitle = "";
  var total = 0;

  var shown = -1;
  /* The body of the chapter on the page */
  var onPage = null;
  /* Counts chapters asked for, so that work for one the reader has left is dropped */
  var turn = 0;
  /* The page last told to the app */
  var sent = 0;
  var opened = false;
  /* Whether the reader has moved the page since the chapter went on it */
  var moved = false;
  /* Set by a search: every chapter is read and kept, so the whole book can be searched */
  var wholeBook = false;
  /* While that reading is going on */
  var reading = false;
  /* Object URLs made for the chapter on the page, by path, given back when it leaves */
  var held = {};
  /* The same for what stylesheets name, kept as long as the book, since chapters share them */
  var shared = {};
  /* Each stylesheet as the page can use it, by path */
  var sheets = {};

  var host = document.getElementById("book");
  var shadow = host.attachShadow({ mode: "open" });
  var bookSheets = document.createElement("div");
  var page = document.createElement("div");
  /* A chapter's @font-face rules, which do nothing inside a shadow tree */
  var faces = document.createElement("style");
  var EMPTY = document.createElement("div");

  var bar = document.getElementById("epub-bar");
  var where = document.getElementById("epub-where");
  var turnBar = document.getElementById("epub-turn");
  var prev = document.getElementById("epub-prev");
  var next = document.getElementById("epub-next");
  var panel = document.getElementById("epub-contents");
  var list = document.getElementById("epub-contents-list");
  var openContents = document.getElementById("epub-contents-open");
  var closeContents = document.getElementById("epub-contents-close");

  (function () {
    var base = document.createElement("style");
    base.textContent = BOOK_CSS;
    page.className = "vw-page";
    shadow.appendChild(base);
    shadow.appendChild(bookSheets);
    shadow.appendChild(page);
    document.head.appendChild(faces);
  })();

  /* An error the reader is shown under a title of its own */
  function problem(title, detail) {
    var e = new Error(detail);
    e.vwTitle = title;
    return e;
  }

  function local(el) {
    return (el.localName || "").toLowerCase();
  }

  function clean(s) {
    return String(s || "").replace(/\s+/g, " ").trim();
  }

  // ---------------------------------------------------------------------------------------
  // The zip
  // ---------------------------------------------------------------------------------------

  function entry(path) {
    var file = zip.file(path);
    if (file) return file;
    var real = names[path.toLowerCase()];
    return real ? zip.file(real) : null;
  }

  function bytes(path) {
    var file = entry(path);
    return file ? file.async("uint8array") : Promise.reject(new Error("The book has no " + path));
  }

  /*
   * Text out of a file's bytes. UTF-8 unless the file says otherwise, in a byte order mark or
   * in the encoding its first line declares, which is where XML and CSS both say it.
   */
  function decode(b) {
    var as = "utf-8";
    if (b.length >= 2 && b[0] === 0xFF && b[1] === 0xFE) as = "utf-16le";
    else if (b.length >= 2 && b[0] === 0xFE && b[1] === 0xFF) as = "utf-16be";
    else {
      var head = "";
      for (var i = 0; i < b.length && i < 200; i++) head += String.fromCharCode(b[i]);
      var said = /^\s*<\?xml[^>]*encoding\s*=\s*["']([\w.-]+)["']/i.exec(head) ||
        /^\s*@charset\s+["']([\w.-]+)["']/i.exec(head);
      if (said) as = said[1];
    }
    try {
      return new TextDecoder(as).decode(b);
    } catch (e) {
      return new TextDecoder("utf-8").decode(b);
    }
  }

  function text(path) {
    return bytes(path).then(decode);
  }

  /*
   * How long a file is once unpacked, without unpacking it. JSZip keeps the figure from the
   * zip's directory but offers no call for it, so this reads where it keeps it; VendoredLibsTest
   * pins the file, so the field cannot move unnoticed. Nought where it is not there, and a
   * chapter of unknown length counts as one page.
   */
  function sizeOf(path) {
    var file = entry(path);
    var data = file && file._data;
    return data && data.uncompressedSize > 0 ? data.uncompressedSize : 0;
  }

  /*
   * The file a reference written in the file at [base] points to, and the place in it, or null
   * for one that leaves the book: anything with a scheme, which is every web and mail address.
   */
  function resolve(base, href) {
    href = String(href == null ? "" : href).trim();
    if (!href || /^[a-z][a-z0-9+.-]*:/i.test(href) || href.indexOf("//") === 0) return null;
    var hash = href.indexOf("#");
    var frag = hash < 0 ? "" : href.slice(hash + 1);
    var path = hash < 0 ? href : href.slice(0, hash);
    var query = path.indexOf("?");
    if (query >= 0) path = path.slice(0, query);
    try { path = decodeURIComponent(path); } catch (e) { /* as written, then */ }
    try { frag = decodeURIComponent(frag); } catch (e) { /* as written, then */ }
    if (!path) return { path: base, frag: frag };
    var from = path.charAt(0) === "/" ? "" : base.slice(0, base.lastIndexOf("/") + 1);
    var steps = (from + path).split("/");
    var out = [];
    for (var i = 0; i < steps.length; i++) {
      if (steps[i] === "" || steps[i] === ".") continue;
      // Nothing is above the top of the zip, so a step up from there stays there
      if (steps[i] === "..") out.pop();
      else out.push(steps[i]);
    }
    return { path: out.join("/"), frag: frag };
  }

  // ---------------------------------------------------------------------------------------
  // The package: what the book is made of, and in what order
  // ---------------------------------------------------------------------------------------

  function xml(source) {
    var doc = new DOMParser().parseFromString(source, "application/xml");
    if (!doc.documentElement || doc.getElementsByTagName("parsererror").length) {
      throw new Error("One of the book's own files is not sound XML.");
    }
    return doc;
  }

  function all(node, name) {
    return node.getElementsByTagNameNS("*", name);
  }

  /* Which files are encrypted, from the list a book carries when any are */
  function readLocks() {
    if (!entry("META-INF/encryption.xml")) return Promise.resolve();
    return text("META-INF/encryption.xml").then(function (source) {
      var data = all(xml(source), "EncryptedData");
      for (var i = 0; i < data.length; i++) {
        var method = all(data[i], "EncryptionMethod")[0];
        var ref = all(data[i], "CipherReference")[0];
        var at = ref && resolve("", ref.getAttribute("URI"));
        if (!at) continue;
        sealed[at.path] = true;
        if (!FONT_SCRAMBLES.test(method ? method.getAttribute("Algorithm") || "" : "")) {
          locked[at.path] = true;
        }
      }
    }).catch(function () { /* a list that cannot be read locks nothing */ });
  }

  function readPackage(doc, path) {
    var byId = {};
    var nav = null;
    var ncx = null;
    var items = all(doc, "item");
    for (var i = 0; i < items.length; i++) {
      var at = resolve(path, items[i].getAttribute("href"));
      if (!at) continue;
      var type = (items[i].getAttribute("media-type") || "").toLowerCase();
      types[at.path] = type;
      byId[items[i].getAttribute("id")] = at.path;
      if (/(^|\s)nav(\s|$)/.test(items[i].getAttribute("properties") || "")) nav = at.path;
      if (type === "application/x-dtbncx+xml" && !ncx) ncx = at.path;
    }

    var refs = all(doc, "itemref");
    for (var r = 0; r < refs.length; r++) {
      var file = byId[refs[r].getAttribute("idref")];
      if (!file || !entry(file) || index[file] !== undefined) continue;
      if (locked[file]) {
        throw problem("This book is locked",
          "It is protected against copying (DRM), so only the app of the shop or library " +
          "it came from can open it.");
      }
      var pages = Math.max(1, Math.ceil(sizeOf(file) / VW_EPUB_PAGE_BYTES));
      index[file] = spine.length;
      spine.push({ path: file, pages: pages, start: total, wait: null, ready: null });
      total += pages;
    }
    if (!spine.length) throw new Error("The book lists no chapters.");

    var named = all(doc, "title")[0];
    bookTitle = named ? clean(named.textContent) : "";
    var tongue = all(doc, "language")[0];
    if (tongue && clean(tongue.textContent)) host.lang = clean(tongue.textContent);

    var spineEl = all(doc, "spine")[0];
    var declared = spineEl && byId[spineEl.getAttribute("toc")];
    return { nav: nav, ncx: declared || ncx };
  }

  // ---------------------------------------------------------------------------------------
  // The contents list, from the newer kind of book's nav file or the older kind's NCX
  // ---------------------------------------------------------------------------------------

  function listed(label, depth, base, href) {
    var at = href ? resolve(base, href) : null;
    var place = at && index[at.path] !== undefined ? index[at.path] : -1;
    return { label: label, depth: depth, at: place, frag: at && place >= 0 ? at.frag : "" };
  }

  function navList(ol, depth, base, out) {
    for (var li = ol.firstElementChild; li; li = li.nextElementSibling) {
      if (local(li) !== "li") continue;
      var named = null;
      var under = null;
      for (var c = li.firstElementChild; c; c = c.nextElementSibling) {
        var name = local(c);
        if (name === "ol") under = c;
        else if (!named && (name === "a" || name === "span")) named = c;
      }
      if (named && clean(named.textContent)) {
        out.push(listed(clean(named.textContent), depth, base, named.getAttribute("href")));
      }
      if (under) navList(under, depth + 1, base, out);
    }
  }

  function readNav(path) {
    return text(path).then(function (source) {
      var navs = all(markup(source), "nav");
      var toc = null;
      for (var i = 0; i < navs.length && !toc; i++) {
        if (/(^|\s)toc(\s|$)/.test(navs[i].getAttribute("epub:type") || "")) toc = navs[i];
      }
      toc = toc || navs[0];
      var out = [];
      for (var c = toc ? toc.firstElementChild : null; c; c = c.nextElementSibling) {
        if (local(c) === "ol") navList(c, 0, path, out);
      }
      return out;
    });
  }

  function ncxPoints(parent, depth, base, out) {
    for (var c = parent.firstElementChild; c; c = c.nextElementSibling) {
      if (local(c) !== "navpoint") continue;
      var label = "";
      var src = null;
      for (var k = c.firstElementChild; k; k = k.nextElementSibling) {
        if (local(k) === "navlabel") label = clean(k.textContent);
        else if (local(k) === "content") src = k.getAttribute("src");
      }
      if (label) out.push(listed(label, depth, base, src));
      ncxPoints(c, depth + 1, base, out);
    }
  }

  function readNcx(path) {
    return text(path).then(function (source) {
      var map = all(xml(source), "navMap")[0];
      var out = [];
      if (map) ncxPoints(map, 0, path, out);
      return out;
    });
  }

  /* The list a book gives, or failing both kinds, its chapters by number */
  function readContents(from) {
    function attempt(read, path) {
      return path ? read(path).catch(function () { return []; }) : Promise.resolve([]);
    }
    return attempt(readNav, from.nav).then(function (found) {
      return found.length ? found : attempt(readNcx, from.ncx);
    }).then(function (found) {
      contents = found;
      if (contents.length) return;
      for (var i = 0; i < spine.length; i++) {
        contents.push({ label: "Part " + (i + 1), depth: 0, at: i, frag: "" });
      }
    });
  }

  function open(buffer) {
    return JSZip.loadAsync(buffer).then(function (z) {
      zip = z;
      for (var name in z.files) names[name.toLowerCase()] = name;
      if (!entry("META-INF/container.xml")) throw new Error("not a book");
      return readLocks();
    }, function () {
      throw new Error("not a book");
    }).catch(function () {
      throw problem("Could not open this book", "It is not an EPUB, or the file is damaged.");
    }).then(function () {
      return text("META-INF/container.xml");
    }).then(function (source) {
      var roots = all(xml(source), "rootfile");
      var path = null;
      for (var i = 0; i < roots.length && !path; i++) path = roots[i].getAttribute("full-path");
      if (!path || !entry(path)) throw new Error("The book does not say where its chapters are listed.");
      return text(path).then(function (pkg) { return readPackage(xml(pkg), path); });
    }).then(readContents);
  }

  // ---------------------------------------------------------------------------------------
  // A chapter, from its file to something safe to put on the page
  // ---------------------------------------------------------------------------------------

  /*
   * A chapter's file as a document. XHTML is XML, and read as XML an empty <a id="x"/> is
   * the empty element it says it is. Half the books in circulation are not sound XML though,
   * most often for an &nbsp; that XML does not know, so one that fails is read again as
   * HTML, which takes anything. Its empty elements are spelled out first, because HTML reads
   * <title/> as a title that never ends and takes the whole chapter for its text.
   */
  function markup(source) {
    var doc = new DOMParser().parseFromString(source, "application/xhtml+xml");
    if (doc.documentElement && !doc.getElementsByTagName("parsererror").length) return doc;
    var spelled = source.replace(/<([A-Za-z][\w:.-]*)((?:\s[^<>]*?)?)\s*\/>/g, function (m, tag, attrs) {
      return VOID.test(tag) ? m : "<" + tag + attrs + "></" + tag + ">";
    });
    return new DOMParser().parseFromString(spelled, "text/html");
  }

  function setAside(el, name) {
    var value = el.getAttribute(name);
    if (value === null) return;
    el.removeAttribute(name);
    el.setAttribute("data-vw-" + name, value);
  }

  /*
   * Takes every address off a chapter before any of it reaches this page, and keeps each in
   * a data attribute for dress() to answer from the zip.
   *
   * It has to happen here, in the document the parser made, which fetches nothing. An img
   * that arrived on this page with its src would be fetched from Gander's own host the moment
   * it was copied in, as a path that is not there.
   */
  function disarm(doc) {
    var els = doc.querySelectorAll("[src], [poster], [style], image, use, feImage, feimage");
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      setAside(el, "src");
      setAside(el, "poster");
      if (/url\(/i.test(el.getAttribute("style") || "")) setAside(el, "style");
      var name = local(el);
      if (name !== "image" && name !== "use" && name !== "feimage") continue;
      var href = el.getAttributeNS(XLINK, "href") || el.getAttribute("href") || "";
      // A use that points into its own drawing is left alone
      if (name === "use" && href.charAt(0) === "#") continue;
      el.removeAttributeNS(XLINK, "href");
      el.removeAttribute("href");
      if (name === "image" && href) el.setAttribute("data-vw-href", href);
    }
  }

  /* The few attributes of a book's html or body that its stylesheets go by */
  function kept(el) {
    var out = {};
    if (!el) return out;
    ["class", "id", "dir", "lang"].forEach(function (name) {
      var value = el.getAttribute(name);
      if (value) out[name] = value;
    });
    if (!out.lang && el.getAttribute("xml:lang")) out.lang = el.getAttribute("xml:lang");
    var style = el.getAttribute("style");
    if (style && !/url\(/i.test(style)) out.style = style;
    return out;
  }

  function chapter(source, path) {
    var doc = markup(source);
    var body = doc.body;
    if (!body) {
      // A chapter that is a drawing, or anything else with no body: given one
      var around = document.implementation.createHTMLDocument("");
      around.body.appendChild(around.importNode(doc.documentElement, true));
      doc = around;
      body = around.body;
    }

    var styles = [];
    var marks = doc.querySelectorAll("link, style");
    for (var i = 0; i < marks.length; i++) {
      if (local(marks[i]) === "style") {
        styles.push({ css: marks[i].textContent });
        continue;
      }
      var rel = marks[i].getAttribute("rel") || "";
      if (!/(^|\s)stylesheet(\s|$)/i.test(rel) || /(^|\s)alternate(\s|$)/i.test(rel)) continue;
      var at = resolve(path, marks[i].getAttribute("href"));
      if (at && entry(at.path)) styles.push({ path: at.path });
    }

    disarm(doc);
    var holder = document.createElement("body");
    holder.appendChild(DOMPurify.sanitize(body, PURIFY));
    return { body: holder, html: kept(doc.documentElement), own: kept(body), styles: styles };
  }

  function load(i) {
    var ch = spine[i];
    if (ch.wait) return ch.wait;
    ch.wait = text(ch.path).then(function (source) {
      return chapter(source, ch.path);
    }).catch(function () {
      // One chapter that cannot be read leaves the rest of the book readable
      var holder = document.createElement("body");
      var said = document.createElement("p");
      said.textContent = "This part of the book could not be read.";
      holder.appendChild(said);
      return { body: holder, html: {}, own: {}, styles: [] };
    }).then(function (made) {
      ch.ready = made;
      return made;
    });
    return ch.wait;
  }

  /* Without a search asking for the whole book, only the chapter being read and its two
     neighbours are kept, so a long book does not stay in memory as it is read through. */
  function trim() {
    if (wholeBook) return;
    for (var i = 0; i < spine.length; i++) {
      if (Math.abs(i - shown) > 1) {
        spine[i].wait = null;
        spine[i].ready = null;
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // What a chapter needs from the zip: pictures, fonts and stylesheets
  // ---------------------------------------------------------------------------------------

  /*
   * The type a file is handed to the page as, or null for one that is not handed over. Only
   * pictures, sound, film and fonts, whatever the package calls the file: an object URL for a
   * web page or a script is one that nothing here has any use for.
   */
  function typeOf(path) {
    var said = types[path] || "";
    if (/^(image|audio|video|font)\//.test(said)) return said;
    if (/font|opentype|truetype/.test(said)) return "application/octet-stream";
    return TYPES[path.slice(path.lastIndexOf(".") + 1).toLowerCase()] || null;
  }

  /* An object URL for a file in the book, kept in [store], or null */
  function objectUrl(path, store) {
    if (store[path]) return store[path];
    var type = typeOf(path);
    if (!type || sealed[path] || !entry(path)) return Promise.resolve(null);
    store[path] = bytes(path).then(function (b) {
      return URL.createObjectURL(new Blob([b], { type: type }));
    }, function () {
      return null;
    });
    return store[path];
  }

  function release() {
    for (var path in held) {
      held[path].then(function (url) { if (url) URL.revokeObjectURL(url); });
    }
    held = {};
  }

  function cssAddress(match) {
    return (match[1] !== undefined ? match[1] : match[2] !== undefined ? match[2] : match[3] || "").trim();
  }

  /*
   * Stylesheet text with every url() answered from the zip. One that points outside the book,
   * or at nothing, becomes an empty data: address, which fetches nothing and breaks no rule
   * around it. One that is already bytes, or a place in the same file, stays.
   */
  function urls(css, base, store) {
    var wanted = [];
    var match;
    URL_IN_CSS.lastIndex = 0;
    while ((match = URL_IN_CSS.exec(css))) {
      var at = resolve(base, cssAddress(match));
      if (at) wanted.push(at.path);
    }
    if (!wanted.length && !/url\(/i.test(css)) return Promise.resolve(css);
    return Promise.all(wanted.map(function (path) { return objectUrl(path, store); })).then(function (made) {
      var urlOf = {};
      for (var i = 0; i < wanted.length; i++) urlOf[wanted[i]] = made[i];
      return css.replace(URL_IN_CSS, function (whole, a, b, c) {
        var href = cssAddress([whole, a, b, c]);
        if (/^(data|blob):/i.test(href) || href.charAt(0) === "#") return whole;
        var to = resolve(base, href);
        return 'url("' + ((to && urlOf[to.path]) || "data:,") + '")';
      });
    });
  }

  /*
   * A stylesheet with the ones it imports written into it, each read from where it lives, so
   * the addresses in one are worked out against its own folder. [chain] is the files already
   * being read on the way here, which is how one that imports itself comes to an end.
   */
  function gathered(css, base, chain) {
    var asked = [];
    var marked = css.replace(IMPORT_IN_CSS, function (whole, quote, href) {
      var at = resolve(base, href);
      var ok = at && chain.length < 8 && chain.indexOf(at.path) < 0 && entry(at.path);
      asked.push(ok
        ? text(at.path).then(function (inner) {
          return gathered(inner, at.path, chain.concat(at.path));
        }, function () { return ""; })
        : Promise.resolve(""));
      return "/*vw-import-" + (asked.length - 1) + "*/";
    });
    return urls(marked, base, shared).then(function (own) {
      return Promise.all(asked).then(function (inner) {
        return own.replace(/\/\*vw-import-(\d+)\*\//g, function (whole, n) {
          return inner[Number(n)] || "";
        });
      });
    });
  }

  /*
   * A stylesheet as the shadow tree can use it: { css, faces }. Its @font-face rules are
   * taken out, since a shadow tree ignores them, and put in the page's own head by show().
   * One left with no font behind it is dropped: a scrambled font, or one the book does not
   * hold, would only be a rule the engine complains it cannot decode. :root is the page's
   * root and not the book's, so it is written as the html element the chapter is given.
   */
  function fitted(css, base) {
    return gathered(css, base, [base]).then(function (whole) {
      var fonts = "";
      var rest = whole.replace(/@font-face\s*\{[^}]*\}/gi, function (rule) {
        if (/url\("(blob:|data:[^,])/i.test(rule)) fonts += rule + "\n";
        return "";
      });
      return { css: rest.replace(/:root\b/g, "html"), faces: fonts };
    });
  }

  function sheet(path) {
    if (!sheets[path]) {
      sheets[path] = text(path).then(function (css) {
        return fitted(css, path);
      }).catch(function () {
        return { css: "", faces: "" };
      });
    }
    return sheets[path];
  }

  /*
   * Everything the chapter in [body] set aside, read from the zip. Answers a function that
   * puts it all in place at once, so the chapter is dressed in one step and not picture by
   * picture.
   */
  function fetched(body, base) {
    var jobs = [];

    function one(el, name, put) {
      var value = el.getAttribute("data-vw-" + name);
      if (value === null) return;
      el.removeAttribute("data-vw-" + name);
      if (/^data:(image|audio|video)\//i.test(value)) {
        jobs.push(Promise.resolve(function () { put(el, value); }));
        return;
      }
      var at = resolve(base, value);
      jobs.push((at ? objectUrl(at.path, held) : Promise.resolve(null)).then(function (url) {
        return function () { if (url) put(el, url); };
      }));
    }

    var els = body.querySelectorAll("[data-vw-src], [data-vw-poster], [data-vw-href], [data-vw-style]");
    for (var i = 0; i < els.length; i++) {
      one(els[i], "src", function (el, url) { el.setAttribute("src", url); });
      one(els[i], "poster", function (el, url) { el.setAttribute("poster", url); });
      one(els[i], "href", function (el, url) { el.setAttributeNS(XLINK, "xlink:href", url); });
      (function (el) {
        var style = el.getAttribute("data-vw-style");
        if (style === null) return;
        el.removeAttribute("data-vw-style");
        jobs.push(urls(style, base, held).then(function (css) {
          return function () { el.setAttribute("style", css); };
        }));
      })(els[i]);
    }

    return Promise.all(jobs).then(function (puts) {
      return function () { puts.forEach(function (put) { put(); }); };
    });
  }

  /* Once the chapter's pictures have their sizes, or after a second and a half without them */
  function settled(body) {
    return new Promise(function (resolve) {
      var imgs = body.querySelectorAll("img");
      var waiting = 0;
      var done = false;
      function finish() {
        if (done) return;
        done = true;
        resolve();
      }
      function one() {
        if (--waiting <= 0) finish();
      }
      for (var i = 0; i < imgs.length; i++) {
        if (imgs[i].complete) continue;
        waiting++;
        imgs[i].addEventListener("load", one);
        imgs[i].addEventListener("error", one);
      }
      if (!waiting) finish();
      else setTimeout(finish, 1500);
    });
  }

  // ---------------------------------------------------------------------------------------
  // Where the reader is
  // ---------------------------------------------------------------------------------------

  /* How far down the chapter the top of the screen is, from 0 to just under 1 */
  function fraction() {
    var box = page.getBoundingClientRect();
    var root = document.documentElement;
    // The foot of a chapter cannot be brought to the top of the screen, so being as far down
    // as the page goes is what counts as its last page
    if (window.pageYOffset > 0 && window.pageYOffset + window.innerHeight >= root.scrollHeight - 2) {
      return 0.999999;
    }
    return Math.max(0, Math.min(0.999999, (vwTopPx - box.top) / (box.height || 1)));
  }

  function pageNow() {
    var ch = spine[shown];
    return ch.start + Math.min(ch.pages - 1, Math.floor(fraction() * ch.pages + 1e-6)) + 1;
  }

  /* Tells the app which page is on screen, in the words pdf.html uses: see PortMessage.kt */
  function report() {
    if (shown < 0 || !onPage) return;
    var n = pageNow();
    if (n !== sent && vwFind.post("page " + n + " " + total)) sent = n;
  }

  /* The chapter a page is in and how far down it: the reverse of pageNow() */
  function spot(n) {
    var i = 0;
    while (i < spine.length - 1 && spine[i].start + spine[i].pages < n) i++;
    var ch = spine[i];
    // A little past the line between two pages, so that rounding cannot land on the one before
    return { at: i, to: { part: (n - ch.start - 1 + 0.02) / ch.pages } };
  }

  function named(frag) {
    var el = shadow.getElementById(frag);
    if (el || !window.CSS || !CSS.escape) return el;
    return onPage.querySelector('[name="' + CSS.escape(frag) + '"]');
  }

  /* Puts the screen at [to]: { frag } a place the book names, { part } a fraction of the way
     down the chapter, or anything else its top, with the strip above it in view. */
  function place(to) {
    var mark = to && to.frag ? named(to.frag) : null;
    if (mark) {
      mark.scrollIntoView();
    } else if (to && to.part > 0) {
      var box = page.getBoundingClientRect();
      window.scrollTo(0, window.pageYOffset + box.top - vwTopPx + to.part * box.height);
    } else {
      window.scrollTo(0, 0);
    }
  }

  /* The element in the middle of the screen, which a search has just brought there */
  function middle() {
    var el = shadow.elementFromPoint
      ? shadow.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2) : null;
    return el && onPage && onPage !== el && onPage.contains(el) ? el : null;
  }

  // ---------------------------------------------------------------------------------------
  // Putting a chapter on the page
  // ---------------------------------------------------------------------------------------

  /* The name the contents give the chapter on the page, or the nearest one before it */
  function nameOf(i) {
    var best = "";
    var bestAt = -1;
    for (var k = 0; k < contents.length; k++) {
      var at = contents[k].at;
      if (at < 0 || at > i || at < bestAt) continue;
      if (at > bestAt || !best) {
        best = contents[k].label;
        bestAt = at;
      }
    }
    return best;
  }

  function dressLike(el, attrs) {
    for (var name in attrs) el.setAttribute(name, attrs[name]);
  }

  /*
   * Puts chapter [i] on the page, which must have been read already.
   *
   * In two steps, because find.js needs the first to be done by the time this returns: the
   * chapter's words go in at once, unseen, and its stylesheets and pictures follow when the
   * zip has given them up. [quiet] is a search bringing the chapter up for a match in it,
   * which places the screen itself, so what it brought to the middle is kept there while
   * the chapter is dressed and nothing is said to find.js about a change it made.
   */
  function show(i, to, quiet) {
    var made = spine[i].ready;
    var mine = ++turn;
    var base = spine[i].path;
    release();
    shown = i;
    moved = false;

    var html = document.createElement("html");
    var body = made.body.cloneNode(true);
    dressLike(html, made.html);
    dressLike(body, made.own);
    html.appendChild(body);
    bookSheets.textContent = "";
    faces.textContent = "";
    page.textContent = "";
    // Unseen rather than hidden, so the middle of the screen can still be asked what is in it
    page.style.opacity = "0";
    page.appendChild(html);
    onPage = body;

    where.textContent = nameOf(i);
    prev.disabled = i <= 0;
    next.disabled = i >= spine.length - 1;
    if (!quiet) place(to);

    var styled = Promise.all(made.styles.map(function (s) {
      return s.path ? sheet(s.path) : fitted(s.css, base);
    }));
    Promise.all([styled, fetched(body, base)]).then(function (got) {
      if (mine !== turn) return null;
      var keep = quiet ? middle() : null;
      var fonts = "";
      got[0].forEach(function (s) {
        var el = document.createElement("style");
        el.textContent = s.css;
        bookSheets.appendChild(el);
        fonts += s.faces;
      });
      faces.textContent = fonts;
      got[1]();
      page.style.opacity = "";
      if (keep) keep.scrollIntoView({ block: "center" });
      else if (!quiet) place(to);
      return settled(body).then(function () { return keep; });
    }).then(function (keep) {
      if (mine !== turn) return;
      if (!moved) {
        if (keep) keep.scrollIntoView({ block: "center" });
        else if (!quiet) place(to);
      }
      if (!opened) {
        opened = true;
        bar.hidden = false;
        turnBar.hidden = false;
        vwStatusDone();
      }
      report();
      if (!quiet) vwFind.changed();
      trim();
    }).catch(function (e) {
      page.style.opacity = "";
      if (!opened) fail(e);
    });
  }

  function go(i, to) {
    if (i < 0 || i >= spine.length) return Promise.resolve();
    if (i === shown && onPage) {
      place(to);
      report();
      return Promise.resolve();
    }
    var mine = ++turn;
    return load(i).then(function () {
      if (mine === turn) show(i, to, false);
    });
  }

  function toPage(n) {
    if (!(n >= 1 && n <= total)) return;
    var at = spot(n);
    go(at.at, n === 1 ? null : at.to);
  }

  // ---------------------------------------------------------------------------------------
  // Find in page, across the whole book: see find.js for what it asks of a page in parts
  // ---------------------------------------------------------------------------------------

  /* Reads every chapter, one after another, then has the search run again over all of them */
  function readWholeBook() {
    if (wholeBook) return;
    wholeBook = true;
    reading = true;
    var chain = Promise.resolve();
    spine.forEach(function (ch, i) {
      chain = chain.then(function () { return load(i); });
    });
    chain.then(function () {
      reading = false;
      vwFind.reread();
    });
  }

  window.vwFindParts = {
    count: function () { return spine.length; },
    root: function (i) {
      if (i === shown && onPage) return onPage;
      if (spine[i].ready) return spine[i].ready.body;
      // Asked for a chapter that is not in memory, which only a search does
      readWholeBook();
      return EMPTY;
    },
    show: function (i) {
      if (spine[i].ready) show(i, null, true);
      return onPage;
    },
    shown: function () { return shown; },
    busy: function () { return reading; }
  };

  /* What the app sends besides search: "g<n>" to go to a page */
  window.vwPageCommand = function (msg) {
    if (msg.charAt(0) === "g") toPage(parseInt(msg.slice(1), 10));
  };

  window.vwPortReady = function () {
    sent = 0;
    report();
  };

  // ---------------------------------------------------------------------------------------
  // The page's own parts: the two buttons, the contents and links between chapters
  // ---------------------------------------------------------------------------------------

  function buildContents() {
    document.getElementById("epub-title").textContent = bookTitle;
    for (var i = 0; i < contents.length && i < 2000; i++) {
      var li = document.createElement("li");
      var el = document.createElement(contents[i].at < 0 ? "span" : "button");
      if (contents[i].at >= 0) {
        el.type = "button";
        el.setAttribute("data-n", i);
      }
      el.textContent = contents[i].label;
      el.style.paddingLeft = (12 + 16 * Math.min(contents[i].depth, 4)) + "px";
      li.appendChild(el);
      list.appendChild(li);
    }
  }

  function showContents(on) {
    if (on) {
      var here = nameOf(shown);
      var buttons = list.querySelectorAll("button");
      var current = null;
      for (var i = 0; i < buttons.length; i++) {
        var e = contents[Number(buttons[i].getAttribute("data-n"))];
        var is = !current && e.at === shown && e.label === here;
        if (is) {
          buttons[i].setAttribute("aria-current", "true");
          current = buttons[i];
        } else {
          buttons[i].removeAttribute("aria-current");
        }
      }
      panel.hidden = false;
      if (current) current.scrollIntoView({ block: "center" });
      closeContents.focus({ preventScroll: true });
    } else {
      panel.hidden = true;
      openContents.focus({ preventScroll: true });
    }
  }

  openContents.addEventListener("click", function () { showContents(true); });
  closeContents.addEventListener("click", function () { showContents(false); });
  prev.addEventListener("click", function () { go(shown - 1, null); });
  next.addEventListener("click", function () { go(shown + 1, null); });

  list.addEventListener("click", function (e) {
    var el = e.target;
    if (!el || !el.getAttribute || el.getAttribute("data-n") === null) return;
    var to = contents[Number(el.getAttribute("data-n"))];
    showContents(false);
    go(to.at, to.frag ? { frag: to.frag } : null);
  });

  /*
   * A link in a chapter. One to somewhere else in the book goes there. Any other does
   * nothing, as in every viewer here: Gander opens no web address, and the page has no
   * network to open one with.
   */
  shadow.addEventListener("click", function (e) {
    var a = e.target;
    while (a && a !== shadow && !(a.nodeType === 1 && local(a) === "a")) a = a.parentNode;
    if (!a || a === shadow) return;
    var href = a.getAttribute("href") || a.getAttributeNS(XLINK, "href");
    if (!href) return;
    e.preventDefault();
    var at = resolve(spine[shown].path, href);
    if (!at || index[at.path] === undefined) return;
    go(index[at.path], at.frag ? { frag: at.frag } : null);
  });

  var reporting = false;
  window.addEventListener("scroll", function () {
    if (reporting) return;
    reporting = true;
    requestAnimationFrame(function () {
      reporting = false;
      report();
    });
  }, { passive: true });

  ["touchstart", "wheel", "keydown"].forEach(function (name) {
    window.addEventListener(name, function () { moved = true; }, { passive: true });
  });

  function fail(e) {
    vwError((e && e.vwTitle) || "Could not open this book", String((e && e.message) || e));
  }

  vwFetchDoc("buffer").then(open).then(function () {
    buildContents();
    // The page the book was left at, which the app sends as it does a PDF's
    var resume = parseInt(vwParams.get("resume"), 10);
    if (resume >= 2 && resume <= total) {
      var at = spot(resume);
      return go(at.at, at.to);
    }
    return go(0, null);
  }).catch(fail);

  return { go: go, toPage: toPage };
})();
