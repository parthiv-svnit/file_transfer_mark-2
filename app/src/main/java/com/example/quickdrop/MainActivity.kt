package com.example.quickdrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.ArrayList
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private var server: WebServer? = null
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var infoText: TextView

    private val STORAGE_PERMISSION_CODE = 100
    private val ALL_FILES_ACCESS_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // --- UI Setup ---
            val layout = LinearLayout(this)
            layout.orientation = LinearLayout.VERTICAL
            layout.gravity = Gravity.CENTER
            layout.setPadding(60, 60, 60, 60)
            layout.setBackgroundColor(0xFF111827.toInt())

            val title = TextView(this)
            title.text = "QuickDrop"
            title.textSize = 36f
            title.gravity = Gravity.CENTER
            title.setTextColor(0xFF818CF8.toInt())
            title.typeface = android.graphics.Typeface.DEFAULT_BOLD
            layout.addView(title)

            statusText = TextView(this)
            statusText.text = "Initializing..."
            statusText.setTextColor(0xFF9CA3AF.toInt())
            statusText.textSize = 18f
            statusText.gravity = Gravity.CENTER
            statusText.setPadding(0, 40, 0, 10)
            layout.addView(statusText)

            urlText = TextView(this)
            urlText.text = "..."
            urlText.setTextColor(0xFFFFFFFF.toInt())
            urlText.textSize = 28f
            urlText.gravity = Gravity.CENTER
            urlText.typeface = android.graphics.Typeface.MONOSPACE
            urlText.setPadding(0, 10, 0, 40)
            layout.addView(urlText)

            infoText = TextView(this)
            infoText.text = "Starting..."
            infoText.setTextColor(0xFF6B7280.toInt())
            infoText.gravity = Gravity.CENTER
            layout.addView(infoText)

            setContentView(layout)

            if (checkPermission()) {
                handleIntent(intent)
            } else {
                requestPermission()
            }

        } catch (e: Exception) {
            showError("Startup Error: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            if (checkPermission()) {
                handleIntent(intent)
            }
        } catch (e: Exception) {
            showError("Intent Error: ${e.message}")
        }
    }

    // --- PERMISSION LOGIC ---
    private fun checkPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                startActivityForResult(intent, ALL_FILES_ACCESS_CODE)
                Toast.makeText(this, "Please allow 'All files access' to share storage", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, ALL_FILES_ACCESS_CODE)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ALL_FILES_ACCESS_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    handleIntent(intent)
                } else {
                    statusText.text = "Permission Denied"
                    infoText.text = "All files access is required."
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                handleIntent(intent)
            } else {
                statusText.text = "Permission Denied"
                infoText.text = "Storage access is required."
            }
        }
    }

    // --- APP LOGIC ---
    @Suppress("DEPRECATION")
    private fun getParcelableUri(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key) as? Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun getParcelableUriList(intent: Intent, key: String): ArrayList<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }
    }

    private fun handleIntent(intent: Intent) {
        try {
            val action = intent.action
            val sharedUris = ArrayList<Uri>()

            val isSend = Intent.ACTION_SEND == action
            val isSendMultiple = Intent.ACTION_SEND_MULTIPLE == action

            if (isSend) {
                val uri = getParcelableUri(intent, Intent.EXTRA_STREAM)
                if (uri != null) {
                    sharedUris.add(uri)
                    statusText.text = "Sharing 1 File"
                }
            }
            else if (isSendMultiple) {
                val uris = getParcelableUriList(intent, Intent.EXTRA_STREAM)
                if (uris != null) {
                    sharedUris.addAll(uris)
                    statusText.text = "Sharing ${sharedUris.size} Files"
                }
            }
            else {
                statusText.text = "Sharing Full Storage"
            }

            startServer(sharedUris)

        } catch (e: Exception) {
            showError("Logic Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startServer(sharedUris: List<Uri>) {
        try {
            server?.stopServer()

            val port = 5000
            val ip = getIpAddress()

            // Default to full internal storage
            val rootDir = if (sharedUris.isEmpty()) {
                Environment.getExternalStorageDirectory()
            } else {
                null
            }

            server = WebServer(port, contentResolver, if (sharedUris.isNotEmpty()) sharedUris else null, rootDir)
            server?.start()

            if (ip == "0.0.0.0") {
                urlText.text = "No Network"
                urlText.setTextColor(0xFFEF4444.toInt())
                infoText.text = "Check Hotspot/Wi-Fi"
            } else {
                urlText.text = "http://$ip:$port"
                urlText.setTextColor(0xFFFFFFFF.toInt())
                infoText.text = "Open in browser"
            }
        } catch (e: Exception) {
            showError("Server Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "0.0.0.0"
    }

    private fun showError(message: String) {
        runOnUiThread {
            try {
                statusText.text = "Error"
                infoText.text = message
                infoText.setTextColor(0xFFEF4444.toInt())
            } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            server?.stopServer()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}