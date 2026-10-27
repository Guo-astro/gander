/*
 * The colours a workbook gives its cells: the fill of a cell, and the colour and weight of its text.
 *
 * SheetJS's community build, which reads the workbook, keeps each cell's fill and nothing of its
 * font, and loses fills on the way: in an .xlsx, one given by its number in Excel's old palette
 * keeps no colour, one made from the first theme colour, the white that Excel's greys are shades of,
 * is left unworked, and a gradient in the list moves every fill after it onto other cells. It reads
 * nothing set on a whole row or column either. So the styles are read here, from the file itself:
 * an .xlsx's parts, or an .xls's records. An .xlsb or .ods is drawn plain, as before.
 *
 * What a cell shows is its look: a fill, a colour for its text, bold, italic, underlined, struck
 * through. Looks are numbered across the workbook, 0 for a cell drawn plain, and each other is a
 * class, vw-l1 and on, with a rule for the day and one for the night.
 */

/* Excel's 64 numbered colours, for a colour given by its number. 64 is the text's colour, 65 the window's. */
var VW_XL_PALETTE = [
  "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
  "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
  "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080",
  "9999FF", "993366", "FFFFCC", "CCFFFF", "660066", "FF8080", "0066CC", "CCCCFF",
  "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000", "008080", "0000FF",
  "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99",
  "3366FF", "33CCCC", "99CC00", "FFCC00", "FF9900", "FF6600", "666699", "969696",
  "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333"
];

/*
 * The colours of a theme's scheme in the order a cell's theme number counts them, which puts each
 * light colour before its dark one, the other way round from the scheme itself. With them, the
 * Office theme's colours, for a workbook that has no theme of its own.
 */
var VW_XL_SCHEME = ["lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4",
  "accent5", "accent6", "hlink", "folHlink"];
var VW_XL_THEME = ["FFFFFF", "000000", "E7E6E6", "44546A", "4472C4", "ED7D31", "A5A5A5", "FFC000",
  "5B9BD5", "70AD47", "0563C1", "954F72"];

/*
 * How much of a patterned fill is the pattern's colour, the rest being the colour behind it. A
 * pattern is drawn as one colour mixed from the two, in LibreOffice's shares, which is near enough
 * at the size of a cell.
 */
var VW_XL_PATTERN = {
  solid: 1, darkGray: 0.75, darkTrellis: 0.75, mediumGray: 0.5, darkHorizontal: 0.5,
  darkVertical: 0.5, darkDown: 0.5, darkUp: 0.5, darkGrid: 0.5, lightGrid: 0.4375,
  lightTrellis: 0.375, lightGray: 0.25, lightHorizontal: 0.25, lightVertical: 0.25,
  lightDown: 0.25, lightUp: 0.25, gray125: 0.125, gray0625: 0.0625
};

/* The ground and ink of a sheet at night: --vw-ground and --vw-ink in app.css's night palette */
var VW_SHEET_NIGHT_GROUND = "17130A";
var VW_SHEET_NIGHT_INK = "E7E2D9";

/*
 * At night a colour is turned over as a Word page's are, then laid between the night ground and
 * ink, so a white fill is the ground and black text the ink, as an unstyled cell has them. Turned
 * over, a pale fill is nearly the ground, and so it is lifted by this power: the largest that
 * leaves every fill in Excel's colour picker as far from the night ground, by OKLab distance, as it
 * is from white paper by day. Pure yellow is the fill that sets it.
 */
var VW_SHEET_NIGHT_LIFT = 0.7;

/* Text below this contrast with its fill at night takes the ground or the ink instead, unless it
   was fainter than that by day too: WCAG's floor for text this size */
var VW_SHEET_CONTRAST = 4.5;

/* Each element [name] in [xml], whatever its namespace prefix, as its attributes and its contents */
function vwXmlAll(xml, name) {
  var re = new RegExp("<(?:[\\w.-]+:)?" + name + "(?=[\\s/>])([^>]*?)(?:/>|>([\\s\\S]*?)</(?:[\\w.-]+:)?" +
    name + "\\s*>)", "g");
  var out = [], m;
  while ((m = re.exec(xml))) out.push([m[1], m[2] || ""]);
  return out;
}

function vwXmlFirst(xml, name) {
  var m = new RegExp("<(?:[\\w.-]+:)?" + name + "(?=[\\s/>])([^>]*?)(?:/>|>([\\s\\S]*?)</(?:[\\w.-]+:)?" +
    name + "\\s*>)").exec(xml);
  return m ? [m[1], m[2] || ""] : null;
}

var vwXmlAttrRes = {};

/* Attribute [name] of a tag's attributes, or null */
function vwXmlAttr(attrs, name) {
  var re = vwXmlAttrRes[name] ||
    (vwXmlAttrRes[name] = new RegExp("(?:^|\\s)" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')"));
  var m = re.exec(attrs);
  return m ? (m[1] !== undefined ? m[1] : m[2]) : null;
}

/* A switch such as <b/>, which is on unless its value says it is not */
function vwXmlFlag(xml, name) {
  var el = vwXmlFirst(xml, name);
  return !!el && !/^(0|false)$/i.test(vwXmlAttr(el[0], "val") || "");
}

/* A part of the file as text. Excel writes UTF-8; a part may be UTF-16, with a byte order mark. */
function vwXmlText(bytes) {
  if (!bytes) return "";
  var encoding = bytes[0] === 0xFF && bytes[1] === 0xFE ? "utf-16le"
    : bytes[0] === 0xFE && bytes[1] === 0xFF ? "utf-16be" : "utf-8";
  try {
    return new TextDecoder(encoding).decode(bytes);
  } catch (e) {
    return "";
  }
}

function vwRgb(hex) {
  return [parseInt(hex.slice(0, 2), 16), parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16)];
}

function vwHex(rgb) {
  return rgb.map(function (v) {
    var s = Math.round(Math.min(255, Math.max(0, v))).toString(16).toUpperCase();
    return s.length < 2 ? "0" + s : s;
  }).join("");
}

/* [a] mixed with [share] of [b] */
function vwBlend(a, b, share) {
  var x = vwRgb(a), y = vwRgb(b);
  return vwHex(x.map(function (v, i) { return v + (y[i] - v) * share; }));
}

/*
 * A colour lightened or darkened by its tint, as ECMA-376 says: in hue, lightness and saturation,
 * the lightness moved that far towards white, or towards black for a tint below nought.
 */
function vwTint(hex, tint) {
  var c = vwRgb(hex).map(function (v) { return v / 255; });
  var max = Math.max(c[0], c[1], c[2]), min = Math.min(c[0], c[1], c[2]);
  var l = (max + min) / 2, d = max - min, h = 0, s = 0;
  if (d) {
    s = l <= 0.5 ? d / (max + min) : d / (2 - max - min);
    h = max === c[0] ? (c[1] - c[2]) / d + (c[1] < c[2] ? 6 : 0)
      : max === c[1] ? (c[2] - c[0]) / d + 2 : (c[0] - c[1]) / d + 4;
    h /= 6;
  }
  l = tint < 0 ? l * (1 + tint) : l * (1 - tint) + tint;
  if (!s) return vwHex([l * 255, l * 255, l * 255]);
  var q = l <= 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
  function channel(t) {
    t = (t % 1 + 1) % 1;
    var v = t < 1 / 6 ? p + (q - p) * 6 * t : t < 1 / 2 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p;
    return v * 255;
  }
  return vwHex([channel(h + 1 / 3), channel(h), channel(h - 1 / 3)]);
}

/* WCAG's contrast between two colours */
function vwContrast(a, b) {
  function luminance(hex) {
    var c = vwRgb(hex).map(function (v) {
      v /= 255;
      return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2] + 0.05;
  }
  var x = luminance(a), y = luminance(b);
  return x > y ? x / y : y / x;
}

/* A colour at night: see VW_SHEET_NIGHT_LIFT */
function vwSheetNight(hex) {
  var c = vwRgb(hex), ground = vwRgb(VW_SHEET_NIGHT_GROUND), ink = vwRgb(VW_SHEET_NIGHT_INK);
  return vwHex(VW_NIGHT_MATRIX.map(function (row, i) {
    var v = row[0] * c[0] / 255 + row[1] * c[1] / 255 + row[2] * c[2] / 255 + row[3];
    v = Math.pow(Math.min(1, Math.max(0, v)), VW_SHEET_NIGHT_LIFT);
    return ground[i] + v * (ink[i] - ground[i]);
  }));
}

/* The black or white that reads better on [fill] */
function vwReadableOn(fill, dark, light) {
  return vwContrast(dark, fill) >= vwContrast(light, fill) ? dark : light;
}

/* A colour as Excel gives one, by its value, its theme number or its palette number, and a tint;
   null for one left automatic */
function vwXlColour(attrs, theme, palette) {
  var hex = null, v;
  if ((v = vwXmlAttr(attrs, "rgb")) !== null && /^([0-9A-Fa-f]{2})?[0-9A-Fa-f]{6}$/.test(v)) {
    // Excel writes ARGB and draws every colour opaque, whatever its alpha says
    hex = v.slice(-6).toUpperCase();
  } else if ((v = vwXmlAttr(attrs, "theme")) !== null) {
    hex = theme[+v] || null;
  } else if ((v = vwXmlAttr(attrs, "indexed")) !== null) {
    hex = palette[+v] || (+v === 64 ? "000000" : +v === 65 ? "FFFFFF" : null);
  }
  if (!hex) return null;
  var tint = parseFloat(vwXmlAttr(attrs, "tint"));
  return tint ? vwTint(hex, tint) : hex;
}

/* A theme's colours, in the order a theme number counts them */
function vwXlTheme(xml) {
  var scheme = vwXmlFirst(xml, "clrScheme");
  if (!scheme) return VW_XL_THEME;
  return VW_XL_SCHEME.map(function (name, i) {
    var part = vwXmlFirst(scheme[1], name), m = null;
    if (part) {
      m = /<(?:[\w.-]+:)?srgbClr\b[^>]*?\bval\s*=\s*["']([0-9A-Fa-f]{6})["']/.exec(part[1]) ||
        /<(?:[\w.-]+:)?sysClr\b[^>]*?\blastClr\s*=\s*["']([0-9A-Fa-f]{6})["']/.exec(part[1]);
    }
    return m ? m[1].toUpperCase() : VW_XL_THEME[i];
  });
}

/* A fill as one colour, or null for none. A gradient is drawn as the mean of its stops. */
function vwXlFill(xml, theme, palette) {
  var gradient = vwXmlFirst(xml, "gradientFill");
  if (gradient) {
    var stops = [];
    vwXmlAll(gradient[1], "stop").forEach(function (stop) {
      var colour = vwXmlFirst(stop[1], "color");
      var hex = colour && vwXlColour(colour[0], theme, palette);
      if (hex) stops.push(vwRgb(hex));
    });
    if (!stops.length) return null;
    return vwHex([0, 1, 2].map(function (i) {
      return stops.reduce(function (sum, c) { return sum + c[i]; }, 0) / stops.length;
    }));
  }
  var pattern = vwXmlFirst(xml, "patternFill");
  var share = pattern ? VW_XL_PATTERN[vwXmlAttr(pattern[0], "patternType") || "none"] : 0;
  if (!share) return null;
  // An automatic pattern colour is the text's, black; an automatic colour behind it the window's
  var fg = vwXmlFirst(pattern[1], "fgColor"), bg = vwXmlFirst(pattern[1], "bgColor");
  var front = (fg && vwXlColour(fg[0], theme, palette)) || "000000";
  if (share === 1) return front;
  return vwBlend((bg && vwXlColour(bg[0], theme, palette)) || "FFFFFF", front, share);
}

/* Every look in the workbook, and the stylesheet that draws them */
function VwLooks() {
  this.list = [null];
  this.byKey = {};
  this.rules = [];
  this.style = null;
}

/* The number of the look with these parts, made if it is new; 0 for none */
VwLooks.prototype.add = function (fill, ink, b, i, u, s) {
  // White is the page's own ground by day and turns over to the night's, so a white fill draws as
  // none; and black text is the page's own, by day and as the night ink
  if (fill === "FFFFFF") fill = null;
  if (ink === "000000") ink = null;
  if (!fill && !ink && !b && !i && !u && !s) return 0;
  var key = [fill, ink, b ? 1 : 0, i ? 1 : 0, u ? 1 : 0, s ? 1 : 0].join();
  var n = this.byKey[key];
  if (n !== undefined) return n;
  n = this.byKey[key] = this.list.length;
  this.list.push((fill ? "vw-f " : "") + "vw-l" + n);
  this.rules.push(vwLookRules(n, fill, ink, b, i, u, s));
  return n;
};

/* The rules for looks made since the last call, added to the page */
VwLooks.prototype.flush = function () {
  if (!this.rules.length) return;
  if (!this.style) {
    this.style = document.createElement("style");
    document.head.appendChild(this.style);
  }
  this.style.appendChild(document.createTextNode(this.rules.join("\n") + "\n"));
  this.rules = [];
};

/*
 * A look's rules. Text given no colour of its own on a fill too dark for black takes white, as it
 * does in LibreOffice. At night the fill and the text are each turned over, and text left too faint
 * on its fill takes whichever of the night's ground and ink reads, unless the file set it that faint
 * by day, as for a figure kept out of sight in white on white.
 */
function vwLookRules(n, fill, ink, b, i, u, s) {
  var rule = "#sheet td.vw-l" + n;
  var day = [], night = [];
  var paper = fill || "FFFFFF";
  var text = ink || (fill ? vwReadableOn(fill, "000000", "FFFFFF") : "000000");
  if (fill) day.push("background-color: #" + fill);
  if (text !== "000000") day.push("color: #" + text);
  if (b) day.push("font-weight: 700");
  if (i) day.push("font-style: italic");
  if (u || s) day.push("text-decoration: " + (u ? "underline" : "") + (u && s ? " " : "") + (s ? "line-through" : ""));
  var out = rule + " { " + day.join("; ") + " }";
  if (fill || ink) {
    var paperAtNight = fill ? vwSheetNight(fill) : VW_SHEET_NIGHT_GROUND;
    var textAtNight = vwSheetNight(text);
    if (vwContrast(textAtNight, paperAtNight) < Math.min(VW_SHEET_CONTRAST, vwContrast(text, paper))) {
      textAtNight = vwReadableOn(paperAtNight, VW_SHEET_NIGHT_GROUND, VW_SHEET_NIGHT_INK);
    }
    if (fill) night.push("background-color: #" + paperAtNight);
    night.push("color: #" + textAtNight);
    out += "\n@media (prefers-color-scheme: dark) { " + rule + " { " + night.join("; ") + " } }";
  }
  return out;
}

/* A column's number from its letters, A being 0 */
function vwColumnOf(letters) {
  var n = 0;
  for (var i = 0; i < letters.length; i++) n = n * 26 + (letters.charCodeAt(i) & 0x1F);
  return n - 1;
}

var VW_SHEET_TAG = /<(?:[\w.-]+:)?(row|c)(?=[\s\/>])([^>]*)>/g;
var VW_ATTR_ROW = /\sr\s*=\s*["'](\d+)/;
var VW_ATTR_CELL = /\sr\s*=\s*["']([A-Za-z]+)(\d+)/;
var VW_ATTR_STYLE = /\ss\s*=\s*["'](\d+)/;
var VW_ATTR_ROW_STYLED = /\scustomFormat\s*=\s*["'](1|true)["']/;

/*
 * A sheet's looks, as its reader found them: at(row, column) for a cell's, and row(row) for whether
 * any cell of a row has one, so that a sheet's plain rows are passed over whole. A cell written in
 * the file has its own style. One that is not, an empty cell, has its row's, where the row has one,
 * and otherwise its column's: Excel writes out each cell where a row and a column it has styled
 * cross, so that order is all there is to know. [cells] holds a written cell's look by row and
 * column, a plain one only where it hides its row's or column's, and [marked] each row with a look
 * of a cell's own.
 */
function vwSheetLooks(columns, rows, cells, marked) {
  var anyColumn = columns.length > 0;
  return {
    row: function (row) { return anyColumn || !!rows[row] || !!marked[row]; },
    at: function (row, col) {
      var own = cells[row];
      if (own !== undefined && own[col] !== undefined) return own[col];
      var look = rows[row];
      return look !== undefined ? look : columns[col] || 0;
    }
  };
}

/*
 * The looks of an .xlsx sheet's cells, from its XML, as vwSheetLooks gives them. The XML is read
 * whole, once: a sheet of 100,000 rows by ten columns, a yellow row in every ten, took 0.16 s in
 * Node on a Mac, a tenth of the time SheetJS took to read the workbook. Read a piece at a time
 * instead, the XML would have to be kept, up to twice its size in memory, for as long as the sheet
 * is open.
 */
function vwXlsxSheetLooks(xml, lookOf, lastColumn) {
  var data = /<(?:[\w.-]+:)?sheetData(?=[\s\/>])/.exec(xml);
  if (!data) return null;
  var columns = [], rows = [], cells = [], marked = [], any = false;
  vwXmlAll(xml.slice(0, data.index), "col").forEach(function (col) {
    var look = lookOf(+vwXmlAttr(col[0], "style") || 0);
    if (!look) return;
    var min = +vwXmlAttr(col[0], "min") || 1, max = Math.min(+vwXmlAttr(col[0], "max") || min, lastColumn + 1);
    for (var c = min - 1; c < max; c++) columns[c] = look;
    any = true;
  });
  var close = /<\/(?:[\w.-]+:)?sheetData\s*>/g;
  close.lastIndex = data.index;
  var end = close.exec(xml) ? close.lastIndex : xml.length;
  var tag = VW_SHEET_TAG, r = -1, c = -1, rowLook = 0, m, v;
  tag.lastIndex = data.index;
  while ((m = tag.exec(xml)) && m.index < end) {
    var attrs = m[2];
    if (m[1] === "row") {
      v = VW_ATTR_ROW.exec(attrs);
      r = v ? +v[1] - 1 : r + 1;
      c = -1;
      rowLook = 0;
      if (VW_ATTR_ROW_STYLED.test(attrs)) {
        v = VW_ATTR_STYLE.exec(attrs);
        rowLook = v ? lookOf(+v[1]) : 0;
        rows[r] = rowLook;
        if (rowLook) any = true;
      }
    } else {
      v = VW_ATTR_CELL.exec(attrs);
      if (v) {
        c = vwColumnOf(v[1].toUpperCase());
        r = +v[2] - 1;
      } else {
        c++;
      }
      v = VW_ATTR_STYLE.exec(attrs);
      var look = v ? lookOf(+v[1]) : 0;
      // A plain cell is kept only where it hides its row's look or its column's
      if (look || rowLook || columns[c]) (cells[r] || (cells[r] = []))[c] = look;
      if (look) any = marked[r] = true;
    }
  }
  tag.lastIndex = 0;
  return any ? vwSheetLooks(columns, rows, cells, marked) : null;
}

/*
 * The looks of each of an .xlsx's sheets, in [wb.SheetNames]'s order, as a function that gives
 * vwXlsxSheetLooks's reading, or null for a sheet drawn plain. Null altogether for a workbook that
 * is not an .xlsx. A sheet's XML is read the first time it is asked for.
 */
function vwXlsxLooks(wb, looks) {
  var dir = wb.Directory, files = wb.files;
  if (!files || !dir || !dir.style || !/\.xml$/i.test(dir.style) || !dir.workbooks || !dir.workbooks.length) {
    return null;
  }
  var byPath = {};
  Object.keys(files).forEach(function (key) { byPath[key.toLowerCase()] = files[key]; });
  function part(path) {
    var file = byPath[path.replace(/^\//, "").toLowerCase()];
    return file ? file.content : null;
  }

  var theme = dir.themes && dir.themes.length ? vwXlTheme(vwXmlText(part(dir.themes[0]))) : VW_XL_THEME;
  var xml = vwXmlText(part(dir.style));
  var palette = VW_XL_PALETTE;
  var custom = vwXmlFirst(xml, "indexedColors");
  if (custom) {
    palette = VW_XL_PALETTE.slice();
    vwXmlAll(custom[1], "rgbColor").forEach(function (colour, i) {
      var hex = i < 64 && vwXlColour(colour[0], theme, VW_XL_PALETTE);
      if (hex) palette[i] = hex;
    });
  }
  var fontsXml = vwXmlFirst(xml, "fonts"), fillsXml = vwXmlFirst(xml, "fills"), xfsXml = vwXmlFirst(xml, "cellXfs");
  var fonts = vwXmlAll(fontsXml ? fontsXml[1] : "", "font").map(function (font) {
    var colour = vwXmlFirst(font[1], "color"), u = vwXmlFirst(font[1], "u");
    return {
      ink: colour ? vwXlColour(colour[0], theme, palette) : null,
      b: vwXmlFlag(font[1], "b"),
      i: vwXmlFlag(font[1], "i"),
      u: !!u && vwXmlAttr(u[0], "val") !== "none",
      s: vwXmlFlag(font[1], "strike")
    };
  });
  var fills = vwXmlAll(fillsXml ? fillsXml[1] : "", "fill").map(function (fill) {
    return vwXlFill(fill[1], theme, palette);
  });
  var xfs = vwXmlAll(xfsXml ? xfsXml[1] : "", "xf");
  // The workbook's own text colour is the page's: a cell set in it draws as one in no colour at all
  var normal = fonts[xfs.length ? +vwXmlAttr(xfs[0][0], "fontId") || 0 : 0];
  var normalInk = normal ? normal.ink : null;
  var any = false;
  var xfLooks = xfs.map(function (xf) {
    var font = fonts[+vwXmlAttr(xf[0], "fontId") || 0] || {};
    var fill = fills[+vwXmlAttr(xf[0], "fillId") || 0] || null;
    var look = looks.add(fill, font.ink === normalInk ? null : font.ink, font.b, font.i, font.u, font.s);
    if (look) any = true;
    return look;
  });
  if (!any) return null;
  function lookOf(n) { return xfLooks[n] || 0; }

  // Each sheet's part, from the workbook's list of its sheets and where its relationships point
  var book = dir.workbooks[0].replace(/^\//, "");
  var folder = book.replace(/[^\/]*$/, "");
  var targets = {};
  vwXmlAll(vwXmlText(part(folder + "_rels/" + book.slice(folder.length) + ".rels")), "Relationship")
    .forEach(function (rel) { targets[vwXmlAttr(rel[0], "Id")] = vwXmlAttr(rel[0], "Target"); });
  var list = vwXmlFirst(vwXmlText(part(book)), "sheets");
  var paths = vwXmlAll(list ? list[1] : "", "sheet").map(function (sheet) {
    var id = /\s[\w.-]+:id\s*=\s*["']([^"']*)["']/.exec(sheet[0]);
    var target = id && targets[id[1]];
    if (!target) return null;
    if (target.charAt(0) === "/") return target.slice(1);
    var path = (folder + target).split("/"), out = [];
    path.forEach(function (step) {
      if (step === "..") out.pop();
      else if (step !== ".") out.push(step);
    });
    return out.join("/");
  });

  var sheetParts = paths.map(function (path) { return path ? part(path) : null; });
  // The functions below would otherwise keep every part of the file alive, not just their sheet's
  byPath = files = null;

  return wb.SheetNames.map(function (name, i) {
    var bytes = sheetParts[i];
    sheetParts[i] = null;
    if (!bytes) return null;
    var ref = wb.Sheets[name] && wb.Sheets[name]["!ref"];
    var lastColumn = ref ? XLSX.utils.decode_range(ref).e.c : -1;
    var sheet;
    return function () {
      if (sheet === undefined) {
        sheet = vwXlsxSheetLooks(vwXmlText(bytes), lookOf, lastColumn);
        bytes = null;
      }
      return sheet;
    };
  });
}

/* Excel 97-2003's fill patterns, by the number an .xls gives one */
var VW_XLS_PATTERNS = ["none", "solid", "mediumGray", "darkGray", "lightGray", "darkHorizontal",
  "darkVertical", "darkDown", "darkUp", "darkGrid", "darkTrellis", "lightHorizontal", "lightVertical",
  "lightDown", "lightUp", "lightGrid", "lightTrellis", "gray125", "gray0625"];

/* Records of an .xls, by the numbers MS-XLS gives them */
var VW_XLS_BOF = 0x0809, VW_XLS_EOF = 0x000A, VW_XLS_FILEPASS = 0x002F, VW_XLS_FONT = 0x0031,
  VW_XLS_XF = 0x00E0, VW_XLS_PALETTE = 0x0092, VW_XLS_BOUNDSHEET = 0x0085, VW_XLS_ROW = 0x0208,
  VW_XLS_COLINFO = 0x007D, VW_XLS_MULRK = 0x00BD, VW_XLS_MULBLANK = 0x00BE;

/* The records for one cell, each of which starts with its row, its column and its XF */
var VW_XLS_CELLS = { 0x0006: 1, 0x00D6: 1, 0x00FD: 1, 0x0201: 1, 0x0203: 1, 0x0204: 1, 0x0205: 1, 0x027E: 1 };

/*
 * The looks of each of an .xls's sheets, in [wb.SheetNames]'s order, as vwXlsxLooks gives them.
 * Read from the workbook stream's records as MS-XLS lays them out: FONT, XF and PALETTE for the
 * styles, and for each sheet its cells, ROW and COLINFO. Excel 97 to 2003's format alone, and not a
 * file with a password, whose records are encrypted. A cell's XF is taken whole, as Excel writes
 * them, though an XF can say it takes some of its parts from its style: taking them from the style
 * changed no cell of the 84 .xls files scored against LibreOffice (scripts/sheet-corpus).
 */
function vwXlsLooks(wb, looks) {
  // A file from Excel 4 or before is a bare stream, which SheetJS keeps as it came, with no
  // directory to find a stream in
  var cfb = wb.cfb && wb.cfb.FullPaths ? wb.cfb : null;
  var stream = cfb && (XLSX.CFB.find(cfb, "/Workbook") || XLSX.CFB.find(cfb, "/Book"));
  var b = stream && stream.content;
  if (!b || b.length < 8) return null;
  if (!(b instanceof Uint8Array)) b = new Uint8Array(b);
  function u16(at) { return b[at] | b[at + 1] << 8; }
  function u32(at) { return u16(at) + u16(at + 2) * 0x10000; }
  // BIFF8's workbook globals open the stream
  if (u16(0) !== VW_XLS_BOF || u16(4) !== 0x0600) return null;

  var fonts = [], xfs = [], sheets = [], palette = VW_XL_PALETTE.slice();
  for (var at = 0; at + 4 <= b.length;) {
    var type = u16(at), d = at + 4;
    at = d + u16(at + 2);
    if (type === VW_XLS_FILEPASS) return null;
    if (type === VW_XLS_EOF) break;
    if (type === VW_XLS_FONT) {
      var options = u16(d + 2), icv = u16(d + 4);
      fonts.push({ ink: icv < 64 ? icv : null, b: u16(d + 6) >= 700, i: !!(options & 2), s: !!(options & 8), u: b[d + 10] !== 0 });
    } else if (type === VW_XLS_XF) {
      xfs.push({ font: u16(d), pattern: u32(d + 14) >>> 26, area: u16(d + 18) });
    } else if (type === VW_XLS_PALETTE) {
      for (var k = 0; k < u16(d) && 8 + k < 64; k++) palette[8 + k] = vwHex([b[d + 2 + k * 4], b[d + 3 + k * 4], b[d + 4 + k * 4]]);
    } else if (type === VW_XLS_BOUNDSHEET) {
      sheets.push(u32(d));
    }
  }
  // Numbers past the palette are the system's colours: 64 the window's text, 65 the window
  function colour(icv, auto) {
    return icv < 64 ? palette[icv] : icv === 64 ? "000000" : icv === 65 ? "FFFFFF" : auto;
  }
  // The fifth font is never written, so XFs number the fonts after it one higher
  var normal = fonts[0] || {};
  var any = false;
  var xfLooks = xfs.map(function (xf) {
    var font = fonts[xf.font < 4 ? xf.font : xf.font - 1] || {};
    var share = VW_XL_PATTERN[VW_XLS_PATTERNS[xf.pattern]] || 0, fill = null;
    if (share) {
      var front = colour(xf.area & 0x7F, "000000");
      fill = share === 1 ? front : vwBlend(colour(xf.area >> 7 & 0x7F, "FFFFFF"), front, share);
    }
    var ink = font.ink === null || font.ink === normal.ink ? null : palette[font.ink];
    var look = looks.add(fill, ink, font.b, font.i, font.u, font.s);
    if (look) any = true;
    return look;
  });
  if (!any) return null;

  // SheetJS lists the sheets in the order they lie in the stream
  sheets.sort(function (x, y) { return x - y; });
  if (sheets.length !== wb.SheetNames.length) return null;
  return wb.SheetNames.map(function (name, i) {
    var start = sheets[i];
    // A worksheet's own BOF; a chart or a macro sheet has no cells to colour
    if (u16(start) !== VW_XLS_BOF || u16(start + 6) !== 0x0010) return null;
    var ref = wb.Sheets[name] && wb.Sheets[name]["!ref"];
    var lastColumn = ref ? XLSX.utils.decode_range(ref).e.c : -1;
    var sheet;
    return function () {
      if (sheet === undefined) sheet = vwXlsSheetLooks(b, start, u16, xfLooks, lastColumn);
      return sheet;
    };
  });
}

/*
 * The looks of an .xls sheet's cells, from its records, as vwSheetLooks gives them. Excel puts each
 * block of rows' ROW records before their cells, but a file need not, so the rows and columns are
 * read first, and the cells after.
 */
function vwXlsSheetLooks(b, start, u16, xfLooks, lastColumn) {
  var columns = [], rows = [], cells = [], marked = [], any = false, at, type, d, c, n, i;
  function lookOf(xf) { return xfLooks[xf] || 0; }
  for (at = start; at + 4 <= b.length;) {
    type = u16(at);
    d = at + 4;
    at = d + u16(at + 2);
    if (type === VW_XLS_EOF) break;
    // A row with fGhostDirty set gives the cells not written in it its XF
    if (type === VW_XLS_ROW && u16(d + 12) & 0x80) {
      rows[u16(d)] = lookOf(u16(d + 14) & 0xFFF);
      if (rows[u16(d)]) any = true;
    } else if (type === VW_XLS_COLINFO) {
      var look = lookOf(u16(d + 6));
      for (c = u16(d); look && c <= Math.min(u16(d + 2), lastColumn); c++) columns[c] = look;
      if (look) any = true;
    }
  }
  function cell(row, col, xf) {
    var look = lookOf(xf);
    if (look || rows[row] || columns[col]) (cells[row] || (cells[row] = []))[col] = look;
    if (look) any = marked[row] = true;
  }
  for (at = start; at + 4 <= b.length;) {
    type = u16(at);
    d = at + 4;
    n = u16(at + 2);
    at = d + n;
    if (type === VW_XLS_EOF) break;
    if (VW_XLS_CELLS[type]) {
      cell(u16(d), u16(d + 2), u16(d + 4));
    } else if (type === VW_XLS_MULRK || type === VW_XLS_MULBLANK) {
      // A run of cells along a row: each an XF, and for MULRK a number after it
      var step = type === VW_XLS_MULRK ? 6 : 2;
      for (i = 0; i < (n - 6) / step; i++) cell(u16(d), u16(d + 2) + i, u16(d + 4 + i * step));
    }
  }
  return any ? vwSheetLooks(columns, rows, cells, marked) : null;
}

/*
 * For each of [wb]'s sheets, a function that gives each cell of a table SheetJS wrote for it the
 * class of its look, or null for a sheet drawn plain. SheetJS read [wb] with bookFiles, which keeps
 * the file's parts, or for an .xls the file itself.
 */
function vwCellLooks(wb) {
  var looks = new VwLooks(), sheets = null;
  // A file these readers cannot make sense of is drawn plain, as every file was before them,
  // rather than not at all
  try {
    sheets = vwXlsxLooks(wb, looks) || vwXlsLooks(wb, looks);
  } catch (e) {
    sheets = null;
  }
  // Kept for this alone; each sheet's own part or records are held until it has been read
  delete wb.files;
  delete wb.keys;
  delete wb.cfb;
  return wb.SheetNames.map(function (name, k) {
    var get = sheets && sheets[k], failed = false;
    if (!get) return null;
    return function (table) {
      if (failed) return;
      try {
        var sheet = get();
        for (var i = 0; sheet && i < table.rows.length; i++) {
          var cells = table.rows[i].cells, row = -1;
          for (var j = 0; j < cells.length; j++) {
            // sheet_to_html names each cell after its address, as sjs-B7
            var m = /^sjs-([A-Z]+)(\d+)$/.exec(cells[j].id);
            if (!m) continue;
            if (row < 0) {
              row = +m[2] - 1;
              if (!sheet.row(row)) break;
            }
            var look = sheet.at(row, vwColumnOf(m[1]));
            if (look) cells[j].className = looks.list[look];
          }
        }
      } catch (e) {
        failed = true;
      }
      looks.flush();
    };
  });
}
