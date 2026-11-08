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

# 2. Собрать и проверить.
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleRelease          # без подписи: app-release-unsigned.apk, не устанавливается

# 3. Установить на телефон (arm64-v8a, Android 11+) и запустить.
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (около 2 ГБ, модели внутри). Модели в Git не хранятся
(`.gitignore`), сборка останавливается, если файла нет или его хеш не совпал с манифестом.

Тесты на устройстве (нужен подключённый arm64-телефон или эмулятор):

```bash
./gradlew :app:connectedDebugAndroidTest
```

Пользовательская инструкция (установка APK, импорт, экспорт JSON) — в корневом [`README.md`](../README.md).
Архитектура: [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md). Таблица моделей с контрольными суммами:
[`mobile/android/README.md`](mobile/android/README.md).
