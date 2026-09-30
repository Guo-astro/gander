/*
 * Issue #37. Given bytes, SheetJS reads a text file as Latin-1 unless it starts with a UTF-8
 * byte order mark, and a CSV saved as UTF-8 seldom has one, so Флаг came out as Ð¤Ð»Ð°Ð³. A file
 * that is valid UTF-8 goes in already decoded. Anything else goes in as bytes, as it always did:
 * every binary workbook, a UTF-16 file, which SheetJS knows by its mark, and a Latin-1 one.
 *
 * Strict, and both halves of that matter. Decoded leniently, a Latin-1 file reaches SheetJS with
 * its accents turned to U+FFFD, and an .xlsx reaches it as text, which it never finished reading.
 * SheetJS's own codepage: 65001 was no better: it read "Café,Zürich" as "Caf鬚𲩣h".
 */
function readWorkbook(bytes) {
  var text = null;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch (e) {
    // Not UTF-8
  }
  return text === null
    ? XLSX.read(bytes, { type: "array", cellDates: true })
    : XLSX.read(text, { type: "string", cellDates: true });
}

vwFetchDoc("buffer")
  .then(function (buf) {
    var wb = readWorkbook(new Uint8Array(buf));
    if (!wb.SheetNames.length) throw new Error("The workbook has no sheets");
    var tabs = document.getElementById("tabs");
    var sheetDiv = document.getElementById("sheet");
    function show(idx) {
      var ws = wb.Sheets[wb.SheetNames[idx]];
      sheetDiv.innerHTML = XLSX.utils.sheet_to_html(ws, { editable: false });
      vwDisarmLinks(sheetDiv);
      var btns = tabs.querySelectorAll("button");
      for (var i = 0; i < btns.length; i++) btns[i].className = i === idx ? "active" : "";
    }
    wb.SheetNames.forEach(function (sn, idx) {
      var b = document.createElement("button");
      b.textContent = sn;
      b.onclick = function () { show(idx); };
      tabs.appendChild(b);
    });
    if (wb.SheetNames.length > 1) tabs.style.display = "";
    show(0);

    /* Find reaches every sheet, not only the one drawn: see find.js. A sheet not on the page is
       drawn into an element of its own to be read, the same drawing show() makes, so what is
       searched is what will be shown. */
    var current = 0;
    var drawn = show;
    show = function (idx) { current = idx; drawn(idx); };
    var offPage = [];
    window.vwFindParts = {
      count: function () { return wb.SheetNames.length; },
      root: function (i) {
        if (i === current) return sheetDiv;
        if (!offPage[i]) {
          offPage[i] = document.createElement("div");
          offPage[i].innerHTML = XLSX.utils.sheet_to_html(wb.Sheets[wb.SheetNames[i]], { editable: false });
        }
        return offPage[i];
      },
      show: function (i) { if (i !== current) show(i); return sheetDiv; },
      shown: function () { return current; }
    };
    vwStatusDone();
  })
  .catch(function (e) { vwError("Could not open this spreadsheet", String(e)); });
