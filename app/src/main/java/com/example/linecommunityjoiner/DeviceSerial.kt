package com.example.linecommunityjoiner

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object DeviceSerial {
    private const val PREF = "offline_license_pref"
    private const val KEY_INSTALL_ID = "install_id"
    private const val PRODUCT_CODE = "LINE_COMMUNITY_JOINER"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun getOrCreateInstallId(ctx: Context): String {
        val old = prefs(ctx).getString(KEY_INSTALL_ID, null)
        if (old.isNullOrBlank()) {
            val uuid = UUID.randomUUID().toString()
            val newId = uuid.replace("-", "").uppercase(Locale.ROOT)
            prefs(ctx).edit().putString(KEY_INSTALL_ID, newId).apply()
            return newId
        }
        return old
    }

    fun getProductSerial(ctx: Context): String {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val installId = getOrCreateInstallId(ctx)
        val raw = "${ctx.packageName}|$PRODUCT_CODE|$androidId|$installId"
        val sha = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return sha.take(16).joinToString("") { "%02X".format(it) }
    }
}
