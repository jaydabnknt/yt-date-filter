package app.revanced.patches.youtube.datefilter

import app.revanced.patcher.definingClass
import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.firstMethodDeclaratively
import app.revanced.patcher.name
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.returnType

private const val MAIN_ACTIVITY_CLASS_TYPE =
    "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;"

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/datefilter/SearchDateRangePatch;"

/**
 * Adds a date-range button next to the YouTube search box.
 *
 * Only the (non-obfuscated) MainActivity.onCreate is hooked. Everything else is done at runtime
 * by the extension, so the patch does not depend on obfuscated YouTube internals and should keep
 * working across YouTube versions.
 */
@Suppress("unused")
val searchDateRangePatch = bytecodePatch(
    name = "Search date range",
    description = "Adds a button to the search screen that filters results by a custom upload date range.",
) {
    compatibleWith("com.google.android.youtube")

    extendWith("extensions/datefilter.rve")

    apply {
        firstMethodDeclaratively {
            name("onCreate")
            definingClass(MAIN_ACTIVITY_CLASS_TYPE)
            returnType("V")
        }.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->onCreate(Landroid/app/Activity;)V",
        )
    }
}
