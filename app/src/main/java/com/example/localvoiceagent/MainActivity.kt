package com.example.localvoiceagent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var permissionStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        permissionStatus = findViewById(R.id.permissionStatus)
        permissionStatus.setOnClickListener { requestMic() }
        // エンジンのバージョン表示は Issue #7（.so ロードスモーク）で接続する
        findViewById<TextView>(R.id.engineVersion).text =
            "engine: not loaded\n" + SherpaRuntime.status()
        requestMic()
    }

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionStatus.setText(R.string.mic_granted)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        permissionStatus.setText(if (granted) R.string.mic_granted else R.string.mic_denied)
    }
}
