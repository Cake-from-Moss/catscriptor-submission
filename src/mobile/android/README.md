# CatScriptor

Android-приложение для хакатона Nuclear IT Hack. Превращает аудиозаписи разговоров в расшифровку со спикерами и таймкодами. Вся обработка выполняется на устройстве, без интернета.

**Статус:** пользовательский импорт и запись подключены к полному офлайн-пути
декодирование → Silero VAD → диаризация (эмбеддинги ERes2Net через sherpa-onnx + спектральная кластеризация) →
GigaAM v3 CTC int8 по ходам спикеров → слияние реплик → LLM-коррекция текста →
готовый транскрипт со спикерами, таймкодами и исправленным текстом (`RecordingStatus.Ready`).
Готовые записи открываются на экране транскрипта с правкой реплик и имён спикеров.
На главном экране и в библиотеке свайп вправо раскрывает переименование, влево — удаление.
Переключатель «Пунктуация и правка» (Выкл / Быстрая / Умная) в настройках управляет LLM-этапом первичной
обработки и по умолчанию включён. Для готовой записи без сохранённой метки успешной
коррекции этот этап можно запустить из контекстного меню без повторного распознавания
аудио. После успешной коррекции пункт исчезает.
Подробнее: [действия с записями](docs/recording-actions.md),
[проверка переключателя и метки коррекции](docs/text-correction-option-validation.md).
Модели упакованы в APK, сеть не используется. Библиотека, результаты, правки и tombstone
сохраняются атомарным снимком в приватном каталоге приложения.
Полный путь проверен end-to-end на vivo V2538 (Snapdragon 4 Gen 2): сэмпл ~15 с
превращается в готовый транскрипт за ~35 с (LLM-коррекция 3,5 ток/с — доминирующий
этап; WER до/после на записях A/B/C команда ещё не измерила).
Диаризация проверена на 9 записях с известным числом говорящих (в том числе 3 реальных русских,
одна из них дебаты с перебиваниями): число спикеров верно во всех 9. Точность границ реплик (DER)
и WER на размеченных русских записях **не измерены**: такой разметки у команды нет.
См. [диаризация](docs/diarization.md), [LLM-коррекция](docs/llm-correction.md),
[VAD/ASR](docs/vad-asr.md).

LLM-коррекция (#7): Qwen2.5-1.5B-Instruct Q4_K_M на закреплённом llama.cpp v0.9.0
через JNI (`LlamaTextCorrector`) — встроена в `DiarizedDraftRunner` как этап
`CORRECT_TEXT`: модель грузится после освобождения GigaAM и освобождается после
этапа; промпт на русском, жадное декодирование, защита от галлюцинаций
(`CorrectionGuard`) возвращает исходный текст при сомнительной правке. Подробности
и ограничения: [llm-correction](docs/llm-correction.md).

Запись микрофона: один AudioRecord сохраняет WAV PCM16 mono 16 кГц (1,92 МБ/мин),
поддерживает паузу, остановку с финальной обработкой и отдельную отмену без импорта.
Необязательный Live-предпросмотр использует настоящие Silero/GigaAM на ограниченных
фрагментах того же PCM; это ещё не финальная диаризованная расшифровка.
Личные текстовые пометки и отметки времени доступны во время записи и после обработки,
сохраняются между запусками и не включаются в JSON расшифровки.
См. [инструкцию и ограничения](docs/audio-recording.md).

## Контракты пайплайна

В `app/src/main/java/org/cfm/catscriptor/` выделены границы `ui`, `viewmodel`,
`domain/pipeline` и `native/models`. Контракты отделены от конкретных моделей.
Новых Gradle-модулей нет. Для экспорта добавлены Gson, Android-корутины и Lifecycle.

- Этапы: `AudioDecoder` → `VoiceActivityDetector` → `TranscriptionBackend` →
  `DiarizationBackend` → `UtteranceMerger` → `TextCorrectionBackend` → `TranscriptExporter`.
- PCM-источник даёт 16 кГц mono порциями по абсолютному смещению в **сэмплах**.
  Все интервалы используют **миллисекунды от начала исходной записи**.
  Источник принадлежит вызывающему коду; модельные backend реализуют `ModelResource`
  и закрываются после своего этапа, включая ошибки и отмену.
- ASR возвращает `AsrSegment(time, text)`: текст и абсолютный интервал обработанного
  аудиофрагмента из VAD или последующего разбиения по говорящим. Пустой текст
  допустим для тишины. Пословные метки и forced alignment не обязательны по ТЗ
  (слайды 8, 9, 14, 15, 17); прежняя обязательность `TimedWord` была ошибкой архитектуры.
- Один VAD-фрагмент может содержать нескольких говорящих. Issue #6 уточняет
  интервалы реплик и при необходимости повторно вызывает ASR на меньших фрагментах.
  Нельзя делить готовый текст между спикерами по выдуманным границам слов.
- `Utterance` хранит спикера, интервал реплики и текст без обязательного списка слов.
  LLM возвращает только исправленный текст; `copy(text = ...)` сохраняет спикера
  и временной интервал. Экспериментальный CTC alignment не участвует в этом пути.
- `Transcript.transcript` отсортирован по началу реплик, перекрытия допустимы.
  Контракт JSON не меняется: экспортируются только `speaker_id`, `start_ms`,
  `end_ms`, `text` внутри `transcript`.
   `DefaultTranscriptExporter` реализует JSON/TEXT, схема проверяется JVM-тестами.
- Длительные методы — `suspend`. Конкретные реализации должны выполнять тяжёлую
  работу вне UI-потока, поддерживать отмену и передавать ошибки вызывающему коду.
  Полная оркестрация, прогресс и fallback остаются отдельной интеграцией.

Контрактные JVM-тесты используют подмены только в `src/test`; они проверяют модель,
передачу текста и интервалов реплик и заменяемость этапов, но не качество распознавания,
алгоритм объединения или освобождение ресурсов настоящих моделей.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew assembleDebug assembleRelease test
```

## Экспорт JSON и TEXT

`DefaultTranscriptExporter` принимает существующий `Transcript` и пишет UTF-8 в
переданный `OutputStream`: не закрывает его и не сортирует реплики.
Gson `JsonWriter` используется без рефлексии и промежуточного JSON-дерева;
временные метки записываются как целые `Long`. TEXT использует формат
`[00:00:01.250 - 00:00:04.380] speaker_1: Добрый день!`, с переводом строки после
каждой реплики; число часов не ограничено двумя разрядами.

В приложении экспорт открывается листом «Экспорт расшифровки» (`ExportSheet`) на экране
расшифровки и в списке записей. Выбор:

- формат: **JSON** (строгая схема выше), **TXT** или **SRT**; для TXT и SRT можно подставлять
  имена спикеров вместо `speaker_N`, в JSON всегда `speaker_N`;
- «Сохранять с аудиофайлом» — ZIP с файлом расшифровки и исходным аудио без перекодирования
  (при «Поделиться» уходят два файла);
- «Только аудиофайл» — исходное аудио без расшифровки;
- «Сохранить» (системный диалог `CreateDocument`, без широких разрешений на хранилище) и
  «Поделиться» (share sheet через `FileProvider`).

Сериализацию JSON выполняет `DefaultTranscriptExporter`, остальные форматы собирает
`TranscriptRendering`, файлы пишет `FileExportGateway`. Готовой расшифровки и демонстрационных
данных в production-коде нет. Отдельные `ContentResolverTranscriptWriter` и
`TranscriptExportController` остались как тонкий слой записи документа через SAF.
Внешнее окно DocumentsUI и поворот экрана проверяются вручную. При ошибке провайдер может
оставить частично записанный документ; блокирующая операция провайдера не всегда прерывается
сразу при отмене.

Проверки:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

`DefaultTranscriptExporterTest` проверяет схему, экранирование, UTF-8, порядок,
перекрытия, большие метки и владение потоком. `ContentResolverTranscriptWriterTest`
использует провайдер только тестового APK: проверяет настоящий ContentResolver,
фоновую запись, закрытие при успехе/ошибке/отмене, MIME и отмену выбора.
Внешнее окно DocumentsUI и поворот экрана требуют отдельной ручной проверки
после подключения к экрану с настоящим `Transcript`. При ошибке провайдер может
оставить частично записанный документ; блокирующая операция провайдера не всегда
прерывается сразу при отмене. Поток `null` и ошибка закрытия обработаны кодом,
но пока не воспроизведены инструментальным тестом.

## Известные ограничения

- Скорость: на vivo V2538 (Snapdragon 4 Gen 2) сэмпл ~15 с обрабатывается ~35 с (RTF ≈ 2,3);
  основное время занимает LLM-коррекция (3,5 токена/с). Уровни «Быстрая» (0,5B) и «Выкл» в настройках
  ускоряют обработку; замер по уровням на длинных записях не проведён.
- Перекрывающаяся речь не разделяется: каждый момент принадлежит одному спикеру.
- Параметры диаризации подобраны на небольшом наборе (9 записей), число спикеров — по ответам
  членов команды, а не по разметке.
- Размер APK с моделями около 2 ГБ; ограничение организаторов на размер не уточнено.
- Только `arm64-v8a`.

## Сборка

Нужны Android SDK, NDK `28.2.13676358`, CMake `3.22.1`, JDK для текущего Gradle
и Python 3.10+. Сначала подготовьте модели и официальный AAR на компьютере:

```bash
python3 scripts/prepare_models.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease
```

Приложение поддерживает только архитектуру `arm64-v8a`.
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Release APK без настроенной
подписи: `app/build/outputs/apk/release/app-release-unsigned.apk`; успешная сборка
не означает, что этот файл можно установить без подписания.

## Модели

Модели и AAR не хранятся в Git. Единственный способ подготовки — скрипт выше:
он проверяет SHA-256 архива, отдельных обязательных файлов, AAR и лицензий.
Сеть нужна только компьютеру разработчика. При сборке сеть для получения моделей
не используется, отсутствие или повреждение обязательных файлов останавливает сборку.
Для первой сборки Gradle может отдельно потребоваться скачать обычные зависимости.

В таблице пути указаны относительно `app/src/main/assets/models/`.

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

Выбран официальный AAR sherpa-onnx **2.0.1**, а не собственная сборка: Android API
покрывает загрузку, decode, VAD и освобождение. Исследовательская сборка с экспортом
CTC logits включается отдельно, не заменяет официальный AAR по умолчанию. Нативные
библиотеки поступают из `dependencies/sherpa-onnx-2.0.1.aar`, в APK остаётся только ARM64.
Версия, URL и SHA-256 зафиксированы в `dependencies/sherpa-onnx.properties`.

`BundledModelStore` копирует проверенные assets в `noBackupFilesDir/models/`,
не перезаписывает неизменённые файлы и проверяет SHA-256 перед нативной загрузкой.
Нужны дополнительно примерно 214 МиБ внутреннего хранилища для ASR/VAD и ещё
~1065 МиБ под GGUF LLM. `SherpaModelResource`
загружает модель лениво на `Dispatchers.IO`; поток распознавания освобождается в
`finally`, recognizer — через `close()` владельца этапа. Закрытие идемпотентно,
его нужно вызывать вне UI-потока. Приложение не запрашивает `INTERNET`.

LLM (issue #7) работает через собственный JNI-мост `libllm-jni.so` поверх
llama.cpp v0.9.0 (vendored в `dependencies/llama.cpp`, в git не коммитится).
Исходники, версия и SHA-256 зафиксированы в `dependencies/llama-cpp.properties`,
лицензия MIT лежит в `assets/third-party/`. `LlamaTextCorrector` держит модель
один раз на этап `CORRECT_TEXT` и освобождает контекст и веса в `close()`.

Выбор моделей: ASR — GigaAM v3 CTC int8 (русская модель, команда выбрала её заранее; сравнение
вариантов v3 по WER/RTF на телефоне не проведено); диаризация — эмбеддинги ERes2Net-base по окнам
1,5 с и своя спектральная кластеризация (замена CAM++ обоснована в
[docs/diarization.md](docs/diarization.md)); LLM — Qwen2.5 0.5B/1.5B Q4_K_M. Все модели лежат
в APK, интернет не нужен. Токенные точки времени не подменяют интервалы реплик; пословное
выравнивание оставлено необязательным исследованием.

Диагностика на подключённом ARM64-телефоне:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.cfm.catscriptor.native.models.SherpaModelSmokeTest
```

LLM-коррекция проверяется отдельным долгим smoke-тестом (реальный JNI + GGUF из APK,
проверка в авиарежиме, пунктуация, неприкосновенность цифр/отрицаний, отмена,
повторная загрузка):

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.cfm.catscriptor.native.models.LlamaCorrectionSmokeTest
```

Тест действительно распознаёт WAV из официального архива, проверяет повторное
создание и закрытие модели. WAV упакован только в тестовый APK; ожидаемая готовая
расшифровка в основном APK отсутствует. Подробности, ограничения и результаты:
[docs/sherpa-onnx.md](docs/sherpa-onnx.md).
