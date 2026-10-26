/* Nothing is read below marked's floor. The card is already up, and the library in the
   head either did not parse, below Chromium 84, or would fail on its first document,
   below 93: issue #31, where this said "marked is not defined" instead. See
   vwWebViewTooOld in app.js. */
if (!vwWebViewTooOld("Markdown files")) {
  vwFetchDoc("text")
    .then(function (txt) {
      var html = marked.parse(txt, { gfm: true, breaks: false });
      var content = document.getElementById("content");
      content.innerHTML = DOMPurify.sanitize(html);
      // Code reads left to right in a document of any language, unless the code
      // itself says otherwise.
      content.querySelectorAll("pre, code").forEach(function (el) {
        if (!el.hasAttribute("dir") && !el.parentElement.closest("pre[dir], code[dir]")) el.dir = "ltr";
      });
      content.dir = vwMostlyRightToLeft(proseText(content)) ? "rtl" : "ltr";
      vwStatusDone();
    })
    .catch(function (e) { vwError("Could not render this Markdown file", String(e)); });
}

/* The text that decides the document's direction, up to VW_DIRECTION_SAMPLE characters of it.
   Code is left out, since it is mostly Latin whatever language surrounds it, and so is anything
   the author gave its own dir, as that part keeps its direction anyway. */
function proseText(root) {
  var walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), node, parts = [], length = 0;
  while (length < VW_DIRECTION_SAMPLE && (node = walk.nextNode())) {
    if (!node.parentElement.closest("[dir]")) {
      parts.push(node.data);
      length += node.data.length;
    }
  }
  return parts.join("").slice(0, VW_DIRECTION_SAMPLE);
}
