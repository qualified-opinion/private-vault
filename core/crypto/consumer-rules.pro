# R8 rules for apps that use :core:crypto (planning pack §6 PR3: release-build unlock verification).

# --- argon2kt 1.6.0 ships no shrinker rules ------------------------------------------------------
# libargon2jni.so does FindClass("com/lambdapioneer/argon2kt/ByteBufferTarget") and writes the
# result into its "byteBuffer" field (Ljava/nio/ByteBuffer;) with GetFieldID/SetObjectField.
# No Java code writes that field, so R8 could rename or remove it, or fold getByteBuffer() to null,
# and every hash would then fail in release.
# Today that is prevented only indirectly: argon2kt annotates the class with @androidx.annotation.Keep,
# and the androidx-annotation jar (here only because Tink depends on it) ships the matching R8 rule.
# Checked in PR3: without this line the field still survives. This line removes the dependency on that
# transitive rule.
-keep class com.lambdapioneer.argon2kt.ByteBufferTarget { *; }
# JNI entry points are Java_com_lambdapioneer_argon2kt_<Class>_<method>: keep the holder classes'
# names and their native methods (the default optimize config has a generic rule; this one is
# explicit so it doesn't depend on that file).
-keepclasseswithmembernames,includedescriptorclasses class com.lambdapioneer.argon2kt.** {
    native <methods>;
}

# --- Tink ---------------------------------------------------------------------------------------
# tink-android ships META-INF/proguard/protobuf.pro with this rule (protobuf-lite's schema reads
# message fields reflectively). Repeated here so a Tink update that drops the file can't break
# keyset parsing in release without anyone noticing.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
  <fields>;
}
