# ONNX Whisper Setup Guide

This document explains how to get the built-in offline Whisper input method working in Dicio Lean.
There are two independent setup steps: one for the **AAR library** (compile-time), and one for
the **model files** (runtime).

---

## Step 1 — Add the sherpa-onnx AAR

The Kotlin API and native JNI libraries ship as a single AAR. AGP 8+ forbids raw `.aar` files
in sub-modules that publish their own AAR, but since `app/` builds an APK this restriction
does not apply here; `fileTree` works without any Maven ceremony.

### 1a. Download the AAR

Go to the [sherpa-onnx releases page](https://github.com/k2-fsa/sherpa-onnx/releases) and
find the latest non-pre-release. Look for the file:

```
sherpa-onnx-<version>.aar
```

> If you don't see a standalone `.aar` on the releases page, use JitPack to build one:
> ```
> https://jitpack.io/com/github/k2-fsa/sherpa-onnx/<version>/sherpa-onnx-<version>.aar
> ```
> Substitute `<version>` with a tag such as `v1.13.2`.  JitPack builds it on first request
> (~2 min); refresh the URL after that to download it.
>
> **Tested with sherpa-onnx 1.13.2.**

### 1b. Place the AAR

Create the directory `app/libs/` if it does not exist, then copy the file there and rename it:

```
app/
└── libs/
    └── sherpa-onnx.aar    ← rename to exactly this
```

The `app/build.gradle.kts` already declares:

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
```

So Gradle picks it up automatically on the next sync. No changes to `settings.gradle.kts` are
needed.

### 1c. Verify

After syncing, build the project. You should see no "Unresolved reference" errors for
`com.k2fsa.sherpa.onnx.*` classes.

---

## Step 2 — Place the model files on the device

The model is **not bundled** inside the APK. It must be placed in the app's internal files
directory at runtime. This keeps the APK small and lets you swap models without reinstalling.

### 2a. Download the model

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2
```

Extract the archive. You will find these files (among others):

```
sherpa-onnx-whisper-tiny/
├── tiny-encoder.onnx          ← float32, not used
├── tiny-encoder.int8.onnx     ← used ✓
├── tiny-decoder.onnx          ← float32, not used
├── tiny-decoder.int8.onnx     ← used ✓
├── tiny-tokens.txt            ← used ✓
└── ...
```

### 2b. Push the files to the device

The app reads from:

```
/data/data/org.stypox.dicio/files/sherpa-onnx-whisper-tiny/
```

(For debug builds the path is `org.stypox.dicio.<branch>` due to the `applicationIdSuffix`.)

Use ADB to push only the three files the app needs:

```powershell
# PowerShell — run from the folder where you extracted the archive

$pkg = "org.stypox.dicio"        # change to debug suffix if needed
$dest = "/data/data/$pkg/files/sherpa-onnx-whisper-tiny"

adb shell mkdir -p $dest
adb push sherpa-onnx-whisper-tiny\tiny-encoder.int8.onnx "$dest/tiny-encoder.int8.onnx"
adb push sherpa-onnx-whisper-tiny\tiny-decoder.int8.onnx "$dest/tiny-decoder.int8.onnx"
adb push sherpa-onnx-whisper-tiny\tiny-tokens.txt        "$dest/tiny-tokens.txt"
```

You can verify with:

```powershell
adb shell ls -lh $dest
```

### 2c. Enable the input method

Open Dicio → **Settings → Input method** → select **Built-in Whisper (offline)**.

The first time you tap the microphone button the recognizer loads (~1–2 s on a mid-range device).
Subsequent taps start listening immediately.

---

## File size reference

| File                       | Size (approx.) |
|----------------------------|----------------|
| `tiny-encoder.int8.onnx`   | ~14 MB         |
| `tiny-decoder.int8.onnx`   | ~25 MB         |
| `tiny-tokens.txt`          | ~75 KB         |
| sherpa-onnx AAR (all ABIs) | ~55 MB         |

---

## How the runtime integration works

```
Microphone (16 kHz mono PCM)
        │
        ▼
  Energy VAD loop (100 ms chunks)
        │  speech detected → accumulate samples
        │  silence > 1.5 s → break
        ▼
  sherpa-onnx OfflineRecognizer
    encoder: tiny-encoder.int8.onnx
    decoder: tiny-decoder.int8.onnx
    tokens:  tiny-tokens.txt
    language: "he" or "en" (mirrors the app locale; iw→he remapped)
    task:    "transcribe"
        │
        ▼
  InputEvent.Final(listOf(Pair(text, 1.0f)))
        │
        ▼
  SkillEvaluator (unchanged)
```

The VAD parameters (silence duration, max recording, speech energy threshold) are constants in
`OnnxWhisperInputDevice`. Adjust them there if you find the model cuts off too early or waits
too long.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| State stays at `NotDownloaded` | Model files missing or wrong path | Re-check Step 2b paths |
| `ErrorLoading` on first tap | Wrong ABI in AAR / JNI not found | Ensure the AAR contains `arm64-v8a` libs; check `adb logcat` for `UnsatisfiedLinkError` |
| `ErrorLoading` — file not found | Path typo in model dir | `adb shell ls` to verify exact filenames |
| SIGBUS crash in `libonnxruntime.so` | Model files are in `assets/` and compressed | Model must be in `filesDir`, not assets (already the case in this integration) |
| Empty transcription for Hebrew | Language not set to Hebrew in app settings | Ensure Settings → Language = עברית, or system locale is Hebrew |
