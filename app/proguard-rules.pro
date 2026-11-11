# ---------------------------------------------------------------------------------------------
# SwapStudio AI - R8 rules
# ---------------------------------------------------------------------------------------------

# ONNX Runtime is driven almost entirely through JNI. Its native side looks classes up by name,
# so nothing in the ai.onnxruntime tree may be renamed or stripped.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# MediaPipe Tasks wraps a native engine and uses reflection for the graph config protos.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# OpenCV Java bindings: the Mat class is instantiated from C++ and every method is a JNI entry.
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Optional FFmpegKit bridge (only present when the dependency is enabled).
-dontwarn com.arthenica.ffmpegkit.**

# Kotlin metadata for reflection-free data classes is not needed, but coroutines need their
# internal service loader entries.
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep line numbers so on-device crash reports stay useful.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
