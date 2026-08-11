plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.helmet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.helmet"
        minSdk = 31
        targetSdk = 31
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["cleartextTrafficPermitted"] = "true"
            manifestPlaceholders["diagnosticsExported"] = "true"
            buildConfigField("boolean", "PRODUCTION_BUILD", "false")
        }
        release {
            manifestPlaceholders["cleartextTrafficPermitted"] = "false"
            manifestPlaceholders["diagnosticsExported"] = "false"
            buildConfigField("boolean", "PRODUCTION_BUILD", "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // The product is a dedicated Android 12/API 31 system image, not a Play app.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":device-android:core-model"))
    implementation(project(":device-android:data-local"))
    implementation(project(":device-android:hardware-api"))
    implementation(project(":device-android:service-runtime"))
    implementation(project(":native-hardware-service"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
}
