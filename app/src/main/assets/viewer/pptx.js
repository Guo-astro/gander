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

var vwOpenPackage = JSZip.prototype.load;
JSZip.prototype.load = function () {
  var zip = vwOpenPackage.apply(this, arguments);
  if (!zip.file("docProps/app.xml")) zip.file("docProps/app.xml", "<Properties/>");
  zip.file(/^ppt\/(slides|slideLayouts|slideMasters)\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var drawable = pathsPptxjsDraws(xml);
    if (drawable !== xml) zip.file(part.name, drawable);
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

new MutationObserver(function (records, observer) {
  var result = document.getElementById("result");
  if (!result.querySelector(".slide")) return;
  observer.disconnect();
  spacesThatBreak(result);
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
