#!/usr/bin/env python3
"""Runs PdfRenderClient against a renderer that fails, on one device, and prints what came back.

Each case opens one PDF through the spike with a fault for FaultyPdfRenderService: a renderer
that crashes or hangs while opening or drawing, or draws slowly. Two cases need su, so they run
only where the shell has it, as on a google_apis emulator: "frozen" stops Gander and the
renderer partway through a slow page, as Android's freezer does in the background, and
"reclaimed" kills an idle renderer as Android does for memory. Only those kills go through su:
started by root, the spike would get no read grant for its document. ANDROID_SERIAL picks the
device.

  run_faults.py --gander app-debug.apk --pdf six-pages.pdf [--cases plain,crash-open,...] [--keep]
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

LOG = os.path.join(tempfile.mkdtemp(prefix="pdf-faults-", dir=os.environ.get("PDF_SPIKE_LOG_DIR")), "logcat.txt")

PKG = "com.arjun.gander.debug"
SPIKE = f"{PKG}/com.arjun.gander.PdfSpikeActivity"
NAME = "gander-test-faults.pdf"
DOWNLOAD = "/sdcard/Download"
CASES = "plain,crash-open,crash-draw,hang-open,hang-draw,slow-draw,frozen,reclaimed"


def sh(cmd):
    return ui.shell(cmd).strip()


def say(text):
    print(f"[{time.strftime('%H:%M:%S')}] {text}", flush=True)


def spike_lines():
    with open(LOG, errors="replace") as f:
        return [line.rstrip("\n") for line in f if "PdfSpike(" in line]


def wait_for(prefix, seen, timeout):
    """The PdfSpike lines after the first [seen], once one holding [prefix] has arrived."""
    end = time.time() + timeout
    while time.time() < end:
        fresh = spike_lines()[seen:]
        if any(prefix in line for line in fresh):
            return fresh
        time.sleep(0.5)
    return spike_lines()[seen:]


def stamp(line):
    """The time logcat gave the line, in seconds."""
    m = re.match(r"(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)", line)
    if not m:
        return None
    _, _, h, mi, s, ms = map(int, m.groups())
    return h * 3600 + mi * 60 + s + ms / 1000


def first(lines, prefix):
    return next((line for line in lines if prefix in line), None)


def processes():
    """Gander's own processes and its isolated ones, by pid. An isolated one can be named after
    its service, as com.arjun.gander.debug:com.arjun.gander.PdfRenderService."""
    own, isolated = [], []
    for line in sh("ps -A -o PID,USER,NAME").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 3 and (parts[2] == PKG or parts[2].startswith(PKG + ":")):
            (isolated if re.match(r"u\d+_i\d+", parts[1]) else own).append(parts[0])
    return own, isolated


def media_id(name):
    # API 28's scanner leaves _display_name empty for a file that isn't media, so the path too
    for where in (f"_display_name='{name}'", f"_data LIKE '%/{name}'"):
        out = sh(f"content query --uri content://media/external/file --projection _id --where \"{where}\"")
        m = re.search(r"_id=(\d+)", out)
        if m:
            return m.group(1)
    return None


def start(file_id, fault):
    extra = f"--es fault {fault}" if fault else ""
    # -S, so that each case has a renderer process of its own
    return sh(f"am start -S -W -a com.arjun.gander.PDF_SPIKE -d content://media/external/file/{file_id} "
              f"-t application/pdf --grant-read-uri-permission {extra} -n {SPIKE}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--gander")
    p.add_argument("--pdf", required=True)
    p.add_argument("--cases", default=CASES)
    p.add_argument("--keep", action="store_true")
    a = p.parse_args()
    cases = a.cases.split(",")

    sdk = int(sh("getprop ro.build.version.sdk"))
    say(f"device {sh('getprop ro.product.model')} API {sdk} {os.environ.get('ANDROID_SERIAL', '')}")
    root = sh("su 0 id -u") == "0"
    say(f"su: {root}")
    if a.gander:
        done = ui.sh("install", "-r", "-t", a.gander)
        say(f"install: {(done.stdout + done.stderr).strip().splitlines()[-1]}")
    # Kept awake for the run, and put back as the device had it after
    stay_on = sh("settings get global stay_on_while_plugged_in")
    sh("svc power stayon true")

    target = f"{DOWNLOAD}/{NAME}"
    ui.sh("push", a.pdf, target)
    sh(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://{target}")
    time.sleep(3)
    file_id = media_id(NAME)
    if not file_id:
        say("the PDF is not in MediaStore")
        return

    ui.sh("logcat", "-c")
    stream = subprocess.Popen([ui.ADB, "logcat", "-v", "time"], stdout=open(LOG, "w"),
                              stderr=subprocess.DEVNULL)
    time.sleep(1)
    say(f"logcat streaming to {LOG}")
    results = []

    def report(case, verdict, detail):
        results.append(f"{case}: {'PASS' if verdict else 'FAIL'} {detail}")
        say(results[-1])

    def leftover(wait=5):
        """The renderer processes still alive [wait] seconds on: none, if nothing restarted one."""
        time.sleep(wait)
        return processes()[1]

    def crashes():
        with open(LOG, errors="replace") as f:
            return [line.strip() for line in f if "FATAL EXCEPTION" in line or
                    ("ANR in" in line and PKG in line)]

    for case in cases:
        crashed_before = len(crashes())
        if case in ("frozen", "reclaimed") and not root:
            results.append(f"{case}: SKIPPED (no su)")
            continue
        fault = {"frozen": "slow-draw", "plain": None, "reclaimed": None}.get(case, case)
        seen = len(spike_lines())
        start(file_id, fault)
        if case == "plain":
            lines = wait_for("RESULT", seen, 30)
            result = first(lines, "RESULT") or ""
            # The other cases' "renderers left" means nothing unless a live one is seen here
            bound = processes()[1]
            report(case, "drew=1" in result and not first(lines, "GONE") and len(bound) == 1,
                   f"renderer while bound {bound}; {result.split(': ', 1)[-1]}")
        elif case.startswith("crash-"):
            lines = wait_for("GONE", seen, 30)
            gone = first(lines, "GONE") or ""
            alive = leftover()
            result = first(spike_lines()[seen:], "RESULT") or ""
            report(case, "crashed=true" in gone and not alive,
                   f"{gone.split(': ', 1)[-1]}; renderers left {alive}; {result.split(': ', 1)[-1]}")
        elif case.startswith("hang-"):
            began = wait_for("BIND" if case == "hang-open" else "OPENED", seen, 30)
            began_at = stamp(first(began, "BIND" if case == "hang-open" else "OPENED") or "")
            lines = wait_for("GONE", seen, 60)
            gone = first(lines, "GONE") or ""
            after = (stamp(gone) or 0) - (began_at or 0) if gone and began_at else None
            alive = leftover()
            with open(LOG, errors="replace") as f:
                kills = [line for line in f if "Killing" in line and f"{PKG}:" in line]
            report(case, "crashed=true" in gone and after is not None and 19 <= after <= 30 and not alive,
                   f"GONE {after and round(after, 1)} s after it began; renderers left {alive}; "
                   f"{kills[-1].strip() if kills else 'no kill line'}")
        elif case == "slow-draw":
            lines = wait_for("RESULT", seen, 40)
            result = first(lines, "RESULT") or ""
            report(case, "drew=1" in result and not first(lines, "GONE"), result.split(": ", 1)[-1])
        elif case == "frozen":
            wait_for("OPENED", seen, 30)
            time.sleep(1)
            own, isolated = processes()
            pids = " ".join(own + isolated)
            sh(f"su 0 kill -STOP {pids}")
            say(f"stopped {pids} for 30 s")
            time.sleep(30)
            sh(f"su 0 kill -CONT {pids}")
            lines = wait_for("RESULT", seen, 40)
            time.sleep(3)
            lines = spike_lines()[seen:]
            result = first(lines, "RESULT") or ""
            report(case, "drew=1" in result and not first(lines, "GONE"),
                   f"{result.split(': ', 1)[-1]}; {first(lines, 'GONE') or 'no GONE'}")
        elif case == "reclaimed":
            wait_for("RESULT", seen, 30)
            time.sleep(1)
            isolated = processes()[1]
            sh(f"su 0 kill -9 {' '.join(isolated)}")
            lines = wait_for("GONE", seen, 20)
            gone = first(lines, "GONE") or ""
            alive = leftover()
            report(case, "crashed=false" in gone and not alive,
                   f"killed {isolated}; {gone.split(': ', 1)[-1] or 'no GONE'}; renderers left {alive}")
        new = crashes()[crashed_before:]
        if new:
            results.append(f"{case}: GANDER CRASHED {new[-1]}")
        sh(f"am force-stop {PKG}")
        time.sleep(1)

    stream.terminate()
    print("\n=== results")
    print("\n".join(results))
    print(f"\nlogcat: {LOG}")
    if not a.keep:
        sh(f"rm {target}")
        say(f"removed {NAME}")
    sh(f"settings put global stay_on_while_plugged_in {stay_on}")


if __name__ == "__main__":
    main()
