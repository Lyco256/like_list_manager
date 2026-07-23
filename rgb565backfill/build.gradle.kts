plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lyco256.llm.rgb565backfill"
    compileSdk = 36
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "com.lyco256.llm.rgb565backfill.Rgb565BackfillRunner"
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/shared/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}
