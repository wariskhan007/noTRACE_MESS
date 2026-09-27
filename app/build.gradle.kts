plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.yourapp" // Replace with your actual package name
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.yourapp" // Replace with your actual application ID
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // Required for libsignal-android
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 1. Required for Java 8+ API desugaring (Fixes the build error)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // 2. Your Signal library dependency
    implementation("org.signal:libsignal-android:0.86.5")

    // 3. Essential AndroidX dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 4. Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
