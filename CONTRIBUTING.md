# Contributing to Gander

Thanks for taking a look. Gander is intentionally small; the best contributions
keep it that way.

## Ground rules

- The zero-permission, zero-network property is non-negotiable. PRs that add
  INTERNET, storage permissions, analytics, or network calls will be declined.
- Prefer small, focused PRs over large rewrites.
- New file formats are welcome when they can render fully offline with a
  reasonably sized open source renderer.

## Building

See the "Build from source" section in the README. `./gradlew assembleDebug`
produces an installable build without any signing setup.

## Testing changes

Two suites and lint, all run by CI on every pull request:

```sh
./gradlew testDebugUnitTest      # Kotlin: routing, ranges, the port protocol
./gradlew lintDebug              # fails on any warning not in app/lint-baseline.xml
pip install -r tests/viewer/requirements.txt
python -m playwright install --with-deps chromium
pytest tests/viewer              # the viewer pages, in a real browser
```

The viewer tests drive the shipped HTML unmodified, against a local server
that mimics the Android side: same paths, same query parameters, same range
handling. So a change to `pdf.html` can be checked in a second rather than by
sideloading a build.

Reference screenshots are Linux-only, because text rasterises differently on
macOS. From a Mac, run them the way CI does:

```sh
scripts/test-viewer-visual.sh                    # compare
scripts/test-viewer-visual.sh --update-goldens   # after a deliberate change
```

Device tests exist too, and run nightly rather than per pull request. They
need an emulator whose WebView is Chromium 125 or newer. The one test of the
card's simple view button needs a WebView below 125 and skips anywhere else;
`device.yml` runs `PdfSimpleDeviceTest` on its own on an API 33 image for it:

```sh
./gradlew connectedDebugAndroidTest
# on an emulator whose WebView is below 125
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.arjun.gander.PdfSimpleDeviceTest
```

Fixtures are generated, not hand-made. If you need a new one, add it to
`tests/fixtures/make_fixtures.py` and re-run it; see `tests/fixtures/README.md`
for the rule about invented content.

## Adding a format

The list of what Gander opens lives in more than one place, and
`FormatRegistryTest` fails until they agree. In order:

1. The extension, and the MIME type if there is one, in `FileKind.kt`.
2. The MIME type in **both** intent filters in `AndroidManifest.xml`.
3. A fixture in `tests/fixtures/make_fixtures.py`.
4. A row in the table at the top of `FileKindTest`.
5. A new renderer page also needs a `tests/viewer/test_<page>.py`, a line in
   `test_theme.py`, and an entry in `ViewerFormatsTest`. A new *kind* needs no
   tile of its own, since the ninth says ETC and stands for the kinds without
   one; it does need naming in the `welcome_formats_spoken` string, which is
   what a screen reader hears in place of the tiles.

The viewer routes by extension first, MIME second, in `FileKind.kt`;
WebView-based renderers live in `app/src/main/assets/viewer/`.

## Reporting bugs

Open an issue with the file type, what you expected, what happened, and if
possible a sample file that reproduces it (strip anything private first).
