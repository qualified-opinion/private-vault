# releaseCheck only (never the release APK). The androidTest APK calls some of our own classes
# directly; R8 would otherwise inline or remove those entry points, because the app only reaches
# them through other call paths. This file keeps the public API of our own packages and nothing else.
#
# Why this can't hide a release-only R8 problem in argon2kt or Tink: R8 breaks those libraries by
# renaming or removing members that only native code (argon2kt's JNI writes
# ByteBufferTarget.byteBuffer) or reflection (protobuf-lite reads message fields) touch. Keeping
# our classes keeps what they *statically* reference, which R8 keeps in release too. JNI- and
# reflection-only members are covered by :core:crypto/consumer-rules.pro alone, in both builds.
-keep public class com.qualifiedopinion.privatevault.vault.** { public *; }
-keep public class com.qualifiedopinion.privatevault.crypto.** { public *; }
-keep public class com.qualifiedopinion.privatevault.format.** { public *; }
-keep public class com.qualifiedopinion.privatevault.PrivateVaultApp { public *; }
-keep public class com.qualifiedopinion.privatevault.AppGraph { public *; }

# Libraries the androidTest APK shares with the app. AGP leaves them out of the test APK because the
# app already has them, and R8 strips from the app whatever the app itself doesn't call, which
# androidx.test and the tests then miss at run time (NoSuchMethodError). None of these is involved
# in argon2kt's JNI or Tink's protobuf reflection. scripts/check-releasecheck-refs.sh verifies the
# combined program has no missing references.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.concurrent.futures.** { *; }
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
-keep class androidx.tracing.** { *; }
