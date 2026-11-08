"""Проверка схемы, ошибок CLI и воспроизводимых отчётов."""

import copy
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from tools.evaluation.evaluate import main
from tools.evaluation.schema import InputError, load_json, measurement, transcript


ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "tests/data/evaluation"
VALID = {"transcript": [{"speaker_id": "speaker_1", "start_ms": 0, "end_ms": 1000, "text": "кот"}]}


@pytest.mark.parametrize("field,value", [
    ("speaker_id", "speaker_0"), ("speaker_id", "speaker_x"), ("speaker_id", 1),
    ("start_ms", True), ("end_ms", False), ("start_ms", -1), ("start_ms", 1.0),
    ("end_ms", 0), ("end_ms", "1000"), ("text", None), ("extra", "bad"),
])
def test_invalid_row(field, value):
    data = copy.deepcopy(VALID)
    data["transcript"][0][field] = value
    with pytest.raises(InputError):
        transcript(data)


@pytest.mark.parametrize("value", [[], {}, {"transcript": None}, {"transcript": [0]},
                                    {"transcript": [], "metadata": {}}, {"transcript": [{}]}])
def test_invalid_structure(value):
    with pytest.raises(InputError):
        transcript(value)


def test_order_empty_overlap_large():
    assert transcript({"transcript": []}) == []
    r = VALID["transcript"][0]
    assert len(transcript({"transcript": [r, r]})) == 2
    with pytest.raises(InputError, match="отсортированы"):
        transcript({"transcript": [{**r, "start_ms": 5}, r]})
    assert transcript({"transcript": [{**r, "start_ms": 2**63, "end_ms": 2**63 + 1}]})


@pytest.mark.parametrize("content", ['{', '{"transcript":[],"transcript":[]}', '{"transcript":NaN}', '{"x":Infinity}'])
def test_bad_json(tmp_path, content):
    path = tmp_path / "bad.json"
    path.write_text(content, encoding="utf-8")
    with pytest.raises(InputError):
        load_json(path)


def test_missing_and_invalid_encoding(tmp_path):
    with pytest.raises(InputError):
        load_json(tmp_path / "missing")
    p = tmp_path / "bad.json"
    p.write_bytes(b"\xff")
    with pytest.raises(InputError):
        load_json(p)


@pytest.mark.parametrize("change", [
    {"audio_duration_seconds": 0}, {"audio_duration_seconds": -1},
    {"audio_duration_seconds": True}, {"audio_duration_seconds": float("inf")},
    {"processing_time_seconds": -1}, {"processing_time_seconds": 3},
    {"threads": False}, {"threads": 0}, {"device": 12}, {"extra": 0},
])
def test_measurement_validation(change):
    with pytest.raises(InputError):
        measurement({"audio_duration_seconds": 60, **change})


def test_complete_measurement():
    m = dict(audio_duration_seconds=60, processing_time_seconds=30, model="GigaAM",
             variant="v3 CTC", quantization="int8", device="test device", threads=4)
    assert measurement(m) == m


def test_cli_reports(tmp_path, capsys):
    output = tmp_path / "report.json"
    args = ["--manifest", str(DATA / "manifest.json"), "--output", str(output)]
    assert main(args) == 0
    first = output.read_bytes()
    r = json.loads(first)
    assert r["summary"]["wer"]["wer"] == .25
    assert r["summary"]["diarization"]["der"] == 0
    assert r["records"][0]["sources"]["reference"]["sha256"]
    assert main(args) == 0
    assert output.read_bytes() == first
    assert "ИТОГО" in capsys.readouterr().out
    assert main(["--reference", str(DATA / "reference.json"), "--hypothesis", str(DATA / "hypothesis.json")]) == 0


@pytest.mark.parametrize("args", [[], ["--collar-ms", "-1"], ["--tolerance-ms", "-10"],
                                    ["--reference", "missing", "--hypothesis", "missing"],
                                    ["--manifest", str(DATA / "manifest.json"), "--reference", "x"]])
def test_cli_errors(args, capsys):
    assert main(args) == 2
    err = capsys.readouterr().err
    assert "Ошибка:" in err
    assert "Traceback" not in err


def test_cli_protects_inputs(capsys):
    assert main(["--manifest", str(DATA / "manifest.json"), "--output", str(DATA / "reference.json")]) == 2
    assert "перезаписывать" in capsys.readouterr().err


@pytest.mark.parametrize("manifest", [{"recordings": []}, {"recordings": "bad"},
    {"recordings": [{"id": "a", "reference": 1, "hypothesis": "x"}]},
    {"recordings": [{"id": [], "reference": "x", "hypothesis": "x"}]},
    {"recordings": [{"id": "a", "reference": "x", "hypothesis": "x"}] * 2}])
def test_invalid_manifest(tmp_path, manifest, capsys):
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    assert main(["--manifest", str(path)]) == 2
    assert "Traceback" not in capsys.readouterr().err


def test_direct_script_from_other_directory(tmp_path):
    p = subprocess.run([sys.executable, str(ROOT / "tools/evaluation/evaluate.py"),
                        "--manifest", str(DATA / "manifest.json")], cwd=tmp_path,
                       text=True, encoding="utf-8", capture_output=True)
    assert p.returncode == 0, p.stderr
    assert "WER=0.250000" in p.stdout


def test_batch_cli_with_measured_time(tmp_path):
    m = dict(audio_duration_seconds=60, processing_time_seconds=30, model="test-only",
             variant="synthetic", quantization="none", device="not-a-real-measurement", threads=1)
    (tmp_path / "measurement.json").write_text(json.dumps(m), encoding="utf-8")
    manifest = {"recordings": [
        {"id": "one", "reference": str(DATA / "reference.json"), "hypothesis": str(DATA / "hypothesis.json"),
         "measurement": "measurement.json"},
        {"id": "two", "reference": str(DATA / "reference.json"), "hypothesis": str(DATA / "reference.json")},
    ]}
    (tmp_path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    output = tmp_path / "report.json"
    assert main(["--manifest", str(tmp_path / "manifest.json"), "--output", str(output)]) == 0
    result = json.loads(output.read_text(encoding="utf-8"))
    assert result["summary"]["wer"]["wer"] == 1 / 8
    assert result["summary"]["speed"]["rtf"] == .5
    assert result["summary"]["speed"]["measured_recordings"] == 1


def test_cli_collar_does_not_hide_timing_pairs(tmp_path):
    output = tmp_path / "report.json"
    assert main(["--reference", str(DATA / "reference.json"), "--hypothesis", str(DATA / "reference.json"),
                 "--collar-ms", "1000", "--tolerance-ms", "250", "--output", str(output)]) == 0
    r = json.loads(output.read_text(encoding="utf-8"))["records"][0]
    assert r["diarization"]["reference_speaker_ms"] == 0
    assert r["diarization"]["hypothesis_to_reference"] == {}
    assert r["boundaries"]["matched_utterances"] == 2
    assert r["boundaries"]["within_fraction"] == {"250": 1}


def test_cli_invalid_json_and_duration(tmp_path, capsys):
    path = tmp_path / "bad.json"
    path.write_text("{", encoding="utf-8")
    assert main(["--reference", str(path), "--hypothesis", str(DATA / "reference.json")]) == 2
    path.write_text('{"audio_duration_seconds": 0.1}', encoding="utf-8")
    assert main(["--reference", str(DATA / "reference.json"), "--hypothesis", str(DATA / "reference.json"),
                 "--measurement", str(path)]) == 2
    assert "длительность" in capsys.readouterr().err


def test_cli_protects_hardlinked_input(tmp_path, capsys):
    source = tmp_path / "reference.json"
    source.write_bytes((DATA / "reference.json").read_bytes())
    output = tmp_path / "alias.json"
    try:
        output.hardlink_to(source)
    except OSError:
        pytest.skip("Файловая система не поддерживает hardlink")
    before = source.read_bytes()
    assert main(["--reference", str(source), "--hypothesis", str(DATA / "hypothesis.json"),
                 "--output", str(output)]) == 2
    assert source.read_bytes() == before
    assert "перезаписывать" in capsys.readouterr().err


def test_cli_rejects_overflowing_rtf(tmp_path, capsys):
    empty = tmp_path / "empty.json"
    empty.write_text('{"transcript":[]}', encoding="utf-8")
    m = dict(audio_duration_seconds=1e-308, processing_time_seconds=1e308, model="synthetic",
             variant="test", quantization="none", device="test", threads=1)
    measurement_path = tmp_path / "measurement.json"
    measurement_path.write_text(json.dumps(m), encoding="utf-8")
    assert main(["--reference", str(empty), "--hypothesis", str(empty), "--measurement", str(measurement_path),
                 "--output", str(tmp_path / "report.json")]) == 2
    assert "RTF" in capsys.readouterr().err


def test_redirected_console_uses_utf8(tmp_path):
    p = subprocess.run([sys.executable, str(ROOT / "tools/evaluation/evaluate.py"),
                        "--manifest", str(DATA / "manifest.json")], cwd=tmp_path,
                       env={**os.environ, "PYTHONIOENCODING": "ascii"},
                       text=True, encoding="utf-8", capture_output=True)
    assert p.returncode == 0, p.stderr
    assert "ИТОГО" in p.stdout


def test_cli_handles_aggregate_overflow(tmp_path, capsys):
    empty = tmp_path / "empty.json"
    empty.write_text('{"transcript":[]}', encoding="utf-8")
    m = dict(audio_duration_seconds=1e308, processing_time_seconds=1e308, model="synthetic",
             variant="test", quantization="none", device="test", threads=1)
    (tmp_path / "measurement.json").write_text(json.dumps(m), encoding="utf-8")
    manifest = {"recordings": [dict(id=str(i), reference="empty.json", hypothesis="empty.json",
                                    measurement="measurement.json") for i in range(2)]}
    (tmp_path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    assert main(["--manifest", str(tmp_path / "manifest.json")]) == 2
    assert "Traceback" not in capsys.readouterr().err
