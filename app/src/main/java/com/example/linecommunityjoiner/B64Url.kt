package com.example.linecommunityjoiner

import android.util.Base64

object B64Url {
    fun enc(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun dec(text: String): ByteArray {
        return Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE)
    }
}
