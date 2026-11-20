# No reflection-based code in the app. Keep line numbers for crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Native code calls into these classes by name.
-keep class com.frostwire.jlibtorrent.** { *; }
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-keep class com.wireguard.android.backend.** { *; }
-keep class com.wireguard.crypto.** { *; }
-dontwarn java.awt.**
-dontwarn javax.annotation.**
