# Security

Please report security problems privately, not in a public issue or
discussion.

## How to report

Use [Report a vulnerability](https://github.com/mokshablr/gander/security/advisories/new)
on GitHub. Only you and the maintainer can see the report. If you don't have
a GitHub account, email gander@arjun.maniyani.com.

Include the Gander version from About Gander in the menu, and your Android
and Android System WebView versions. If a particular file sets it off,
attach it.

You'll get a reply within a week. Please keep the details private until a
fixed release is out. When it ships, the release notes credit you, unless
you'd rather not be named.

## What counts

Gander opens files it can't trust. A file you open should not be able to:

- run script that isn't Gander's own
- make Gander load anything besides its own pages and the file itself
- read or write anything beyond itself

Another app on the phone should not be able to use Gander to open a file it
has no access to, or to read Gander's own data, such as your recent files.

Nothing should leave the phone unless you share it.

Flaws in a bundled library such as pdf.js count when a file can reach them.
Crashes and wrong rendering are normal issues. Bugs in Android System
WebView itself belong with Chromium.

## Supported versions

Fixes go into the next release. Older versions aren't patched.
