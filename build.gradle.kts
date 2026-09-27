plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // KSP: used for Room's annotation processor (Phase 2). Chosen over
    // kapt because it's faster and is Room's recommended path.
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
