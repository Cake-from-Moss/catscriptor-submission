package org.cfm.catscriptor.ui.components

import java.util.Locale

/** Таймкод `мм:сс`, а для записей от часа `ч:мм:сс`. */
fun formatTimecode(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
