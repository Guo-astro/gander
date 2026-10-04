package com.arjun.gander

/**
 * The viewer as Gander's own screens open it: see [ViewerActivity.INTERNAL_VIEWER]. A class
 * rather than an alias, because an alias takes on the viewer's documentLaunchMode, and a file
 * opened inside Gander belongs in Gander's card in the recent apps screen, not in one of its own.
 */
class InternalViewer : ViewerActivity()
