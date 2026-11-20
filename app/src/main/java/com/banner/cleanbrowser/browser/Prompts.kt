package com.banner.cleanbrowser.browser

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Prompt {
    class Permission(val tab: Tab, val request: PermissionRequest, val resources: List<String>) : Prompt
    class Geo(val tab: Tab, val origin: String, val callback: GeolocationPermissions.Callback) : Prompt
    class OpenApp(val intent: Intent, val label: String) : Prompt
    class HttpFallback(val tab: Tab, val httpUrl: String) : Prompt
    class Ssl(val tab: Tab, val url: String, val reason: String) : Prompt
    class LinkMenu(val tab: Tab, val link: String?, val image: String?) : Prompt
    class Download(val request: com.banner.cleanbrowser.download.DownloadRequest) : Prompt
    class Magnet(val uri: String) : Prompt
}

class FileChooser(val callback: ValueCallback<Array<Uri>>, val params: WebChromeClient.FileChooserParams)
class CustomView(val view: View, val callback: WebChromeClient.CustomViewCallback)

/** UI requests raised by WebView callbacks, shown by the Compose layer. */
object Ui {
    var prompt by mutableStateOf<Prompt?>(null)
    var fileChooser by mutableStateOf<FileChooser?>(null)
    var customView by mutableStateOf<CustomView?>(null)
    var message by mutableStateOf<String?>(null)
    var findBar by mutableStateOf(false)

    fun toast(text: String) {
        message = text
    }
}
