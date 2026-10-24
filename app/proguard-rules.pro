# JNI: native methods are bound by name, and native code calls the Callbacks methods by name.
-keepclasseswithmembernames,includedescriptorclasses class com.matt.dabtuner.backend.omri.** {
    native <methods>;
}
-keep interface com.matt.dabtuner.backend.omri.OmriNative$Callbacks { *; }
-keepclassmembers class * implements com.matt.dabtuner.backend.omri.OmriNative$Callbacks { *; }
