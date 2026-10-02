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
 */
var DRAWINGML = "http://schemas.openxmlformats.org/drawingml/2006/main";

var vwOpenPackage = JSZip.prototype.load;
JSZip.prototype.load = function () {
  var zip = vwOpenPackage.apply(this, arguments);
  if (!zip.file("docProps/app.xml")) zip.file("docProps/app.xml", "<Properties/>");
  zip.file(/^ppt\/(slides|slideLayouts|slideMasters)\/[^/]+\.xml$/).forEach(function (part) {
    var xml = part.asText();
    var merged = onePathPerShape(xml);
    if (merged !== xml) zip.file(part.name, merged);
  });
  return zip;
};

function onePathPerShape(xml) {
  // A path closed, or written empty, with another straight after it
  if (!/(<\/a:path>|\/>)\s*<a:path\b/.test(xml)) return xml;
  var doc = new DOMParser().parseFromString(xml, "application/xml");
  if (doc.getElementsByTagName("parsererror").length) return xml;
  var lists = doc.getElementsByTagNameNS(DRAWINGML, "pathLst");
  var changed = false;
  for (var i = 0; i < lists.length; i++) {
    var paths = [];
    for (var el = lists[i].firstElementChild; el; el = el.nextElementSibling) {
      if (el.localName === "path") paths.push(el);
    }
    for (var j = 1; j < paths.length; j++) {
      onToGridOf(paths[0], paths[j]);
      while (paths[j].firstChild) paths[0].appendChild(paths[j].firstChild);
      lists[i].removeChild(paths[j]);
      changed = true;
    }
  }
  return changed ? new XMLSerializer().serializeToString(doc) : xml;
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
