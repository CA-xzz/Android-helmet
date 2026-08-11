plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.helmet.service.runtime"
    compileSdk = 35
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_SIMULATED_HARDWARE", "true")
        }
        release {
            buildConfigField("boolean", "ALLOW_SIMULATED_HARDWARE", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":device-android:core-model"))
    implementation(project(":device-android:core-protocol"))
    implementation(project(":device-android:data-local"))
    implementation(project(":device-android:hardware-api"))
    implementation(project(":device-android:feature-camera"))
    implementation(project(":device-android:feature-location"))
    implementation(project(":device-android:feature-connectivity"))
    implementation(project(":device-android:safety-detection"))
    implementation(project(":device-android:location-sync"))
    implementation(project(":device-android:communication-sync"))
    implementation(project(":device-android:alert-sync"))
    implementation(project(":device-android:webrtc-runtime"))
    implementation(project(":device-android:media-sync"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.sqlite:sqlite-framework:2.4.0")
}
