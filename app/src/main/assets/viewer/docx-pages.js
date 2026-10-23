"use strict";

/*
 * Word's own pages, issue #47, and measured ones, issue #55.
 *
 * docx-preview starts a page only at a page or section break, so a document written as one
 * flow of text came out as a single sheet. Word records where each page began when it last
 * laid the file out, as w:lastRenderedPageBreak, and where a file carries that record its
 * pages are split there and numbered here. WPS, LibreOffice and Google Docs write no such
 * record, so for their files the pages are worked out from the first drawing instead: where
 * each page's paper runs out, records are written into the document as Word would have
 * written them, and it is drawn again (vwMeasurePages).
 */
var vwPages = {
  /* Whether this document's pages are Word's */
  split: false,
  /* Whether the document records no page at all, so its pages are measured; decided on its
     first drawing, since the second carries the records vwAddTurns wrote */
  measure: null,
  /* Where the measured pages turn, for the second drawing; see vwSheetTurns */
  turns: null,
  /* Whether the drawing on screen is the last, which the page counter and night mode wait for:
     a second drawing replaces the first's pages and everything marked on them */
  settled: false,
  /* Word's own count of its pages when it last saved the file, from docProps/app.xml */
  wordPages: 0,
  /* The places a second drawing starts a page Word left no record of; see vwFindMissingPages */
  turnAt: null,
  /* The section properties each page was made with, in order */
  props: [],
  /* Fields being read, innermost last; see trackField */
  fields: [],
  nextField: 0
};

function vwIsPageMark(node) {
  return node.type === "break" && node.break === "lastRenderedPageBreak";
}

function vwHasPageMark(node) {
  var kids = node.children || [];
  for (var i = 0; i < kids.length; i++) {
    if (vwIsPageMark(kids[i]) || vwHasPageMark(kids[i])) return true;
  }
  return false;
}

/* Whether anything would show: a paragraph that only holds a bookmark or a break is empty. */
var VW_BLANK = { "break": 1, bookmarkStart: 1, bookmarkEnd: 1 };
var VW_HOLDERS = { run: 1, hyperlink: 1, smartTag: 1, inserted: 1, paragraph: 1 };

function vwHasInk(node) {
  var kids = node.children || [];
  for (var i = 0; i < kids.length; i++) {
    var c = kids[i];
    if (VW_BLANK[c.type]) continue;
    if (VW_HOLDERS[c.type] ? vwHasInk(c) : true) return true;
  }
  return false;
}

function vwColumns(props) {
  return (props && props.columns && props.columns.numberOfColumns) || 1;
}

/*
 * Word records where each column began as well as each page, and the two look the same. So a
 * section set in columns loses every record, [all], and a section running on from one loses
 * the record at its top, which marks where those columns ended.
 */
function vwColumnMarksOut(elements, all) {
  var inked = false;
  function out(node) {
    if (!node.children) return node;
    var kept = [], changed = false;
    node.children.forEach(function (c) {
      if (vwIsPageMark(c) && (all || !inked)) { changed = true; return; }
      if (!c.children && !VW_BLANK[c.type]) inked = true;
      var d = out(c);
      if (d !== c) changed = true;
      kept.push(d);
    });
    return changed ? Object.assign({}, node, { children: kept }) : node;
  }
  return elements.map(out);
}

/*
 * A section with no header or footer of a kind of its own shows the one before it, as Word
 * does. docx-preview drew none there, which on a document of several sections left the page
 * numbers off every page after the first section.
 */
function vwInheritRefs(prev, props, key) {
  var own = props[key] || [];
  var inherited = (prev && prev[key] || []).filter(function (ref) {
    return !own.some(function (mine) { return mine.type === ref.type; });
  });
  if (inherited.length) props[key] = own.concat(inherited);
}

/*
 * Installed from the h hook in docx.js, before the first page is made. Replaces how
 * docx-preview groups the body into pages, with Word's records where the file has them.
 */
function vwSplitAsWordDid(renderer) {
  var createPageElement = renderer.createPageElement;
  var renderHeaderFooter = renderer.renderHeaderFooter;
  var renderRun = renderer.renderRun;
  var renderParagraph = renderer.renderParagraph;
  var renderTableRow = renderer.renderTableRow;
  var renderText = renderer.renderText;

  renderer.splitBySection = function (elements, bodyProps) {
    /* A second drawing counts its pages and fields afresh */
    vwPages.props = [];
    vwPages.fields = [];
    var app = this.document.extendedPropsPart;
    vwPages.wordPages = (app && app.props && app.props.pages) || 0;
    vwPages.split = elements.some(vwHasPageMark);
    if (vwPages.measure === null) vwPages.measure = !vwPages.split;
    return splitPieces(this, elements, bodyProps);
  };

  renderer.groupByPageBreaks = function (pieces) {
    var pages = [], page = [];
    pieces.forEach(function (piece) {
      page.push(piece);
      if (piece.pageBreak) { pages.push(page); page = []; }
    });
    if (page.length) pages.push(page);
    return pages;
  };

  renderer.createPageElement = function (className, props, docStyle) {
    vwPages.props.push(props);
    return createPageElement.call(this, className, props, docStyle);
  };

  /* A header or footer is read on its own, so a field left open in one cannot reach another */
  renderer.renderHeaderFooter = function () {
    var outer = vwPages.fields;
    vwPages.fields = [];
    try {
      return renderHeaderFooter.apply(this, arguments);
    } finally {
      vwPages.fields = outer;
    }
  };

  renderer.renderRun = function (run) {
    if (run.fieldRun) trackField(run);
    var span = renderRun.call(this, run);
    var field = vwPages.fields[vwPages.fields.length - 1];
    if (span && field && field.showing && field.kind) {
      span.setAttribute("data-vw-field", field.id + " " + field.kind + " " + field.format);
    }
    return span;
  };

  /*
   * The places splitPieces numbered, marked for vwFindMissingPages to measure. In a document
   * whose pages are measured, every paragraph, row and piece of text is also named by its
   * place in the document (vwNumberModel), for vwSheetTurns to say where a page turns.
   */
  renderer.renderParagraph = function (para) {
    var el = vwMarkPlace(renderParagraph.call(this, para), para);
    if (el && vwPages.measure && para.vwP !== undefined) {
      el.setAttribute("data-vw-p", para.vwP);
      var keep = vwKeeps(this, para);
      if (keep) el.setAttribute("data-vw-keep", keep);
    }
    return el;
  };
  renderer.renderTableRow = function (row) {
    var el = vwMarkPlace(renderTableRow.call(this, row), row);
    if (el && vwPages.measure && row.vwR !== undefined) {
      el.setAttribute("data-vw-r", row.vwR);
      if (vwIsHeaderRow(row)) el.setAttribute("data-vw-head", "");
    }
    return el;
  };
  renderer.renderText = function (text) {
    var node = renderText.call(this, text);
    if (node && vwPages.measure && text.vwT !== undefined) node.vwT = text.vwT;
    return node;
  };
}

/* Word's keep with next ("n") and keep lines together ("l"), set on the paragraph or its styles */
function vwKeeps(renderer, para) {
  var next = para.keepNext, lines = para.keepLines;
  var style = renderer.findStyle(para.styleName);
  for (var depth = 0; style && depth < 20; depth++) {
    var props = style.paragraphProps || {};
    if (next === undefined) next = props.keepNext;
    if (lines === undefined) lines = props.keepLines;
    style = style.basedOn ? renderer.findStyle(style.basedOn) : null;
  }
  return (next ? "n" : "") + (lines ? "l" : "");
}

function vwMarkPlace(el, node) {
  var spot = node.vwPlace;
  if (el && spot && spot.canTurn) el.setAttribute("data-vw-place", spot.n + " " + spot.stretch);
  return el;
}

/* A paragraph with no run: Word records where a page began in its first run, so here it cannot */
function vwRunless(node) {
  return !(node.children || []).some(function (c) {
    return c.type === "run" || !vwRunless(c);
  });
}

/* docx-preview reads Word's <w:tblHeader/>, which carries no value, as null, and a row
   without one has no isHeader */
function vwIsHeaderRow(row) {
  return "isHeader" in row && row.isHeader !== false;
}

function vwRowStartsRunless(row) {
  var cell = (row.children || [])[0];
  var first = cell && (cell.children || [])[0];
  return !!first && first.type === "paragraph" && vwRunless(first);
}

/*
 * A field is a code between its begin and separate marks, then the value it showed between
 * separate and end. docx-preview drops the code and draws that saved value, so the runs
 * showing a page number are marked here for vwNumberPages to write the right one into.
 */
var VW_FIELD = /^\s*(PAGE|NUMPAGES|SECTIONPAGES)\b(.*)$/i;
var VW_FIELD_FORMATS = {
  roman: "lowerRoman", ROMAN: "upperRoman", Roman: "upperRoman",
  alphabetic: "lowerLetter", ALPHABETIC: "upperLetter", Alphabetic: "upperLetter",
  arabic: "decimal", Arabic: "decimal", ARABIC: "decimal", ArabicDash: "numberInDash"
};

function trackField(run) {
  var fields = vwPages.fields;
  (run.children || []).forEach(function (part) {
    var top = fields[fields.length - 1];
    if (part.type === "instruction") {
      if (top && !top.showing) top.code += part.text || "";
    } else if (part.type === "complexField") {
      if (part.charType === "begin") {
        fields.push({ id: ++vwPages.nextField, code: "", showing: false });
      } else if (part.charType === "separate" && top) {
        top.showing = true;
        var said = VW_FIELD.exec(top.code);
        if (said) {
          var as = /\\\*\s*(\w+)/.exec(said[2]);
          top.kind = said[1].toUpperCase();
          top.format = (as && VW_FIELD_FORMATS[as[1]]) || "-";
        }
      } else if (part.charType === "end") {
        fields.pop();
      }
    }
  });
}

/*
 * The body as pages. A section break starts one unless the next section runs on, a page
 * break always does, and Word's record does once the page has something on it, so a record
 * that repeats a break already made adds no empty page.
 */
function splitPieces(renderer, elements, bodyProps) {
  var sections = [], held = [];
  elements.forEach(function (el) {
    held.push(el);
    if (el.type === "paragraph" && el.sectionProps) {
      sections.push({ props: el.sectionProps, elements: held });
      held = [];
    }
  });
  sections.push({ props: bodyProps, elements: held });

  var pieces = [], piece = null, inked = false;
  /* Places numbered, stretches between ink, and places with no ink after them yet */
  var places = 0, stretch = 0, open = [];
  function start(props) {
    piece = { sectProps: props, elements: [], pageBreak: false };
    pieces.push(piece);
  }
  /*
   * Numbers a place a page can begin without Word's record, the same on every drawing, and
   * says whether this drawing turns there. A turn makes a page only with ink above and below
   * it on the page, and places with no ink between them share a stretch, one turn to each.
   */
  function place(node, canTurn) {
    /* An object, so the copy of the paragraph that is drawn sees a verdict reached later */
    var spot = node.vwPlace = { n: places++, stretch: stretch, canTurn: canTurn };
    if (canTurn) open.push(spot);
    return !!(vwPages.turnAt && vwPages.turnAt[spot.n]);
  }
  function inkSeen() {
    stretch++;
    open = [];
  }
  function pageEnds() {
    open.forEach(function (spot) { spot.canTurn = false; });
    open = [];
  }
  function turn(hard) {
    if (!inked && !hard) return;
    pageEnds();
    piece.pageBreak = true;
    start(piece.sectProps);
    inked = false;
  }
  /* A table's ink is seen row by row as it is read, since its rows are added in slices */
  function add(el) {
    piece.elements.push(el);
    if (el.type === "paragraph" || el.type === "table" ? vwHasInk(el) : !VW_BLANK[el.type]) {
      inked = true;
      if (el.type !== "table") inkSeen();
    }
  }
  function rows(table, list) { return Object.assign({}, table, { children: list }); }

  sections.forEach(function (s, si) {
    var prev = si > 0 ? sections[si - 1].props : null;
    var runsOn = false;
    if (prev) {
      vwInheritRefs(prev, s.props, "headerRefs");
      vwInheritRefs(prev, s.props, "footerRefs");
      runsOn = (s.props.type === "continuous" || s.props.type === "nextColumn") &&
        !renderer.isPageBreakSection(prev, s.props);
      if (!runsOn) {
        pageEnds();
        piece.pageBreak = true;
        inked = false;
      }
    }
    start(s.props);

    var columned = vwColumns(s.props) > 1;
    var elements = columned || (runsOn && vwColumns(prev) > 1)
      ? vwColumnMarksOut(s.elements, columned) : s.elements;
    /* Columns fill side by side, so how far down a page a place sits says nothing in them */
    var placing = !columned;

    elements.forEach(function (el) {
      if (el.type === "table") {
        var all = el.children || [], heads = [], from = 0;
        while (heads.length < all.length && vwIsHeaderRow(all[heads.length])) heads.push(all[heads.length]);
        for (var r = 0; r < all.length; r++) {
          /* Whether rows of the body, or what came before the table, are above this row */
          var above = r > Math.max(from, heads.length) || (!from && inked);
          var turns = placing && !vwIsHeaderRow(all[r]) && vwRowStartsRunless(all[r]) &&
            place(all[r], above);
          if (r === from && (turns || vwHasPageMark(all[r]))) {
            turn();
          } else if (turns || vwHasPageMark(all[r])) {
            add(rows(el, (from >= heads.length && from ? heads : []).concat(all.slice(from, r))));
            turn();
            from = r;
          }
          if (vwHasInk(all[r])) inkSeen();
        }
        add(from ? rows(el, (from >= heads.length ? heads : []).concat(all.slice(from))) : el);
        return;
      }
      if (el.type !== "paragraph") { add(el); return; }
      if (placing && vwRunless(el) && place(el, inked)) turn();

      var style = renderer.findStyle(el.styleName);
      if (el.pageBreakBefore || (style && style.paragraphProps && style.paragraphProps.pageBreakBefore)) {
        turn();
      }

      var part = Object.assign({}, el, { children: [] });
      (el.children || []).forEach(function (run) {
        var rest = run;
        var at = run.type === "run" && run.children ? run.children.findIndex(isBreak) : -1;
        while (at >= 0) {
          if (at > 0) part.children.push(Object.assign({}, rest, { children: rest.children.slice(0, at) }));
          var hard = rest.children[at].break === "page";
          var before = vwHasInk(part);
          if (before || hard) {
            add(part);
            turn(hard);
            part = Object.assign({}, el, { children: [] });
            /* The rest of a paragraph a page cut in two: no second list number, no indent */
            if (before) part.className = [el.className, "vw-cont"].filter(Boolean).join(" ");
          } else {
            turn();
          }
          rest = Object.assign({}, rest, { children: rest.children.slice(at + 1) });
          at = rest.children.findIndex(isBreak);
        }
        if (rest === run || rest.children.length) part.children.push(rest);
      });
      add(part);
    });
  });

  pageEnds();

  /* An empty page left by a break at a section's end gives its break to the page before */
  var kept = [];
  pieces.forEach(function (p) {
    if (p.elements.length || !kept.length) kept.push(p);
    else if (p.pageBreak) kept[kept.length - 1].pageBreak = true;
  });
  return kept;
}

function isBreak(node) {
  return node.type === "break" && (node.break === "page" || node.break === "lastRenderedPageBreak");
}

/*
 * Word records no page that begins on a paragraph without a run, such as a blank line or an
 * empty first cell, so its own count of pages says how many went unrecorded. Resolves true
 * once vwPages.turnAt has places for them, for the document to be drawn again.
 */
function vwFindMissingPages() {
  if (vwPages.measure) return vwMeasurePages();
  var sheets = vwSheets(), missing = vwPages.wordPages - sheets.length;
  if (!vwPages.split || !(missing > 0)) return Promise.resolve(false);
  /* A layout first, so a font the document embeds is loading by the time fonts.ready is read */
  document.body.getBoundingClientRect();
  return document.fonts.ready.then(function () {
    vwPages.turnAt = vwPlaceTurns([].map.call(sheets, vwMeasureSheet), missing);
    return !!vwPages.turnAt;
  });
}

/* Over its paper by more than 2%: lines as Word sets them (docx-lines.js) run that close to Word's */
var VW_OVERFLOW = 1.02;

/*
 * Each missing page goes to the sheet that runs furthest over its paper, while one does and
 * has a place to turn for it.
 */
function vwPlaceTurns(plan, missing) {
  var given = 0, turnAt = null;
  while (given < missing) {
    var best = null, most = VW_OVERFLOW;
    plan.forEach(function (s) {
      var over = s.height / ((s.turns.length + 1) * s.room);
      if (s.room > 0 && !s.full && over > most) { best = s; most = over; }
    });
    if (!best) break;
    var turns = vwTurnsOn(best, best.turns.length + 2);
    if (turns) {
      best.turns = turns;
      given++;
    } else {
      best.full = true;
    }
  }
  plan.forEach(function (s) {
    s.turns.forEach(function (spot) { (turnAt = turnAt || {})[spot.n] = true; });
  });
  return turnAt;
}

/*
 * Where a sheet turns to make [pages] pages: at the place nearest each even share of it, one
 * to a stretch. Word turns at a blank line or a row only once the page above is full, so a
 * turn leaving less than half a page above it is not one Word made, and null says so.
 */
function vwTurnsOn(sheet, pages) {
  var turns = [], next = 0, above = 0;
  for (var j = 1; j < pages; j++) {
    var want = j * sheet.height / pages, pick = null, stretch = 0;
    for (var r = next; r < sheet.places.length; r++) {
      for (var q = 0; q < sheet.places[r].length; q++) {
        var spot = sheet.places[r][q];
        if (spot.at - above < sheet.room / 2) continue;
        if (!pick || Math.abs(spot.at - want) < Math.abs(pick.at - want)) {
          pick = spot;
          stretch = r;
        }
      }
    }
    if (!pick) return null;
    turns.push(pick);
    above = pick.at;
    next = stretch + 1;
  }
  return turns;
}

/*
 * How far down a sheet its body runs, the room its paper has for one, and the places a page
 * could begin on it, a list to each stretch, read before fitPageWidth zooms the sheets.
 */
function vwMeasureSheet(sheet) {
  var style = getComputedStyle(sheet);
  var padTop = parseFloat(style.paddingTop) || 0;
  var room = (parseFloat(style.minHeight) || 0) - padTop - (parseFloat(style.paddingBottom) || 0);
  var top = sheet.getBoundingClientRect().top + padTop, bottom = top;
  /* To the last ink, since Word lets a page's last space after fall below its margin */
  var blocks = sheet.querySelectorAll(":scope > article > *");
  for (var a = 0; a < blocks.length; a++) {
    bottom = Math.max(bottom, blocks[a].getBoundingClientRect().bottom);
  }
  var places = [], last = null;
  var marked = sheet.querySelectorAll(":scope > article [data-vw-place]");
  for (var m = 0; m < marked.length; m++) {
    var said = marked[m].getAttribute("data-vw-place").split(" ");
    var spot = { n: Number(said[0]), at: marked[m].getBoundingClientRect().top - top };
    if (said[1] === last) places[places.length - 1].push(spot);
    else places.push([spot]);
    last = said[1];
  }
  return { room: room, height: bottom - top, places: places, turns: [], full: false };
}

/*
 * Measured pages, issue #55: a file that records no page at all, as WPS, LibreOffice and
 * Google Docs write them, turns a page wherever its paper runs out on the first drawing.
 *
 * Names every paragraph, table row and piece of text in the body by its place, the same on
 * every parse of the same file, so a place measured on the first drawing can be found again
 * in the parse the second drawing is made from. Returns them, for vwAddTurns.
 */
function vwNumberModel(doc) {
  var body = doc.documentPart && doc.documentPart.body;
  var model = { paras: [], rows: [], texts: {} };
  /* [via] is the child of the paragraph a piece of text sits in, the part splitPieces can cut */
  function walk(node, para, via) {
    (node.children || []).forEach(function (c) {
      if (c.type === "paragraph") {
        c.vwP = model.paras.length;
        var p = { node: c, top: node === body, texts: 0 };
        model.paras.push(p);
        walk(c, p, null);
        return;
      }
      if (c.type === "row") {
        c.vwR = model.rows.length;
        model.rows.push(c);
      }
      var through = para && node === para.node ? c : via;
      if (c.type === "text" && para) {
        c.vwT = para.node.vwP + "." + para.texts++;
        model.texts[c.vwT] = { node: c, holder: node, via: through, para: para };
      }
      walk(c, c.type === "table" ? null : para, through);
    });
  }
  if (body) walk(body, null, null);
  return model;
}

function vwPageMark() {
  return { type: "break", break: "lastRenderedPageBreak" };
}

/*
 * Writes a page record where each measured page turns, as Word writes one: before a paragraph,
 * in a table row, which splitPieces then turns before, or in a run where a line begins.
 */
function vwAddTurns(model, turns) {
  var cuts = [];
  turns.forEach(function (t) {
    if (t.p !== undefined) {
      var p = model.paras[t.p];
      if (p && p.top) (p.node.children = p.node.children || []).unshift({ type: "run", children: [vwPageMark()] });
    } else if (t.r !== undefined) {
      var cell = model.rows[t.r] && (model.rows[t.r].children || [])[0];
      if (cell) (cell.children = cell.children || []).unshift(vwPageMark());
    } else if (t.t !== undefined) {
      var at = model.texts[t.t];
      if (!at || !at.para.top) return;
      if (at.holder.type === "run" && at.holder === at.via) {
        var cut = cuts.filter(function (c) { return c.at === at; })[0];
        if (!cut) cuts.push(cut = { at: at, offsets: [] });
        cut.offsets.push(t.o);
      } else {
        /* Deeper than a run of the paragraph's own, such as in a link: the page turns before
           the part of the paragraph that holds it, a line or so early */
        var kids = at.para.node.children, i = kids.indexOf(at.via);
        if (i >= 0) kids.splice(i, 0, { type: "run", children: [vwPageMark()] });
      }
    }
  });
  cuts.forEach(function (cut) {
    var text = cut.at.node, value = text.text || "", parts = [], from = 0;
    cut.offsets.sort(function (a, b) { return a - b; }).forEach(function (o) {
      if (o <= from && parts.length) return;
      if (o > from) parts.push(Object.assign({}, text, { text: value.slice(from, o) }));
      parts.push(vwPageMark());
      from = o;
    });
    if (from < value.length) parts.push(Object.assign({}, text, { text: value.slice(from) }));
    var run = cut.at.holder.children, i = run.indexOf(text);
    if (i >= 0) run.splice.apply(run, [i, 1].concat(parts));
  });
}

/*
 * A second drawing costs about as much again as the first: with the CPU slowed four times, as a
 * stand-in for a phone, a 261-page file took 1.7 s to open where one long sheet took 1.1 s. Past
 * this many turns a file keeps its long sheets, as before measuring, rather than wait longer.
 */
var VW_MOST_TURNS = 500;

/* Resolves true once vwPages.turns says where the pages turn, for the document to be drawn again */
function vwMeasurePages() {
  /* A layout first, so a font the document embeds is loading by the time fonts.ready is read */
  document.body.getBoundingClientRect();
  return document.fonts.ready.then(function () {
    var turns = [], sheets = vwSheets();
    for (var i = 0; i < sheets.length && turns.length <= VW_MOST_TURNS; i++) vwSheetTurns(sheets[i], turns);
    vwPages.turns = turns.length && turns.length <= VW_MOST_TURNS ? turns : null;
    return !!vwPages.turns;
  });
}

/* A rounding's worth: a line that reaches past its paper by less still fits */
var VW_SLACK = 0.5;

/* How much of the body's room a header or footer takes: none while it fits in its margin */
function vwOverMargin(el) {
  if (!el) return 0;
  var style = getComputedStyle(el);
  return Math.max(0, el.getBoundingClientRect().height +
    (parseFloat(style.marginTop) || 0) + (parseFloat(style.marginBottom) || 0));
}

/* Where a block starts, its space before included, which comes with it to a new page */
function vwBlockTop(el) {
  return el.getBoundingClientRect().top - (parseFloat(getComputedStyle(el).marginTop) || 0);
}

/*
 * The turns one sheet needs: down its blocks, a page turns at the first that runs past the
 * paper, before it or, for a paragraph, at a line of it and, for a table, at a row. Read
 * before fitPageWidth zooms the sheets.
 */
function vwSheetTurns(sheet, turns) {
  var articles = sheet.querySelectorAll(":scope > article");
  if (!articles.length) return;
  for (var a = 0; a < articles.length; a++) {
    /* Columns fill side by side, so how far down a page a line sits says nothing in them */
    if ((parseInt(getComputedStyle(articles[a]).columnCount, 10) || 1) > 1) return;
  }
  var style = getComputedStyle(sheet);
  var room = (parseFloat(style.minHeight) || 0) - (parseFloat(style.paddingTop) || 0) -
    (parseFloat(style.paddingBottom) || 0) - vwOverMargin(sheet.querySelector(":scope > header")) -
    vwOverMargin(sheet.querySelector(":scope > footer"));
  if (!(room > 0)) return;

  var blocks = [];
  for (a = 0; a < articles.length; a++) blocks.push.apply(blocks, articles[a].children);
  var top = articles[0].getBoundingClientRect().top;
  var page = { top: top, end: top + room };
  for (var b = 0; b < blocks.length && turns.length <= VW_MOST_TURNS; b++) {
    for (var guard = 0; guard < 1000; guard++) {
      if (blocks[b].getBoundingClientRect().bottom <= page.end + VW_SLACK) break;
      var turn = blocks[b].tagName === "TABLE" ? vwTableTurn(blocks, b, page)
        : blocks[b].hasAttribute("data-vw-p") ? vwParagraphTurn(blocks, b, page) : null;
      /* A turn that would not move the page on, as for one row taller than the paper */
      if (!turn || turn.y <= page.top + VW_SLACK) break;
      turns.push(turn.at);
      page = { top: turn.y, end: turn.y + room };
    }
  }
}

/*
 * A turn before block [b], or before the paragraphs kept with it, as Word keeps a heading on
 * the page its text starts. Word gives up on keeping once that would leave the page empty.
 */
function vwTurnBefore(blocks, b, page) {
  var from = b;
  while (from > 0) {
    var prev = blocks[from - 1];
    if ((prev.getAttribute("data-vw-keep") || "").indexOf("n") < 0) break;
    if (vwBlockTop(prev) <= page.top + VW_SLACK) { from = b; break; }
    from--;
  }
  var el = blocks[from];
  if (vwBlockTop(el) <= page.top + VW_SLACK) return null;
  var row = el.tagName === "TABLE" ? vwRows(el)[0] : null;
  if (el.hasAttribute("data-vw-p")) return { at: { p: Number(el.getAttribute("data-vw-p")) }, y: vwBlockTop(el) };
  if (row && row.hasAttribute("data-vw-r")) return { at: { r: Number(row.getAttribute("data-vw-r")) }, y: vwBlockTop(el) };
  return null;
}

function vwRows(table) {
  return table.querySelectorAll(":scope > tr, :scope > tbody > tr, :scope > thead > tr");
}

/*
 * A table turns before its first row that runs past the paper, and its header rows are drawn
 * again above that row on the new page. If not even its first row of the body fits, the
 * whole table goes, rather than leave its header rows alone at the foot of the page.
 */
function vwTableTurn(blocks, b, page) {
  var rows = vwRows(blocks[b]), heads = 0, headHeight = 0;
  while (heads < rows.length && rows[heads].hasAttribute("data-vw-head")) {
    headHeight += rows[heads].getBoundingClientRect().height;
    heads++;
  }
  for (var r = 0; r < rows.length; r++) {
    var box = rows[r].getBoundingClientRect();
    if (box.top < page.top - VW_SLACK || box.bottom <= page.end + VW_SLACK) continue;
    if (r <= heads || box.top <= page.top + VW_SLACK) {
      return vwBlockTop(blocks[b]) > page.top + VW_SLACK ? vwTurnBefore(blocks, b, page) : null;
    }
    if (!rows[r].hasAttribute("data-vw-r")) return null;
    return { at: { r: Number(rows[r].getAttribute("data-vw-r")) }, y: box.top - (heads ? headHeight : 0) };
  }
  return null;
}

/*
 * A paragraph turns at the first of its lines that runs past the paper, keeping two lines at
 * the foot of the page and two at the head of the next, as Word's widow and orphan control
 * does, or before it when fewer would stay or it is to be kept together.
 */
function vwParagraphTurn(blocks, b, page) {
  var el = blocks[b], lines = vwLineBoxes(el);
  var first = 0;
  while (first < lines.length && lines[first].top < page.top - VW_SLACK) first++;
  var cut = first;
  while (cut < lines.length && lines[cut].bottom <= page.end + VW_SLACK) cut++;
  if (cut >= lines.length) return null;
  var atTop = (first ? lines[first].top : vwBlockTop(el)) <= page.top + VW_SLACK;
  if ((el.getAttribute("data-vw-keep") || "").indexOf("l") >= 0) {
    cut = first;
  } else {
    if (lines.length - cut === 1 && cut - first >= 2) cut--;
    if (cut - first < 2) cut = first;
  }
  if (cut === first) {
    if (!first && !atTop) return vwTurnBefore(blocks, b, page);
    /* Already at the head of the page, so as many lines as fit stay, at least one */
    cut = first + 1;
    while (cut < lines.length && lines[cut].bottom <= page.end + VW_SLACK) cut++;
  }
  for (var k = cut; k < lines.length; k++) {
    if (!lines[k].texts.length) continue;
    var piece = lines[k].texts[0];
    return { at: { t: piece.node.vwT, o: vwLineStart(piece) }, y: lines[k].top };
  }
  return null;
}

/*
 * A paragraph's lines in reading order, each with the pieces of named text drawn on it. A
 * piece starts a new line once it sits more than half its height below the line's top, which
 * holds where tight spacing makes neighbouring lines' text overlap.
 */
function vwLineBoxes(p) {
  var lines = [], line = null, range = document.createRange();
  function add(r, piece) {
    if (!(r.height > 0)) return;
    if (!line || r.top >= line.top + Math.min(line.bottom - line.top, r.height) / 2) {
      line = { top: r.top, bottom: r.bottom, texts: [] };
      lines.push(line);
    } else {
      line.top = Math.min(line.top, r.top);
      line.bottom = Math.max(line.bottom, r.bottom);
    }
    if (piece) line.texts.push(piece);
  }
  var walker = document.createTreeWalker(p, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, null);
  for (var node = walker.nextNode(); node; node = walker.nextNode()) {
    if (node.nodeType === 3) {
      if (!node.nodeValue) continue;
      range.selectNodeContents(node);
      var rects = range.getClientRects();
      for (var i = 0; i < rects.length; i++) {
        add(rects[i], node.vwT !== undefined ? { node: node, rect: rects[i] } : null);
      }
    } else if (/^(img|svg|canvas)$/i.test(node.tagName)) {
      add(node.getBoundingClientRect(), null);
    }
  }
  return lines;
}

/* Where in its text a piece of a line begins: the first character drawn below the middle of the line before */
function vwLineStart(piece) {
  var node = piece.node, text = node.nodeValue, range = document.createRange();
  var mid = piece.rect.top + piece.rect.height / 2;
  function onOrAfter(o) {
    for (var k = o; k < text.length; k++) {
      range.setStart(node, k);
      range.setEnd(node, k + 1);
      var r = range.getBoundingClientRect();
      if (r.height > 0) return r.bottom > mid;
    }
    return true;
  }
  var lo = 0, hi = text.length;
  while (lo < hi) {
    var m = (lo + hi) >> 1;
    if (onOrAfter(m)) hi = m; else lo = m + 1;
  }
  /* Never between the halves of a character outside the BMP */
  var code = text.charCodeAt(lo);
  return code >= 0xDC00 && code <= 0xDFFF && lo > 0 ? lo - 1 : lo;
}

/*
 * Writes each page's number into the fields marked while it was drawn, counting as Word
 * does: on from the page before, unless a section restarts it, in the section's format
 * unless the field asks for its own.
 */
function vwNumberPages(sheets) {
  if (!(vwPages.split || vwPages.measure) || sheets.length !== vwPages.props.length) return;
  var numbers = [], sectionOf = [], sectionPages = [];
  for (var i = 0; i < sheets.length; i++) {
    var props = vwPages.props[i];
    var first = i === 0 || props !== vwPages.props[i - 1];
    var start = props.pageNumber && props.pageNumber.start;
    numbers.push(first && isFinite(start) && start !== null ? start : (i ? numbers[i - 1] + 1 : 1));
    if (first) sectionPages.push(0);
    sectionOf.push(sectionPages.length - 1);
    sectionPages[sectionPages.length - 1]++;
  }

  for (var p = 0; p < sheets.length; p++) {
    var own = vwPages.props[p].pageNumber;
    var seen = {};
    var marked = sheets[p].querySelectorAll("[data-vw-field]");
    for (var m = 0; m < marked.length; m++) {
      var said = marked[m].getAttribute("data-vw-field").split(" ");
      var text = "";
      if (!seen[said[0]]) {
        seen[said[0]] = true;
        var value = said[1] === "PAGE" ? numbers[p]
          : said[1] === "NUMPAGES" ? sheets.length : sectionPages[sectionOf[p]];
        var format = said[2] !== "-" ? said[2] : said[1] === "PAGE" && own ? own.format : "";
        text = vwFormatPageNumber(value, format);
      }
      writeText(marked[m], text);
    }
  }
}

/* The first text in [el] says [text] and the rest says nothing, so the run keeps its look */
function writeText(el, text) {
  var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  var node, done = false;
  while ((node = walker.nextNode())) {
    node.nodeValue = done ? "" : text;
    done = true;
  }
}

function vwFormatPageNumber(n, format) {
  switch (format) {
    case "lowerRoman": return roman(n).toLowerCase();
    case "upperRoman": return roman(n);
    case "lowerLetter": return letters(n).toLowerCase();
    case "upperLetter": return letters(n);
    case "numberInDash": return "- " + n + " -";
    default: return String(n);
  }
}

function roman(n) {
  if (n < 1 || n > 3999) return String(n);
  var out = "", marks = [[1000, "M"], [900, "CM"], [500, "D"], [400, "CD"], [100, "C"],
    [90, "XC"], [50, "L"], [40, "XL"], [10, "X"], [9, "IX"], [5, "V"], [4, "IV"], [1, "I"]];
  marks.forEach(function (m) {
    while (n >= m[0]) { out += m[1]; n -= m[0]; }
  });
  return out;
}

/* A to Z, then AA to ZZ, as Word counts in letters */
function letters(n) {
  if (n < 1) return String(n);
  var c = String.fromCharCode(65 + (n - 1) % 26);
  return new Array(Math.floor((n - 1) / 26) + 2).join(c);
}

/*
 * The page under the middle of the screen, told to the app for its page counter and Go to
 * page, as pdf.html does. A zoomed page's rect leaves the zoom out below about Chromium
 * 128 (see fitPageWidth in docx.js), so rects are read through vwPages.rectScale.
 */
var vwPageSent = 0;
var vwPageAskedFor = null;
var vwPageFrame = 0;

function vwSheets() {
  return document.querySelectorAll(".docx-wrapper > section.docx");
}

function vwSheetTop(sheet) {
  return sheet.getBoundingClientRect().top / (vwPages.rectScale || 1);
}

function vwPageOnScreen(sheets, vv) {
  if (vv.pageTop <= 1) return 1;
  if (vv.pageTop + vv.height >= document.documentElement.scrollHeight - 1) return sheets.length;
  var middle = vv.pageTop + vv.height / 2;
  var lo = 0, hi = sheets.length - 1, at = 0;
  while (lo <= hi) {
    var mid = (lo + hi) >> 1;
    if (vwSheetTop(sheets[mid]) + window.scrollY <= middle) { at = mid; lo = mid + 1; } else { hi = mid - 1; }
  }
  return at + 1;
}

function vwReportPage() {
  var sheets = vwSheets();
  var vv = window.visualViewport;
  if (!vwPages.settled || sheets.length < 2 || !vv || !vv.height) return;
  /* A page Go to page put on screen is named until the reader moves */
  if (vwPageAskedFor && Math.abs(vv.pageTop - vwPageAskedFor.top) > 1) vwPageAskedFor = null;
  var n = vwPageAskedFor ? vwPageAskedFor.n : vwPageOnScreen(sheets, vv);
  if (n !== vwPageSent && vwFind.post("page " + n + " " + sheets.length)) vwPageSent = n;
}

function vwSchedulePageReport() {
  if (vwPageFrame) return;
  vwPageFrame = requestAnimationFrame(function () { vwPageFrame = 0; vwReportPage(); });
}

/* At the top of the screen, or in the middle for a page too short for the middle to fall on */
function vwGoToPage(n) {
  var sheets = vwSheets();
  if (!(n >= 1 && n <= sheets.length)) return;
  var sheet = sheets[n - 1];
  var vv = window.visualViewport;
  var tall = sheet.getBoundingClientRect().height / (vwPages.rectScale || 1);
  sheet.scrollIntoView({ block: vv && tall <= vv.height / 2 ? "center" : "start" });
  vwPageAskedFor = vv ? { n: n, top: vv.pageTop } : null;
  vwReportPage();
}

function vwFollowPages() {
  if (vwSheets().length < 2) return;
  window.addEventListener("scroll", vwSchedulePageReport, { passive: true });
  if (window.visualViewport) {
    window.visualViewport.addEventListener("scroll", vwSchedulePageReport, { passive: true });
    window.visualViewport.addEventListener("resize", vwSchedulePageReport, { passive: true });
  }
  vwReportPage();
}
