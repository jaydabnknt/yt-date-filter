group = "app.revanced"

patches {
    about {
        name = "YouTube date range filter"
        description = "Adds an upload-date range picker to YouTube search"
        source = "https://github.com/jaydabnknt/yt-date-filter"
        author = "Jakub Czebotar"
        contact = "https://github.com/jaydabnknt/yt-date-filter/issues"
        website = "https://github.com/jaydabnknt/yt-date-filter"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters"
        )
    }
}
