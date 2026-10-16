#!/usr/bin/env python3
"""Watches Gander draw a folder's PDF thumbnails through PdfThumbService, on one device.

Opens a folder granted to Gander debug with its thumbnail cache emptied, and reports the
renderer's process (its name, when it started and when it ended), the rows that got a picture
and any crash or ANR. The thumbnails left in the cache are copied out, so that a run on another
build can be compared with them pixel for pixel. --grant adds the folder through the tree picker
first. ANDROID_SERIAL picks the device.

  run_thumbs.py --folder gander-test --out DIR [--gander app-debug.apk] [--grant]
                [--storage "Nothing Phone (2)"] [--compare DIR]
"""
import argparse
import os
import re
import subprocess
import sys
import threading
import time

from PIL import Image, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run_spike  # noqa: E402  (its tree picker, and ui with a deadline on each command)

ui = run_spike.ui

PKG = "com.arjun.gander.debug"
MAIN = f"{PKG}/com.arjun.gander.MainActivity"
THUMBS = f"{PKG}:com.arjun.gander.PdfThumbService"
IDLE_S = 10  # PdfThumbs.IDLE_MS


def sh(cmd):
    return ui.shell(cmd).strip()


def say(text):
    print(f"[{time.strftime('%H:%M:%S')}] {text}", flush=True)


def isolated():
    """Gander's isolated processes running now, pid to name."""
    found = {}
    for line in sh("ps -A -o PID,USER,NAME").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 3 and parts[2].startswith(PKG + ":") and re.match(r"u\d+_i\d+", parts[1]):
            found[parts[0]] = parts[2]
    return found


class Watch(threading.Thread):
    """Notes each isolated process of Gander's as it comes and goes, four times a second."""

    def __init__(self):
        super().__init__(daemon=True)
        self.t0 = time.time()
        self.events = []
        self.alive = {}
        self.done = threading.Event()

    def run(self):
        while not self.done.is_set():
            now = isolated()
            at = round(time.time() - self.t0, 2)
            self.events += [(at, "up", pid, name) for pid, name in now.items() if pid not in self.alive]
            self.events += [(at, "down", pid, name) for pid, name in self.alive.items() if pid not in now]
            self.alive = now
            time.sleep(0.25)

    def at(self):
        return round(time.time() - self.t0, 2)


def rows():
    """Each row on screen as (title, whether its thumbnail shows), or None for a failed dump."""
    root = ui.dump()
    if root is None:
        return None
    parent = {child: node for node in root.iter() for child in node}
    found = []
    for node in root.iter("node"):
        if node.get("resource-id", "").endswith(":id/title"):
            row = parent.get(parent.get(node))
            shows = row is not None and any(
                n.get("resource-id", "").endswith(":id/thumb") for n in row.iter("node"))
            found.append((node.get("text"), shows))
    return found


def front():
    out = sh("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")
    return out.split()[-2] if out else "?"


def grant(folder, storage, sdk):
    for pkg in ("com.android.documentsui", "com.google.android.documentsui"):
        sh(f"am force-stop {pkg}")
    if not ui.tap_text("Add a folder", exact=True, wait=3.0, tries=2):
        return "no Add a folder"
    why = run_spike.pick_tree(folder, storage, sdk)
    time.sleep(3)
    return why


def pull_cache(out):
    os.makedirs(out, exist_ok=True)
    names = [n for n in sh(f"run-as {PKG} ls cache/thumbs").split() if n.endswith(".png")]
    for name in names:
        data = subprocess.run([ui.ADB, "exec-out", f"run-as {PKG} cat cache/thumbs/{name}"],
                              capture_output=True).stdout
        with open(os.path.join(out, name), "wb") as f:
            f.write(data)
    return names


def compare(mine, theirs):
    """Each thumbnail both runs left, by cache name: identical, or how far apart."""
    lines = []
    for name in sorted(set(os.listdir(mine)) & set(os.listdir(theirs))):
        if not name.endswith(".png") or name == "screen.png":
            continue
        a = Image.open(os.path.join(mine, name)).convert("RGBA")
        b = Image.open(os.path.join(theirs, name)).convert("RGBA")
        if a.size != b.size:
            lines.append(f"{name}: sizes differ, {a.size} against {b.size}")
            continue
        diff = ImageChops.difference(a, b)
        box = diff.getbbox()
        worst = max(hi for _, hi in diff.getextrema())
        lines.append(f"{name} {a.size[0]}x{a.size[1]}: " +
                     ("identical" if box is None else f"differs in {box}, by up to {worst} of 255"))
    only = sorted(set(os.listdir(mine)) ^ set(os.listdir(theirs)))
    if only:
        lines.append(f"in one run only: {only}")
    return lines


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--folder", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--gander")
    p.add_argument("--grant", action="store_true")
    p.add_argument("--storage", default="")
    p.add_argument("--compare")
    a = p.parse_args()
    os.makedirs(a.out, exist_ok=True)
    log = os.path.join(a.out, "logcat.txt")

    sdk = int(sh("getprop ro.build.version.sdk"))
    say(f"device {sh('getprop ro.product.model')} API {sdk} {os.environ.get('ANDROID_SERIAL', '')}")
    if a.gander:
        done = ui.sh("install", "-r", "-t", a.gander)
        say(f"install: {(done.stdout + done.stderr).strip().splitlines()[-1]}")
    say(f"installed: {sh(f'dumpsys package {PKG} | grep -m1 lastUpdateTime')}")
    # Kept awake for the run, and put back as the device had it after
    stay_on = sh("settings get global stay_on_while_plugged_in")
    sh("svc power stayon true")
    try:
        run(a, sdk, log)
    finally:
        sh(f"settings put global stay_on_while_plugged_in {stay_on}")
        say(f"stay_on_while_plugged_in back to {stay_on}")


def run(a, sdk, log):
    sh(f"am force-stop {PKG}")
    sh(f"run-as {PKG} rm -rf cache/thumbs")
    ui.sh("logcat", "-c")
    stream = subprocess.Popen([ui.ADB, "logcat", "-v", "time"], stdout=open(log, "w"),
                              stderr=subprocess.DEVNULL)
    sh(f"am start -W -n {MAIN}")
    time.sleep(2)
    listed = rows() or []
    if not any(title == a.folder for title, _ in listed):
        if not a.grant:
            say(f"{a.folder} is not among Gander's folders: {[t for t, _ in listed]}; rerun with --grant")
            stream.terminate()
            return
        why = grant(a.folder, a.storage, sdk)
        say(f"grant: {why or 'done'}, front is {front()}")
        if why:
            stream.terminate()
            return

    watch = Watch()
    watch.start()
    if not ui.tap_text(a.folder, exact=True, wait=0.5, tries=2):
        say(f"no row for {a.folder}")
        watch.done.set()
        stream.terminate()
        return
    opened = watch.at()
    say(f"opened {a.folder} at {opened} s")

    # Until the rows with a picture stop changing
    last, steady, settled_at = None, 0, None
    while watch.at() < opened + 60 and steady < 2:
        time.sleep(2)
        now = rows()
        if now is None:
            continue
        if now == last:
            steady += 1
        else:
            last, steady, settled_at = now, 0, watch.at()
    say(f"rows settled by {settled_at} s")
    for title, shows in last or []:
        say(f"  {'picture' if shows else 'badge  '}  {title}")

    # The renderer should end IDLE_S after its last picture
    deadline = watch.at() + IDLE_S + 15
    while watch.at() < deadline and any(name == THUMBS for name in watch.alive.values()):
        time.sleep(0.5)
    watch.done.set()
    watch.join()
    for at, what, pid, name in watch.events:
        say(f"  {at:7.2f} s  {what:4}  {pid}  {name}")
    left = [name for name in isolated().values()]
    say(f"isolated processes left: {left}")

    with open(os.path.join(a.out, "screen.png"), "wb") as f:
        f.write(subprocess.run([ui.ADB, "exec-out", "screencap -p"], capture_output=True).stdout)
    names = pull_cache(a.out)
    say(f"{len(names)} thumbnails copied to {a.out}")

    stream.terminate()
    time.sleep(0.5)
    with open(log, errors="replace") as f:
        lines = f.readlines()
    for line in lines:
        if "Start proc" in line and f"{PKG}:" in line:
            say(f"  {line.strip()}")
    bad = [line.strip() for line in lines if "FATAL EXCEPTION" in line or
           ("ANR in" in line and PKG in line) or ("has died" in line and f" {PKG} " in line)]
    say(f"crashes or ANRs: {bad or 'none'}")
    if a.compare:
        say(f"against {a.compare}:")
        for line in compare(a.out, a.compare):
            say(f"  {line}")


if __name__ == "__main__":
    main()
