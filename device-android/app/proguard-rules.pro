# Project-specific release rules. AndroidX, Room and WebRTC dependencies supply consumer rules.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# SQLCipher JNI resolves its Java API by the original class and method names.
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
