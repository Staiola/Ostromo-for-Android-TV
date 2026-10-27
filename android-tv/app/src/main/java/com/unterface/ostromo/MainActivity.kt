// The whole app is the web app from ../../nostromo in one full-screen WebView,
// served from https://appassets.androidplatform.net/ so it has a secure
// origin. Requests to api/* there are answered by Api (the Kotlin port of
// nostromo-service), the same paths the Mac preview's dev-server.js answers.
package com.unterface.ostromo

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

class MainActivity : Activity() {

    private lateinit var web: WebView

    // Android keys the WebView doesn't hand the page, sent as the webOS key
    // codes main.js already knows (BACK and the colour buttons)
    private val webOsKeys = mapOf(
        KeyEvent.KEYCODE_BACK to 461,
        KeyEvent.KEYCODE_PROG_RED to 403,
        KeyEvent.KEYCODE_PROG_GREEN to 404,
        KeyEvent.KEYCODE_PROG_YELLOW to 405,
        KeyEvent.KEYCODE_PROG_BLUE to 406
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ostromo draws its own standby (main.js), so the system screensaver
        // stays away while it's in front, as on webOS
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) // chrome://inspect

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            isFocusable = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true            // settings.js, fx.js
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            addJavascriptInterface(Launcher(this@MainActivity), "OstromoAndroid")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN && url.path.orEmpty().startsWith("/api/")) {
                        return Api.handle(url)
                    }
                    return assets.shouldInterceptRequest(url)
                }
            }
        }
        setContentView(web)
        web.requestFocus()
        web.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/index.html")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = webOsKeys[event.keyCode] ?: return super.dispatchKeyEvent(event)
        // main.js decides: back a screen, or out of the app from home (Launcher.leave)
        if (event.action == KeyEvent.ACTION_DOWN) {
            web.evaluateJavascript(
                "(function(c){var e=new KeyboardEvent('keydown',{bubbles:true,cancelable:true});" +
                    "Object.defineProperty(e,'keyCode',{get:function(){return c}});document.dispatchEvent(e)})($code)",
                null
            )
        }
        return true
    }

    // off screen (another app opened from the grid): no video, sound or polling
    override fun onPause() {
        super.onPause()
        web.onPause()
        web.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        web.resumeTimers()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
