package com.example.linecommunityjoiner

import android.content.Context

object LicensePrefs {
    private const val PREF = "offline_license_pref"
    private const val KEY_ACTIVATION_CODE = "activation_code"
    private const val KEY_LICENSE_SERIAL = "license_serial"
    private const val KEY_LICENSE_IAT = "license_iat"
    private const val KEY_LICENSE_EXP = "license_exp"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun saveActivatedLicense(ctx: Context, activationCode: String, serial: String, iat: Long, exp: Long) {
        prefs(ctx).edit()
            .putString(KEY_ACTIVATION_CODE, activationCode)
            .putString(KEY_LICENSE_SERIAL, serial)
            .putLong(KEY_LICENSE_IAT, iat)
            .putLong(KEY_LICENSE_EXP, exp)
            .apply()
    }

    fun getActivationCode(ctx: Context): String? {
        return prefs(ctx).getString(KEY_ACTIVATION_CODE, null)
    }

    fun getSerial(ctx: Context): String? {
        return prefs(ctx).getString(KEY_LICENSE_SERIAL, null)
    }

    fun getIat(ctx: Context): Long {
        return prefs(ctx).getLong(KEY_LICENSE_IAT, 0L)
    }

    fun getExp(ctx: Context): Long {
        return prefs(ctx).getLong(KEY_LICENSE_EXP, 0L)
    }

    fun isActivated(ctx: Context): Boolean {
        return !getActivationCode(ctx).isNullOrBlank()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_ACTIVATION_CODE)
            .remove(KEY_LICENSE_SERIAL)
            .remove(KEY_LICENSE_IAT)
            .remove(KEY_LICENSE_EXP)
            .apply()
    }
}
