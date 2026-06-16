plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import org.gradle.api.tasks.testing.Test
import java.io.File

android {
    namespace = "com.lyco256.llm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lyco256.llm"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

afterEvaluate {
    tasks.withType<Test>().configureEach {
        val sourceTestClassesDirs = files(
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
            layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
            layout.buildDirectory.dir("generated/ksp/debugUnitTest/resources"),
            layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"),
        )
        val asciiTestClassesDir = File(
            System.getProperty("java.io.tmpdir"),
            "like-list-manager-test-classes/${path.replace(':', '_')}",
        )
        classpath = files(asciiTestClassesDir) + classpath
        doFirst {
            delete(asciiTestClassesDir)
            copy {
                from(sourceTestClassesDirs)
                into(asciiTestClassesDir)
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.appauth)
}
