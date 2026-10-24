plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        manifestPlaceholders["autunnClusterTuningEnabled"] =
            providers.gradleProperty("autunnClusterTuning")
                .orElse(providers.gradleProperty("fawClusterTuning")).getOrElse("false")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all {
            it.maxHeapSize = "1g"
            // Production singletons outlive Robolectric sandboxes. Isolate test classes.
            it.forkEvery = 1
            it.maxParallelForks = 2
        }
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    api(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    testImplementation(libs.junit)
    testImplementation(libs.bouncycastle)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}
