package app.aemu.update

/** Sunset modification, 2026-10-02: never cross-install distribution variants.
 * Modified for AEmulator Plus, 2026-10-04: single package, app.aemu.plus; no clone.
 * Modified for QuDroid: the package is the one of this build (applicationId), so any release APK is a candidate;
 * the installer still checks that the downloaded APK has this app's package name before it installs it. GPL-3.0; see NOTICE.md. */
object UpdateAssetPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun matches(name: String, applicationId: String): Boolean =
        name.endsWith(".apk", ignoreCase = true)
}
