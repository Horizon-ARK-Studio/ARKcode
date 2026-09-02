plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.horizonarkstudio.arkware"
    compileSdk = 34

    defaultConfig {
        // Never shipped on its own -- each product flavor below turns
        // this into the real, installable com.horizonarkstudio.arkware.<spa-name>
        // via applicationIdSuffix, per docs/Foundational/ROADMAP.md's
        // v1 "config-driven target SPA, not hardcoded to one site".
        applicationId = "com.horizonarkstudio.arkware"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // One flavor per target SPA. SpaConfig.kt is the single place
    // that reads the four values set per flavor below (TARGET_URL,
    // SPA_DISPLAY_NAME, NAG_HIDE_SELECTORS, NAG_HIDE_TEXT_MATCHES) --
    // adding a new SPA means adding a new flavor here, never touching
    // SpaConfig.kt, ArkScripts.kt, or any other shell code.
    flavorDimensions += "spa"
    productFlavors {
        // The case ARKtube originally proved on its own, single-purpose
        // Android build, reproduced here as just another flavor --
        // nothing in the generic shell knows this is "the YouTube
        // build" beyond these four values and the applicationId.
        create("youtube") {
            dimension = "spa"
            applicationIdSuffix = ".youtube"
            resValue("string", "app_name", "YouTube")
            buildConfigField("String", "TARGET_URL", "\"https://m.youtube.com\"")
            buildConfigField("String", "SPA_DISPLAY_NAME", "\"YouTube\"")
            // Left empty deliberately: real selectors/button text for
            // YouTube's own "open app" nag have to be read off its
            // actual markup, not guessed here -- an empty list is a
            // harmless no-op (see ArkScripts.nagHideJs's own doc),
            // not an inherited assumption.
            buildConfigField("String", "NAG_HIDE_SELECTORS", "\"\"")
            buildConfigField("String", "NAG_HIDE_TEXT_MATCHES", "\"\"")
        }
        // vscode.dev is itself a SPA (the Monaco editor + workbench,
        // no native VS Code desktop app involved) served entirely
        // over HTTPS with no install step of its own -- exactly the
        // shape ARKware targets. No nag banner to hide here (vscode.dev
        // doesn't push an "open our app" prompt), so those two fields
        // stay empty like `template`'s.
        create("vscode") {
            dimension = "spa"
            applicationIdSuffix = ".vscode"
            resValue("string", "app_name", "VS Code")
            buildConfigField("String", "TARGET_URL", "\"https://vscode.dev\"")
            buildConfigField("String", "SPA_DISPLAY_NAME", "\"VS Code\"")
            buildConfigField("String", "NAG_HIDE_SELECTORS", "\"\"")
            buildConfigField("String", "NAG_HIDE_TEXT_MATCHES", "\"\"")
        }
        // Copy this block, rename it, and fill in your own SPA's four
        // values to scaffold a new ARKware build -- this flavor is the
        // whole per-SPA surface area, and a working (if generic)
        // target so `./gradlew assembleDebug` has something to build
        // before any real SPA-specific flavor is added.
        create("template") {
            dimension = "spa"
            applicationIdSuffix = ".template"
            resValue("string", "app_name", "ARKware")
            buildConfigField("String", "TARGET_URL", "\"https://example.com\"")
            buildConfigField("String", "SPA_DISPLAY_NAME", "\"ARKware\"")
            buildConfigField("String", "NAG_HIDE_SELECTORS", "\"\"")
            buildConfigField("String", "NAG_HIDE_TEXT_MATCHES", "\"\"")
        }
    }

    // Signing config (keystore path/passwords) is the project owner's
    // own concern, passed through via env vars. Locally, with these
    // unset, `./gradlew assembleRelease` still works, it just produces
    // an unsigned APK you'd sign yourself.
    // `isNullOrBlank()` (not just a null check) matters here because
    // CI sets this from a GitHub Actions secret via an `env:` block --
    // when that secret isn't configured, the expression evaluating it
    // resolves to an *empty string*, not an unset var, so a plain
    // `!= null` check would still (wrongly) try `file("")` and fail
    // the build instead of falling back to unsigned, same as the
    // local no-env-vars-at-all case does.
    val releaseStorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    // Same optional, env-var-driven pattern as release below, but for
    // the debug keystore. Left unset, Gradle falls back to its own
    // implicit auto-generated ~/.android/debug.keystore like before --
    // this only kicks in when DEBUG_KEYSTORE_PATH is actually set (CI,
    // via the decoded repo secret).
    val debugStorePath = System.getenv("DEBUG_KEYSTORE_PATH")
    signingConfigs {
        if (!releaseStorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
        if (!debugStorePath.isNullOrBlank()) {
            getByName("debug") {
                storeFile = file(debugStorePath)
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DEBUG_KEYSTORE_ALIAS")
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!releaseStorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
        // Required on AGP 8+ (BuildConfig generation is opt-in by
        // default) -- SpaConfig.kt reads the four per-flavor fields
        // declared above via BuildConfig.
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Splash screen (AndroidX backport of the Android 12 SplashScreen
    // API) -- gives a consistent splash on API 24+ instead of only
    // API 31+, via Theme.ArkwareApp.Starting below.
    implementation("androidx.core:core-splashscreen:1.0.1")
    // MediaSessionCompat/MediaMetadataCompat/PlaybackStateCompat, the
    // MediaStyle notification helper, and MediaButtonReceiver -- see
    // MediaPlaybackService.
    implementation("androidx.media:media:1.7.0")
}
