"""Одинаковая консервативная нормализация обеих сторон WER."""

import unicodedata


def normalize(text: str, keep_yo: bool = False) -> str:
    text = unicodedata.normalize("NFC", text).lower()
    if not keep_yo:
        text = text.replace("ё", "е")
    # Пробел вместо пунктуации не позволяет склеить соседние слова.
    text = "".join(" " if unicodedata.category(c).startswith("P") else c for c in text)
    return " ".join(text.split())
