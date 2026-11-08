"""Только синтетические временные WAV/права: не реальные разговоры и не согласия."""

import copy
import json
from pathlib import Path
import wave

import pytest

from tools.evaluation import dataset, schema


@pytest.fixture
def bundle(tmp_path):
    audio = tmp_path / "audio.wav"
    with wave.open(str(audio), "wb") as stream:
        stream.setparams((1, 2, 16000, 32000, "NONE", "not compressed"))
        stream.writeframes(b"\x00\x00" * 32000)
    reference = {"transcript": [
        dict(speaker_id="speaker_1", start_ms=0, end_ms=1200, text="Тестовая фикстура"),
        dict(speaker_id="speaker_2", start_ms=1000, end_ms=1800, text="Не настоящая речь"),
    ]}
    (tmp_path / "reference.json").write_text(json.dumps(reference), encoding="utf-8")
    (tmp_path / "rights.txt").write_text("СИНТЕТИЧЕСКАЯ ФИКСТУРА, НЕ ЮРИДИЧЕСКОЕ СОГЛАСИЕ", encoding="utf-8")
    metadata = dict(recording_id="unit-test-only", scenario="C", audio_file="audio.wav",
                    audio_sha256=dataset.sha256(audio), reference_file="reference.json",
                    reference_sha256=dataset.sha256(tmp_path / "reference.json"),
                    source=dict(kind="team_recording", description="Синтетический unit test, не запись людей",
                                rights_holder="Test stub", license="Test stub", rights_reviewed=True,
                                rights_file="rights.txt", rights_sha256=dataset.sha256(tmp_path / "rights.txt"),
                                publication_allowed=False),
                    recording=dict(device="Test stub", conditions="Silence generated in test", recorded_at="Test stub"),
                    annotation=dict(status="verified", annotators=["test-annotator"], reviewers=["test-reviewer"],
                                    tool="Test stub", policy="Test stub", word_timestamps="unavailable", notes="Unit test"),
                    audio=dataset.wav_info(audio))
    path = tmp_path / "metadata.json"

    def save(value=None):
        path.write_text(json.dumps(metadata if value is None else value), encoding="utf-8")
        return path

    save()
    return metadata, save, tmp_path


def test_validate_bundle(bundle):
    _, save, _ = bundle
    r = dataset.validate(save(), require_verified=True)
    assert r["audio"]["duration_seconds"] == 2
    assert r["speakers"] == 2
    assert r["cross_speaker_overlap"] is True
    assert r["independent_review"] is True
    assert any("Публикация запрещена" in warning for warning in r["warnings"])
    assert "не достоверность" in r["limitation"]


@pytest.mark.parametrize("section,key,value", [
    (None, "audio_sha256", "invalid"), (None, "reference_sha256", "0" * 64),
    (None, "scenario", "D"), (None, "recording_id", ""),
    ("source", "rights_reviewed", False), ("source", "publication_allowed", "true"),
    ("source", "rights_sha256", "0" * 64), ("source", "kind", "unknown"),
    ("source", "rights_holder", ""), ("source", "license", ""),
    ("annotation", "status", "automatic"), ("annotation", "annotators", []),
    ("annotation", "reviewers", ["test", "test"]), ("annotation", "annotators", [False]),
    ("annotation", "word_timestamps", "approximate"),
    ("audio", "channels", True), ("audio", "frame_count", 100), ("audio", "duration_seconds", 0),
    ("recording", "conditions", ""),
])
def test_invalid_metadata(bundle, section, key, value):
    metadata, save, _ = bundle
    target = metadata if section is None else metadata[section]
    target[key] = value
    with pytest.raises(schema.InputError):
        dataset.validate(save())


def test_draft_and_single_annotator(bundle):
    metadata, save, _ = bundle
    metadata["annotation"].update(status="draft", reviewers=[])
    r = dataset.validate(save())
    assert r["independent_review"] is False
    assert len(r["warnings"]) == 3
    with pytest.raises(schema.InputError, match="draft"):
        dataset.validate(save(), require_verified=True)


@pytest.mark.parametrize("mutation", ["out_of_bounds", "empty", "blank_text", "one_speaker", "no_overlap"])
def test_invalid_reference(bundle, mutation):
    metadata, save, directory = bundle
    path = directory / "reference.json"
    ref = json.loads(path.read_text(encoding="utf-8"))
    if mutation == "out_of_bounds":
        ref["transcript"][1]["end_ms"] = 2001
    elif mutation == "empty":
        ref["transcript"] = []
    elif mutation == "blank_text":
        ref["transcript"][0]["text"] = " "
    elif mutation == "one_speaker":
        ref["transcript"][1]["speaker_id"] = "speaker_1"
    else:
        ref["transcript"][0]["end_ms"] = 1000
    path.write_text(json.dumps(ref), encoding="utf-8")
    metadata["reference_sha256"] = dataset.sha256(path)
    with pytest.raises(schema.InputError):
        dataset.validate(save())


def test_audio_tampering_and_truncation(bundle):
    metadata, save, directory = bundle
    audio = directory / "audio.wav"
    audio.write_bytes(audio.read_bytes()[:-100])
    with pytest.raises(schema.InputError, match="SHA-256"):
        dataset.validate(save())
    metadata["audio_sha256"] = dataset.sha256(audio)
    with pytest.raises(schema.InputError, match="повреждён"):
        dataset.validate(save())


def test_wrong_pcm_format(tmp_path):
    path = tmp_path / "wrong.wav"
    with wave.open(str(path), "wb") as stream:
        stream.setparams((2, 2, 48000, 48000, "NONE", "not compressed"))
        stream.writeframes(b"\x00" * 192000)
    with pytest.raises(schema.InputError, match="16000"):
        dataset.wav_info(path)


def test_convert_preserves_text_and_overlap(tmp_path):
    path = tmp_path / "annotations.tsv"
    path.write_text("speaker_id\tstart_ms\tend_ms\ttext\n"
                    "speaker_2\t0\t1000\tЁжик, кот!\n"
                    "speaker_1\t500\t1500\tДа.\n", encoding="utf-8-sig")
    result = dataset.convert(path)
    assert result["transcript"][0]["text"] == "Ёжик, кот!"
    assert result["transcript"][1]["start_ms"] == 500
    assert dataset.cross_speaker_overlap(result["transcript"])
    same = copy.deepcopy(result["transcript"])
    same[1]["speaker_id"] = "speaker_2"
    assert not dataset.cross_speaker_overlap(same)


@pytest.mark.parametrize("body", [
    "speaker_id,start_ms,end_ms,text\n", "speaker_id\tstart_ms\tend_ms\ttext\nspeaker_1\t0.5\t1000\tкот\n",
    "speaker_id\tstart_ms\tend_ms\ttext\nspeaker_1\t0\t1000\n",
    "speaker_id\tstart_ms\tend_ms\ttext\nspeaker_1\t0\t1000\tкот\textra\n",
    'speaker_id\tstart_ms\tend_ms\ttext\nspeaker_1\t0\t1000\t"незакрытая кавычка',
])
def test_bad_tsv(tmp_path, body, capsys):
    path = tmp_path / "bad.tsv"
    path.write_text(body, encoding="utf-8")
    assert dataset.main(["convert", "--annotations", str(path), "--output", str(tmp_path / "ref.json")]) == 2
    assert "Traceback" not in capsys.readouterr().err


def test_cli_validate_and_convert(bundle, capsys):
    _, save, directory = bundle
    assert dataset.main(["validate", "--metadata", str(save()), "--require-verified"]) == 0
    assert json.loads(capsys.readouterr().out)["recording_id"] == "unit-test-only"
    tsv = directory / "turns.tsv"
    tsv.write_text("speaker_id\tstart_ms\tend_ms\ttext\nspeaker_1\t0\t1000\tТест\n", encoding="utf-8")
    output = directory / "converted.json"
    args = ["convert", "--annotations", str(tsv), "--output", str(output)]
    assert dataset.main(args) == 0
    schema.transcript(schema.load_json(output))
    before = output.read_bytes()
    assert dataset.main(args) == 2
    assert output.read_bytes() == before


def test_cli_inspect(bundle, capsys):
    metadata, _, directory = bundle
    assert dataset.main(["inspect", "--audio", str(directory / "audio.wav"),
                         "--reference", str(directory / "reference.json"), "--rights", str(directory / "rights.txt")]) == 0
    actual = json.loads(capsys.readouterr().out)
    assert actual["audio"]["frame_count"] == 32000
    assert actual["audio_sha256"] == metadata["audio_sha256"]


def test_unfilled_kit_is_not_a_dataset():
    template = Path(__file__).resolve().parents[3] / "tests/data/evaluation/recording-kit/metadata.template.json"
    with pytest.raises(schema.InputError, match="SHA-256"):
        dataset.validate(template, require_verified=True)
