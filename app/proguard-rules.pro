# ART's JIT crashes in OkHttp's hostname code when R8 inlines the IDN mapping table's strings into it.
-keep,allowobfuscation class okhttp3.internal.idn.** { <fields>; <methods>; }
