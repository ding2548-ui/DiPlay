plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

// CI stamps the run number and the short commit sha into versionName, so the first line of a
// diagnostic report names the exact build that produced it: "0.2.15（186-b815324）". The run
// number alone only says when a build ran, not what was in it.
// The major version follows the upstream release the source is based on: the tree merged upstream
// 0.2.15, so the app reports 0.2.15 even though this line's own release tags moved with it.
val buildNumber = providers.environmentVariable("DIPLAY_BUILD_NUMBER").orNull
val buildCommit = providers.environmentVariable("DIPLAY_BUILD_COMMIT").orNull

// The beta channel is a second variant of this same line, published from the psa-beta branch under
// its own release-tag namespace. It ships a distinct applicationId so it can sit next to the
// release build on the head unit, which is what lets a beta APK be tested without uninstalling the
// release one first. AppUpdater reads the same suffix back to pick its tag namespace.
val betaChannel = providers.environmentVariable("DIPLAY_CHANNEL").orNull == "beta"

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 25
        targetSdk = 37
        versionCode = 31
        versionName = buildNumber?.let { run ->
            val channelTag = if (betaChannel) "-beta" else ""
            buildCommit?.let { sha -> "0.2.15（$run-$sha）$channelTag" } ?: "0.2.15（$run）$channelTag"
        } ?: "0.2.15"
        if (betaChannel) {
            // Coexists with the release build; see the comment on [betaChannel].
            applicationIdSuffix = ".psabeta"
        }
        // The beta channel identifies itself wherever the app name is shown: the launcher label,
        // the home header, the About page and the session notification all read these two.
        // They are generated rather than kept in res/ so the release channel stays plain "DiPlay".
        val shownName = if (betaChannel) "DiPlay Beta" else "DiPlay"
        resValue("string", "app_name", shownName)
        resValue("string", "diplay", shownName)

        // The ABI is deliberately NOT pinned. Pinning it to armeabi-v7a was done so the v7a-only
        // libdiplay_lwip.so could load, but it forces the whole process to 32 bits — and the car's
        // own hotspot stopped accepting the phone as soon as the APK stopped shipping arm64-v8a.
        // Report 840 (178, 64-bit) and report 402 (194, 32-bit) bring the hotspot up identically
        // and then differ exactly there: tcpAccepted=1 against tcpAccepted=0. lwIP is not wanted
        // anyway, and attachLwip() already falls back to the VPN transport when
        // LwipNative.available is false, so ship every ABI and let the head unit install arm64.
    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            // No applicationIdSuffix and no versionNameSuffix: the car unit installs the release
            // identity, and a debug-flavoured package name or label would ship the wrong app.
            // Keep applicationId/versionName identical to release so an in-place update works.
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Supplies java.time and java.util.Base64 on Android 7/7.1 (API 24/25).
        isCoreLibraryDesugaringEnabled = true
    }
    // Android 7 cannot reliably dlopen libs that stay uncompressed inside the APK
    // (extractNativeLibs=false, the AGP default): extract at install time instead.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    buildFeatures {
        compose = true
        // Needed for the channel-dependent app_name/diplay strings in defaultConfig.
        resValues = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a platform-signed standalone release APK with provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleRelease")
}
