"""Строгая проверка экспорта и вспомогательных JSON-файлов."""

import json
import math
from pathlib import Path
import re
from typing import TypedDict


class Utterance(TypedDict):
    """Реплика строгого экспортного контракта; время в миллисекундах."""

    speaker_id: str
    start_ms: int
    end_ms: int
    text: str


class InputError(ValueError):
    """Ошибка входных данных, пригодная для показа пользователю."""


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InputError(f"Повторяющийся JSON-ключ: {key}")
        result[key] = value
    return result


def _constant(value):
    raise InputError(f"Недопустимая JSON-константа: {value}")


def load_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"),
                          object_pairs_hook=_pairs, parse_constant=_constant)
    except (OSError, UnicodeError, ValueError, RecursionError) as exc:
        raise InputError(f"{path}: {exc}") from exc


def keys(value, required, optional=(), context="JSON"):
    if not isinstance(value, dict):
        raise InputError(f"{context}: ожидается объект")
    missing = set(required) - value.keys()
    extra = value.keys() - set(required) - set(optional)
    if missing or extra:
        raise InputError(f"{context}: отсутствуют поля {sorted(missing)}, лишние поля {sorted(extra)}")


def transcript(value) -> list[Utterance]:
    keys(value, ["transcript"])
    rows = value["transcript"]
    if not isinstance(rows, list):
        raise InputError("transcript: ожидается массив")
    previous = -1
    for index, row in enumerate(rows):
        ctx = f"transcript[{index}]"
        keys(row, ["speaker_id", "start_ms", "end_ms", "text"], context=ctx)
        if not isinstance(row["speaker_id"], str) or not re.fullmatch(r"speaker_[1-9][0-9]*", row["speaker_id"]):
            raise InputError(f"{ctx}.speaker_id: ожидается speaker_N, N >= 1")
        start, end = row["start_ms"], row["end_ms"]
        if type(start) is not int or type(end) is not int or not 0 <= start < end:
            raise InputError(f"{ctx}: нужны целые 0 <= start_ms < end_ms (не bool)")
        if start < previous:
            raise InputError(f"{ctx}: реплики должны быть отсортированы по start_ms")
        if not isinstance(row["text"], str):
            raise InputError(f"{ctx}.text: ожидается строка")
        previous = start
    return rows


def number(value, name, positive=False):
    if type(value) not in (int, float):
        raise InputError(f"{name}: ожидается конечное число")
    try:
        valid = math.isfinite(value) and (value > 0 if positive else value >= 0)
    except OverflowError:
        valid = False
    if not valid:
        raise InputError(f"{name}: ожидается конечное {'положительное' if positive else 'неотрицательное'} число")
    return value


def measurement(value):
    keys(value, ["audio_duration_seconds"],
         ["processing_time_seconds", "model", "variant", "quantization", "device", "threads",
          "peak_memory_mb", "notes"], context="measurement")
    number(value["audio_duration_seconds"], "audio_duration_seconds", positive=True)
    for name in ("processing_time_seconds", "peak_memory_mb"):
        if value.get(name) is not None:
            number(value[name], name)
    for name in ("model", "variant", "quantization", "device", "notes"):
        if name in value and not isinstance(value[name], str):
            raise InputError(f"{name}: ожидается строка")
    if "threads" in value and (type(value["threads"]) is not int or value["threads"] < 1):
        raise InputError("threads: ожидается положительное целое число")
    if value.get("processing_time_seconds") is not None:
        for name in ("model", "variant", "quantization", "device", "threads"):
            if name not in value or value[name] == "":
                raise InputError(f"Для измеренного времени обязательно поле {name}")
    return value
