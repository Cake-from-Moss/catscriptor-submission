package org.cfm.catscriptor.native.models

import android.content.res.AssetManager

/**
 * Полный набор моделей приложения: тот же, что упаковывается в APK `full`. Манифесты лежат в assets
 * в обоих вариантах сборки (они маленькие), поэтому `lite` знает, что и откуда скачивать.
 */
object ModelCatalog {
    fun load(assets: AssetManager): List<BundledModelSpec> = listOf(
        "model-manifest.properties" to BundledModelSpec.Companion::read,
        "vad-manifest.properties" to BundledModelSpec.Companion::readVad,
        "diarization-manifest.properties" to BundledModelSpec.Companion::readDiarization,
        "llm-fast-manifest.properties" to BundledModelSpec.Companion::readLlm,
        "llm-manifest.properties" to BundledModelSpec.Companion::readLlm,
    ).map { (manifest, reader) -> assets.open(manifest).use(reader) }
}
