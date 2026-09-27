plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

ksp {
    // Exports Room's schema JSON per version (exportSchema = true on
    // NoTraceDatabase) so migrations can eventually be tested against
    // real prior-version schemas, not just asserted by inspection.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.notrace.messenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notrace.messenger"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // --- Secure debug/release separation (plan Section 22) ---
    signingConfigs {
        // Release signing is intentionally NOT configured here.
        // No keystore, no signing credentials are committed to this repo.
        // CI builds an unsigned/debug-signed APK only. Release signing is a
        // manual, local step performed by Hrink when ready to publish, per
        // plan Section 22 ("Protect release signing keys").
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Debug logging is allowed to exist in debug builds ONLY.
            // Plan rule #8 (never log plaintext/keys) still applies even here.
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No test/demo credentials, no verbose logging in release (plan rule #3/#8).
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
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // required by Robolectric (Phase 2 storage tests)
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- Phase 2: secure local storage ---
    // Room: structured local persistence with typed DAOs and a
    // migration framework (plan Section 6 "must support migrations").
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // SQLCipher: transparent AES-256 encryption of the SQLite file
    // itself (encryption at rest for the whole Room database), bridged
    // into Room's SupportSQLite interface. Chosen because it is a
    // mature, widely audited encrypted-SQLite implementation rather
    // than a homegrown encrypt-on-write layer (plan rule #4 "never
    // invent cryptography").
    implementation("net.zetetic:android-database-sqlcipher:4.5.6")
    implementation("androidx.sqlite:sqlite:2.4.0")

    // Jetpack Security: Keystore-backed MasterKey + EncryptedSharedPreferences
    // (holds the random SQLCipher passphrase, never the passphrase in
    // plaintext prefs) and EncryptedFile (for future encrypted
    // attachments in Phase 7). Hardware-backed where the device supports it.
    // Bumped from 1.1.0-alpha06 to the stable 1.1.0 release (Phase 13
    // security review) - no API changes were needed for this project's
    // usage (MasterKey + EncryptedSharedPreferences), but shipping an
    // alpha crypto-adjacent library in a security-focused release build
    // wasn't a risk worth carrying once a stable release existed.
    implementation("androidx.security:security-crypto:1.1.0")

    // --- Phase 12: performance and reliability ---
    // WorkManager: Android's recommended API for guaranteed, constraint-
    // aware DEFERRABLE background work (plan Section 12 "WorkManager for
    // deferrable work"). Used for exactly one job (MaintenanceWorker) -
    // pruning orphaned incomplete attachment transfers that will never
    // finish (e.g. the peer went offline permanently mid-transfer) -
    // NOT for anything latency-sensitive: its minimum periodic interval
    // is 15 minutes, far too coarse for Phase 11's disappearing-message
    // sweep (which deliberately stays a lightweight in-process loop
    // instead - see MessageExpiryManager's class doc for that reasoning).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- Phase 4: secure one-to-one messaging ---
    // The Signal Protocol reference implementation: identity keys, X3DH
    // key agreement, the Double Ratchet, per-message keys, replay
    // protection, and secure session state - satisfying plan Section 5
    // ("do not invent cryptography... must use a mature, actively
    // maintained... protocol/library"). This is Signal's own production
    // library (Maven Central, actively maintained).
    //
    // LICENSE NOTE: libsignal-android is AGPLv3. Linking it means
    // NoTrace itself takes on AGPL's copyleft obligation: anyone you
    // distribute the app to must be able to get NoTrace's own source.
    // See LICENSE_NOTES.md at the repo root - this was an explicit,
    // discussed decision, not a default I picked quietly.
    implementation("org.signal:libsignal-android:0.86.5")

    // --- Phase 5: signaling and P2P ---
    // WebRTC: Google's own library hasn't published to Maven Central
    // directly in years (deprecated JCenter distribution). This is an
    // actively maintained, widely used (LiveKit and others build on it)
    // prebuilt mirror published to Maven Central - avoids a from-source
    // WebRTC build (a much bigger undertaking than the vodozemac
    // build we already ruled out in Phase 4 for the same reason).
    // Bumped from 114.5735.10 (Chromium/WebRTC M114, mid-2023) to a
    // current release (Phase 13 security review, priority-1 finding):
    // the old version was ~30 point releases and roughly 2.5 years
    // behind upstream, and WebRTC's native media/ICE parsing surface is
    // exactly where memory-safety CVEs accumulate in old builds. Verify
    // this against https://github.com/webrtc-sdk/android/releases at
    // build time in case a newer patch has shipped since this was
    // written, and re-run the Phase 9/8 call tests after upgrading -
    // ICE/SDP behavior can shift between major M-versions.
    implementation("io.github.webrtc-sdk:android:144.7559.09")

    // OkHttp: for the signaling WebSocket connection. Mature, extremely
    // widely used, simple synchronous-callback WebSocket API - not a
    // cryptography library, so it doesn't raise the same "never invent
    // cryptography" concerns as libsignal/WebRTC do.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // NOTE: No networking/WebRTC/libsignal dependencies yet — those
    // are introduced in Phases 4-5 with their own justification.
}
