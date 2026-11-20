// Newer R8 than AGP 8.8 ships: tor-android 0.4.9.x classes are Java 24 (class file 68).
buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:8.13.25") }
}

plugins {
    id("com.android.application") version "8.8.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
