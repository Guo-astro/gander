#!/usr/bin/env python3
"""Runs the isolated PDF renderer spike on one device and prints what came back.

Every route a descriptor reaches Gander by, each handed on to PdfRenderService: MediaStore
(and its Downloads collection from API 29), another app's provider (the helper app), the file
picker's Downloads, and a folder picked as a tree. ANDROID_SERIAL picks the device.

  run_spike.py --gander app-debug.apk --helper spike-helper.apk --pdf six-pages.pdf \
      [--tree-folder gander-test] [--make-tree forty.pdf encrypted.pdf not-a-pdf.pdf] \
      [--cases mediastore,downloads,helper,all,picker,tree] [--keep]

--make-tree creates Download/<tree folder> and fills it, for an emulator. Without it the
folder must already exist. Pushed files go by exact name afterwards, unless --keep.
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "screenshots"))
import ui  # noqa: E402

_plain_sh = ui.sh


def _sh_with_timeout(*args, **kw):
    """ui.sh with a deadline: a uiautomator dump on a busy emulator can wait forever."""
    kw.setdefault("timeout", 40)
    try:
        return _plain_sh(*args, **kw)
    except subprocess.TimeoutExpired:
        subprocess.run([ui.ADB, "shell", "pkill -f uiautomator"], capture_output=True)
        return subprocess.CompletedProcess(args, 1, "", "timeout")


ui.sh = _sh_with_timeout

# A phone's logcat can rotate within a minute, so the whole run is streamed to a file
LOG = os.path.join(tempfile.mkdtemp(prefix="pdf-spike-", dir=os.environ.get("PDF_SPIKE_LOG_DIR")), "logcat.txt")

PKG = "com.arjun.gander.debug"
SPIKE = f"{PKG}/com.arjun.gander.PdfSpikeActivity"
HELPER = "com.arjun.gander.spikehelper"
NAME = "gander-test-spike.pdf"
DOWNLOAD = "/sdcard/Download"


def sh(cmd):
    return ui.shell(cmd).strip()


def say(text):
    print(f"[{time.strftime('%H:%M:%S')}] {text}", flush=True)


def spike_lines():
    with open(LOG, errors="replace") as f:
        return [line.rstrip("\n") for line in f if "PdfSpike(" in line]


def wait_for(prefix, seen, timeout=60):
    """New PdfSpike lines, once one starting with prefix has arrived."""
    end = time.time() + timeout
    while time.time() < end:
        lines = spike_lines()
        fresh = lines[seen:]
        if any(prefix in line for line in fresh):
            time.sleep(1.5)
            return spike_lines()[seen:]
        time.sleep(1)
    return spike_lines()[seen:] + [f"TIMEOUT waiting for {prefix}"]


def media_id(collection, name):
    # API 28's scanner leaves _display_name empty for a file that isn't media, so the path too
    for where in (f"_display_name='{name}'", f"_data LIKE '%/{name}'"):
        out = sh(f"content query --uri content://media/external/{collection} --projection _id "
                 f"--where \"{where}\"")
        m = re.search(r"_id=(\d+)", out)
        if m:
            return m.group(1)
    return None


def start_spike(extra):
    # -S starts each case from a stopped app, with a renderer process of its own. Without it a
    # second start of the same document only brings the first one's task forward.
    return sh(f"am start -S -W -a com.arjun.gander.PDF_SPIKE {extra} -n {SPIKE}")


def install(apk):
    done = ui.sh("install", "-r", "-t", apk)
    out = (done.stdout + done.stderr).strip()
    say(f"install {os.path.basename(apk)}: {out.splitlines()[-1] if out else '?'}")


def lower_texts():
    return [t.lower() for t in ui.texts()]


def tap_any(*labels, wait=2.0, min_y=None):
    for label in labels:
        if ui.tap_text(label, exact=False, wait=wait, tries=1, min_y=min_y):
            return label
    return None


def show_roots():
    for _ in range(6):
        if ui.by_text("Show roots") is not None:
            ui.tap_text("Show roots", wait=2.0)
            return True
        time.sleep(1.5)
    return False


def pick_file():
    """In the picker: the Downloads root, then the spike's file."""
    if not show_roots():
        return "no Show roots"
    if not tap_any("Downloads", "Download"):
        return "no Downloads root"
    for _ in range(4):
        if ui.tap_text(NAME, exact=True, wait=3.0, tries=1, min_y=200):
            return None
        ui.swipe(540, 1500, 540, 700, ms=250, wait=1.2)
    return "file not listed"


def pick_tree(folder, storage, sdk):
    """In the tree picker: device storage, Download, the folder, then use it and allow."""
    time.sleep(3)
    # From API 30 the tree picker opens on the device's storage. Before it, on Downloads,
    # with the device's storage hidden until the menu shows it.
    if ui.by_text("Download", exact=True, min_y=250, cls="TextView") is None:
        if sdk <= 29 and ui.tap_text("More options", exact=True, wait=1.5, tries=2):
            if not ui.tap_text("Show internal storage", exact=True, wait=2.0, tries=1):
                ui.back(wait=1.0)
        if not show_roots():
            return "no Show roots"
        if not (storage and ui.tap_text(storage, exact=True, wait=2.5, tries=1)) and \
                not tap_any("Internal storage", "sdk_gphone", "Android SDK", wait=2.5):
            return "no storage root"
    for name in ("Download", folder):
        found = False
        for _ in range(4):
            if ui.tap_text(name, exact=True, wait=2.5, tries=1, min_y=250, cls="TextView"):
                found = True
                break
            ui.swipe(540, 1500, 540, 700, ms=250, wait=1.2)
        if not found:
            return f"no {name} folder"
    if not press_button("Use this folder", "Select"):
        return "no use-this-folder button"
    time.sleep(2.5)
    # Android 10 and later ask again in a dialog, whose title also says Allow
    press_button("Allow")
    return None


def press_button(*labels):
    """A button by its whole label in any case. The picker's bottom bar can come back from
    uiautomator with no bounds at all, and is then pressed where it sits."""
    wanted = {label.lower() for label in labels}
    for n in ui.dump().iter("node"):
        # A whole label only, so a dialog's title that mentions it never matches. API 28's
        # SELECT is a TextView, so the class is not checked.
        if (n.get("text") or "").strip().lower() in wanted:
            if n.get("bounds") == "[0,0][0,0]":
                w, h = map(int, re.findall(r"(\d+)x(\d+)", sh("wm size"))[-1])
                ui.tap(w // 2, h - h * 6 // 100, wait=1.0)
            else:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
                ui.tap((x1 + x2) // 2, (y1 + y2) // 2, wait=1.0)
            return True
    return False


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--gander")
    p.add_argument("--helper")
    p.add_argument("--pdf", required=True)
    p.add_argument("--tree-folder", default="gander-test")
    p.add_argument("--make-tree", nargs="*", default=[])
    p.add_argument("--storage", default=None, help="the picker's name for device storage")
    p.add_argument("--cases", default="mediastore,downloads,helper,all,picker,tree")
    p.add_argument("--keep", action="store_true")
    a = p.parse_args()
    cases = a.cases.split(",")

    sdk = int(sh("getprop ro.build.version.sdk"))
    say(f"device {sh('getprop ro.product.model')} API {sdk} {os.environ.get('ANDROID_SERIAL', '')}")
    if a.gander:
        install(a.gander)
    if a.helper:
        install(a.helper)

    pushed = [f"{DOWNLOAD}/{NAME}"]
    ui.sh("push", a.pdf, pushed[0])
    tree_dir = f"{DOWNLOAD}/{a.tree_folder}"
    if a.make_tree:
        sh(f"mkdir -p {tree_dir}")
        for path in a.make_tree:
            target = f"{tree_dir}/gander-test-spike-{os.path.basename(path)}"
            ui.sh("push", path, target)
            pushed.append(target)
    for path in pushed:
        sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{path}")
    time.sleep(3)

    sh(f"am force-stop {PKG}")
    ui.sh("logcat", "-c")
    stream = subprocess.Popen([ui.ADB, "logcat", "-v", "time"], stdout=open(LOG, "w"),
                              stderr=subprocess.DEVNULL)
    time.sleep(1)
    say(f"logcat streaming to {LOG}")
    results = []

    def run(label, prefix, action):
        seen = len(spike_lines())
        problem = action()
        if problem:
            results.append(f"{label}: SKIPPED ({problem})")
            say(f"{label}: {problem}")
            return
        lines = wait_for(prefix, seen)
        results.extend(f"{label}: {line.split(': ', 1)[-1]}" for line in lines)
        say(f"{label}: {len(lines)} lines")

    file_id = media_id("file", NAME)
    if "mediastore" in cases:
        run("mediastore", "RESULT", lambda: None if file_id and start_spike(
            f"-d content://media/external/file/{file_id} -t application/pdf --grant-read-uri-permission")
            else "not in MediaStore")
    if "downloads" in cases and sdk >= 29:
        dl = media_id("downloads", NAME)
        run("downloads-collection", "RESULT", lambda: None if dl and start_spike(
            f"-d content://media/external/downloads/{dl} -t application/pdf --grant-read-uri-permission")
            else "not in the Downloads collection")
    if "helper" in cases:
        sh(f"am force-stop {PKG}")
        run("helper-provider", "RESULT", lambda: None if "Error" not in sh(
            f"am start -W -n {HELPER}/.Go") else "helper did not start")
    if "all" in cases:
        run("all-pages", "RESULT", lambda: None if file_id and start_spike(
            f"-d content://media/external/file/{file_id} -t application/pdf "
            "--grant-read-uri-permission --ez all true") else "not in MediaStore")
    ps = sh("ps -A -Z 2>/dev/null | grep -i gander || ps | grep -i gander")
    if "picker" in cases:
        run("picker-downloads", "RESULT source=picker",
            lambda: (start_spike("--es pick file") and None) or pick_file())
    if "tree" in cases:
        def tree():
            start_spike("--es pick tree")
            return pick_tree(a.tree_folder, a.storage, sdk)
        seen = len(spike_lines())
        problem = tree()
        if problem:
            results.append(f"tree: SKIPPED ({problem})")
            say(f"tree: {problem}; visible: {ui.texts()[:12]}")
        else:
            lines = wait_for("TREE", seen, timeout=40)
            count = next((int(m.group(1)) for line in lines
                          for m in [re.search(r"holds (\d+) PDFs", line)] if m), 0)
            end = time.time() + 30 + 20 * count
            while time.time() < end and sum("source=tree" in line for line in lines) < count:
                time.sleep(2)
                lines = spike_lines()[seen:]
            results.extend(f"tree: {line.split(': ', 1)[-1]}" for line in lines)

    # Leaving the spike unbinds the renderer, whose process should end with it
    ui.back(wait=1.0)
    ui.back(wait=2.0)
    sh(f"am force-stop {PKG}")
    time.sleep(2)
    after = sh("ps -A 2>/dev/null | grep -i gander || ps | grep -i gander")
    time.sleep(1)
    stream.terminate()
    with open(LOG, errors="replace") as f:
        denials = f.read()
    avc = [line for line in denials.splitlines() if "avc:" in line and "isolated_app" in line]
    service_log = [line for line in denials.splitlines()
                   if "PdfRenderService" in line or "PdfSpikeHelper" in line or
                   ("AndroidRuntime" in line and "gander" in line.lower())]

    print("\n=== results")
    print("\n".join(results))
    print("\n=== gander processes while the spike ran\n" + ps)
    print("\n=== gander processes after leaving it\n" + (after or "(none)"))
    print("\n=== isolated_app denials\n" + ("\n".join(avc[:20]) or "(none)"))
    print("\n=== service and helper log\n" + ("\n".join(service_log[:30]) or "(none)"))

    if not a.keep:
        for path in pushed:
            sh(f"rm {path}")
        if a.make_tree:
            sh(f"rmdir {tree_dir}")
        if a.helper:
            ui.sh("uninstall", HELPER)
        say("removed " + ", ".join(os.path.basename(x) for x in pushed) +
            (" and the helper" if a.helper else ""))


if __name__ == "__main__":
    main()
