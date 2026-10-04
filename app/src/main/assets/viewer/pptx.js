/*
 * Two things PPTXjs takes for granted that the format does not promise, either of which
 * puts the error card up in place of every slide. Both are put right here, in the package
 * as PPTXjs opens it and before it reads a part, so the library stays as upstream ships it
 * (docs/VENDORED.md). Upstream has not moved since 2022 and has both open, as issues 42
 * and 32.
 *
 * docProps/app.xml is optional, and Google Slides leaves it out. PPTXjs reads it without
 * looking: "Cannot read properties of null (reading 'Properties')". A deck without one is
 * given an empty one. PPTXjs wants only the version of PowerPoint inside, to tell Office
 * 2007's files apart, and an empty one says not 2007.
 *
 * A custom shape can be drawn in several paths, and Google Slides and WPS both use that: a
 * logic gate is its outline and a path for each wire. PPTXjs reads only one ("Cannot read
 * properties of undefined (reading 'w')"), so the paths of a shape become subpaths of its
 * first, a path drawn on a grid of its own moved onto the first's. Each still gets the
 * shape's fill and line, the only ones PPTXjs gives any path. One thing does change: where
 * two of them overlap, wound in opposite directions, the overlap is no longer filled.
 *
 * PPTXjs also draws a path's straight segments only when it has more than one. A lone one
 * it reads field by field as though they were the list, and nothing is drawn: no error, and
 * no line. A rule under a heading is such a path, so a deck can lose one from every slide.
 * Its segment is written twice, which draws the same line.
 */
var DRAWINGML = "http://schemas.openxmlformats.org/drawingml/2006/main";
var PRESENTATIONML = "http://schemas.openxmlformats.org/presentationml/2006/main";

var vwOpenPackage = JSZip.prototype.load;
JSZip.prototype.load = function () {
  var zip = vwOpenPackage.apply(this, arguments);
  if (!zip.file("docProps/app.xml")) zip.file("docProps/app.xml", "<Properties/>");
  zip.file(/^ppt\/(slides|slideLayouts|slideMasters)\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var drawable = breaksPptxjsKeeps(pathsPptxjsDraws(xml));
    if (drawable !== xml) zip.file(part.name, drawable);
  });
  var designs = {};
  zip.file(/^ppt\/slides\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var styled = styleTheDesignGives(zip, part.name, xml, designs);
    if (styled !== xml) zip.file(part.name, styled);
  });
  return zip;
};

function pathsPptxjsDraws(xml) {
  // A path closed, or written empty, with another straight after it, or a lone segment
  if (!/(<\/a:path>|\/>)\s*<a:path\b/.test(xml) && !hasLoneSegment(xml)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var lists = doc.getElementsByTagNameNS(DRAWINGML, "pathLst");
  var changed = false;
  for (var i = 0; i < lists.length; i++) {
    var paths = childrenNamed(lists[i], "path");
    for (var j = 1; j < paths.length; j++) {
      onToGridOf(paths[0], paths[j]);
      while (paths[j].firstChild) paths[0].appendChild(paths[j].firstChild);
      lists[i].removeChild(paths[j]);
      changed = true;
    }
    var segments = paths.length ? childrenNamed(paths[0], "lnTo") : [];
    if (segments.length === 1) {
      paths[0].insertBefore(segments[0].cloneNode(true), segments[0].nextSibling);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

function hasLoneSegment(xml) {
  var paths = xml.match(/<a:path\b[^>]*>[\s\S]*?<\/a:path>/g) || [];
  for (var i = 0; i < paths.length; i++) {
    if ((paths[i].match(/<a:lnTo\b/g) || []).length === 1) return true;
  }
  return false;
}

function childrenNamed(parent, name) {
  var found = [];
  for (var el = parent.firstElementChild; el; el = el.nextElementSibling) {
    if (el.localName === name) found.push(el);
  }
  return found;
}

/* A path's w and h are the size of the grid its points are on. */
function onToGridOf(first, path) {
  var sx = gridRatio(first, path, "w");
  var sy = gridRatio(first, path, "h");
  if (sx === 1 && sy === 1) return;
  var points = path.getElementsByTagNameNS(DRAWINGML, "pt");
  for (var i = 0; i < points.length; i++) {
    scaleAttr(points[i], "x", sx);
    scaleAttr(points[i], "y", sy);
  }
  var arcs = path.getElementsByTagNameNS(DRAWINGML, "arcTo");
  for (var k = 0; k < arcs.length; k++) {
    scaleAttr(arcs[k], "wR", sx);
    scaleAttr(arcs[k], "hR", sy);
  }
}

function gridRatio(first, path, side) {
  var to = parseFloat(first.getAttribute(side));
  var from = parseFloat(path.getAttribute(side));
  return to > 0 && from > 0 ? to / from : 1;
}

/* A point can also name a guide rather than give a number, and is left as it is. */
function scaleAttr(el, name, by) {
  var v = el.getAttribute(name);
  if (v !== null && /^-?\d+$/.test(v)) el.setAttribute(name, String(Math.round(Number(v) * by)));
}

/*
 * A paragraph with more than one line break in its text loses the first of them: PPTXjs
 * shifts it off the list before it draws the rest (genTextBody). So a title set on three
 * lines drew its first two as one, and two breaks in a row, which leave a blank line,
 * drew as one break. Across 86 decks, 22 paragraphs in 9 decks have more than one. Each
 * is given another in front of its first, for PPTXjs to drop instead.
 */
function breaksPptxjsKeeps(xml) {
  if ((xml.match(/<a:br\b/g) || []).length < 2) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var paragraphs = doc.getElementsByTagNameNS(DRAWINGML, "p");
  var changed = false;
  for (var i = 0; i < paragraphs.length; i++) {
    var breaks = childrenNamed(paragraphs[i], "br");
    if (breaks.length > 1 && childrenNamed(paragraphs[i], "r").length) {
      paragraphs[i].insertBefore(breaks[0].cloneNode(true), breaks[0]);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

/*
 * PPTXjs makes a run bold or italic only when the run itself says so (getFontBold,
 * getFontItalic). A deck's design usually says it instead: the layout's title is bold, or
 * the master's title style is, and the run says nothing. Across 86 decks that left 125 runs
 * regular that PowerPoint draws bold, 110 of them titles, on 108 slides. A run that says
 * nothing is given what it inherits, where PowerPoint looks for it: its shape's own list
 * style, the layout's placeholder, the master's, then the master's style for titles, body
 * text or the rest, the first at the paragraph's level that says either way. A slide whose
 * design nowhere sets either is left as it is.
 */
var SETS_BOLD_OR_ITALIC = /<a:defRPr\b[^>]*\s[bi]="(1|true)"/;

function styleTheDesignGives(zip, name, xml, designs) {
  var layout = designPart(zip, relatedPart(zip, name, "slideLayout"), designs);
  var master = layout && designPart(zip, relatedPart(zip, layout.name, "slideMaster"), designs);
  if (!SETS_BOLD_OR_ITALIC.test(xml) && !(layout && layout.sets) && !(master && master.sets)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var changed = false;
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  for (var i = 0; i < shapes.length; i++) {
    var body = childrenNamed(shapes[i], "txBody")[0];
    if (!body) continue;
    var lists = [childrenNamed(body, "lstStyle")[0]];
    var ph = placeholderOf(shapes[i]);
    if (ph) {
      var inLayout = layout && placeholderIn(layout.doc, ph.type, ph.idx);
      var type = masterType(inLayout ? placeholderOf(inLayout).type : ph.type);
      var inMaster = master && placeholderIn(master.doc, type, null);
      lists.push(listStyleOf(inLayout), listStyleOf(inMaster), master && masterStyle(master.doc, type));
    }
    var paragraphs = childrenNamed(body, "p");
    for (var j = 0; j < paragraphs.length; j++) {
      var pPr = childrenNamed(paragraphs[j], "pPr")[0];
      var level = "lvl" + ((parseInt(pPr && pPr.getAttribute("lvl"), 10) || 0) + 1) + "pPr";
      var runs = childrenNamed(paragraphs[j], "r").concat(childrenNamed(paragraphs[j], "fld"));
      for (var k = 0; k < runs.length; k++) {
        if (inherit(doc, runs[k], lists, level, "b")) changed = true;
        if (inherit(doc, runs[k], lists, level, "i")) changed = true;
      }
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
}

/* Gives a run that does not say bold, or italic, the value its design says, if that is on. */
function inherit(doc, run, lists, level, attr) {
  var rPr = childrenNamed(run, "rPr")[0];
  if (rPr && rPr.hasAttribute(attr)) return false;
  var said = null;
  for (var i = 0; i < lists.length && said === null; i++) {
    var lvl = lists[i] && childrenNamed(lists[i], level)[0];
    var defaults = lvl && childrenNamed(lvl, "defRPr")[0];
    if (defaults && defaults.hasAttribute(attr)) said = defaults.getAttribute(attr);
  }
  if (said !== "1" && said !== "true") return false;
  if (!rPr) {
    rPr = doc.createElementNS(DRAWINGML, (run.prefix ? run.prefix + ":" : "") + "rPr");
    run.insertBefore(rPr, run.firstChild);
  }
  rPr.setAttribute(attr, "1");
  return true;
}

/* A layout or a master, read once a deck, and whether it sets bold or italic anywhere. */
function designPart(zip, name, designs) {
  if (!name) return null;
  if (!(name in designs)) {
    var file = zip.file(name);
    var xml = file && file.asText();
    var doc = xml && new DOMParser().parseFromString(xml, "application/xml");
    designs[name] = doc && !doc.getElementsByTagName("parsererror").length
      ? { name: name, doc: doc, sets: SETS_BOLD_OR_ITALIC.test(xml) }
      : null;
  }
  return designs[name];
}

/* The part a relationship of the given type points to, from the part's own .rels. */
function relatedPart(zip, name, type) {
  var slash = name.lastIndexOf("/");
  var rels = zip.file(name.slice(0, slash) + "/_rels/" + name.slice(slash + 1) + ".rels");
  var rel = rels && new RegExp('<Relationship\\b[^>]*Type="[^"]*/' + type + '"[^>]*>').exec(rels.asText());
  var target = rel && /Target="([^"]+)"/.exec(rel[0]);
  if (!target) return null;
  var path = target[1].charAt(0) === "/" ? [] : name.slice(0, slash).split("/");
  target[1].split("/").forEach(function (step) {
    if (step === "..") path.pop();
    else if (step && step !== ".") path.push(step);
  });
  return path.join("/");
}

function placeholderOf(sp) {
  var ph = sp.getElementsByTagNameNS(PRESENTATIONML, "ph")[0];
  return ph ? { type: ph.getAttribute("type") || "obj", idx: ph.getAttribute("idx") } : null;
}

/* The placeholder of the same idx, as PowerPoint pairs a slide's with its layout's, or else the first of the same type. */
function placeholderIn(doc, type, idx) {
  var shapes = doc.getElementsByTagNameNS(PRESENTATIONML, "sp");
  var sameType = null;
  for (var i = 0; i < shapes.length; i++) {
    var ph = placeholderOf(shapes[i]);
    if (!ph) continue;
    if (idx !== null && ph.idx === idx) return shapes[i];
    if (!sameType && ph.type === type) sameType = shapes[i];
  }
  return sameType;
}

/* A master has a title and a body placeholder for all the kinds a layout has, and its own few. */
function masterType(type) {
  return { title: "title", ctrTitle: "title", dt: "dt", ftr: "ftr", sldNum: "sldNum", hdr: "hdr" }[type] || "body";
}

function masterStyle(doc, type) {
  var styles = doc.getElementsByTagNameNS(PRESENTATIONML, "txStyles")[0];
  var name = type === "title" ? "titleStyle" : type === "body" ? "bodyStyle" : "otherStyle";
  return styles ? childrenNamed(styles, name)[0] : null;
}

function listStyleOf(sp) {
  var body = sp && childrenNamed(sp, "txBody")[0];
  return body ? childrenNamed(body, "lstStyle")[0] : null;
}

/*
 * PPTXjs writes every space in a run of text as &nbsp; (genSpanElement), so a line of a
 * paragraph has nowhere to break, and the paragraph's overflow-wrap: break-word breaks it
 * wherever it runs out of room instead: "Language" at the end of one line and "s" at the
 * start of the next. Across 105 decks in desktop Chrome, that cut 763 words in 29 of the
 * 86 PPTXjs opens. Every word still cut after this is wider than its whole box, or sits in
 * a box PPTXjs draws with no width. The spaces go back to spaces here, and pptx.html gives the runs white-space: pre-wrap,
 * which keeps a run of them as wide as before and breaks lines at them, as PowerPoint
 * does. A no-break space a deck really has becomes an ordinary one too: those decks had
 * 15 in 9,031 runs of text.
 *
 * PPTXjs puts every slide in at once, in a single task, so an observer, which runs when
 * that task ends, finds them all and changes them before they are first drawn. The poll
 * below would leave the cut words on screen for up to half a second.
 */
function spacesThatBreak(root) {
  var blocks = root.querySelectorAll(".text-block");
  for (var i = 0; i < blocks.length; i++) {
    var walker = document.createTreeWalker(blocks[i], NodeFilter.SHOW_TEXT);
    for (var node = walker.nextNode(); node; node = walker.nextNode()) {
      if (node.data.indexOf("\u00a0") !== -1) node.data = node.data.replace(/\u00a0/g, " ");
    }
  }
}

/*
 * PPTXjs gives every paragraph of a body, an object or a plain shape font-weight: 100,
 * beside the font-size: 0 that closes the gaps between its runs, and a run that is not
 * bold inherits it. A deck has no such weight: its text is bold or it is not. On the Mac,
 * Arial and Calibri have no face that light, so the text drew regular there. Android
 * draws them and other sans-serif faces in Roboto, which has one, so the text came out in
 * Roboto Thin, with a third of the ink. The weight goes back to normal in the rules PPTXjs
 * wrote it in, which leaves a bold run's own weight alone. PPTXjs appends those rules after
 * the last slide, in the same task, so the observer below finds them too.
 */
function regularWeight(root) {
  var styles = root.querySelectorAll("style");
  for (var i = 0; i < styles.length; i++) {
    var rules = styles[i].sheet ? styles[i].sheet.cssRules : [];
    for (var j = 0; j < rules.length; j++) {
      if (rules[j].style && rules[j].style.fontWeight === "100") rules[j].style.fontWeight = "normal";
    }
  }
}

new MutationObserver(function (records, observer) {
  var result = document.getElementById("result");
  if (!result.querySelector(".slide")) return;
  observer.disconnect();
  spacesThatBreak(result);
  regularWeight(result);
}).observe(document.getElementById("result"), { childList: true });

try {
  $("#result").pptxToHtml({
    pptxFileUrl: "/doc/file.pptx",
    slideMode: false,
    keyBoardShortCut: false,
    mediaProcess: true
  });
} catch (e) {
  vwError("Could not render this presentation", String(e));
}
var vwChecks = 0;
var vwPoll = setInterval(function () {
  vwChecks++;
  var slides = document.querySelectorAll("#result .slide");
  if (slides.length > 0) {
    vwDisarmLinks(document.getElementById("result"));
    /* Once the slides exist, because it is they that widen the layout viewport. */
    vwFitHeight();
    vwStatusDone();
    clearInterval(vwPoll);
  } else if (vwChecks > 40) {
    clearInterval(vwPoll);
    vwError(
      "Could not render this presentation",
      "This .pptx may use features the built-in renderer does not understand yet."
    );
  }
}, 500);
