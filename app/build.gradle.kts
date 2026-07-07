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
        buildConfigField("boolean", "TEST_HARNESS", "false")
        buildConfigField("String", "STORAGE_DATABASE_NAME", "\"like_list_manager.db\"")
        buildConfigField("String", "STORAGE_IMAGES_DIRECTORY", "\"images\"")
        buildConfigField("String", "STORAGE_DATA_DIRECTORY", "\"post_data\"")
        buildConfigField("String", "STORAGE_PREFERENCES_NAME", "\"post_storage_settings\"")
        buildConfigField("String", "API_PREFERENCES_NAME", "\"api_settings\"")
        buildConfigField("String", "X_API_BASE_URL", "\"https://api.x.com/2\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        create("integrationTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".test"
            versionNameSuffix = "-integration-test"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "TEST_HARNESS", "true")
            buildConfigField("String", "STORAGE_DATABASE_NAME", "\"like_list_manager_test.db\"")
            buildConfigField("String", "STORAGE_IMAGES_DIRECTORY", "\"test_images\"")
            buildConfigField("String", "STORAGE_DATA_DIRECTORY", "\"test_post_data\"")
            buildConfigField("String", "STORAGE_PREFERENCES_NAME", "\"post_storage_test_settings\"")
            buildConfigField("String", "API_PREFERENCES_NAME", "\"api_test_settings\"")
            buildConfigField("String", "X_API_BASE_URL", "\"http://127.0.0.1/disabled\"")
        }
        create("benchmark") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".test.benchmark"
            versionNameSuffix = "-benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
            isDebuggable = false
            buildConfigField("boolean", "TEST_HARNESS", "true")
            buildConfigField("String", "STORAGE_DATABASE_NAME", "\"like_list_manager_benchmark.db\"")
            buildConfigField("String", "STORAGE_IMAGES_DIRECTORY", "\"benchmark_images\"")
            buildConfigField("String", "STORAGE_DATA_DIRECTORY", "\"benchmark_post_data\"")
            buildConfigField("String", "STORAGE_PREFERENCES_NAME", "\"post_storage_benchmark_settings\"")
            buildConfigField("String", "API_PREFERENCES_NAME", "\"api_benchmark_settings\"")
            buildConfigField("String", "X_API_BASE_URL", "\"http://127.0.0.1/disabled\"")
        }
    }

    testBuildType = "integrationTest"

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
        providers.gradleProperty("snapshotRoot").orNull?.let { systemProperty("llm.snapshot.root", it) }
        providers.gradleProperty("snapshotImages").orNull?.let { systemProperty("llm.snapshot.images", it) }
    }

    tasks.register("verifyTestEnvironmentIsolation") {
        group = "verification"
        description = "Verifies that the integration-test app cannot share production identity, storage, OAuth, or API settings."
        dependsOn(
            "generateDebugBuildConfig",
            "generateIntegrationTestBuildConfig",
            "processIntegrationTestManifestForPackage",
            "generateBenchmarkBuildConfig",
            "processBenchmarkManifestForPackage",
        )
        doLast {
            val debugConfig = layout.buildDirectory.file(
                "generated/source/buildConfig/debug/com/lyco256/llm/BuildConfig.java",
            ).get().asFile.readText()
            val testConfig = layout.buildDirectory.file(
                "generated/source/buildConfig/integrationTest/com/lyco256/llm/BuildConfig.java",
            ).get().asFile.readText()
            val testManifest = layout.buildDirectory.file(
                "intermediates/packaged_manifests/integrationTest/processIntegrationTestManifestForPackage/AndroidManifest.xml",
            ).get().asFile.readText()
            val benchmarkConfig = layout.buildDirectory.file(
                "generated/source/buildConfig/benchmark/com/lyco256/llm/BuildConfig.java",
            ).get().asFile.readText()
            val benchmarkManifest = layout.buildDirectory.file(
                "intermediates/packaged_manifests/benchmark/processBenchmarkManifestForPackage/AndroidManifest.xml",
            ).get().asFile.readText()

            check(debugConfig.contains("APPLICATION_ID = \"com.lyco256.llm\""))
            check(debugConfig.contains("TEST_HARNESS = false"))
            check(debugConfig.contains("X_API_BASE_URL = \"https://api.x.com/2\""))
            check(testConfig.contains("APPLICATION_ID = \"com.lyco256.llm.test\""))
            check(testConfig.contains("TEST_HARNESS = true"))
            check(testConfig.contains("STORAGE_DATABASE_NAME = \"like_list_manager_test.db\""))
            check(testConfig.contains("STORAGE_IMAGES_DIRECTORY = \"test_images\""))
            check(testConfig.contains("STORAGE_PREFERENCES_NAME = \"post_storage_test_settings\""))
            check(testConfig.contains("API_PREFERENCES_NAME = \"api_test_settings\""))
            check(testConfig.contains("X_API_BASE_URL = \"http://127.0.0.1/disabled\""))
            check(testManifest.contains("package=\"com.lyco256.llm.test\""))
            check(!testManifest.contains("RedirectUriReceiverActivity"))
            check(benchmarkConfig.contains("APPLICATION_ID = \"com.lyco256.llm.test.benchmark\""))
            check(benchmarkConfig.contains("TEST_HARNESS = true"))
            check(benchmarkConfig.contains("STORAGE_DATABASE_NAME = \"like_list_manager_benchmark.db\""))
            check(benchmarkConfig.contains("API_PREFERENCES_NAME = \"api_benchmark_settings\""))
            check(benchmarkConfig.contains("X_API_BASE_URL = \"http://127.0.0.1/disabled\""))
            check(benchmarkManifest.contains("package=\"com.lyco256.llm.test.benchmark\""))
            check(!benchmarkManifest.contains("RedirectUriReceiverActivity"))
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
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
    add("integrationTestImplementation", libs.androidx.compose.ui.tooling)
    add("integrationTestImplementation", libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.appauth)
    implementation(libs.mlkit.text.japanese)
}
