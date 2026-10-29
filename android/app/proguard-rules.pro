# JNI resolves this object and method by their exported native names.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Keep source positions for private mapping-based crash retracing.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
