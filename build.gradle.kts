// ---------------------------------------------------------------------------
// Root build-bestand.
//
// Hier worden de plug-ins één keer gedeclareerd met `apply false`. Elke module
// kiest daarna zelf welke plug-in hij écht gebruikt. Zo blijven versies van
// plug-ins op één plek beheerd (in gradle/libs.versions.toml).
// ---------------------------------------------------------------------------

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}
