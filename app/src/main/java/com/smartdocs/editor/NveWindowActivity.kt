package com.smartdocs.editor

/**
 * NVE-shell: a second reader window (Android multi-window / "New Window" of the desktop tab menu). Same UI as
 * [MainActivity], but declared with its own task affinity and the standard launch mode, so it opens next to the
 * main window (FLAG_ACTIVITY_LAUNCH_ADJACENT) instead of replacing it. Started by NveWindows.openInNewWindow.
 */
class NveWindowActivity : MainActivity()
