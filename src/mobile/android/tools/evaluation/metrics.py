"""WER, непрерывный DER, диагностика границ и агрегирование счётчиков."""

from collections import Counter, defaultdict
import math

import jiwer

from .matching import maximum_assignment
from .normalization import normalize
from .schema import Utterance, number


def ratio(errors, total):
    # Пустой эталон: ноль при полном совпадении, иначе доля не определена.
    return errors / total if total else (0.0 if not errors else None)


def word_error(reference: list[Utterance], hypothesis: list[Utterance], keep_yo: bool = False) -> dict:
    raw_ref = "\n".join(r["text"] for r in reference)
    raw_hyp = "\n".join(r["text"] for r in hypothesis)
    ref, hyp = normalize(raw_ref, keep_yo), normalize(raw_hyp, keep_yo)
    result = jiwer.process_words(ref, hyp)
    counts = dict(substitutions=result.substitutions, deletions=result.deletions,
                  insertions=result.insertions, reference_words=len(ref.split()))
    return {**counts, "wer": ratio(sum(counts[k] for k in ("substitutions", "deletions", "insertions")),
                                  counts["reference_words"]),
            "reference_raw": raw_ref, "hypothesis_raw": raw_hyp,
            "reference_normalized": ref, "hypothesis_normalized": hyp}


def diarization(reference: list[Utterance], hypothesis: list[Utterance], collar_ms: int = 0) -> dict:
    # События счётчиков объединяют перекрытия одного спикера, но не разных.
    events = defaultdict(lambda: [Counter(), Counter(), 0])
    for side, rows in enumerate((reference, hypothesis)):
        for row in rows:
            events[row["start_ms"]][side][row["speaker_id"]] += 1
            events[row["end_ms"]][side][row["speaker_id"]] -= 1
    if collar_ms:
        for row in reference:
            for boundary in (row["start_ms"], row["end_ms"]):
                events[max(0, boundary - collar_ms)][2] += 1
                events[boundary + collar_ms][2] -= 1
    active = [Counter(), Counter()]
    excluded = 0
    spans = []
    previous = None
    overlap = Counter()
    for time in sorted(events):
        if previous is not None and time > previous and not excluded:
            r, h = (set(s for s, n in a.items() if n > 0) for a in active)
            duration = time - previous
            spans.append((duration, r, h))
            for rs in r:
                for hs in h:
                    overlap[rs, hs] += duration
        for side in (0, 1):
            active[side].update(events[time][side])
        excluded += events[time][2]
        previous = time
    ref_ids = sorted({r["speaker_id"] for r in reference})
    hyp_ids = sorted({r["speaker_id"] for r in hypothesis})
    weights = [[overlap[r, h] for h in hyp_ids] for r in ref_ids]
    mapping = {hyp_ids[j]: ref_ids[i] for i, j in maximum_assignment(weights)}
    missed = false_alarm = confusion = total = 0
    for duration, r, h in spans:
        correct = sum(mapping.get(s) in r for s in h)
        total += duration * len(r)
        missed += duration * max(0, len(r) - len(h))
        false_alarm += duration * max(0, len(h) - len(r))
        confusion += duration * (min(len(r), len(h)) - correct)
    return dict(der=ratio(missed + false_alarm + confusion, total),
                missed_speech_ms=missed, false_alarm_ms=false_alarm,
                speaker_confusion_ms=confusion, reference_speaker_ms=total,
                hypothesis_to_reference=mapping, collar_ms=collar_ms,
                overlap_scored=True)


def boundaries(reference: list[Utterance], hypothesis: list[Utterance], mapping: dict[str, str],
               tolerances: tuple[int, ...] | list[int] = (100, 250, 500)) -> dict:
    weights = []
    for r in reference:
        weights.append([max(0, min(r["end_ms"], h["end_ms"]) - max(r["start_ms"], h["start_ms"]))
                        if mapping.get(h["speaker_id"]) == r["speaker_id"] else 0 for h in hypothesis])
    pairs = []
    for i, j in maximum_assignment(weights):
        r, h = reference[i], hypothesis[j]
        pairs.append(dict(reference_index=i, hypothesis_index=j,
                          start_error_ms=h["start_ms"] - r["start_ms"],
                          end_error_ms=h["end_ms"] - r["end_ms"]))
    matched_r = {p["reference_index"] for p in pairs}
    matched_h = {p["hypothesis_index"] for p in pairs}
    result = dict(pairs=pairs, matched_utterances=len(pairs),
                  reference_utterances=len(reference), hypothesis_utterances=len(hypothesis),
                  unmatched_reference=[i for i in range(len(reference)) if i not in matched_r],
                  unmatched_hypothesis=[i for i in range(len(hypothesis)) if i not in matched_h],
                  start_absolute_error_sum_ms=sum(abs(p["start_error_ms"]) for p in pairs),
                  end_absolute_error_sum_ms=sum(abs(p["end_error_ms"]) for p in pairs),
                  within_counts={str(t): sum(abs(p[k]) <= t for p in pairs
                                            for k in ("start_error_ms", "end_error_ms")) for t in tolerances})
    return boundary_summary(result)


def boundary_summary(result):
    n = result["matched_utterances"]
    start, end = result["start_absolute_error_sum_ms"], result["end_absolute_error_sum_ms"]
    result.update(start_mae_ms=start / n if n else None, end_mae_ms=end / n if n else None,
                  boundary_mae_ms=(start + end) / (2 * n) if n else None,
                  within_fraction={t: count / (2 * n) if n else None for t, count in result["within_counts"].items()},
                  reference_boundary_coverage={t: count / (2 * result["reference_utterances"])
                                               if result["reference_utterances"] else None
                                               for t, count in result["within_counts"].items()})
    return result


def speed(measurement):
    if measurement is None:
        return {"rtf": None, "measurement": None}
    elapsed = measurement.get("processing_time_seconds")
    rtf = number(elapsed / measurement["audio_duration_seconds"], "RTF") if elapsed is not None else None
    return {"rtf": rtf,
            "measurement": measurement}


def aggregate(records):
    wer_keys = ("substitutions", "deletions", "insertions", "reference_words")
    w = {k: sum(r["wer"][k] for r in records) for k in wer_keys}
    w["wer"] = ratio(w["substitutions"] + w["deletions"] + w["insertions"], w["reference_words"])
    der_keys = ("missed_speech_ms", "false_alarm_ms", "speaker_confusion_ms", "reference_speaker_ms")
    d = {k: sum(r["diarization"][k] for r in records) for k in der_keys}
    d["der"] = ratio(d["missed_speech_ms"] + d["false_alarm_ms"] + d["speaker_confusion_ms"], d["reference_speaker_ms"])
    bkeys = ("matched_utterances", "reference_utterances", "hypothesis_utterances",
             "start_absolute_error_sum_ms", "end_absolute_error_sum_ms")
    b = {k: sum(r["boundaries"][k] for r in records) for k in bkeys}
    b["unmatched_reference_count"] = b["reference_utterances"] - b["matched_utterances"]
    b["unmatched_hypothesis_count"] = b["hypothesis_utterances"] - b["matched_utterances"]
    b["within_counts"] = {t: sum(r["boundaries"]["within_counts"][t] for r in records)
                          for t in records[0]["boundaries"]["within_counts"]}
    timed = [r["speed"]["measurement"] for r in records if r["speed"]["rtf"] is not None]
    elapsed = number(math.fsum(m["processing_time_seconds"] for m in timed), "Суммарное время обработки")
    duration = number(math.fsum(m["audio_duration_seconds"] for m in timed), "Суммарная длительность")
    return dict(recordings=len(records), wer=w, diarization=d, boundaries=boundary_summary(b),
                speed=dict(rtf=elapsed / duration if duration else None, measured_recordings=len(timed),
                           processing_time_seconds=elapsed if timed else None,
                           audio_duration_seconds=duration if timed else None))
