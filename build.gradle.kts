// Top-level build file for Flashcards Everywhere.
// Plugin versions are declared in gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// The project lives on an HFS+ external drive that creates "._*" resource
// forks on every file write. KSP/Hilt generated sources get poisoned by these,
// crashing KSP with NullPointerException on `qualifiedName`. Redirect all
// build output to a non-HFS+ location.
allprojects {
    layout.buildDirectory.set(
        file("${System.getProperty("user.home")}/.cache/flashcards-everywhere/${project.name}/build")
    )
}
