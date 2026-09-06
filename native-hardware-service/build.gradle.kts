plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.helmet.hardware.service"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 31
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_TEST_PTY", "true")
            externalNativeBuild {
                cmake {
                    arguments += "-DHELMET_ENABLE_TEST_PTY=ON"
                }
            }
        }
        release {
            buildConfigField("boolean", "ALLOW_TEST_PTY", "false")
            externalNativeBuild {
                cmake {
                    arguments += "-DHELMET_ENABLE_TEST_PTY=OFF"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":device-android:core-protocol"))
    implementation(project(":device-android:hardware-api"))
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
