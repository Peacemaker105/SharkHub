package com.chris.sharkhub.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chris.sharkhub.data.Prefs

/**
 * Holds the selected colour theme and shape style and persists both. Create once (in MainActivity)
 * and pass down. Reading [current] / [style] inside a composable makes it recompose when the
 * selection changes, so picking a new one restyles the whole app instantly.
 */
class ThemeController(context: Context) {
    private val prefs = Prefs(context)

    var current by mutableStateOf(Themes.byId(prefs.themeId))
        private set

    var style by mutableStateOf(Styles.byId(prefs.styleId))
        private set

    fun select(spec: ThemeSpec) {
        current = spec
        prefs.themeId = spec.id
    }

    fun selectStyle(spec: StyleSpec) {
        style = spec
        prefs.styleId = spec.style.id
    }
}
