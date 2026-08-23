# OpenCV Android 4.5.3 arm64 16 KB build

`opencv-4.5.3-android-16k-arm64.aar` starts from the `com.quickbirdstudios:opencv:4.5.3` AAR and replaces only:

- `jni/arm64-v8a/libopencv_java4.so`
- `jni/arm64-v8a/libc++_shared.so`

The OpenCV library is built from the official OpenCV `4.5.3` tag at commit `ad6e82942b37be8ee2c71c1d9bc7fe79cd16f7ab`, with Android NDK `28.2.13676358`, API 29, and these modules: `core,imgproc,imgcodecs,java`. Both replacement ELF files use 16 KB LOAD alignment. The other AAR entries and ABIs remain byte-for-byte from the original dependency.

Build linker flags:

```text
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
```

SHA-256:

- patched AAR: `8d310bbcce091b92f5c7789219e9299c444d72dcfac139813e5f7b4c82670992`
- arm64 `libopencv_java4.so`: `2e757eba4711ad5115629b9d176583d1712c1ffe9e21c7feffc1c076ebe46302`
- arm64 `libc++_shared.so`: `ab4e6c71b96b851de45a8a9bd86369e7dbc2130a44b3b4520564be94847910f2`
- `OPENCV-LICENSE`: `3ddf9be5c28fe27dad143a5dc76eea25222ad1dd68934a047064e56ed2fa40c5`

The rebuild follows Android's official 16 KB page-size guidance: https://developer.android.com/guide/practices/page-sizes
