# The widget runner's UniFFI bindings call native code through JNA, which finds methods,
# structure fields and callbacks by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class ai.opencode.widget.** { *; }
-dontwarn java.awt.**
