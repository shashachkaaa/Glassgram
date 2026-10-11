# AliuHook native libraries

`libaliuhook.so`, `liblsplant.so` and `libc++_shared.so` from
[AliuHook](https://github.com/Aliucord/hook) 1.1.4 (`com.aliucord:Aliuhook:1.1.4`, Open Software
License 3.0; LSPlant is LGPL-3.0), unchanged. The Java part of the library lives in
`src/main/java/de/robv/android/xposed`, with a fix for a race in `XposedBridge.HookInfo`: a
method called on another thread while its hook was still being installed crashed with a
NullPointerException in `XposedBridge.invokeMethod`.
