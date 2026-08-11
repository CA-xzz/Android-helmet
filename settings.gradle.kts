pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-helmet"

include(
    ":device-android:app",
    ":device-android:core-model",
    ":device-android:core-protocol",
    ":device-android:data-local",
    ":device-android:hardware-api",
    ":device-android:feature-camera",
    ":device-android:feature-location",
    ":device-android:feature-connectivity",
    ":device-android:safety-detection",
    ":device-android:alert-sync",
    ":device-android:location-sync",
    ":device-android:communication-sync",
    ":device-android:webrtc-runtime",
    ":device-android:media-sync",
    ":device-android:service-runtime",
    ":native-hardware-service",
)
