# NoTrace Messenger release ProGuard/R8 rules.

# --- Phase 2: SQLCipher + Room ---
# SQLCipher's native/JNI bridge classes must not be renamed/stripped.
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.* { *; }

# Room entities/DAOs are accessed via generated code and reflection at
# the edges; keep annotated classes so field names survive.
-keep class com.notrace.messenger.storage.db.** { *; }
-keep class com.notrace.messenger.identity.data.** { *; }
-keep class com.notrace.messenger.crypto.data.** { *; }
-keep class com.notrace.messenger.attachment.data.** { *; }
-keep class com.notrace.messenger.group.data.** { *; }

# --- Phase 4: libsignal ---
# libsignal-android uses JNI; its native bridge classes and the public
# protocol API must survive un-renamed or native calls break at runtime.
-keep class org.signal.libsignal.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Future phases MUST add keep-rules here for WebRTC as that dependency
# is introduced, with a comment explaining why.

# --- Phase 5: WebRTC + OkHttp ---
# WebRTC uses JNI extensively; its native bridge and public API must
# survive un-renamed or native calls break at runtime.
-keep class org.webrtc.** { *; }

# OkHttp/Okio use some reflection and R8 has known false-positive warnings
# for optional platform-specific classes that aren't actually used here.
-dontwarn okhttp3.**
-dontwarn okio.**
