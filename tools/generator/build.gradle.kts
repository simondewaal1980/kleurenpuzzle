// ---------------------------------------------------------------------------
// :tools:generator — desktop-CLI om de meegeleverde kleurplaten te maken.
//
// Starten met:
//   ./gradlew :tools:generator:run --args="content/content.json app/src/main/assets"
//
// De CLI leest de bronfoto's, draait dezelfde :pipeline als de app en schrijft
// .kplate-bestanden + previews + een manifest naar de assets van de app.
// ---------------------------------------------------------------------------

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":format"))
    implementation(project(":pipeline"))
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("nl.kleuren.generator.MainKt")
}

// Zorg dat relatieve paden (content/content.json, app/src/main/assets) vanaf de
// projectroot werken, ook als Gradle de taak vanuit de modulemap start.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
