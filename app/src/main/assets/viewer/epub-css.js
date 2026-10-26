"use strict";

/*
 * A book's stylesheets, read the way the engine reads them, for epub.js. A tokenizer and
 * rewriter written to the CSS Syntax 3 rules, so that no way of spelling an address in a book's
 * stylesheet gets past the reader: each is answered from the zip or dropped. It knows nothing
 * of the book itself, and is handed the two functions that find a file in it.
 */
var vwBookCss = (function () {

  /*
   * A stylesheet as CSS Syntax 3 has the engine read it: one kind of line end and no NULs.
   * A backslash at the very end is written as the letter it stands for there, so that
   * nothing put after the sheet can become part of its last escape.
   */
  function prepared(css) {
    css = String(css).replace(/\r\n?|\f/g, "\n").replace(/\u0000/g, "\uFFFD");
    var end = css.length;
    while (end > 0 && css.charCodeAt(end - 1) === 92) end--;
    return (css.length - end) % 2 ? css + "\uFFFD" : css;
  }

  function codeAt(css, i) {
    return i < css.length ? css.charCodeAt(i) : -1;
  }

  function isDigit(c) {
    return c >= 48 && c <= 57;
  }

  function isHex(c) {
    return isDigit(c) || (c >= 65 && c <= 70) || (c >= 97 && c <= 102);
  }

  function isNameStart(c) {
    return (c >= 65 && c <= 90) || (c >= 97 && c <= 122) || c === 95 || c >= 128;
  }

  function isName(c) {
    return isNameStart(c) || isDigit(c) || c === 45;
  }

  function isSpace(c) {
    return c === 10 || c === 9 || c === 32;
  }

  function isUnprintable(c) {
    return (c >= 0 && c <= 8) || c === 11 || (c >= 14 && c <= 31) || c === 127;
  }

  /* Whether the backslash at [i] begins an escape, which anything but a line end may follow */
  function escapes(css, i) {
    return codeAt(css, i) === 92 && codeAt(css, i + 1) !== 10;
  }

  /* The letter an escape stands for, its backslash being at [i - 1], and where it ends */
  function escaped(css, i) {
    var c = codeAt(css, i);
    if (c < 0) return { ch: "\uFFFD", to: i };
    if (!isHex(c)) return { ch: css.charAt(i), to: i + 1 };
    var j = i;
    while (j < i + 6 && isHex(codeAt(css, j))) j++;
    var n = parseInt(css.slice(i, j), 16);
    // One space after the number is part of the escape
    if (isSpace(codeAt(css, j))) j++;
    if (n === 0 || (n >= 0xD800 && n <= 0xDFFF) || n > 0x10FFFF) return { ch: "\uFFFD", to: j };
    if (n < 0x10000) return { ch: String.fromCharCode(n), to: j };
    n -= 0x10000;
    return { ch: String.fromCharCode(0xD800 + (n >> 10), 0xDC00 + (n & 0x3FF)), to: j };
  }

  /* Whether a name begins at [i]: an identifier's, a function's or an @rule's */
  function startsName(css, i) {
    var c = codeAt(css, i);
    if (c === 45) {
      var d = codeAt(css, i + 1);
      return isNameStart(d) || d === 45 || escapes(css, i + 1);
    }
    return isNameStart(c) || escapes(css, i);
  }

  function startsNumber(css, i) {
    var c = codeAt(css, i);
    if (c === 43 || c === 45) {
      c = codeAt(css, i + 1);
      return isDigit(c) || (c === 46 && isDigit(codeAt(css, i + 2)));
    }
    return c === 46 ? isDigit(codeAt(css, i + 1)) : isDigit(c);
  }

  /* The name that begins at [i], with its escapes read, and where it ends */
  function nameAt(css, i) {
    var value = "";
    var run = i;
    for (;;) {
      if (isName(codeAt(css, i))) {
        i++;
      } else if (escapes(css, i)) {
        var e = escaped(css, i + 1);
        value += css.slice(run, i) + e.ch;
        i = run = e.to;
      } else {
        return { value: value + css.slice(run, i), to: i };
      }
    }
  }

  /* Where the number that begins at [i] ends, with its unit or its percent sign */
  function numberEnd(css, i) {
    if (codeAt(css, i) === 43 || codeAt(css, i) === 45) i++;
    while (isDigit(codeAt(css, i))) i++;
    if (codeAt(css, i) === 46 && isDigit(codeAt(css, i + 1))) {
      i += 2;
      while (isDigit(codeAt(css, i))) i++;
    }
    var c = codeAt(css, i);
    var d = codeAt(css, i + 1);
    if ((c === 69 || c === 101) && (isDigit(d) || ((d === 43 || d === 45) && isDigit(codeAt(css, i + 2))))) {
      i += isDigit(d) ? 2 : 3;
      while (isDigit(codeAt(css, i))) i++;
    }
    if (startsName(css, i)) return nameAt(css, i).to;
    return codeAt(css, i) === 37 ? i + 1 : i;
  }

  /*
   * The quoted text whose opening quote is at [i - 1]. A line end ends it too, as a bad
   * string, before the line end; the end of the sheet closes it.
   */
  function stringAt(css, i, quote) {
    var value = "";
    var run = i;
    for (;;) {
      var c = codeAt(css, i);
      if (c === quote || c < 0) {
        return { type: "string", value: value + css.slice(run, i), to: c < 0 ? i : i + 1, closed: c >= 0 };
      }
      if (c === 10) return { type: "bad-string", to: i };
      if (c !== 92) {
        i++;
        continue;
      }
      value += css.slice(run, i);
      var d = codeAt(css, i + 1);
      if (d < 0 || d === 10) {
        // Nothing at the very end, and a line carried on to the next
        i = run = d < 0 ? i + 1 : i + 2;
      } else {
        var e = escaped(css, i + 1);
        value += e.ch;
        i = run = e.to;
      }
    }
  }

  /* An address written bare after url(, from [i] */
  function urlAt(css, i) {
    var value = "";
    var run = i;
    for (;;) {
      var c = codeAt(css, i);
      if (c === 41 || c < 0) return { type: "url", value: value + css.slice(run, i), to: c < 0 ? i : i + 1 };
      if (isSpace(c)) {
        var end = i;
        while (isSpace(codeAt(css, i))) i++;
        c = codeAt(css, i);
        if (c === 41 || c < 0) return { type: "url", value: value + css.slice(run, end), to: c < 0 ? i : i + 1 };
        return badUrlAt(css, i);
      }
      if (c === 34 || c === 39 || c === 40 || isUnprintable(c) || (c === 92 && !escapes(css, i))) {
        return badUrlAt(css, i);
      }
      if (c === 92) {
        var e = escaped(css, i + 1);
        value += css.slice(run, i) + e.ch;
        i = run = e.to;
      } else {
        i++;
      }
    }
  }

  /* The rest of an address the engine gives up on, which runs to its bracket, escapes and all */
  function badUrlAt(css, i) {
    for (;;) {
      var c = codeAt(css, i);
      if (c === 41 || c < 0) return { type: "bad-url", to: c < 0 ? i : i + 1 };
      i = escapes(css, i) ? escaped(css, i + 1).to : i + 1;
    }
  }

  /* An identifier, a function, or url( and the bare address after it, from [i] */
  function nameLike(css, i) {
    var name = nameAt(css, i);
    if (codeAt(css, name.to) !== 40) return { type: "ident", value: name.value, to: name.to };
    if (/^url$/i.test(name.value)) {
      var k = name.to + 1;
      while (isSpace(codeAt(css, k))) k++;
      // A quoted address is a function and its text, as the engine reads it
      if (codeAt(css, k) !== 34 && codeAt(css, k) !== 39) return urlAt(css, k);
    }
    return { type: "function", value: name.value, to: name.to + 1 };
  }

  /*
   * A stylesheet in the pieces the engine reads it in, CSS Syntax 3's tokens: each is
   * { type, from, to }, and a value where it has one. A character that is a token of its
   * own is its own type.
   *
   * Read any other way, a sheet can be spelled so that the engine finds an address where
   * the reading found none: u\72l(, a quote a line end closes, a comment inside an address.
   * So nothing below goes by patterns, and whatever the engine will take for an address is
   * one here too.
   */
  function tokens(css) {
    var out = [];
    var i = 0;
    while (i < css.length) {
      var c = css.charCodeAt(i);
      var t;
      if (c === 47 && codeAt(css, i + 1) === 42) {
        var end = css.indexOf("*/", i + 2);
        t = { type: "comment", to: end < 0 ? css.length : end + 2 };
      } else if (isSpace(c)) {
        var j = i;
        while (isSpace(codeAt(css, j))) j++;
        t = { type: "space", to: j };
      } else if (c === 34 || c === 39) {
        t = stringAt(css, i + 1, c);
      } else if (c === 35 && (isName(codeAt(css, i + 1)) || escapes(css, i + 1))) {
        t = { type: "hash", to: nameAt(css, i + 1).to };
      } else if (c === 64 && startsName(css, i + 1)) {
        var at = nameAt(css, i + 1);
        t = { type: "at", value: at.value, to: at.to };
      } else if (startsNumber(css, i)) {
        t = { type: "number", to: numberEnd(css, i) };
      } else if (c === 45 && codeAt(css, i + 1) === 45 && codeAt(css, i + 2) === 62) {
        t = { type: "cdc", to: i + 3 };
      } else if (c === 60 && css.substr(i + 1, 3) === "!--") {
        t = { type: "cdo", to: i + 4 };
      } else if (startsName(css, i)) {
        t = nameLike(css, i);
      } else {
        t = { type: css.charAt(i), to: i + 1 };
      }
      t.from = i;
      out.push(t);
      i = t.to;
    }
    return out;
  }

  /* An address as the URL parser takes it, which trims spaces and controls from either end */
  function urlTrim(s) {
    var a = 0;
    var b = s.length;
    while (a < b && s.charCodeAt(a) <= 32) a++;
    while (b > a && s.charCodeAt(b - 1) <= 32) b--;
    return s.slice(a, b);
  }

  /* Text written to go between double quotes in a stylesheet */
  function cssString(s) {
    return s.replace(/[\\"]/g, "\\$&").replace(/\n/g, "\\a ");
  }

  function closerOf(type) {
    return type === "{" ? "}" : type === "[" ? "]" : type === "(" || type === "function" ? ")" : "";
  }

  /* Past where the bracket token [i] opens is closed, or [to] */
  function closedAt(list, i, to) {
    var open = [closerOf(list[i].type)];
    for (var j = i + 1; j < to; j++) {
      var close = closerOf(list[j].type);
      if (close) {
        open.push(close);
      } else if (list[j].type === open[open.length - 1]) {
        open.pop();
        if (!open.length) return j + 1;
      }
    }
    return to;
  }

  /*
   * Past where the rule that begins at token [i] ends: its semicolon or its block. Inside
   * another rule's block, the end of that block ends it too.
   */
  function ruleEnd(list, i, to, nested) {
    for (var j = i + 1; j < to; j++) {
      var type = list[j].type;
      if (type === ";") return j + 1;
      if (type === "}" && nested) return j;
      if (type === "{") return closedAt(list, j, to);
      if (closerOf(type)) j = closedAt(list, j, to) - 1;
    }
    return to;
  }

  /* Whether the rule from token [i] to [end] has a block of its own */
  function blocked(list, i, end) {
    for (var j = i + 1; j < end; j++) {
      if (list[j].type === "{") return true;
      if (closerOf(list[j].type)) j = closedAt(list, j, end) - 1;
    }
    return false;
  }

  /* The next token after [i] that is not a space or a comment */
  function after(list, i, to) {
    for (i++; i < to && (list[i].type === "space" || list[i].type === "comment"); i++);
    return i;
  }

  /* The address the @import at token [i] names, or null */
  function importing(list, i, to) {
    var j = after(list, i, to);
    var t = j < to ? list[j] : null;
    if (t && (t.type === "string" || t.type === "url")) return t.value;
    if (t && t.type === "function" && /^url$/i.test(t.value)) {
      var k = after(list, j, to);
      if (k < to && list[k].type === "string") return list[k].value;
    }
    return null;
  }

  /* Rules that hold rules, inside which a font rule may begin as at the top of a sheet */
  var HOLDERS = /^(media|supports|document|-moz-document|layer|container|scope|starting-style)$/i;

  /* What a picture offered at several sizes may be written with besides an address: a picture, and a size */
  var PICTURED = /^((-webkit-|-moz-)?(image-set|image|cross-fade|element|gradient|(repeating-)?(linear|radial|conic)-gradient)|type|calc|min|max|clamp)$/;

  /*
   * The properties EPUB once had names of its own for, which the engine still answers to
   * some of, -epub-writing-mode among them. These are the ones it does not, so a book that
   * hyphenates by -epub-hyphens would otherwise go unhyphenated.
   */
  var EPUB_ONLY = /^-epub-(hyphens|line-break|text-align-last|text-underline-position)$/i;

  /*
   * Writes tokens [from, to) of [css] into [into], as text and, for each address to be
   * answered from the zip, { path }. In a sheet, [how.sheet], an @import at the top is
   * { imp: its name in the zip } and a font rule { face: its parts }, and :root, :host and
   * the EPUB properties are written for the shadow tree.
   *
   * Everything else is written as it was, but for what could take in whatever is written
   * after it, which is written closed: an address as url("..."), one the engine would not
   * take as url() with a control letter in it, which it gives up on, a comment as an empty
   * one, and a quote or a bracket the text leaves open by closing it.
   *
   * Answers how many addresses there were, and how many of them were not a place in the
   * chapter itself: { all, far }. [how.resolve] and [how.entry] are the reader's, which find a
   * file in the book.
   */
  function rewrite(css, list, from, to, into, how) {
    var text = "";
    var found = { all: 0, far: 0 };
    var open = [];
    var top = { rules: !!how.sheet, start: true, at: false, holds: false };
    var levels = [top];

    function flush() {
      if (text) into.push(text);
      text = "";
    }

    function address(value) {
      var trimmed = urlTrim(value);
      var here = trimmed.charAt(0) === "#";
      found.all++;
      if (!here) found.far++;
      if (here || /^(data|blob):/i.test(trimmed) || !how.answer) {
        text += 'url("' + cssString(value) + '")';
        return;
      }
      var at = how.resolve(how.base, trimmed);
      if (!at) {
        text += 'url("data:,")';
        return;
      }
      flush();
      into.push({ path: at.path });
    }

    function unread() {
      found.all++;
      found.far++;
      text += "url(\u0001)";
    }

    for (var i = from; i < to; i++) {
      var t = list[i];
      var type = t.type;
      var inner = open.length ? open[open.length - 1] : null;
      var level = levels[levels.length - 1];
      // Not inside a bracket or a function, where a rule may begin or end
      var bare = !inner || !!inner.level;

      if (type === "comment") {
        text += "/**/";
        continue;
      }
      if (type === "space") {
        text += css.slice(t.from, t.to);
        continue;
      }

      if (bare && level.rules && level.start && type !== "cdo" && type !== "cdc") {
        level.start = false;
        level.at = type === "at";
        level.holds = false;
        var name = type === "at" ? t.value.toLowerCase() : "";
        var first = levels.length === 1;
        var own = (first && (name === "import" || name === "namespace")) || name === "font-face";
        var end = own ? ruleEnd(list, i, to, !first) : i;
        // A namespace with a block is no namespace, and is read as any other rule is
        if (name === "namespace" && list[end - 1].type === "}") own = false;
        if (own) {
          flush();
          if (name === "import") {
            var href = importing(list, i, end);
            var target = href === null ? null : how.resolve(how.base, urlTrim(href));
            var file = target && how.entry(target.path);
            if (file) into.push({ imp: file.name });
          } else if (name === "namespace" || blocked(list, i, end)) {
            // A namespace's address is a name and nothing to fetch, so it is not answered
            var part = [];
            rewrite(css, list, i, end, part,
              { base: how.base, answer: name === "font-face", resolve: how.resolve, entry: how.entry });
            // Ended, so that the rules an import puts after one a sheet ends in stay out of it
            if (name === "namespace" && list[end - 1].type !== ";") part.push(";");
            into.push(name === "font-face" ? { face: part } : part.join(""));
          }
          // A font rule with no block of its own is none, and is left out as the engine leaves
          // it out: written as it was, it would take the next font rule for its block
          i = end - 1;
          level.start = true;
          continue;
        }
        level.holds = HOLDERS.test(name);
      }

      if (type === "{") {
        text += "{";
        var block = bare ? { rules: level.rules && level.at && level.holds, start: true, at: false, holds: false } : null;
        open.push({ close: "}", level: block });
        if (block) levels.push(block);
        continue;
      }
      if (type === "}" || type === ")" || type === "]") {
        text += type;
        if (inner && inner.close === type) {
          open.pop();
          if (inner.level) {
            levels.pop();
            levels[levels.length - 1].start = true;
          }
        }
        continue;
      }
      if (type === ";") {
        text += ";";
        if (bare && level.at) level.start = true;
        continue;
      }
      if (type === "url") {
        address(t.value);
        continue;
      }
      if (type === "bad-url") {
        unread();
        continue;
      }
      if (type === "string") {
        // A picture offered at several sizes may be named by its address in quotes alone
        if (inner && inner.sets) address(t.value);
        else text += t.closed ? css.slice(t.from, t.to) : '"' + cssString(t.value) + '"';
        continue;
      }
      if (type === "function") {
        var fn = t.value.toLowerCase();
        if (fn === "url" || fn === "src") {
          var arg = after(list, i, to);
          var shut = closedAt(list, i, to);
          if (arg < shut && list[arg].type === "string") address(list[arg].value);
          else unread();
          i = shut - 1;
          continue;
        }
        // A size of a picture named by anything but a picture or a size, as var(), if() and a
        // book's own functions are, is only known when the page is drawn
        if (inner && inner.sets && !PICTURED.test(fn)) {
          unread();
          i = closedAt(list, i, to) - 1;
          continue;
        }
        text += css.slice(t.from, t.to);
        open.push({ close: ")", sets: fn === "image-set" || fn === "-webkit-image-set" || fn === "image" });
        continue;
      }
      if (type === "(" || type === "[") {
        text += type;
        open.push({ close: closerOf(type) });
        continue;
      }
      if (how.sheet && type === ":" && i + 1 < to) {
        var next = list[i + 1];
        var called = next.type === "ident" || next.type === "function" ? next.value.toLowerCase() : "";
        // :root is the page's root and not the book's, so it is the html element the chapter is given
        if (next.type === "ident" && called === "root") {
          text += "html";
          i++;
          continue;
        }
        // The element the shadow tree hangs on is Gander's, and a book has no business with it
        if (called === "host" || (next.type === "function" && called === "host-context")) {
          text += ":not(*)";
          i = next.type === "function" ? closedAt(list, i + 1, to) - 1 : i + 1;
          continue;
        }
      }
      if (how.sheet && type === "ident" && EPUB_ONLY.test(t.value)) {
        var k = after(list, i, to);
        if (k < to && list[k].type === ":") {
          text += t.value.slice(6);
          continue;
        }
      }
      text += css.slice(t.from, t.to);
    }

    // A rule the text leaves begun, given an empty block so it ends where the text does
    var unfinished = how.sheet && !top.start && !(open.length && open[0].level);
    for (var o = open.length - 1; o >= 0; o--) text += open[o].close;
    if (unfinished) text += "{}";
    flush();
    return found;
  }

  /* The style of one element, read as any stylesheet is: { parts, found }, as rewrite() has them */
  function inStyle(style, base, answer, book) {
    var css = prepared(style);
    var list = tokens(css);
    var parts = [];
    var found = rewrite(css, list, 0, list.length, parts,
      { base: base, answer: answer, resolve: book.resolve, entry: book.entry });
    return { parts: parts, found: found };
  }

  /* A stylesheet's text in parts, as rewrite() writes them */
  function parsed(source, base, book) {
    var css = prepared(source);
    var list = tokens(css);
    var parts = [];
    rewrite(css, list, 0, list.length, parts,
      { base: base, sheet: true, answer: true, resolve: book.resolve, entry: book.entry });
    return parts;
  }

  return { inStyle: inStyle, parsed: parsed };
})();
