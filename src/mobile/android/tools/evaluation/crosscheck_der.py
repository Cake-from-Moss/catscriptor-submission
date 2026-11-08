"""Необязательная независимая проверка DER через pyannote.metrics, без аудио."""

import argparse
import importlib.metadata
import json
import math
import random

from .metrics import diarization


def check(cases=200, seed=11):
    from pyannote.core import Annotation, Segment, Timeline
    from pyannote.metrics.diarization import DiarizationErrorRate

    def annotation(rows):
        result = Annotation()
        for i, row in enumerate(rows):
            result[Segment(row["start_ms"] / 1000, row["end_ms"] / 1000), i] = row["speaker_id"]
        return result

    rng = random.Random(seed)
    examples = [([], []), ([], [dict(speaker_id="speaker_1", start_ms=0, end_ms=1000, text="")])]
    for _ in range(cases):
        sides = []
        for _side in range(2):
            rows = []
            # Ноль — четыре спикера, пересечения, пропуски и разные сегментации.
            for speaker in range(1, rng.randrange(5) + 1):
                for _segment in range(rng.randrange(1, 4)):
                    start = rng.randrange(0, 10000)
                    rows.append(dict(speaker_id=f"speaker_{speaker}", start_ms=start,
                                     end_ms=start + rng.randrange(1, 2000), text=""))
            sides.append(sorted(rows, key=lambda r: r["start_ms"]))
        examples.append(tuple(sides))
    comparisons = 0
    for reference, hypothesis in examples:
        extent = max([r["end_ms"] for r in reference + hypothesis] + [1]) / 1000
        uem = Timeline([Segment(0, extent)])
        for collar in (0, 100, 250):
            ours = diarization(reference, hypothesis, collar)
            scorer = DiarizationErrorRate(collar=0, skip_overlap=False)
            raw_reference = annotation(reference)
            # pyannote считает треки: сначала устраняем дубли одного спикера.
            # Collar строит сама библиотека по исходным, а не объединённым границам.
            scoring_uem = scorer.extrude(uem, raw_reference, collar=2 * collar / 1000, skip_overlap=False)
            theirs = scorer(raw_reference.support(), annotation(hypothesis).support(),
                            uem=scoring_uem, detailed=True)
            for local, external in (("missed_speech_ms", "missed detection"), ("false_alarm_ms", "false alarm"),
                                    ("speaker_confusion_ms", "confusion"), ("reference_speaker_ms", "total")):
                if not math.isclose(ours[local], 1000 * theirs[external], abs_tol=1e-6):
                    raise AssertionError(json.dumps(dict(reference=reference, hypothesis=hypothesis,
                                                         collar_ms=collar, ours=ours, theirs=theirs)))
            comparisons += 1
    return dict(comparisons=comparisons, random_cases=cases, seed=seed,
                pyannote_metrics=importlib.metadata.version("pyannote.metrics"),
                pyannote_core=importlib.metadata.version("pyannote.core"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=int, default=200)
    parser.add_argument("--seed", type=int, default=11)
    args = parser.parse_args()
    print(json.dumps(check(args.cases, args.seed), indent=2))
