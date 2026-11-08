"""Локальная конвертация ручной TSV-разметки и аудит настоящего набора."""

import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import wave

from . import schema


FIELDS = ["speaker_id", "start_ms", "end_ms", "text"]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def convert(path: Path) -> dict:
    """Числа вводит человек; скрипт не придумывает интервалы или текст."""
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t", strict=True)
        if reader.fieldnames != FIELDS:
            raise schema.InputError(f"TSV: нужен заголовок с табуляцией: {FIELDS}")
        rows = []
        for line, row in enumerate(reader, 2):
            if set(row) != set(FIELDS) or any(v is None for v in row.values()):
                raise schema.InputError(f"TSV строка {line}: нужно ровно четыре поля")
            for key in ("start_ms", "end_ms"):
                value = row[key].strip()
                if not re.fullmatch(r"0|[1-9][0-9]*", value):
                    raise schema.InputError(f"TSV строка {line}, {key}: нужны целые миллисекунды")
                row[key] = int(value)
            rows.append(row)
    result = {"transcript": rows}
    schema.transcript(result)
    return result


def text(value, context):
    if not isinstance(value, str) or not value.strip():
        raise schema.InputError(f"{context}: нужна непустая строка")
    return value


def wav_info(path: Path) -> dict:
    with wave.open(str(path), "rb") as stream:
        rate, channels = stream.getframerate(), stream.getnchannels()
        width, frames = stream.getsampwidth(), stream.getnframes()
        if (rate, channels, width, stream.getcomptype()) != (16000, 1, 2, "NONE"):
            raise schema.InputError("Рабочее аудио должно быть PCM WAV, 16000 Гц, mono, 16 бит")
        actual_bytes = 0
        for chunk in iter(lambda: stream.readframes(65536), b""):
            actual_bytes += len(chunk)
        if frames <= 0 or actual_bytes != frames * channels * width:
            raise schema.InputError("WAV пуст или повреждён: данные не соответствуют числу сэмплов")
    return dict(sample_rate_hz=rate, channels=channels, sample_width_bytes=width,
                frame_count=frames, duration_seconds=frames / rate)


def cross_speaker_overlap(rows: list[schema.Utterance]) -> bool:
    events = defaultdict(Counter)
    for row in rows:
        events[row["start_ms"]][row["speaker_id"]] += 1
        events[row["end_ms"]][row["speaker_id"]] -= 1
    active, previous = Counter(), None
    for time in sorted(events):
        if previous is not None and time > previous and sum(v > 0 for v in active.values()) >= 2:
            return True
        active.update(events[time])
        previous = time
    return False


def validate(path: Path, require_verified=False) -> dict:
    metadata = schema.load_json(path)
    schema.keys(metadata, ["recording_id", "scenario", "audio_file", "audio_sha256", "reference_file",
                           "reference_sha256", "source", "recording", "annotation", "audio"])
    text(metadata["recording_id"], "recording_id")
    scenario = metadata["scenario"]
    if scenario not in ("A", "B", "C"):
        raise schema.InputError("scenario: нужен A, B или C")
    paths = {}
    for kind in ("audio", "reference"):
        paths[kind] = path.parent / text(metadata[f"{kind}_file"], f"{kind}_file")
        expected = metadata[f"{kind}_sha256"]
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise schema.InputError(f"{kind}_sha256: нужен SHA-256, 64 строчных hex-символа")
        if sha256(paths[kind]) != expected:
            raise schema.InputError(f"{kind}: SHA-256 не совпадает")
    source = metadata["source"]
    schema.keys(source, ["kind", "description", "rights_holder", "license", "rights_reviewed",
                         "rights_file", "rights_sha256", "publication_allowed"])
    if source["kind"] not in ("team_recording", "licensed_external"):
        raise schema.InputError("source.kind: team_recording или licensed_external")
    for key in ("description", "rights_holder", "license", "rights_file"):
        text(source[key], f"source.{key}")
    if source["rights_reviewed"] is not True:
        raise schema.InputError("Нужна ручная проверка прав: source.rights_reviewed=true")
    if type(source["publication_allowed"]) is not bool:
        raise schema.InputError("source.publication_allowed: нужен bool")
    if not isinstance(source["rights_sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", source["rights_sha256"]):
        raise schema.InputError("source.rights_sha256: нужен SHA-256 файла согласий/лицензии")
    if sha256(path.parent / source["rights_file"]) != source["rights_sha256"]:
        raise schema.InputError("SHA-256 документа о правах не совпадает")
    recording = metadata["recording"]
    schema.keys(recording, ["device", "conditions", "recorded_at"])
    for key in recording:
        text(recording[key], f"recording.{key}")
    annotation = metadata["annotation"]
    schema.keys(annotation, ["status", "annotators", "reviewers", "tool", "policy", "word_timestamps", "notes"])
    if annotation["status"] not in ("draft", "verified"):
        raise schema.InputError("annotation.status: draft или verified")
    if require_verified and annotation["status"] != "verified":
        raise schema.InputError("Разметка ещё draft; требуется ручная проверка")
    for key in ("annotators", "reviewers"):
        people = annotation[key]
        if not isinstance(people, list) or any(not isinstance(p, str) or not p.strip() for p in people):
            raise schema.InputError(f"annotation.{key}: нужен список непустых псевдонимов")
        if len(people) != len(set(people)) or (key == "annotators" and not people):
            raise schema.InputError(f"annotation.{key}: нужны уникальные участники, annotators не пуст")
    for key in ("tool", "policy", "notes"):
        text(annotation[key], f"annotation.{key}")
    if annotation["word_timestamps"] != "unavailable":
        raise schema.InputError("Пословный валидатор пока не реализован: word_timestamps должен быть unavailable")
    info = wav_info(paths["audio"])
    schema.keys(metadata["audio"], list(info))
    for key, expected in info.items():
        actual = metadata["audio"][key]
        if key == "duration_seconds":
            schema.number(actual, key, positive=True)
            matches = math.isclose(actual, expected, rel_tol=0, abs_tol=1e-9)
        else:
            matches = type(actual) is int and actual == expected
        if not matches:
            raise schema.InputError(f"audio.{key}: ожидается {expected}, получено {actual}")
    rows = schema.transcript(schema.load_json(paths["reference"]))
    if not rows or any(not r["text"].strip() for r in rows):
        raise schema.InputError("Реальный диалог не должен быть пустым или содержать пустые тексты реплик")
    if any(r["end_ms"] * info["sample_rate_hz"] > info["frame_count"] * 1000 for r in rows):
        raise schema.InputError("Реплики выходят за длительность WAV")
    speakers = {r["speaker_id"] for r in rows}
    if len(speakers) not in ((2,) if scenario == "A" else (2, 3)):
        raise schema.InputError("Нужно 2 спикера для A или 2–3 спикера для B/C")
    overlap = cross_speaker_overlap(rows)
    if scenario == "C" and not overlap:
        raise schema.InputError("Сценарий C требует размеченного перекрытия разных спикеров")
    independent = bool(set(annotation["reviewers"]) - set(annotation["annotators"]))
    warnings = []
    if not independent:
        warnings.append("Нет независимого второго разметчика; нужна проверка спорных фрагментов")
    if annotation["status"] == "draft":
        warnings.append("Draft не является проверенным эталоном")
    if not source["publication_allowed"]:
        warnings.append("Публикация запрещена: храните аудио, эталоны и документы о правах вне публичного Git")
    return dict(recording_id=metadata["recording_id"], scenario=scenario, annotation_status=annotation["status"],
                independent_review=independent, speakers=len(speakers), utterances=len(rows),
                cross_speaker_overlap=overlap, audio=info, warnings=warnings,
                limitation="Проверяется согласованность файлов, не достоверность речи, разметки или юридических прав")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    converter = subparsers.add_parser("convert", help="Ручной TSV → строгий reference.json")
    converter.add_argument("--annotations", type=Path, required=True)
    converter.add_argument("--output", type=Path, required=True)
    validator = subparsers.add_parser("validate", help="Проверить WAV, SHA-256, эталон, права и метаданные")
    validator.add_argument("--metadata", type=Path, required=True)
    validator.add_argument("--require-verified", action="store_true")
    inspector = subparsers.add_parser("inspect", help="Получить реальные свойства WAV и SHA-256 для метаданных")
    for name in ("audio", "reference", "rights"):
        inspector.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "convert":
            result = convert(args.annotations)
            # Создаём только новый файл: никогда не затираем оригинал разметки.
            with args.output.open("x", encoding="utf-8") as stream:
                stream.write(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
            print(f"Сохранено реплик: {len(result['transcript'])}")
        elif args.command == "inspect":
            print(json.dumps(dict(audio=wav_info(args.audio), audio_sha256=sha256(args.audio),
                                  reference_sha256=sha256(args.reference), rights_sha256=sha256(args.rights)), indent=2))
        else:
            print(json.dumps(validate(args.metadata, args.require_verified), ensure_ascii=False, indent=2))
    except (schema.InputError, OSError, UnicodeError, ValueError, csv.Error, wave.Error, EOFError) as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
