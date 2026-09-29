# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ---------------------------------------------------------------------------
# Skip: no project-level keep rules are required today.
#
# `isMinifyEnabled` and `isShrinkResources` are enabled for release, and this
# file intentionally stays empty of keep rules:
#   - Room ships its consumer rules; the generated DAO/database classes and the
#     @Entity data classes are covered by them.
#   - Compose ships its consumer rules.
#   - The app has no reflection-based lookups, no WebView JS bridge, no
#     serialization library that needs class-name preservation, and no JNI.
#
# Do not add speculative `-keep` rules: they silently defeat shrinking. If a
# release-only crash appears, add the narrowest rule that fixes it and record
# the reason here.
#
# Verification requirement: RELEASE_TEST_MATRIX.md expects an R8/release smoke
# test on a real device before a release is called ready. The matrix currently
# records that step as untested, so R8 behaviour is NOT verified.
# ---------------------------------------------------------------------------

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile