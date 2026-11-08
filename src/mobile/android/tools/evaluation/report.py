"""Компактный читаемый отчёт; полный результат сохраняется в JSON."""


def _value(value):
    return "нет данных" if value is None else f"{value:.6f}"


def render(report):
    lines = []
    for name, r in [(r["id"], r) for r in report["records"]] + [("ИТОГО", report["summary"])]:
        w, d, b = r["wer"], r["diarization"], r["boundaries"]
        unmatched_r = b["reference_utterances"] - b["matched_utterances"]
        unmatched_h = b["hypothesis_utterances"] - b["matched_utterances"]
        lines.extend([
            f"{name}: WER={_value(w['wer'])} (S={w['substitutions']}, D={w['deletions']}, I={w['insertions']}, N={w['reference_words']})",
            f"  DER={_value(d['der'])}; miss/FA/confusion (speaker-ms): "
            f"{d['missed_speech_ms']}/{d['false_alarm_ms']}/{d['speaker_confusion_ms']}",
            f"  MAE начала/конца/границ (мс): {_value(b['start_mae_ms'])}/"
            f"{_value(b['end_mae_ms'])}/{_value(b['boundary_mae_ms'])}; пары={b['matched_utterances']}; "
            f"без пары эталон/результат={unmatched_r}/{unmatched_h}",
            "  Доля границ в допуске (только пары): " + ", ".join(
                f"{t} мс={_value(v)}" for t, v in b["within_fraction"].items()),
            "  Покрытие всех эталонных границ: " + ", ".join(
                f"{t} мс={_value(v)}" for t, v in b["reference_boundary_coverage"].items()),
            f"  RTF={_value(r['speed']['rtf'])}",
        ])
    return "\n".join(lines)
