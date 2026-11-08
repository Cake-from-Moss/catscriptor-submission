package org.cfm.catscriptor

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.cfm.catscriptor.app.CatscriptorApp
import org.cfm.catscriptor.ui.navigation.StenografApp
import org.cfm.catscriptor.ui.theme.StenografTheme

class MainActivity : ComponentActivity() {

    /**
     * Интерфейс только на русском, поэтому локаль закреплена: иначе на телефоне с английской
     * системой склонения («2 спикера», «5 минут») и названия месяцев считались бы по-английски.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag("ru")))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as CatscriptorApp).graph
        setContent {
            StenografTheme {
                StenografApp(graph)
            }
        }
    }

    /** Реализовано в нативной библиотеке 'catscriptor' (app/src/main/cpp/native-lib.cpp). */
    external fun stringFromJNI(): String

    companion object {
        init {
            System.loadLibrary("catscriptor")
        }
    }
}
