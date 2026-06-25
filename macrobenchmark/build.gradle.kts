plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lyco256.llm.macrobenchmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lyco256.llm.macrobenchmark.host"
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "NOT-SELF-INSTRUMENTING"
    }

    buildTypes {
        create("benchmark") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    testBuildType = "benchmark"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.benchmark.macro.junit4)
}
