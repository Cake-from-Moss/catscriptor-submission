"""CLI локальной оценки; запуск модулем или непосредственно файлом."""

import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import sys
import unicodedata

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from tools.evaluation import metrics, report, schema
else:
    from . import metrics, report, schema


def source(path):
    return {"path": str(path), "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest()}


def evaluate_record(identifier, reference_path, hypothesis_path, measurement_path, collar, tolerances, keep_yo):
    reference = schema.transcript(schema.load_json(reference_path))
    hypothesis = schema.transcript(schema.load_json(hypothesis_path))
    measurement = schema.measurement(schema.load_json(measurement_path)) if measurement_path else None
    if measurement:
        duration_ms = measurement["audio_duration_seconds"] * 1000
        if any(r["end_ms"] > duration_ms for r in reference):
            raise schema.InputError(f"{identifier}: эталон выходит за длительность аудио")
    d = metrics.diarization(reference, hypothesis, collar)
    # Для диагностики границ берём перестановку без collar: короткие реплики не исчезают.
    timing_mapping = metrics.diarization(reference, hypothesis, 0)["hypothesis_to_reference"] if collar else d["hypothesis_to_reference"]
    b = metrics.boundaries(reference, hypothesis, timing_mapping, tolerances)
    b["hypothesis_to_reference"] = timing_mapping
    sources = {"reference": source(reference_path), "hypothesis": source(hypothesis_path)}
    if measurement_path:
        sources["measurement"] = source(measurement_path)
    return dict(id=identifier, sources=sources, wer=metrics.word_error(reference, hypothesis, keep_yo),
                diarization=d, boundaries=b, speed=metrics.speed(measurement))


def parser():
    p = argparse.ArgumentParser(description="Локальная оценка Catscriptor: WER, DER, границы, RTF")
    p.add_argument("--reference", type=Path)
    p.add_argument("--hypothesis", type=Path)
    p.add_argument("--measurement", type=Path, help="JSON с длительностью и измерением на Android")
    p.add_argument("--manifest", type=Path, help="Пакет записей; пути относительно manifest")
    p.add_argument("--collar-ms", type=int, default=0, help="Исключить ±N мс вокруг границ эталона из DER")
    p.add_argument("--tolerance-ms", type=int, nargs="+", default=[100, 250, 500])
    p.add_argument("--keep-yo", action="store_true")
    p.add_argument("--output", type=Path, help="Сохранить полный JSON-отчёт")
    return p


def run(args):
    if args.collar_ms < 0 or any(t < 0 for t in args.tolerance_ms):
        raise schema.InputError("Collar и допуски должны быть неотрицательными")
    if args.manifest:
        if args.reference or args.hypothesis or args.measurement:
            raise schema.InputError("--manifest нельзя совмещать с одиночными входными файлами")
        manifest = schema.load_json(args.manifest)
        schema.keys(manifest, ["recordings"], context="manifest")
        rows = manifest["recordings"]
        if not isinstance(rows, list) or not rows:
            raise schema.InputError("manifest.recordings: ожидается непустой массив")
        inputs, seen = [], set()
        for row in rows:
            schema.keys(row, ["id", "reference", "hypothesis"], ["measurement"], context="recording")
            if not isinstance(row["id"], str) or not row["id"] or row["id"] in seen:
                raise schema.InputError("recording.id: нужна уникальная непустая строка")
            seen.add(row["id"])
            paths = []
            for k in ("reference", "hypothesis", "measurement"):
                if k not in row:
                    paths.append(None)
                elif not isinstance(row[k], str) or not row[k]:
                    raise schema.InputError(f"recording.{k}: ожидается непустой путь")
                else:
                    paths.append(args.manifest.parent / row[k])
            inputs.append((row["id"], *paths))
    else:
        if not args.reference or not args.hypothesis:
            raise schema.InputError("Нужны --reference и --hypothesis либо --manifest")
        inputs = [(args.reference.stem, args.reference, args.hypothesis, args.measurement)]
    if args.output:
        protected = [p.resolve() for entry in inputs for p in entry[1:] if p is not None]
        if args.manifest:
            protected.append(args.manifest.resolve())
        if args.output.resolve() in protected or (args.output.exists() and any(
                path.exists() and args.output.samefile(path) for path in protected)):
            raise schema.InputError("Выходной отчёт не должен перезаписывать входные файлы")
    tolerances = sorted(set(args.tolerance_ms))
    records = [evaluate_record(*entry, args.collar_ms, tolerances, args.keep_yo) for entry in inputs]
    return dict(report_version=1, environment=dict(python=platform.python_version(),
                jiwer=importlib.metadata.version("jiwer"), rapidfuzz=importlib.metadata.version("rapidfuzz"),
                unicode=unicodedata.unidata_version),
                configuration=dict(collar_ms=args.collar_ms, tolerance_ms=tolerances, keep_yo=args.keep_yo,
                                   normalization="NFC/lower/punctuation-to-space/whitespace-v1"),
                records=records, summary=metrics.aggregate(records))


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    try:
        result = run(args)
        if args.output:
            args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        print(report.render(result))
    except (schema.InputError, OSError, UnicodeError, OverflowError) as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    # UTF-8 нужен и при перенаправлении вывода в Windows с однобайтной локалью.
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
