package org.cfm.catscriptor.app

import android.app.Application

/** Держит граф зависимостей на всё время жизни процесса. */
class CatscriptorApp : Application() {
    val graph: AppGraph by lazy { ProductionAppGraph(this) }
}
