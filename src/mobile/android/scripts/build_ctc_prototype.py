#!/usr/bin/env python3
"""Сборка исследовательского AAR с экспортом CTC logits; основной AAR не изменяется."""

import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

from prepare_models import ROOT, digest, properties

SOURCE_URL = "https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/refs/tags/v2.0.1"
SOURCE_SHA = "24ecc0fccf6344c2556e7c229c849e9f61bf9456b915615197d85f35e3f177e7"
ORT_URL = "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip"
ORT_SHA = "01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d"


def download(url, checksum, target):
    if target.is_file() and digest(target) == checksum:
        return
    partial = target.with_suffix(target.suffix + ".partial")
    subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--max-time", "300",
                    url, "--output", str(partial)], check=True)
    if digest(partial) != checksum:
        raise ValueError(f"Неверный SHA-256 {url}")
    os.replace(partial, target)


def main():
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    ndk = sdk / "ndk/28.2.13676358"
    if not (ndk / "build/cmake/android.toolchain.cmake").is_file():
        raise ValueError("Нужен Android NDK 28.2.13676358; задайте ANDROID_HOME")
    cache = ROOT / ".model-cache/ctc-prototype"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / "sherpa-v2.0.1.tar.gz"
    ort_archive = cache / "ort-1.28.2.zip"
    download(SOURCE_URL, SOURCE_SHA, archive)
    download(ORT_URL, ORT_SHA, ort_archive)
    patch = ROOT / "dependencies/patches/sherpa-onnx-2.0.1-ctc-probe.patch"
    # Новая папка для каждой версии патча исключает повторное наложение и старые объекты сборки.
    workspace = cache / digest(patch)[:16]
    source = workspace / "sherpa-onnx-2.0.1"
    marker = source / ".catscriptor-patch-sha256"
    if not source.exists():
        with tarfile.open(archive) as stream:
            stream.extractall(workspace, members=(m for m in stream.getmembers()
                              if m.isfile() or m.isdir()), filter="data")
        subprocess.run(["patch", "--batch", "-p1", "-i", str(patch)], cwd=source, check=True)
        marker.write_text(digest(patch))
    elif not marker.is_file() or marker.read_text() != digest(patch):
        raise ValueError(f"Незавершённая подготовка {workspace}; не используйте её как проверенную сборку")
    ort = cache / "ort"
    with zipfile.ZipFile(ort_archive) as stream:
        stream.extractall(ort)
    env = dict(os.environ,
               GIT_CEILING_DIRECTORIES=str(workspace),
               SHERPA_ONNXRUNTIME_LIB_DIR=str(ort / "jni/arm64-v8a"),
               SHERPA_ONNXRUNTIME_INCLUDE_DIR=str(ort / "headers"))
    build = workspace / "build"
    options = {
        "CMAKE_TOOLCHAIN_FILE": str(ndk / "build/cmake/android.toolchain.cmake"),
        "ANDROID_ABI": "arm64-v8a", "ANDROID_PLATFORM": "android-30",
        "CMAKE_BUILD_TYPE": "Release", "BUILD_SHARED_LIBS": "ON",
        "SHERPA_ONNX_ENABLE_JNI": "ON",
    }
    for feature in ("TTS", "SPEAKER_DIARIZATION", "BINARY", "C_API", "PYTHON", "TESTS",
                    "CHECK", "PORTAUDIO", "WEBSOCKET"):
        options[f"SHERPA_ONNX_ENABLE_{feature}"] = "OFF"
    subprocess.run(["cmake", "-S", str(source), "-B", str(build), "-G", "Ninja"] +
                   [f"-D{key}={value}" for key, value in options.items()], env=env, check=True)
    subprocess.run(["cmake", "--build", str(build), "--target", "sherpa-onnx-jni", "-j", "4"], env=env, check=True)
    library = properties(ROOT / "dependencies/sherpa-onnx.properties")
    original = ROOT / "dependencies" / library["file"]
    if digest(original) != library["sha256"]:
        raise ValueError("Сначала подготовьте официальный AAR: python3 scripts/prepare_models.py")
    jni = workspace / "libsherpa-onnx-jni.so"
    shutil.copyfile(build / "lib/libsherpa-onnx-jni.so", jni)
    subprocess.run([str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"),
                    "--strip-unneeded", str(jni)], check=True)
    target = ROOT / "dependencies/sherpa-onnx-2.0.1-ctc-prototype.aar"
    temporary = workspace / "prototype.aar"
    with zipfile.ZipFile(original) as previous, zipfile.ZipFile(temporary, "w") as output:
        for entry in previous.infolist():
            if not entry.filename.startswith("jni/"):
                output.writestr(entry, previous.read(entry.filename))
        for name, path in (("libsherpa-onnx-jni.so", jni),
                           ("libonnxruntime.so", ort / "jni/arm64-v8a/libonnxruntime.so")):
            entry = zipfile.ZipInfo("jni/arm64-v8a/" + name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(entry, path.read_bytes())
    os.replace(temporary, target)
    stamp = ROOT / ".model-cache/ctc-prototype.properties"
    stamp.write_text(f"file={target.name}\nsha256={digest(target)}\n"
                     f"source.sha256={SOURCE_SHA}\nort.sha256={ORT_SHA}\npatch.sha256={digest(patch)}\n")
    print(f"Диагностический AAR: {target}\nSHA-256: {digest(target)}")
    print("Только для исследования: ./gradlew -PctcPrototype=true :app:assembleDebug")


if __name__ == "__main__":
    main()
