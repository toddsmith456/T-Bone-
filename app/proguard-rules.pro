-keep class social.tbone.nostr.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# LiteRT 1.4.2 JNI entry points for offline NSFW media classification
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**
