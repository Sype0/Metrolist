-dontobfuscate
-keepattributes SourceFile,LineNumberTable,RuntimeVisibleAnnotations,AnnotationDefault

## Kotlin Serialization
-if @kotlinx.serialization.Serializable class **
-keepclasseswithmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclasseswithmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclasseswithmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# QuickJS (used by the InnerTubeX signature solver) is reached through JNI.
-keep class com.dokar.quickjs.** { *; }

-dontwarn javax.servlet.ServletContainerInitializer
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn java.beans.**

-keep class kotlin.Metadata { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
