package com.example.linecommunityjoiner

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object ActivationVerifier {
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUNqihkl0D+BqwoHyhO2d7NdobLJOsaKGxpoFa8Ul98Biq1MGEm4+qZqA7uEwAEA1CiYtc43zajeO6OZBOPn5Sw=="

    data class VerifyResult(
        val ok: Boolean,
        val message: String,
        val serial: String = "",
        val iat: Long = 0L,
        val exp: Long = 0L
    )

    private fun loadPublicKey(): PublicKey? {
        if (PUBLIC_KEY_B64.isBlank()) return null
        val der = Base64.decode(PUBLIC_KEY_B64.trim(), Base64.DEFAULT)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(der))
    }

    fun verifyActivationCode(localSerial: String, activationCode: String): VerifyResult {
        return try {
            val code = activationCode.trim()
            val parts = code.split(".")
            if (parts.size == 3 && parts[0] == "AC1") {
                val publicKey = loadPublicKey() ?: return VerifyResult(false, "客戶端尚未設定公鑰")
                val payloadBytes = B64Url.dec(parts[1])
                val sigBytes = B64Url.dec(parts[2])
                val sig = Signature.getInstance("SHA256withECDSA")
                sig.initVerify(publicKey)
                sig.update(payloadBytes)
                if (!sig.verify(sigBytes)) {
                    return VerifyResult(false, "啟動碼簽章驗證失敗")
                }
                val payloadStr = String(payloadBytes, StandardCharsets.UTF_8)
                val fields = payloadStr.split("|")
                if (fields.size != 3) return VerifyResult(false, "啟動碼內容格式錯誤")
                val serial = fields[0].trim()
                val iat = fields[1].trim().toLongOrNull() ?: return VerifyResult(false, "啟動碼 iat 錯誤")
                val exp = fields[2].trim().toLongOrNull() ?: return VerifyResult(false, "啟動碼 exp 錯誤")
                if (serial != localSerial) {
                    return VerifyResult(false, "此啟動碼不屬於目前這台裝置")
                }
                val now = System.currentTimeMillis() / 1000
                if (exp != 0L && now > exp) {
                    return VerifyResult(false, "啟動碼已過期")
                }
                VerifyResult(true, "啟動成功", serial, iat, exp)
            } else {
                VerifyResult(false, "啟動碼格式錯誤")
            }
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: e.javaClass.simpleName
            VerifyResult(false, "驗證失敗：$msg")
        }
    }

    fun isStoredLicenseStillValid(ctx: Context): Boolean {
        val code = LicensePrefs.getActivationCode(ctx) ?: return false
        val serial = DeviceSerial.getProductSerial(ctx)
        val result = verifyActivationCode(serial, code)
        if (!result.ok) {
            LicensePrefs.clear(ctx)
            return false
        }
        return true
    }
}
