package com.example.linecommunityjoiner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class OfflineActivateActivity : ComponentActivity() {
    private lateinit var tvSerial: TextView
    private lateinit var etActivation: EditText
    private lateinit var btnCopySerial: Button
    private lateinit var btnPaste: Button
    private lateinit var btnActivate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startMain()
        return
        setContentView(R.layout.activity_offline_activate)
        tvSerial = findViewById(R.id.tvSerial)
        etActivation = findViewById(R.id.etActivation)
        btnCopySerial = findViewById(R.id.btnCopySerial)
        btnPaste = findViewById(R.id.btnPaste)
        btnActivate = findViewById(R.id.btnActivate)

        val serial = DeviceSerial.getProductSerial(this)
        tvSerial.text = serial

        btnCopySerial.setOnClickListener {
            copyText(serial)
            toast("已複製產品序號")
        }
        btnPaste.setOnClickListener { etActivation.setText(readClipboard()) }
        btnActivate.setOnClickListener {
            val code = etActivation.text?.toString()?.trim().orEmpty()
            if (code.isBlank()) {
                toast("請貼上啟動碼")
                return@setOnClickListener
            }
            val result = ActivationVerifier.verifyActivationCode(serial, code)
            if (!result.ok) {
                toast(result.message)
                return@setOnClickListener
            }
            LicensePrefs.saveActivatedLicense(
                this,
                code,
                result.serial,
                result.iat,
                result.exp
            )
            toast("啟動成功")
            startMain()
        }
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("serial", text))
    }

    private fun readClipboard(): String {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val item = cm.primaryClip?.getItemAt(0)
        return item?.coerceToText(this)?.toString().orEmpty()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
