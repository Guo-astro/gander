# Screenshot tooling

Regenerates the Play Store and README screenshots: the sample documents, the
device captures, and the captioned store images.

**Maintainer tooling.** It is tested on macOS against an emulator and is not
supported as a general-purpose tool. It is here because rebuilding it from
nothing costs the better part of a day, and the store listing wants re-shooting
every time the UI changes or a listing experiment needs a new variant.

## What else is in here

Two subdirectories hold the generators for the 1.14 listing assets, which are a
separate pass over the captures this tooling produces:

- **`store-art/`** builds the seven phone frames, the four tablet frames and the
  feature graphic. Its README carries the capture gotchas worth reading before any
  reshoot, the debug resource overlay among them.
- **`promo-video/`** builds the Play promo video, and holds the YouTube and Play
  Console settings it is uploaded with.

Neither keeps its working data in the repo. The captures and rendered canvases sit
under the gitignored `docs/screenshots/v1.14/`, and the assets that actually ship are
committed under `fastlane/metadata/android/en-US/images/`.

## Why the samples look like real documents

The store screenshots used to show `sample.pdf` and a Region / Q1-Q4 grid. Filler
content reads as a demo, and a shopper deciding whether the app looks finished
cannot picture their own files in it. So the samples are a plausible fictional
world: an ecology consultancy running a wetland restoration, plus an unrelated
flat rental. Dense, ordinary, believable documents.

### Read this before inventing an identifier

Everything in the samples is fictional and must stay that way, but *fictional* is
not the same as *not real*. The first version of the lease gave its invented
letting agent an invented eight-digit company number. It turned out to be a live
Companies House number belonging to a real firm, which was also in
administration, so the store listing would have tied a made-up business to a real
insolvent one. It was caught only because someone checked it against the
register.

An invented identifier that follows a real registry's format will sometimes name
a real entity, because those number spaces are dense. So: **do not invent
registry-shaped identifiers.** Company or VAT numbers, DUNS, ISBNs, bank sort
codes and account numbers, real postcode areas, real phone ranges, real domains.
Prefer free-form references that belong to no registry at all, which is why the
samples use things like `REF: AC-14/2026-08`, `contract WM-2026-114` and
`CX-2611`. If you must use one, check it against the actual registry first.

The invented postcode area "KB" and the invented place names (Kestrel Bay,
Willowmere, Harrowgate) were checked the same way.

## Requirements

- **Python 3** with `python-docx`, `python-pptx`, `openpyxl`, `defusedxml`
- **LibreOffice** (`soffice`) to convert the generated `.docx` to PDF
- **ImageMagick** (`magick`)
- **Chrome, Chromium or Brave** for caption rendering
- **Android SDK** with `adb` and an emulator
- **Network access**, for the Google Fonts the caption page pulls

`adb` is found via `ANDROID_HOME`, then `PATH`, then the default macOS install.

## The emulator

PDF rendering needs **Android System WebView 133 or newer**; below Chromium 125
the app shows a card or the simple view in place of pdf.js. Stock API 28 and API 33
images are too old, and their WebView cannot be updated without signing into
Play. Use a recent `google_apis_playstore` image:

```sh
sdkmanager "system-images;android-36;google_apis_playstore;arm64-v8a"
```

Create the AVD at **1080x1920, 420dpi**, so `screencap` lands on the size Play
wants without rescaling.

## Running it

```sh
# 1. Sample documents -> scripts/screenshots/samples/
python3 scripts/screenshots/make_samples.py
python3 scripts/screenshots/make_lease.py
python3 scripts/screenshots/make_budget.py

# 2. Push the samples to the device, then capture. ui.py is the adb/uiautomator
#    driver; open_file.py drives the SAF picker, which is the fiddly part.
#    Captures land in docs/screenshots/<version>/raw/.
python3 scripts/screenshots/open_file.py Documents "Tenancy Agreement"

# 3. Captions -> docs/screenshots/<version>/*.png plus a contact sheet
scripts/render-screenshot-captions.sh v1.9
```

Override with `GANDER_SHOT_VERSION` (default `v1.9`) and `GANDER_SHOT_STORAGE`
(the picker's label for device storage, default the emulator's product name).

The app must be installed as the **debug** build. Seeding a realistic recents
list with staggered timestamps needs `run-as`, which only works on a debuggable
package. It is the same code and the same look as release.

Normalise the status bar before capturing, or the clock and battery differ
between shots: `ui.demo_mode()` sets 09:30, full battery, four bars of wifi and
no notifications. Call `ui.demo_exit()` afterwards.

## The photo

Shot 2 uses an aerial of Quartier Saint-Sacrement, Quebec City, by Wilfredor,
**CC0 Public Domain Dedication**, no attribution required:

<https://commons.wikimedia.org/wiki/File:Quartier_Saint-Sacrement,_Quebec_city,_Canada.jpg>

It is 7896x5264 and is not committed here, because it is larger than the entire
app. Download it and crop to portrait (the shipped version is 3639x5264) so it
fills a phone screen instead of letterboxing. Any large CC0 image works; the
point of the shot is that the tiled decoder stays sharp on something huge.

The walkthrough video in the recents list is likewise not committed. Any short
`.mp4` will do; only its thumbnail is ever visible.

## The README demo GIF

`docs/demo.gif` is rebuilt by `record_demo.py`, which records the flow named in the
README's alt text (recents, folder browsing, PDF, Word, Excel, Markdown) and
assembles the GIF:

```sh
python3 scripts/screenshots/record_demo.py
```

Device state must be the same as for the stills: samples pushed, a folder
granted, recents populated. Intermediates land in `docs/screenshots/demo-build/`,
which is gitignored.

Three things about this are worth knowing before changing it, because each cost a
wasted recording.

**`screenrecord` only writes a frame when the screen changes.** A two-second hold
on a document produces three near-identical frames, not sixty, and about 70% of
what it does write are exact duplicates. Encoding at a constant frame rate
therefore destroys the pacing: the holds collapse to a flash and the whole thing
races. The dwell time lives in the presentation timestamps, so the assembler
reads them back and gives every frame its own delay.

**"Has the new screen rendered" cannot be answered by "is the content
non-blank".** The screen being navigated away from is itself non-blank, so that
test passes instantly and every subsequent tap lands on the wrong screen - the
first attempt produced a recording in which all four viewers were blank.
Readiness means the content has *changed from the screen that was tapped on* and
then stopped moving, and the reference must be sampled **before** the tap or the
back, never after.

**Played straight, the recording is about 60% one scrolling PDF**, plus three
seconds of loading screens in which the Excel spinner outlasts Excel. So frames
are grouped by their toolbar strip, loading screens dropped, the scroll thinned,
and each document given a deliberate dwell from `DWELL`. That is also what keeps
the file small: the balanced cut is ~630kB against 1.4MB for the same footage
played straight.

Detecting the "Rendering document..." card needs care. Mean brightness does not
separate it - its dark band covers only about a third of the body, so the white
around it lifts the mean to 230 against a page's 239, and its dark-pixel fraction
sits too close to a dense file listing's. What no text page ever has is a
**full-width dark row**; measured, the card scores 44 and every real screen 0 to 3.

## Known rough edges

- **SheetJS drops cell fills**, so the workbook's header shading does not survive
  into the app. Bold does. Nothing to fix here; it is the renderer.
- **openpyxl writes no cached formula results**, and SheetJS reads the cache, so
  formula cells render blank. Every computed cell in `make_budget.py` is a
  literal for that reason.
- **Pinch zoom cannot be automated.** `input` has no multitouch, so the photo
  shot is fit-to-screen and the caption states the zoom rather than showing it.
