# CatScriptor — Android-приложение

Исходный код приложения. Сборка и запуск: [`../../README.md`](../../README.md). Использование:
[`../../../README.md`](../../../README.md). Архитектура: [`../../../docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md).

Два варианта сборки: `full` (модели внутри APK) и `lite` (модели скачиваются при первом запуске); см. `../../README.md`.

## Модели

Файлы лежат в APK и в Git не хранятся. `python3 scripts/prepare_models.py` скачивает их на компьютере
разработчика и проверяет SHA-256 (пути относительно `app/src/main/assets/models/`).

| Назначение | Модель | Версия | Источник | Локальный путь | SHA-256 |
|---|---|---|---|---|---|
| ASR | GigaAM v3 CTC int8 | 2025-12-16 | [Официальный архив](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16.tar.bz2) | `gigaam-v3-ctc-int8-2025-12-16/model.int8.onnx` | `f86ebfa0429ced91be6054fc344827e9c6c2572f3c318416cd974b06f66437ec` |
| Словарь ASR | GigaAM v3 CTC | 2025-12-16 | Тот же архив | `gigaam-v3-ctc-int8-2025-12-16/tokens.txt` | `17cc514451bcceac9c280068c71502f8448f99e9fb1456b8d0761651fd0392f2` |
| Лицензия модели | GigaAM MIT | 2025-12-16 | Тот же архив | `gigaam-v3-ctc-int8-2025-12-16/LICENSE` | `f00de6715714c7a63d08639cdbfaa40224eefc407302614bd19f1a8b98c875aa` |
| VAD | Silero v4, экспорт k2-fsa 16 кГц | SHA-pinned | [ONNX](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx) | `silero-vad-v4-k2-fsa-9e2449e1/silero_vad.onnx` | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |
| Диаризация: эмбеддинги | 3D-Speaker ERes2Net-base zh-cn | speaker-recongition-models | [ONNX](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx) | `speaker-embedding-eres2net-base-zh-cn-3dspeaker/embedding.onnx` | `1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b` |
| Лицензия диаризации | 3D-Speaker Apache-2.0 | см. манифест | `diarization-manifest.properties` | `speaker-embedding-eres2net-base-zh-cn-3dspeaker/LICENSE-3dspeaker` | см. манифест |
| Лицензия VAD | Silero MIT | v4.0 | [LICENSE](https://raw.githubusercontent.com/snakers4/silero-vad/v4.0/LICENSE) | `silero-vad-v4-k2-fsa-9e2449e1/LICENSE` | `2e63e9a38b6e8fc0c7bc37ce174caca1862870856c6daf5697cfb785e925520b` |
| LLM-коррекция, уровень «Умная» | Qwen2.5-1.5B-Instruct Q4_K_M | HF main | [GGUF](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf) | `qwen2.5-1.5b-instruct-q4_k_m-2026-11-07/qwen2.5-1.5b-instruct-q4_k_m.gguf` | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| Лицензия LLM | Apache-2.0 | — | [LICENSE](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/LICENSE) | `qwen2.5-1.5b-instruct-q4_k_m-2026-11-07/LICENSE` | `832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e` |
| LLM-коррекция, уровень «Быстрая» | Qwen2.5-0.5B-Instruct Q4_K_M | HF main | [GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf) | `qwen2.5-0.5b-instruct-q4_k_m-2026-11-08/qwen2.5-0.5b-instruct-q4_k_m.gguf` | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |
| Лицензия LLM «Быстрая» | Apache-2.0 | — | [LICENSE](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/LICENSE) | `qwen2.5-0.5b-instruct-q4_k_m-2026-11-08/LICENSE` | `832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e` |

ASR-движок и VAD — официальный AAR sherpa-onnx 2.0.1 (`dependencies/sherpa-onnx.properties`); LLM —
llama.cpp v0.9.0 через собственный JNI-мост (`dependencies/llama-cpp.properties`). Приложение не
запрашивает разрешение `INTERNET`.
