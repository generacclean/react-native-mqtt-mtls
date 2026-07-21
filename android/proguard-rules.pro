# BouncyCastle ProGuard/R8 Rules
# Prevent stripping of BouncyCastle cryptographic algorithms

# Keep all BouncyCastle classes
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep EC (Elliptic Curve) algorithm implementations
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# Keep Security Provider registration
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }

# Keep cryptographic service classes
-keepclassmembers class * extends java.security.Provider {
    <init>(...);
}

# Prevent optimization of crypto algorithms
-keepnames class org.bouncycastle.jcajce.provider.** { *; }
-keepnames class org.bouncycastle.jce.provider.** { *; }
