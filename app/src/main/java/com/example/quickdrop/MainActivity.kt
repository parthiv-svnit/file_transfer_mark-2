package com.example.quickdrop

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.ArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var infoText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var qrCodeImage: ImageView
    private lateinit var dropZone: LinearLayout
    private lateinit var stopButton: Button
    private lateinit var privateShareCheckbox: CheckBox

    private val currentSharedUris = ArrayList<Uri>()
    private var isServerRunning = false

    private val STORAGE_PERMISSION_CODE = 100
    private val ALL_FILES_ACCESS_CODE = 101

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val newUris = ArrayList<Uri>()
            if (data?.clipData != null) {
                val count = data.clipData!!.itemCount
                for (i in 0 until count) {
                    newUris.add(data.clipData!!.getItemAt(i).uri)
                }
            } else if (data?.data != null) {
                newUris.add(data.data!!)
            }
            if (newUris.isNotEmpty()) {
                addToSharedList(newUris)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val layout = LinearLayout(this)
            layout.orientation = LinearLayout.VERTICAL
            layout.gravity = Gravity.CENTER_HORIZONTAL
            layout.setPadding(40, 40, 40, 40)
            layout.setBackgroundColor(0xFF111827.toInt())

            val title = TextView(this)
            title.text = "QuickDrop"
            title.textSize = 32f
            title.gravity = Gravity.CENTER
            title.setTextColor(0xFF818CF8.toInt())
            title.typeface = android.graphics.Typeface.DEFAULT_BOLD
            title.setPadding(0, 20, 0, 20)
            layout.addView(title)

            qrCodeImage = ImageView(this)
            val qrParams = LinearLayout.LayoutParams(600, 600)
            qrParams.gravity = Gravity.CENTER
            qrParams.setMargins(0, 20, 0, 20)
            qrCodeImage.layoutParams = qrParams
            layout.addView(qrCodeImage)

            statusText = TextView(this)
            statusText.text = "Initializing..."
            statusText.setTextColor(0xFF9CA3AF.toInt())
            statusText.textSize = 16f
            statusText.gravity = Gravity.CENTER
            layout.addView(statusText)

            val urlContainer = LinearLayout(this)
            urlContainer.orientation = LinearLayout.HORIZONTAL
            urlContainer.gravity = Gravity.CENTER
            val urlParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            urlParams.setMargins(0, 10, 0, 0)
            urlContainer.layoutParams = urlParams
            urlContainer.setBackgroundColor(0xFF1F2937.toInt())
            urlContainer.setPadding(30, 20, 30, 20)

            urlText = TextView(this)
            urlText.text = "..."
            urlText.setTextColor(0xFFFFFFFF.toInt())
            urlText.textSize = 20f
            urlText.typeface = android.graphics.Typeface.MONOSPACE
            urlText.setTextIsSelectable(true)
            urlContainer.addView(urlText)

            val copyBtn = ImageButton(this)
            copyBtn.setImageResource(android.R.drawable.ic_menu_save)
            copyBtn.setBackgroundColor(Color.TRANSPARENT)
            copyBtn.setOnClickListener { copyLink() }
            val btnParams = LinearLayout.LayoutParams(100, 100)
            btnParams.setMargins(20, 0, 0, 0)
            copyBtn.layoutParams = btnParams
            urlContainer.addView(copyBtn)

            val refreshBtn = Button(this)
            refreshBtn.text = "↻"
            refreshBtn.textSize = 24f
            refreshBtn.setTextColor(0xFF60A5FA.toInt())
            refreshBtn.setBackgroundColor(Color.TRANSPARENT)
            refreshBtn.setOnClickListener { refreshIp() }
            refreshBtn.layoutParams = btnParams
            refreshBtn.setPadding(0, 0, 0, 10)
            urlContainer.addView(refreshBtn)

            layout.addView(urlContainer)

            feedbackText = TextView(this)
            feedbackText.text = ""
            feedbackText.textSize = 14f
            feedbackText.setTextColor(0xFF10B981.toInt())
            feedbackText.gravity = Gravity.CENTER
            feedbackText.alpha = 0f
            layout.addView(feedbackText)

            infoText = TextView(this)
            infoText.text = "Starting..."
            infoText.setTextColor(0xFF6B7280.toInt())
            infoText.gravity = Gravity.CENTER
            infoText.setPadding(0, 20, 0, 10)
            layout.addView(infoText)

            privateShareCheckbox = CheckBox(this)
            privateShareCheckbox.text = "Private Share (Hide Storage)"
            privateShareCheckbox.isChecked = true
            privateShareCheckbox.setTextColor(0xFFD1D5DB.toInt())
            privateShareCheckbox.gravity = Gravity.CENTER
            privateShareCheckbox.visibility = View.GONE
            privateShareCheckbox.setOnCheckedChangeListener { _, _ ->
                if(isServerRunning && currentSharedUris.isNotEmpty()) restartServiceWithData(currentSharedUris)
            }
            layout.addView(privateShareCheckbox)

            dropZone = LinearLayout(this)
            dropZone.orientation = LinearLayout.VERTICAL
            dropZone.gravity = Gravity.CENTER
            dropZone.setBackgroundColor(0xFF1F2937.toInt())
            val dropParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            dropParams.setMargins(0, 10, 0, 20)
            dropZone.layoutParams = dropParams

            val dropIcon = TextView(this)
            dropIcon.text = "📂"
            dropIcon.textSize = 40f
            dropIcon.gravity = Gravity.CENTER
            dropZone.addView(dropIcon)

            val dropText = TextView(this)
            dropText.text = "Tap to Select or Drop Files"
            dropText.setTextColor(0xFFD1D5DB.toInt())
            dropText.gravity = Gravity.CENTER
            dropZone.addView(dropText)

            layout.addView(dropZone)

            stopButton = Button(this)
            stopButton.text = "Turn Off Server"
            stopButton.setBackgroundColor(0xFFEF4444.toInt())
            stopButton.setTextColor(Color.WHITE)
            val stopParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150)
            stopButton.layoutParams = stopParams
            stopButton.setOnClickListener { toggleServer() }
            layout.addView(stopButton)

            setContentView(layout)

            setupDragAndDrop()
            dropZone.setOnClickListener { openFilePicker() }

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

    private fun showFeedback(message: String) {
        feedbackText.text = message
        feedbackText.alpha = 1f
        feedbackText.animate()
            .alpha(0f)
            .setDuration(2000)
            .setStartDelay(1000)
            .setListener(null)
    }

    private fun animateUrlText() {
        val scaleX = ObjectAnimator.ofFloat(urlText, "scaleX", 1f, 1.05f, 1f)
        val scaleY = ObjectAnimator.ofFloat(urlText, "scaleY", 1f, 1.05f, 1f)
        scaleX.duration = 500
        scaleY.duration = 500
        scaleX.interpolator = AccelerateDecelerateInterpolator()
        scaleY.interpolator = AccelerateDecelerateInterpolator()
        scaleX.start()
        scaleY.start()
    }

    private fun addToSharedList(newUris: ArrayList<Uri>) {
        currentSharedUris.addAll(newUris)
        restartServiceWithData(currentSharedUris)
    }

    private fun setupDragAndDrop() {
        dropZone.setOnDragListener { v, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.setBackgroundColor(0xFF374151.toInt())
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    v.setBackgroundColor(0xFF1F2937.toInt())
                    true
                }
                DragEvent.ACTION_DROP -> {
                    v.setBackgroundColor(0xFF1F2937.toInt())
                    val clipData = event.clipData
                    if (clipData != null && clipData.itemCount > 0) {
                        val uris = ArrayList<Uri>()
                        for (i in 0 until clipData.itemCount) {
                            uris.add(clipData.getItemAt(i).uri)
                        }
                        addToSharedList(uris)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> true
                else -> false
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        filePickerLauncher.launch(intent)
    }

    private fun toggleServer() {
        if (isServerRunning) {
            stopServer()
        } else {
            currentSharedUris.clear()
            restartServiceWithData(currentSharedUris)
        }
    }

    private fun stopServer() {
        try {
            val serviceIntent = Intent(this, QuickDropService::class.java)
            stopService(serviceIntent)
            isServerRunning = false

            statusText.text = "Server Stopped"
            statusText.setTextColor(0xFFEF4444.toInt())
            urlText.text = "Offline"
            infoText.text = "Tap 'Start' to restart"
            qrCodeImage.setImageBitmap(null)

            stopButton.text = "Start Server (Full Storage)"
            stopButton.setBackgroundColor(0xFF10B981.toInt())

            privateShareCheckbox.visibility = View.GONE

        } catch (e: Exception) {
            showError("Stop Error: ${e.message}")
        }
    }

    private fun copyLink() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("QuickDrop URL", urlText.text)
        clipboard.setPrimaryClip(clip)
        showFeedback("Link Copied!")
        animateUrlText()
    }

    private fun refreshIp() {
        if (isServerRunning) {
            restartServiceWithData(currentSharedUris)
            showFeedback("IP Refreshed!")
        }
    }

    private fun restartServiceWithData(uris: ArrayList<Uri>) {
        try {
            val ip = getIpAddress()
            val port = 5000
            val url = "http://$ip:$port"

            if (ip == "0.0.0.0") {
                urlText.text = "No Network"
                urlText.setTextColor(0xFFEF4444.toInt())
                infoText.text = "Check Hotspot/Wi-Fi"
                qrCodeImage.setImageBitmap(null)
            } else {
                urlText.text = url
                urlText.setTextColor(0xFFFFFFFF.toInt())
                infoText.text = "Scan QR or open in browser"
                generateQRCode(url)

                if (uris.isEmpty()) {
                    statusText.text = "Sharing Full Storage"
                    privateShareCheckbox.visibility = View.GONE
                } else {
                    statusText.text = "Sharing ${uris.size} Files"
                    privateShareCheckbox.visibility = View.VISIBLE
                }

                statusText.setTextColor(0xFF10B981.toInt())
                stopButton.text = "Turn Off Server"
                stopButton.setBackgroundColor(0xFFEF4444.toInt())
                isServerRunning = true
                animateUrlText()
            }

            val serviceIntent = Intent(this, QuickDropService::class.java)
            serviceIntent.putParcelableArrayListExtra("SHARED_URIS", uris)
            serviceIntent.putExtra("IS_PRIVATE", privateShareCheckbox.isChecked)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

        } catch (e: Exception) {
            showError("Service Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleIntent(intent: Intent) {
        try {
            val action = intent.action
            val sharedUris = ArrayList<Uri>()

            if (Intent.ACTION_SEND == action) {
                val uri = getParcelableUri(intent, Intent.EXTRA_STREAM)
                if (uri != null) sharedUris.add(uri)
            }
            else if (Intent.ACTION_SEND_MULTIPLE == action) {
                val uris = getParcelableUriList(intent, Intent.EXTRA_STREAM)
                if (uris != null) sharedUris.addAll(uris)
            }

            currentSharedUris.clear()
            currentSharedUris.addAll(sharedUris)
            restartServiceWithData(currentSharedUris)

        } catch (e: Exception) {
            showError("Logic Error: ${e.message}")
            e.printStackTrace()
        }
    }

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
                intent.data = Uri.parse("package:$packageName")
                @Suppress("DEPRECATION")
                startActivityForResult(intent, ALL_FILES_ACCESS_CODE)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                @Suppress("DEPRECATION")
                startActivityForResult(intent, ALL_FILES_ACCESS_CODE)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ALL_FILES_ACCESS_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) handleIntent(intent)
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) handleIntent(intent)
        }
    }

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

    private fun generateQRCode(text: String) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            qrCodeImage.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
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
}