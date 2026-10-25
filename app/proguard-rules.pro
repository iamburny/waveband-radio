# JNI: native methods are bound by name, and native code calls the Callbacks methods by name.
-keepclasseswithmembernames,includedescriptorclasses class com.iamburny.waveband.backend.omri.** {
    native <methods>;
}
-keep interface com.iamburny.waveband.backend.omri.OmriNative$Callbacks { *; }
-keepclassmembers class * implements com.iamburny.waveband.backend.omri.OmriNative$Callbacks { *; }
