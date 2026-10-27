// window.OstromoAndroid: answers the two webOS application manager calls the
// home screen makes (see K.luna in nostromo/kit.js), so home.js runs as is.
//   listLaunchPoints -> the TV's apps, { launchPoints: [{ id, title }] }
//   launch { id }    -> an app by package name, or com.webos.app.hdmiN
// and leave(), for BACK on the home screen.
package com.unterface.ostromo

import android.app.Activity
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class Launcher(private val activity: Activity) {

    @JavascriptInterface
    fun call(uri: String, params: String): String = try {
        val p = JSONObject(params)
        when (uri) {
            "luna://com.webos.applicationManager/listLaunchPoints" -> launchPoints()
            "luna://com.webos.applicationManager/launch" -> launch(p.optString("id"))
            else -> fail("not on Android: $uri")
        }
    } catch (e: Exception) {
        fail(e.message ?: e.toString())
    }.toString()

    // BACK on the home screen: back to Google TV, keeping Ostromo as it is
    @JavascriptInterface
    fun leave() {
        activity.runOnUiThread { activity.moveTaskToBack(true) }
    }

    private fun ok() = JSONObject().put("returnValue", true)
    private fun fail(text: String) = JSONObject().put("returnValue", false).put("errorText", text)

    private fun launchPoints(): JSONObject {
        val pm = activity.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val points = JSONArray()
        pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != activity.packageName }
            .forEach { points.put(JSONObject().put("id", it.activityInfo.packageName).put("title", it.loadLabel(pm).toString())) }
        return ok().put("launchPoints", points)
    }

    private fun launch(id: String): JSONObject {
        val hdmi = Regex("^com\\.webos\\.app\\.hdmi(\\d)$").find(id)
        val intent = if (hdmi != null) hdmiIntent(hdmi.groupValues[1].toInt())
        else activity.packageManager.getLeanbackLaunchIntentForPackage(id)
            ?: activity.packageManager.getLaunchIntentForPackage(id)
        intent ?: return fail("no app or input $id")
        activity.startActivity(intent)
        return ok()
    }

    // HDMI port n as the TV's own TV app shows it. Only TVs have passthrough
    // inputs (a Chromecast or Shield has none), and which input is "HDMI 2"
    // isn't in the API, so this counts the HDMI inputs in id order.
    private fun hdmiIntent(port: Int): Intent? {
        val tv = activity.getSystemService(TvInputManager::class.java) ?: return null
        val inputs = tv.tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.isPassthroughInput && it.parentId == null }
            .sortedBy { it.id }
        val input = inputs.getOrNull(port - 1) ?: return null
        return Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.id))
    }
}
