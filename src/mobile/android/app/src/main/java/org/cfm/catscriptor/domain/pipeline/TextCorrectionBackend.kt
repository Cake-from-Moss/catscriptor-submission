package org.cfm.catscriptor.domain.pipeline

import org.cfm.catscriptor.native.models.ModelResource

interface TextCorrectionBackend : ModelResource {
    /**
     * Исправить текст локальной LLM. Если защита от галлюцинаций отклоняет правку, вернуть оригинал.
     * Вызывающий код применяет только copy(text = correctedText), сохраняя спикера, метки
     * и исходные слова. Для исправленных/добавленных слов новой привязки нет; выдумывать её нельзя.
     */
    suspend fun correct(utterance: Utterance): String
}
