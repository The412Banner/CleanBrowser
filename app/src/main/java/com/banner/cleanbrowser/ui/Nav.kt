package com.banner.cleanbrowser.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { BROWSER, TABS, SETTINGS, BOOKMARKS, HISTORY, DOWNLOADS }

object Nav {
    var screen by mutableStateOf(Screen.BROWSER)
    /** Settings sub-page stack; empty = settings home. */
    val settingsStack = mutableStateListOf<String>()
    var focusAddress by mutableStateOf(false)
    var showPrivateTabs by mutableStateOf(false)

    fun open(s: Screen) {
        if (s == Screen.SETTINGS) settingsStack.clear()
        screen = s
    }

    fun back(): Boolean {
        if (screen == Screen.SETTINGS && settingsStack.isNotEmpty()) {
            settingsStack.removeAt(settingsStack.lastIndex)
            return true
        }
        if (screen != Screen.BROWSER) {
            screen = Screen.BROWSER
            return true
        }
        return false
    }
}
