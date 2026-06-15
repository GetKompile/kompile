plugins {
    id("com.android.application") version "{{agpVersion}}" apply false
    id("com.android.library") version "{{agpVersion}}" apply false
    id("org.jetbrains.kotlin.android") version "{{kotlinVersion}}" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "{{kotlinVersion}}" apply false
    id("com.google.devtools.ksp") version "{{kotlinVersion}}-1.0.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
