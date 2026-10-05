// DiPlay Home: a small launcher with the live CarPlay map and any Android widgets.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.home"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.home"
        minSdk = 30 // SurfaceView.getHostToken and setChildSurfacePackage
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

tasks.withType<Test>().configureEach {
    // Robolectric's API 36 shared-memory implementation needs this JDK interface.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
