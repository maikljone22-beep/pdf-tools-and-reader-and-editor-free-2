# Libraries reached through JNI / reflection: keep them whole.
-keep class com.tom_roush.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.googlecode.tesseract.** { *; }
-keep class com.googlecode.leptonica.** { *; }
-keep class io.legere.pdfiumandroid.** { *; }
-keep class com.shockwave.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Our own print bridge lives in android.print to reach package-private callbacks
-keep class android.print.** { *; }
# kotlinx serialization / Room generated code
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class com.smartdocs.editor.core.data.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
-dontwarn com.tom_roush.**
-dontwarn org.slf4j.**
-dontwarn java.awt.**
-dontwarn javax.annotation.**

-keep class com.smartdocs.editor.BuildConfig { *; }

# Rhino (form JavaScript, forms5b)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**

# ONNX Runtime (PRO_OCR_r5): JNI looks classes and fields up by name
-keep class ai.onnxruntime.** { *; }
