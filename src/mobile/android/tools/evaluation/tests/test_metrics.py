"""Искусственные примеры проверяют математику, не качество ASR."""

from itertools import permutations
import random

import pytest

from tools.evaluation.matching import maximum_assignment
from tools.evaluation.metrics import aggregate, boundaries, diarization, speed, word_error
from tools.evaluation.normalization import normalize


def row(start=0, end=1000, speaker=1, text="кот спит"):
    return dict(speaker_id=f"speaker_{speaker}", start_ms=start, end_ms=end, text=text)


@pytest.mark.parametrize("ref,hyp,expected", [
    ("кот спит", "кот спит", (0, 0, 0, 2, 0)),
    ("кот спит", "кот ест", (1, 0, 0, 2, .5)),
    ("кот спит", "кот", (0, 1, 0, 2, .5)),
    ("кот спит", "кот уже спит", (0, 0, 1, 2, .5)),
    ("", "", (0, 0, 0, 0, 0)),
    ("", "кот спит", (0, 0, 2, 0, None)),
    ("кот спит", "", (0, 2, 0, 2, 1)),
    ("Ёжик,  кот!\n Спит…", "ежик кот спит", (0, 0, 0, 3, 0)),
])
def test_wer(ref, hyp, expected):
    actual = word_error([row(text=ref)], [row(text=hyp)])
    assert tuple(actual[k] for k in ("substitutions", "deletions", "insertions", "reference_words", "wer")) == expected
    assert actual["reference_raw"] == ref


def test_normalization_conservative():
    assert normalize("Е\u0308ЖИК\t— кот,пёс") == "ежик кот пес"
    assert normalize("Ёжик", keep_yo=True) == "ёжик"
    assert normalize("два 2 + три") == "два 2 + три"
    assert normalize("кот-пёс") == "кот пес"
    assert word_error([row(text="кот спит")], [row(text="спит кот")])["wer"] > 0


def test_speaker_permutation():
    ref = [row(0, 1000, 1), row(1000, 2000, 2)]
    hyp = [row(0, 1000, 2), row(1000, 2000, 1)]
    d = diarization(ref, hyp)
    assert d["der"] == 0
    assert d["hypothesis_to_reference"] == {"speaker_1": "speaker_2", "speaker_2": "speaker_1"}


@pytest.mark.parametrize("ref,hyp,expected", [
    ([row()], [], (1000, 0, 0, 1000, 1)),
    ([row()], [row(0, 2000)], (0, 1000, 0, 1000, 1)),
    ([], [row()], (0, 1000, 0, 0, None)),
    ([], [], (0, 0, 0, 0, 0)),
    ([row(0, 1000, 1), row(500, 1500, 2)], [row(0, 1000, 1)], (1000, 0, 0, 2000, .5)),
    ([row(0, 1000, 1), row(0, 1000, 2)], [row(0, 1000, 3), row(0, 1000, 4)], (0, 0, 0, 2000, 0)),
    ([row(0, 1000, 1), row(500, 1500, 1)], [row(0, 1500, 2)], (0, 0, 0, 1500, 0)),
    ([row(0, 1000, 1), row(1000, 2000, 2)], [row(0, 2000, 3)], (0, 0, 1000, 2000, .5)),
    ([row(0, 10, 1), row(11, 20, 1)], [row(0, 20, 2)], (0, 1, 0, 19, 1 / 19)),
])
def test_der_components(ref, hyp, expected):
    d = diarization(ref, hyp)
    assert tuple(d[k] for k in ("missed_speech_ms", "false_alarm_ms", "speaker_confusion_ms", "reference_speaker_ms", "der")) == expected


def test_collar_and_short_segments():
    ref, hyp = [row(100, 900)], [row(0, 1000)]
    assert diarization(ref, hyp, 100)["der"] == 0
    assert diarization(ref, hyp, 0)["false_alarm_ms"] == 200
    assert diarization([row(100, 110)], [], 100)["reference_speaker_ms"] == 0


def test_large_time():
    t = 2**63 - 10000
    d = diarization([row(t, t + 1000)], [row(t + 100, t + 1100)])
    assert d["der"] == .2
    b = boundaries([row(t, t + 1000)], [row(t + 100, t + 1100)], d["hypothesis_to_reference"])
    assert b["boundary_mae_ms"] == 100


def test_boundary_shift():
    r, h = [row(1000, 2000)], [row(1100, 2250)]
    b = boundaries(r, h, {"speaker_1": "speaker_1"})
    assert (b["start_mae_ms"], b["end_mae_ms"], b["boundary_mae_ms"]) == (100, 250, 175)
    assert b["within_fraction"] == {"100": .5, "250": 1, "500": 1}


def test_split_and_merge():
    whole = [row(0, 1000)]
    split = [row(0, 400), row(400, 1000)]
    mapping = {"speaker_1": "speaker_1"}
    b = boundaries(whole, split, mapping)
    assert b["pairs"][0]["hypothesis_index"] == 1
    assert b["unmatched_hypothesis"] == [0]
    b = boundaries(split, whole, mapping)
    assert b["unmatched_reference"] == [0]
    assert b["reference_boundary_coverage"]["100"] == .25


def test_matching_not_by_index_and_no_overlap():
    ref = [row(0, 1000, 1), row(100, 1200, 2)]
    hyp = [row(0, 1200, 2), row(100, 1000, 1)]
    b = boundaries(ref, hyp, {"speaker_1": "speaker_1", "speaker_2": "speaker_2"})
    assert [(p["reference_index"], p["hypothesis_index"]) for p in b["pairs"]] == [(0, 1), (1, 0)]
    b = boundaries([row(0, 100)], [row(100, 200)], {"speaker_1": "speaker_1"})
    assert b["matched_utterances"] == 0
    assert b["boundary_mae_ms"] is None
    assert b["unmatched_reference"] == [0]


def test_speed():
    assert speed({"audio_duration_seconds": 60, "processing_time_seconds": 30})["rtf"] == .5
    assert speed({"audio_duration_seconds": 60, "processing_time_seconds": 0})["rtf"] == 0
    assert speed({"audio_duration_seconds": 60})["rtf"] is None
    assert speed(None)["rtf"] is None


def record(ref, hyp, measurement=None):
    d = diarization(ref, hyp)
    return dict(wer=word_error(ref, hyp), diarization=d,
                boundaries=boundaries(ref, hyp, d["hypothesis_to_reference"]), speed=speed(measurement))


def test_aggregation_micro():
    a = record([row(text="кот")], [row(text="пёс")], {"audio_duration_seconds": 60, "processing_time_seconds": 30})
    b = record([row(text="один два три четыре")], [row(text="один два три четыре")])
    c = record([], [row(text="лишнее")])
    result = aggregate([a, b, c])
    assert result["wer"]["wer"] == 2 / 5
    assert result["wer"]["insertions"] == 1
    assert result["diarization"]["der"] == .5
    assert result["boundaries"]["matched_utterances"] == 2
    assert result["boundaries"]["unmatched_hypothesis_count"] == 1
    assert result["speed"]["rtf"] == .5
    assert result["speed"]["measured_recordings"] == 1
    assert aggregate([b])["speed"]["rtf"] is None


def test_aggregate_rtf_is_duration_weighted():
    a = record([row()], [row()], {"audio_duration_seconds": 60, "processing_time_seconds": 30})
    b = record([row()], [row()], {"audio_duration_seconds": 10, "processing_time_seconds": 20})
    s = aggregate([a, b])["speed"]
    assert s["rtf"] == 50 / 70
    assert s["measured_recordings"] == 2


def test_empty_boundary_summary():
    b = boundaries([], [], {})
    assert b["matched_utterances"] == 0
    assert b["unmatched_reference"] == b["unmatched_hypothesis"] == []
    assert b["boundary_mae_ms"] is None
    assert b["within_fraction"]["100"] is None


def test_matched_mae_must_be_read_with_coverage():
    ref = [row(0, 1000), row(2000, 3000)]
    b = boundaries(ref, [row(0, 1000)], {"speaker_1": "speaker_1"})
    assert b["boundary_mae_ms"] == 0
    assert b["within_fraction"]["100"] == 1
    assert b["reference_boundary_coverage"]["100"] == .5
    assert b["unmatched_reference"] == [1]


def test_assignment_against_exhaustive_oracle():
    rng = random.Random(11)
    for n in range(1, 6):
        for m in range(1, 6):
            for _ in range(10):
                weights = [[rng.randrange(30) for _ in range(m)] for _ in range(n)]
                size = max(n, m)
                expected = max(sum(weights[i][j] for i, j in enumerate(p) if i < n and j < m)
                               for p in permutations(range(size)))
                pairs = maximum_assignment(weights)
                assert sum(weights[i][j] for i, j in pairs) == expected
                assert len({i for i, _ in pairs}) == len(pairs)
                assert len({j for _, j in pairs}) == len(pairs)
    assert maximum_assignment([[2**80, 1], [1, 2**80]]) == [(0, 0), (1, 1)]


def test_der_against_discrete_exhaustive_oracle():
    rng = random.Random(12)
    for _ in range(100):
        ref, hyp = [], []
        for rows in (ref, hyp):
            for speaker in range(1, 4):
                start = rng.randrange(6)
                rows.append(row(start, start + rng.randrange(1, 5), speaker))
        ref.sort(key=lambda r: r["start_ms"])
        hyp.sort(key=lambda r: r["start_ms"])
        totals = []
        for perm in permutations(range(1, 4)):
            missed = false_alarm = confusion = total = 0
            for t in range(10):
                r = {int(x["speaker_id"].split("_")[1]) for x in ref if x["start_ms"] <= t < x["end_ms"]}
                h = {perm[int(x["speaker_id"].split("_")[1]) - 1] for x in hyp if x["start_ms"] <= t < x["end_ms"]}
                total += len(r)
                missed += max(0, len(r) - len(h))
                false_alarm += max(0, len(h) - len(r))
                confusion += min(len(r), len(h)) - len(r & h)
            totals.append((missed + false_alarm + confusion, total))
        errors, total = min(totals)
        d = diarization(ref, hyp)
        assert d["der"] == errors / total
