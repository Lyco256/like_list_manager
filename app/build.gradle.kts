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
private val EMBEDDINGGEMMA_REVISION = "75a84c732f1884df76bec365346230e32f582c82"
private val EMBEDDINGGEMMA_REPOSITORY = "onnx-community/embeddinggemma-300m-ONNX"
private val EMBEDDINGGEMMA_MAX_BUNDLE_BYTES = 300L * 1024L * 1024L
private val JAPANESE_CLIP_REVISION = "c924148be2e25b6e4d98e66d8dc1768adb72d079"
private val JAPANESE_CLIP_REPOSITORY = "AUXOUT-TEAM/clip-japanese-base-v2-onnx"
private val JAPANESE_CLIP_MAX_BUNDLE_BYTES = 300L * 1024L * 1024L
private val ALL_BUNDLED_ASSET_MAX_BYTES = 1L * 1024L * 1024L * 1024L
private val USEARCH_VERSION = "2.26.0"
private val USEARCH_NDK_VERSION = "28.2.13676358"
private val USEARCH_MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L

private data class UsearchAbi(
    val name: String,
    val archiveName: String,
    val archiveSha256: String,
    val compilerTarget: String,
)

private val USEARCH_ABIS = listOf(
    UsearchAbi(
        name = "arm64-v8a",
        archiveName = "usearch_android_arm64_2.26.0.zip",
        archiveSha256 = "480751585525b93446850d1b38586eac984044e82b480f6c64cef09c0ff48696",
        compilerTarget = "aarch64-linux-android29",
    ),
    UsearchAbi(
        name = "armeabi-v7a",
        archiveName = "usearch_android_arm32_2.26.0.zip",
        archiveSha256 = "5c71f67473f45821b57306330f621eb50b46285847e33095f0839d267bec2485",
        compilerTarget = "armv7a-linux-androideabi29",
    ),
)

private data class PinnedAsset(
    val name: String,
    val sha256: String,
)

private val EMBEDDINGGEMMA_ASSETS = listOf(
    PinnedAsset(
        name = "model_q4.onnx",
        sha256 = "ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043",
    ),
    PinnedAsset(
        name = "model_q4.onnx_data",
        sha256 = "599962c3143b040de2dd05e5975be3e9091dd067cacc6a8f7186e3203bab9e02",
    ),
    PinnedAsset(
        name = "tokenizer.json",
        sha256 = "4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47",
    ),
)

private data class JapaneseClipPinnedAsset(
    val name: String,
    val remotePath: String,
    val sha256: String,
)

private val JAPANESE_CLIP_ASSETS = listOf(
    JapaneseClipPinnedAsset(
        name = "text_model_q4f16.onnx",
        remotePath = "onnx/text_model_q4f16.onnx",
        sha256 = "653d6e3393d4c989cee268a5280fccd96dab1f6a93223e56bcb6cb9757503cb6",
    ),
    JapaneseClipPinnedAsset(
        name = "vision_model_q4f16.onnx",
        remotePath = "onnx/vision_model_q4f16.onnx",
        sha256 = "ef224e6127d6b66e79eab7ec2b4245acfa9ca070f0c66dbb27e93732bf09a76f",
    ),
    JapaneseClipPinnedAsset(
        name = "tokenizer.json",
        remotePath = "tokenizer.json",
        sha256 = "c11bafa5bdbcc1470ef2c81c6f32686547acace5c5e5a6b67a1d3b8501df6b86",
    ),
)

private fun sha256(file: File): String {
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

private fun deleteExact(file: File) {
    if (file.exists() && !file.delete()) {
        throw GradleException("Failed to remove ${file.absolutePath}")
    }
}

private fun downloadAsset(destination: File, url: String, maxBytes: Long) {
    val partial = File(destination.parentFile, "${destination.name}.partial")
    deleteExact(partial)
    try {
        val connection = URL(url).openConnection().apply {
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
                    if (total > maxBytes) {
                        throw GradleException("Downloaded asset exceeds its configured size limit: $url")
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

android {
    namespace = "com.lyco256.llm"
    compileSdk = 36
    ndkVersion = USEARCH_NDK_VERSION

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
    implementation(libs.onnxruntime.android)
    implementation(libs.djl.huggingface.tokenizers)
    implementation(libs.djl.android.tokenizer.native)
}

val sudachiGeneratedAssetsDir = layout.buildDirectory.dir("generated/sudachi/full/assets")
val prepareSudachiFullDictionary = tasks.register("prepareSudachiFullDictionary") {
    outputs.dir(sudachiGeneratedAssetsDir)
    outputs.upToDateWhen { false }

    doLast {
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

val embeddingGemmaGeneratedAssetsDir = layout.buildDirectory.dir("generated/embeddinggemma/assets")
val prepareEmbeddingGemmaAssets = tasks.register("prepareEmbeddingGemmaAssets") {
    outputs.dir(embeddingGemmaGeneratedAssetsDir)
    outputs.upToDateWhen { false }

    doLast {
        val cacheDir = File(
            gradle.gradleUserHomeDir,
            "caches/like-list-manager/embeddinggemma/$EMBEDDINGGEMMA_REVISION",
        )
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw GradleException("Failed to create EmbeddingGemma cache directory: ${cacheDir.absolutePath}")
        }

        val verifiedFiles = EMBEDDINGGEMMA_ASSETS.map { asset ->
            val cacheFile = File(cacheDir, asset.name)
            val valid = cacheFile.isFile && cacheFile.length() > 0L &&
                runCatching { sha256(cacheFile).equals(asset.sha256, ignoreCase = true) }.getOrDefault(false)
            if (!valid) {
                deleteExact(cacheFile)
                val remotePath = if (asset.name == "tokenizer.json") asset.name else "onnx/${asset.name}"
                val url =
                    "https://huggingface.co/$EMBEDDINGGEMMA_REPOSITORY/resolve/$EMBEDDINGGEMMA_REVISION/$remotePath"
                downloadAsset(cacheFile, url, EMBEDDINGGEMMA_MAX_BUNDLE_BYTES)
                val downloadedHash = if (cacheFile.isFile && cacheFile.length() > 0L) sha256(cacheFile) else ""
                if (!downloadedHash.equals(asset.sha256, ignoreCase = true)) {
                    deleteExact(cacheFile)
                    throw GradleException(
                        "Downloaded EmbeddingGemma asset SHA-256 does not match the pinned value: ${asset.name}",
                    )
                }
            }
            asset to cacheFile
        }

        val embeddingBytes = verifiedFiles.sumOf { (_, file) -> file.length() }
        if (embeddingBytes > EMBEDDINGGEMMA_MAX_BUNDLE_BYTES) {
            throw GradleException(
                "EmbeddingGemma assets exceed the 300 MiB limit: $embeddingBytes bytes",
            )
        }

        val generatedRoot = embeddingGemmaGeneratedAssetsDir.get().asFile
        project.delete(generatedRoot)
        val generatedModelDir = File(generatedRoot, "embeddinggemma/$EMBEDDINGGEMMA_REVISION")
        if (!generatedModelDir.mkdirs()) {
            throw GradleException("Failed to create generated EmbeddingGemma asset directory")
        }
        verifiedFiles.forEach { (_, file) ->
            file.copyTo(File(generatedModelDir, file.name), overwrite = true)
        }
        File(generatedModelDir, "metadata.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"repository\": \"$EMBEDDINGGEMMA_REPOSITORY\",")
                appendLine("  \"revision\": \"$EMBEDDINGGEMMA_REVISION\",")
                appendLine("  \"files\": [")
                verifiedFiles.forEachIndexed { index, (asset, file) ->
                    val comma = if (index + 1 == verifiedFiles.size) "" else ","
                    appendLine(
                        "    {\"name\": \"${asset.name}\", \"sha256\": \"${asset.sha256}\", " +
                            "\"byteSize\": ${file.length()}}$comma",
                    )
                }
                appendLine("  ]")
                appendLine("}")
            },
        )
    }
}

val japaneseClipGeneratedAssetsDir = layout.buildDirectory.dir("generated/japanese-clip/assets")
val prepareJapaneseClipAssets = tasks.register("prepareJapaneseClipAssets") {
    outputs.dir(japaneseClipGeneratedAssetsDir)
    outputs.upToDateWhen { false }

    doLast {
        val cacheDir = File(
            gradle.gradleUserHomeDir,
            "caches/like-list-manager/japanese-clip/$JAPANESE_CLIP_REVISION",
        )
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw GradleException("Failed to create Japanese CLIP cache directory: ${cacheDir.absolutePath}")
        }

        val verifiedFiles = JAPANESE_CLIP_ASSETS.map { asset ->
            val cacheFile = File(cacheDir, asset.name)
            val valid = cacheFile.isFile && cacheFile.length() > 0L &&
                runCatching { sha256(cacheFile).equals(asset.sha256, ignoreCase = true) }.getOrDefault(false)
            if (!valid) {
                deleteExact(cacheFile)
                val url =
                    "https://huggingface.co/$JAPANESE_CLIP_REPOSITORY/resolve/$JAPANESE_CLIP_REVISION/${asset.remotePath}"
                downloadAsset(cacheFile, url, JAPANESE_CLIP_MAX_BUNDLE_BYTES)
                val downloadedHash = if (cacheFile.isFile && cacheFile.length() > 0L) sha256(cacheFile) else ""
                if (!downloadedHash.equals(asset.sha256, ignoreCase = true)) {
                    deleteExact(cacheFile)
                    throw GradleException(
                        "Downloaded Japanese CLIP asset SHA-256 does not match the pinned value: ${asset.name}",
                    )
                }
            }
            asset to cacheFile
        }

        val japaneseClipBytes = verifiedFiles.sumOf { (_, file) -> file.length() }
        if (japaneseClipBytes > JAPANESE_CLIP_MAX_BUNDLE_BYTES) {
            throw GradleException(
                "Japanese CLIP assets exceed the 300 MiB limit: $japaneseClipBytes bytes",
            )
        }

        val generatedRoot = japaneseClipGeneratedAssetsDir.get().asFile
        project.delete(generatedRoot)
        val generatedModelDir = File(generatedRoot, "japanese_clip/$JAPANESE_CLIP_REVISION")
        if (!generatedModelDir.mkdirs()) {
            throw GradleException("Failed to create generated Japanese CLIP asset directory")
        }
        verifiedFiles.forEach { (asset, file) ->
            file.copyTo(File(generatedModelDir, asset.name), overwrite = true)
        }
        File(generatedModelDir, "metadata.json").writeText(
            buildString {
                appendLine("{")
                appendLine("  \"repository\": \"$JAPANESE_CLIP_REPOSITORY\",")
                appendLine("  \"revision\": \"$JAPANESE_CLIP_REVISION\",")
                appendLine("  \"files\": [")
                verifiedFiles.forEachIndexed { index, (asset, file) ->
                    val comma = if (index + 1 == verifiedFiles.size) "" else ","
                    appendLine(
                        "    {\"name\": \"${asset.name}\", \"sha256\": \"${asset.sha256}\", " +
                            "\"byteSize\": ${file.length()}}$comma",
                    )
                }
                appendLine("  ]")
                appendLine("}")
            },
        )
    }
}

val verifyBundledModelCapacity = tasks.register("verifyBundledModelCapacity") {
    dependsOn(prepareSudachiFullDictionary, prepareEmbeddingGemmaAssets, prepareJapaneseClipAssets)
    doLast {
        fun mib(bytes: Long): String = "%.3f MiB".format(bytes / (1024.0 * 1024.0))

        val ocrBytes = fileTree(rootProject.file("ppocr-sdk/src/main/assets/models"))
            .matching { include("**/*.onnx") }
            .files
            .sumOf(File::length)
        val sudachiFile = File(
            sudachiGeneratedAssetsDir.get().asFile,
            "sudachi/$SUDACHI_DICTIONARY_VERSION/$SUDACHI_DICTIONARY_ARCHIVE_NAME",
        )
        val sudachiBytes = sudachiFile.length()
        val embeddingBytes = EMBEDDINGGEMMA_ASSETS.sumOf { asset ->
            File(
                embeddingGemmaGeneratedAssetsDir.get().asFile,
                "embeddinggemma/$EMBEDDINGGEMMA_REVISION/${asset.name}",
            ).length()
        }
        val japaneseClipBytes = JAPANESE_CLIP_ASSETS.sumOf { asset ->
            File(
                japaneseClipGeneratedAssetsDir.get().asFile,
                "japanese_clip/$JAPANESE_CLIP_REVISION/${asset.name}",
            ).length()
        }
        if (japaneseClipBytes > JAPANESE_CLIP_MAX_BUNDLE_BYTES) {
            throw GradleException(
                "Japanese CLIP assets exceed the 300 MiB limit: $japaneseClipBytes bytes",
            )
        }
        val totalBytes = ocrBytes + sudachiBytes + embeddingBytes + japaneseClipBytes
        if (totalBytes > ALL_BUNDLED_ASSET_MAX_BYTES) {
            throw GradleException(
                "OCR + Sudachi + EmbeddingGemma assets exceed the 1 GiB limit: $totalBytes bytes",
            )
        }
        logger.lifecycle(
            "Bundled model sizes: JapaneseCLIP=${mib(japaneseClipBytes)}, " +
                "EmbeddingGemma=${mib(embeddingBytes)}, OCR=${mib(ocrBytes)}, " +
                "Sudachi=${mib(sudachiBytes)}, total=${mib(totalBytes)}",
        )
    }
}

android.sourceSets.getByName("main").assets.srcDir(sudachiGeneratedAssetsDir)
android.sourceSets.getByName("main").assets.srcDir(embeddingGemmaGeneratedAssetsDir)
android.sourceSets.getByName("main").assets.srcDir(japaneseClipGeneratedAssetsDir)
tasks.matching { task -> task.name.startsWith("merge") && task.name.endsWith("Assets") }.configureEach {
    dependsOn(verifyBundledModelCapacity)
}

val usearchGeneratedRoot = layout.buildDirectory.dir("generated/usearch")
val usearchJniLibsDir = usearchGeneratedRoot.map { it.dir("jniLibs") }
val usearchAdapterSource = layout.projectDirectory.file("src/main/cpp/usearch_jni_bridge.c")

val prepareUsearchNative = tasks.register("prepareUsearchNative") {
    group = "build setup"
    description = "Downloads and verifies the pinned USearch Android release artifacts."
    outputs.dir(usearchGeneratedRoot)
    outputs.upToDateWhen { false }
    doLast {
        val generatedRoot = usearchGeneratedRoot.get().asFile
        val cacheRoot = File(gradle.gradleUserHomeDir, "caches/like-list-manager/usearch/$USEARCH_VERSION")
        project.delete(generatedRoot)

        fun extractEntry(zip: ZipFile, entryName: String, destination: File) {
            val partial = File(destination.parentFile, ".${destination.name}.partial")
            deleteExact(partial)
            try {
                zip.getInputStream(zip.getEntry(entryName)).use { input ->
                    FileOutputStream(partial).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                deleteExact(partial)
            }
        }

        var copiedHeader = false
        USEARCH_ABIS.forEach { abi ->
            val cacheDirectory = File(cacheRoot, abi.name)
            if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
                throw GradleException("Failed to create USearch cache directory: ${cacheDirectory.absolutePath}")
            }
            val archive = File(cacheDirectory, abi.archiveName)
            val validCache = archive.isFile && archive.length() <= USEARCH_MAX_ARCHIVE_BYTES &&
                sha256(archive).equals(abi.archiveSha256, ignoreCase = true)
            if (!validCache) {
                deleteExact(archive)
                downloadAsset(
                    archive,
                    "https://github.com/unum-cloud/USearch/releases/download/v$USEARCH_VERSION/${abi.archiveName}",
                    USEARCH_MAX_ARCHIVE_BYTES,
                )
                check(sha256(archive).equals(abi.archiveSha256, ignoreCase = true)) {
                    "USearch ${abi.archiveName} SHA-256 does not match the pinned release"
                }
            }

            ZipFile(archive).use { zip ->
                val libraryEntries = zip.entries().asSequence().filter {
                    !it.isDirectory && it.name.substringAfterLast('/') == "libusearch_c.so"
                }.toList()
                check(libraryEntries.size == 1) {
                    "USearch ${abi.archiveName} must contain exactly one libusearch_c.so"
                }
                val headerEntries = zip.entries().asSequence().filter {
                    !it.isDirectory && it.name.substringAfterLast('/') == "usearch.h"
                }.toList()
                check(headerEntries.size == 1) {
                    "USearch ${abi.archiveName} must contain exactly one usearch.h"
                }

                val abiOutput = File(generatedRoot, "jniLibs/${abi.name}").apply { mkdirs() }
                extractEntry(zip, libraryEntries.single().name, File(abiOutput, "libusearch_c.so"))
                if (!copiedHeader) {
                    val includeDirectory = File(generatedRoot, "include").apply { mkdirs() }
                    extractEntry(zip, headerEntries.single().name, File(includeDirectory, "usearch.h"))
                    copiedHeader = true
                }
            }
        }
    }
}

val compileUsearchJni = tasks.register("compileUsearchJni") {
    group = "build setup"
    description = "Builds the small Android load adapter for the pinned USearch C release."
    dependsOn(prepareUsearchNative)
    inputs.file(usearchAdapterSource)
    outputs.dir(usearchJniLibsDir)
    doLast {
        val generatedRoot = usearchGeneratedRoot.get().asFile
        val sdkDirectory = android.sdkDirectory
        val ndkDirectory = File(sdkDirectory, "ndk/$USEARCH_NDK_VERSION")
        check(ndkDirectory.isDirectory) { "Required Android NDK is missing: ${ndkDirectory.absolutePath}" }
        val hostTag = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux-x86_64"
            else -> error("Unsupported host OS for the USearch Android adapter")
        }
        val toolchain = File(ndkDirectory, "toolchains/llvm/prebuilt/$hostTag")
        val compiler = File(toolchain, "bin/clang.exe").takeIf { it.isFile }
            ?: File(toolchain, "bin/clang").takeIf { it.isFile }
            ?: error("Android clang compiler is missing under ${toolchain.absolutePath}")
        val includeDirectory = File(generatedRoot, "include")
        USEARCH_ABIS.forEach { abi ->
            val abiOutput = File(generatedRoot, "jniLibs/${abi.name}")
            val adapter = File(abiOutput, "libusearch.so")
            project.exec {
                commandLine(
                    compiler.absolutePath,
                    "--target=${abi.compilerTarget}",
                    "-std=c11",
                    "-O2",
                    "-fPIC",
                    "-shared",
                    "-I${includeDirectory.absolutePath}",
                    "${usearchAdapterSource.asFile.absolutePath}",
                    "-L${abiOutput.absolutePath}",
                    "-Wl,-soname,libusearch.so",
                    "-Wl,-z,relro",
                    "-Wl,-z,now",
                    "-Wl,-l:libusearch_c.so",
                    "-o",
                    adapter.absolutePath,
                )
            }
            check(adapter.isFile && adapter.length() > 0L) {
                "USearch JNI adapter was not generated for ${abi.name}"
            }
        }
    }
}

val verifyUsearchNativePackaging = tasks.register("verifyUsearchNativePackaging") {
    group = "verification"
    description = "Checks that both pinned USearch ABIs have generated native libraries."
    dependsOn(compileUsearchJni)
    doLast {
        USEARCH_ABIS.forEach { abi ->
            val directory = File(usearchJniLibsDir.get().asFile, abi.name)
            listOf("libusearch_c.so", "libusearch.so").forEach { filename ->
                val library = File(directory, filename)
                check(library.isFile && library.length() > 0L) {
                    "Missing generated USearch native library: ${library.absolutePath}"
                }
            }
        }
    }
}

android.sourceSets.getByName("main").jniLibs.srcDir(usearchJniLibsDir)
tasks.matching { task ->
    task.name.startsWith("merge") &&
        (task.name.endsWith("JniLibFolders") || task.name.endsWith("NativeLibs"))
}.configureEach {
    dependsOn(compileUsearchJni)
}
tasks.matching { task -> task.name == "verifyTestEnvironmentIsolation" }.configureEach {
    dependsOn(verifyUsearchNativePackaging)
}
