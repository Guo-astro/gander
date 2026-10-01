#!/usr/bin/env python3
"""Draws the launcher icon's themed layer from the icon itself.

Why this exists
---------------
With themed icons on (Android 13 and later), the launcher draws an app's <monochrome> layer in
one colour taken from the wallpaper, on a background of another, and reads nothing from it but
alpha. The foreground cannot be that layer as it stands: its white cards, dark goose and coloured
badges would all come out the same colour, as one blob.

So this redraws the same picture in one colour. The goose stays solid, with its cheek patch and
eye cut out. The cards become outlines, and each one hides what is behind it with a thin gap. The
front card keeps its badge and two lines. The back two keep only their outline and folded corner,
because at launcher size their part-hidden badges and line ends read as specks. Every shape comes
from ic_launcher_foreground.xml, so a redrawn icon needs only a rerun.

The same picture is the notification icon, which the status bar also reads only for alpha. It is
cropped to the picture, because in the launcher's 108 units the picture is under half the width,
and a 24dp status bar icon drawn from all 108 would be a speck.

Usage
-----
    pip install skia-pathops fonttools
    python3 scripts/make-monochrome-icon.py

Writes app/src/main/res/drawable/ic_launcher_monochrome.xml and ic_notification.xml.
"""
import math
import os
import xml.etree.ElementTree as ET

import pathops
from fontTools.misc.transform import Transform
from fontTools.pens.transformPen import TransformPen
from fontTools.svgLib.path import parse_path

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DRAWABLE = os.path.join(REPO, "app", "src", "main", "res", "drawable")
FOREGROUND = os.path.join(DRAWABLE, "ic_launcher_foreground.xml")
OUT = os.path.join(DRAWABLE, "ic_launcher_monochrome.xml")
NOTIFICATION_OUT = os.path.join(DRAWABLE, "ic_notification.xml")

# A status bar icon is 24dp with the picture inside the middle 22, as Android's own are drawn.
NOTIFICATION_LIVE = 22 / 24

ANDROID = "{http://schemas.android.com/apk/res/android}"

# The picture is drawn at this fraction of the colour icon's size, about the middle. At full size
# it filled three quarters of the launcher's disc, where the system's own themed icons fill a half
# to three fifths, and the goose's head all but touched the rim.
SCALE = 0.85

# In the 108-unit viewport, of which the launcher shows the middle 72, after SCALE.
# The outline sits inside each card, so a card's edge stays where the colour icon has it.
OUTLINE = 2.4
GAP = 1.5


def group_matrix(group):
    """A <group>'s transform, composed the way VectorDrawable composes it."""
    def attr(name, default=0.0):
        return float(group.get(ANDROID + name, default))
    px, py = attr("pivotX"), attr("pivotY")
    return (Transform()
            .translate(attr("translateX") + px, attr("translateY") + py)
            .rotate(math.radians(attr("rotation")))
            .scale(attr("scaleX", 1.0), attr("scaleY", 1.0))
            .translate(-px, -py))


def shapes(element, matrix):
    """Every <path> under [element], in drawing order, placed in viewport coordinates."""
    found = []
    for child in element:
        if child.tag == "group":
            found += shapes(child, matrix.transform(group_matrix(child)))
        elif child.tag == "path":
            path = pathops.Path()
            parse_path(child.get(ANDROID + "pathData"), TransformPen(path.getPen(), matrix))
            found.append(path)
    return found


def union(*paths):
    result = paths[0]
    for path in paths[1:]:
        result = pathops.op(result, path, pathops.PathOp.UNION)
    return result


def minus(a, b):
    return pathops.op(a, b, pathops.PathOp.DIFFERENCE)


def band(path, width):
    """The strip [width] wide centred on [path]'s outline."""
    strip = pathops.Path()
    strip.addPath(path)
    strip.stroke(width, pathops.LineCap.ROUND_CAP, pathops.LineJoin.ROUND_JOIN, 4)
    strip.convertConicsToQuads()
    return strip


def grown(path, by):
    return union(path, band(path, 2 * by))


def rim(path, width):
    """The strip [width] wide just inside [path]'s outline."""
    return pathops.op(path, band(path, 2 * width), pathops.PathOp.INTERSECTION)


def corner_notch(fold):
    """The triangle a folded corner leaves empty: between the fold and the card's square corner."""
    top, end, inner = [points[0] for _, points in fold.segments if points][:3]
    corner = (top[0] + end[0] - inner[0], top[1] + end[1] - inner[1])
    notch = pathops.Path()
    notch.moveTo(*top)
    notch.lineTo(*corner)
    notch.lineTo(*end)
    notch.close()
    return notch


def number(value):
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return "0" if text == "-0" else text


def path_data(path):
    """Absolute M, L, Q, C and Z only, so every parser reads it the same way."""
    out = []
    for verb, points in path.segments:
        pairs = [f"{number(x)},{number(y)}" for x, y in points]
        if verb == "moveTo":
            out.append("M" + pairs[0])
        elif verb == "lineTo":
            out.append("L" + pairs[0])
        elif verb == "qCurveTo":
            # pathops runs quadratics together TrueType style, with the on-curve point between
            # two control points left out. Put each one back.
            for (ax, ay), (bx, by) in zip(points, points[1:-1]):
                out.append(f"Q{number(ax)},{number(ay)} {number((ax + bx) / 2)},{number((ay + by) / 2)}")
            out.append("Q" + " ".join(pairs[-2:]))
        elif verb == "curveTo":
            out.append("C" + " ".join(pairs))
        elif verb == "closePath":
            out.append("Z")
    return "".join(out)


def main():
    root = ET.parse(FOREGROUND).getroot()
    goose_group, *card_groups = [g for g in root if g.tag == "group"]
    place = Transform().translate(54, 54).scale(SCALE).translate(-54, -54)
    head, cheek, eye = shapes(goose_group, place.transform(group_matrix(goose_group)))
    # Back left, back right, front: the order they are drawn in, so later ones cover earlier.
    card_shapes = [shapes(g, place.transform(group_matrix(g))) for g in card_groups]
    if len(card_shapes) != 3 or any(len(found) != 5 for found in card_shapes):
        raise SystemExit("ic_launcher_foreground.xml is no longer a goose and three cards; "
                         "update this script to match it")
    cards = [dict(zip(("paper", "fold", "badge", "line1", "line2"), found)) for found in card_shapes]

    ink = minus(minus(head, cheek), eye)
    for card in cards:
        cover = grown(card["paper"], GAP)
        if card is cards[-1]:
            # In colour the neck, and a corner of the back right card, carry on into the front
            # card's folded corner. In one colour they read as a spur and a speck beside the
            # fold, so the front card hides that corner as though it were square.
            cover = union(cover, grown(corner_notch(card["fold"]), GAP))
        ink = minus(ink, cover)
        ink = union(ink, rim(card["paper"], OUTLINE), card["fold"])
        if card is cards[-1]:
            ink = union(ink, card["badge"], card["line1"], card["line2"])

    with open(OUT, "w") as f:
        f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- The launcher icon in one colour, for themed icons on Android 13 and later. The launcher
     tints it and reads only its alpha. Generated by scripts/make-monochrome-icon.py from
     ic_launcher_foreground.xml; edit that rather than this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FF000000"
        android:pathData="{path_data(ink)}" />
</vector>
''')
    print(f"Wrote {os.path.relpath(OUT, REPO)}")

    # A square around the picture, moved to the origin by the group rather than by rewriting
    # the path, so both files carry the same path data.
    left, top, right, bottom = ink.bounds
    side = max(right - left, bottom - top) / NOTIFICATION_LIVE
    x = (left + right - side) / 2
    y = (top + bottom - side) / 2
    with open(NOTIFICATION_OUT, "w") as f:
        f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- The launcher icon in one colour, cropped to the picture, for the status bar while a track
     plays with the screen off. Generated by scripts/make-monochrome-icon.py from
     ic_launcher_foreground.xml; edit that rather than this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{number(side)}"
    android:viewportHeight="{number(side)}">
    <group
        android:translateX="{number(-x)}"
        android:translateY="{number(-y)}">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="{path_data(ink)}" />
    </group>
</vector>
''')
    print(f"Wrote {os.path.relpath(NOTIFICATION_OUT, REPO)}")


if __name__ == "__main__":
    main()
