plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import org.gradle.api.tasks.testing.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

private val SUDACHI_DICTIONARY_VERSION = "20260723"
private val SUDACHI_DICTIONARY_ARCHIVE_NAME = "sudachi-dictionary-20260723-full.zip"
private val SUDACHI_DICTIONARY_ARCHIVE_URL =
    "https://d2ej7fkh96fzlu.cloudfront.net/sudachidict/sudachi-dictionary-20260723-full.zip"
private val SUDACHI_DICTIONARY_ARCHIVE_SHA256 =
    "fc87525a4c7639ea46d81a3e4e3976853240ec07e3db3991190c920a02efe107"
private val SUDACHI_DICTIONARY_MAX_ARCHIVE_BYTES = 300L * 1024L * 1024L
private val SUDACHI_MAX_BUNDLED_ASSET_BYTES = 1L * 1024L * 1024L * 1024L

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
        create("benchmarkSetup") {
            initWith(getByName("benchmark"))
            isDebuggable = true
            matchingFallbacks += listOf("benchmark")
        }
    }

    sourceSets {
        getByName("benchmarkSetup") {
            java.srcDir("src/benchmark/java")
            res.srcDir("src/benchmark/res")
            manifest.srcFile("src/benchmark/AndroidManifest.xml")
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
            "generateReleaseBuildConfig",
            "generateIntegrationTestBuildConfig",
            "processDebugManifestForPackage",
            "processReleaseManifestForPackage",
            "processIntegrationTestManifestForPackage",
            "generateBenchmarkBuildConfig",
            "processBenchmarkManifestForPackage",
        )
        doLast {
            val debugConfig = layout.buildDirectory.file(
                "generated/source/buildConfig/debug/com/lyco256/llm/BuildConfig.java",
            ).get().asFile.readText()
            val debugManifest = layout.buildDirectory.file(
                "intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml",
            ).get().asFile.readText()
            val releaseManifest = layout.buildDirectory.file(
                "intermediates/packaged_manifests/release/processReleaseManifestForPackage/AndroidManifest.xml",
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
            check(debugManifest.contains("com.lyco256.llm.MainActivity"))
            check(!debugManifest.contains("BenchmarkMainActivity"))
            check(!debugManifest.contains("BenchmarkSnapshotSetupActivity"))
            check(releaseManifest.contains("com.lyco256.llm.MainActivity"))
            check(!releaseManifest.contains("BenchmarkMainActivity"))
            check(!releaseManifest.contains("BenchmarkSnapshotSetupActivity"))
            check(testConfig.contains("APPLICATION_ID = \"com.lyco256.llm.test\""))
            check(testConfig.contains("TEST_HARNESS = true"))
            check(testConfig.contains("STORAGE_DATABASE_NAME = \"like_list_manager_test.db\""))
            check(testConfig.contains("STORAGE_IMAGES_DIRECTORY = \"test_images\""))
            check(testConfig.contains("STORAGE_PREFERENCES_NAME = \"post_storage_test_settings\""))
            check(testConfig.contains("API_PREFERENCES_NAME = \"api_test_settings\""))
            check(testConfig.contains("X_API_BASE_URL = \"http://127.0.0.1/disabled\""))
            check(testManifest.contains("package=\"com.lyco256.llm.test\""))
            check(testManifest.contains("com.lyco256.llm.MainActivity"))
            check(!testManifest.contains("BenchmarkMainActivity"))
            check(!testManifest.contains("BenchmarkSnapshotSetupActivity"))
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
    implementation(project(":ppocr-sdk"))
    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.work.testing)
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
    implementation(libs.androidx.sqlite.bundled)

    implementation(libs.coil.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.appauth)
    implementation(libs.sudachi)
}

val sudachiGeneratedAssetsDir = layout.buildDirectory.dir("generated/sudachi/full/assets")
val prepareSudachiFullDictionary = tasks.register("prepareSudachiFullDictionary") {
    outputs.dir(sudachiGeneratedAssetsDir)
    outputs.upToDateWhen { false }

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        fun deleteExact(file: File) {
            if (file.exists() && !file.delete()) {
                throw GradleException("Failed to remove ${file.absolutePath}")
            }
        }

        data class ArchiveInfo(
            val archiveSha256: String,
            val archiveByteSize: Long,
            val systemDictionaryEntryName: String,
            val systemDictionarySha256: String,
            val systemDictionaryByteSize: Long,
        )

        fun inspectArchive(file: File): ArchiveInfo? {
            if (!file.isFile || file.length() > SUDACHI_DICTIONARY_MAX_ARCHIVE_BYTES) return null
            val archiveSha256 = sha256(file)
            if (!archiveSha256.equals(SUDACHI_DICTIONARY_ARCHIVE_SHA256, ignoreCase = true)) return null

            val systemEntries = ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.substringAfterLast('/') == "system_full.dic" }
                    .toList()
            }
            if (systemEntries.size != 1) {
                throw GradleException(
                    "Sudachi Full archive must contain exactly one system_full.dic entry, found ${systemEntries.size}",
                )
            }
            val systemEntry = systemEntries.single()
            val systemDigest = MessageDigest.getInstance("SHA-256")
            var systemBytes = 0L
            ZipFile(file).use { zip ->
                zip.getInputStream(zip.getEntry(systemEntry.name)).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        systemBytes += read.toLong()
                        systemDigest.update(buffer, 0, read)
                    }
                }
            }
            return ArchiveInfo(
                archiveSha256 = archiveSha256,
                archiveByteSize = file.length(),
                systemDictionaryEntryName = systemEntry.name,
                systemDictionarySha256 = systemDigest.digest().joinToString("") { byte -> "%02x".format(byte) },
                systemDictionaryByteSize = systemBytes,
            )
        }

        fun downloadArchive(destination: File) {
            val partial = File(destination.parentFile, "$SUDACHI_DICTIONARY_ARCHIVE_NAME.partial")
            deleteExact(partial)
            try {
                val connection = URL(SUDACHI_DICTIONARY_ARCHIVE_URL).openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                connection.getInputStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read.toLong()
                            if (total > SUDACHI_DICTIONARY_MAX_ARCHIVE_BYTES) {
                                throw GradleException("Sudachi Full archive exceeds the 300 MiB limit")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                deleteExact(partial)
            }
        }

        val cacheDir = File(gradle.gradleUserHomeDir, "caches/like-list-manager/sudachi/$SUDACHI_DICTIONARY_VERSION")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw GradleException("Failed to create Sudachi cache directory: ${cacheDir.absolutePath}")
        }
        val cacheFile = File(cacheDir, SUDACHI_DICTIONARY_ARCHIVE_NAME)
        val archiveInfo = inspectArchive(cacheFile) ?: run {
            deleteExact(cacheFile)
            downloadArchive(cacheFile)
            inspectArchive(cacheFile)
                ?: run {
                    deleteExact(cacheFile)
                    throw GradleException("Downloaded Sudachi Full archive SHA-256 does not match the pinned value")
                }
        }

        val ocrBytes = fileTree(rootProject.file("ppocr-sdk/src/main/assets/models"))
            .matching { include("**/*.onnx") }
            .files
            .sumOf(File::length)
        if (ocrBytes + archiveInfo.archiveByteSize > SUDACHI_MAX_BUNDLED_ASSET_BYTES) {
            throw GradleException("OCR ONNX files plus Sudachi Full archive exceed the 1 GiB asset limit")
        }

        val generatedRoot = sudachiGeneratedAssetsDir.get().asFile
        project.delete(generatedRoot)
        val generatedDictionaryDir = File(generatedRoot, "sudachi/$SUDACHI_DICTIONARY_VERSION")
        if (!generatedDictionaryDir.mkdirs()) {
            throw GradleException("Failed to create generated Sudachi asset directory")
        }
        cacheFile.copyTo(File(generatedDictionaryDir, SUDACHI_DICTIONARY_ARCHIVE_NAME), overwrite = true)
        File(generatedDictionaryDir, "metadata.json").writeText(
            """
            {
              "dictionaryVersion": "$SUDACHI_DICTIONARY_VERSION",
              "archiveFileName": "$SUDACHI_DICTIONARY_ARCHIVE_NAME",
              "archiveUrl": "$SUDACHI_DICTIONARY_ARCHIVE_URL",
              "archiveSha256": "${archiveInfo.archiveSha256}",
              "archiveByteSize": ${archiveInfo.archiveByteSize},
              "systemDictionaryEntryName": "${archiveInfo.systemDictionaryEntryName}",
              "systemDictionarySha256": "${archiveInfo.systemDictionarySha256}",
              "systemDictionaryByteSize": ${archiveInfo.systemDictionaryByteSize}
            }
            """.trimIndent(),
        )
    }
}

android.sourceSets.getByName("main").assets.srcDir(sudachiGeneratedAssetsDir)
tasks.matching { task -> task.name.startsWith("merge") && task.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareSudachiFullDictionary)
}
