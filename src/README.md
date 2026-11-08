# Исходный код

Код лежит в подкаталоге платформы:

- [`mobile/android`](mobile/android) — Android-приложение (Kotlin, Jetpack Compose, JNI на C++17).

## Инструкция по запуску и сборке проекта

Все команды выполняются из `src/mobile/android`.

**Что нужно:** Android SDK, NDK `28.2.13676358`, CMake `3.22.1`, JDK для текущего Gradle,
Python 3.10+ (для подготовки моделей), около 3 ГБ свободного места под модели и сборку. Интернет нужен
только на этапе подготовки (скачивание моделей и обычных зависимостей Gradle); приложение его не использует.

```bash
cd src/mobile/android

# 1. Скачать и проверить модели, официальный AAR sherpa-onnx и llama.cpp (проверка SHA-256).
python3 scripts/prepare_models.py

# 2. Собрать и проверить. Два варианта: full (модели в APK) и lite (модели скачиваются при первом запуске).
./gradlew assembleDebug assembleRelease test
# или по отдельности: :app:assembleFullDebug, :app:assembleLiteDebug

# 3. Установить на телефон (arm64-v8a, Android 11+) и запустить.
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

APK: `app/build/outputs/apk/full/debug/app-full-debug.apk` (~2 ГБ, модели внутри, `INTERNET` нет) и
`app/build/outputs/apk/lite/debug/app-lite-debug.apk` (~110 МБ, скачивает модели). Release-варианты не подписаны
(`*-release-unsigned.apk`) и не устанавливаются. Модели в Git не хранятся (`.gitignore`); сборка `full`
останавливается, если файла нет или его хеш не совпал с манифестом.

Тесты на устройстве (нужен подключённый arm64-телефон или эмулятор):

```bash
./gradlew :app:connectedFullDebugAndroidTest   # или :app:connectedLiteDebugAndroidTest (модели должны быть скачаны)
```

Пользовательская инструкция (установка APK, импорт, экспорт JSON) — в корневом [`README.md`](../README.md).
Архитектура: [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md). Таблица моделей с контрольными суммами:
[`mobile/android/README.md`](mobile/android/README.md).
