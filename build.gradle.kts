// Top-level build file for Flashcards Everywhere.
// Plugin versions are declared in gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// LOCAL-ONLY workaround: when the project lives on a macOS HFS+/exFAT
// external drive (e.g. /Volumes/T7), the filesystem creates "._*" AppleDouble
// resource forks on every file write. KSP/Hilt generated sources get
// poisoned by these, crashing KSP with NPE on `qualifiedName`.
//
// Detect the bad case and redirect build output to a clean location.
// On Linux CI runners (and on local APFS) this branch is skipped and the
// in-tree app/build/outputs path works as normal — which is what
// .github/workflows/build-apk.yml expects.
val projectOnVolumes = rootDir.absolutePath.startsWith("/Volumes/")
if (projectOnVolumes) {
    allprojects {
        layout.buildDirectory.set(
            file("${System.getProperty("user.home")}/.cache/flashcards-everywhere/${project.name}/build")
        )
    }
}
