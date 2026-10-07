package so.ijarjar.app

import android.content.Context

/** Very small two-language helper: Somali (default) and English. */
object L {
    var english = false
        private set

    fun init(context: Context) {
        english = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("english", false)
    }

    fun setEnglish(context: Context, value: Boolean) {
        english = value
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("english", value).apply()
    }

    fun t(so: String, en: String): String = if (english) en else so
}
