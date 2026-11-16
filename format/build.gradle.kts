// ---------------------------------------------------------------------------
// :format — het binaire .kplate-bestandsformaat.
//
// Dit is een "pure Kotlin/JVM"-module: geen Android-afhankelijkheden. Daardoor
// kan exact dezelfde code worden gebruikt door:
//   * de Android-app (decoder),
//   * de generator-CLI op de desktop (encoder),
//   * snelle unit tests op je laptop.
// ---------------------------------------------------------------------------

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Java 17 is de taalversie van de bytecode. Android ondersteunt dit.
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
