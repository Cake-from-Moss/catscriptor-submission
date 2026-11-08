#!/usr/bin/env python3
"""Подготовка зафиксированных моделей и AAR на компьютере разработчика."""

import hashlib
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if line.strip() and not line.startswith("#"))


def digest(path):
    with path.open("rb") as stream:
        result = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
        return result.hexdigest()


def download(url, expected, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and digest(destination) == expected:
        return
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            print(f"Загрузка {url}", flush=True)
            with urllib.request.urlopen(url, timeout=120) as source:
                shutil.copyfileobj(source, output)
        if digest(temporary) != expected:
            raise ValueError(f"Неверный SHA-256: {url}")
        os.replace(temporary, destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def extract_engine(properties_path):
    """Распаковка закреплённого архива llama.cpp в dependencies/llama.cpp (версия — в .catscriptor-version)."""
    engine = properties(properties_path)
    target = ROOT / "dependencies/llama.cpp"
    marker = target / ".catscriptor-version"
    if marker.is_file() and marker.read_text().strip() == engine["version"] and (target / "CMakeLists.txt").is_file():
        return
    archive = ROOT / ".model-cache" / engine["file"]
    download(engine["url"], engine["sha256"], archive)
    temporary = ROOT / ".model-cache" / "llama.cpp-extract"
    if temporary.exists():
        shutil.rmtree(temporary)
    with tarfile.open(archive) as source:
        source.extractall(temporary)
    extracted = temporary / f"llama.cpp-{engine['version'].lstrip('v')}"
    if not (extracted / "CMakeLists.txt").is_file():
        raise ValueError(f"В архиве {engine['file']} отсутствует корень llama.cpp")
    if target.exists():
        shutil.rmtree(target)
    shutil.move(str(extracted), target)
    marker.write_text(engine["version"] + "\n")
    shutil.rmtree(temporary)


def prepare_diarization():
    """Эмбеддинги ERes2Net 3D-Speaker (и при наличии — файлы из архива); проверка по SHA-256."""
    manifest = properties(ROOT / "app/src/main/assets/diarization-manifest.properties")
    destination = ROOT / "app/src/main/assets" / manifest["model.directory"]
    destination.mkdir(parents=True, exist_ok=True)
    archive = ROOT / ".model-cache" / Path(manifest["archive.url"]).name if "archive.url" in manifest else None
    for name in manifest["files"].split(","):
        target = destination / name
        expected = manifest[f"{name}.sha256"]
        size = int(manifest[f"{name}.size"])
        if target.exists() and target.stat().st_size == size and digest(target) == expected:
            continue
        member_key = f"{name}.member"
        if member_key in manifest:
            download(manifest["archive.url"], manifest["archive.sha256"], archive)
            with tarfile.open(archive) as source:
                member = source.getmember(f"{manifest['archive.root']}/{manifest[member_key]}")
                if not member.isfile() or member.size != size:
                    raise ValueError(f"Неверный файл в архиве: {name}")
                temporary = None
                try:
                    with tempfile.NamedTemporaryFile(dir=destination, delete=False) as output:
                        temporary = Path(output.name)
                        with source.extractfile(member) as stream:
                            shutil.copyfileobj(stream, output)
                    if digest(temporary) != expected:
                        raise ValueError(f"Неверный SHA-256: {name}")
                    os.replace(temporary, target)
                finally:
                    if temporary is not None:
                        temporary.unlink(missing_ok=True)
        else:
            download(manifest[f"{name}.url"], expected, target)
            if target.stat().st_size != size:
                raise ValueError(f"Неверный размер файла диаризации: {name}")
    # Двухголосый английский пример sherpa нужен только тестовому APK (проверка смены спикера).
    sample = ROOT / "app/src/androidTest/assets/two-speakers-en.wav"
    download(manifest["test.wav.url"], manifest["test.wav.sha256"], sample)


def main():
    model = properties(ROOT / "app/src/main/assets/model-manifest.properties")
    llm = properties(ROOT / "app/src/main/assets/llm-manifest.properties")
    extract_engine(ROOT / "dependencies/llama-cpp.properties")
    library = properties(ROOT / "dependencies/sherpa-onnx.properties")
    licenses = properties(ROOT / "dependencies/licenses.properties")
    for name in licenses["files"].split(","):
        download(licenses[f"{name}.url"], licenses[f"{name}.sha256"],
                 ROOT / "app/src/main/assets/third-party" / name)
    download(library["url"], library["sha256"], ROOT / "dependencies" / library["file"])
    archive = ROOT / ".model-cache" / Path(model["archive.url"]).name
    download(model["archive.url"], model["archive.sha256"], archive)
    destination = ROOT / "app/src/main/assets" / model["model.directory"]
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        for name in model["files"].split(","):
            target = destination / name
            if target.exists() and digest(target) == model[f"{name}.sha256"]:
                continue
            member = source.getmember(f"{model['archive.root']}/{name}")
            if not member.isfile() or member.size != int(model[f"{name}.size"]):
                raise ValueError(f"Неверный файл в архиве: {name}")
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=destination, delete=False) as output:
                    temporary = Path(output.name)
                    with source.extractfile(member) as stream:
                        shutil.copyfileobj(stream, output)
                if digest(temporary) != model[f"{name}.sha256"]:
                    raise ValueError(f"Неверный SHA-256: {name}")
                os.replace(temporary, target)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        # Звуковой пример находится только в тестовом APK, не в основном приложении.
        sample = ROOT / "app/src/androidTest/assets/sherpa-example.wav"
        sample.parent.mkdir(parents=True, exist_ok=True)
        data = source.extractfile(f"{model['archive.root']}/test_wavs/example.wav").read()
        if hashlib.sha256(data).hexdigest() != model["test.wav.sha256"]:
            raise ValueError("Повреждён тестовый WAV")
        sample.write_bytes(data)
    vad = properties(ROOT / "app/src/main/assets/vad-manifest.properties")
    for name in vad["files"].split(","):
        target = ROOT / "app/src/main/assets" / vad["model.directory"] / name
        download(vad[f"{name}.url"], vad[f"{name}.sha256"], target)
        if target.stat().st_size != int(vad[f"{name}.size"]):
            raise ValueError(f"Неверный размер VAD-файла: {name}")
    llm_destination = ROOT / "app/src/main/assets" / llm["model.directory"]
    llm_destination.mkdir(parents=True, exist_ok=True)
    for name in llm["files"].split(","):
        target = llm_destination / name
        download(llm[f"{name}.url"], llm[f"{name}.sha256"], target)
        if target.stat().st_size != int(llm[f"{name}.size"]):
            raise ValueError(f"Неверный размер LLM-файла: {name}")
    prepare_diarization()
    print("AAR, ASR, VAD, LLM и диаризация подготовлены и проверены.")


if __name__ == "__main__":
    main()
