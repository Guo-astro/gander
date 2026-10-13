document.getElementById("fname").textContent = vwName;
var hint = document.getElementById("hint");
if (vwExt === "ppt") {
  hint.textContent =
    "This is a legacy Office format (.ppt). If you re-save it as .pptx, Gander can display it.";
} else {
  // Nothing useful to add; leave the card to the action below
  hint.style.display = "none";
}

// astext=1 arrives when the viewer's menu offers to view unknown files as text. Issue #51.
if (vwParams.get("astext")) document.getElementById("skip").hidden = false;

document.getElementById("asText").addEventListener("click", function () {
  location.replace(
    "text.html?name=" + encodeURIComponent(vwName) + "&ext=" + encodeURIComponent(vwExt)
  );
});
