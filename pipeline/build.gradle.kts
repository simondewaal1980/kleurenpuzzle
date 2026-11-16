// ---------------------------------------------------------------------------
// :pipeline — foto -> kleurplaat.
//
// Ook puur Kotlin, zodat dezelfde segmentatiecode zowel op de desktop
// (generator) als op het toestel (eigen foto's) draait. Bewust géén
// Android- of AWT-klassen: de pipeline werkt alleen met een IntArray pixels.
// ---------------------------------------------------------------------------

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":format"))
    testImplementation(libs.junit)
}
