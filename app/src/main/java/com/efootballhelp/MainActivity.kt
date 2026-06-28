/*
 * Efootballhelp - Floating overlay canvas and gameplay helper.
 * Copyright (C) 2026 Efootballhelp Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.efootballhelp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The main activity that requests permissions (Overlay, POST_NOTIFICATIONS, MediaProjection)
 * and controls the lifecycle of the overlay service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // Permission request launchers
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            checkAndRequestNotificationPermission()
        } else {
            showToast("System overlay permission is required to draw gameplay helper arrows.")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initiateScreenCaptureRequest()
        } else {
            showToast("Notification permission is required to run the background service.")
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val startIntent = Intent(this, AssistantService::class.java).apply {
                putExtra(AssistantService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AssistantService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            showToast("Efootballhelp Assistant service started successfully!")
            finish() // Minimize UI to let the user see the eFootball screen
        } else {
            showToast("Screen capture permission was rejected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Create a beautiful dashboard layout programmatically
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 80)
            setBackgroundColor(0xFF121212.toInt()) // Deep Dark theme
        }

        val headerText = TextView(this).apply {
            text = "Efootballhelp"
            textSize = 32f
            setTextColor(0xFF00E676.toInt()) // Vibrant Green
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val subtitleText = TextView(this).apply {
            text = "Tactical Overlay & Vector Engine"
            textSize = 16f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 10, 0, 80)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val infoCard = TextView(this).apply {
            text = "This app runs a secure background service that scans gameplay graphics to calculate and draw real-time passing routes, player overlays, and defender threats on top of eFootball Mobile."
            textSize = 14f
            setTextColor(0xFFECEFF1.toInt())
            setLineSpacing(0f, 1.2f)
            setPadding(40, 40, 40, 40)
            setBackgroundColor(0xFF1E1E1E.toInt())
        }

        // Add spacer
        val spacer = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 80
        )

        val btnStart = Button(this).apply {
            text = "Launch Tactical Assistant"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF00E676.toInt())
            setPadding(30, 40, 30, 40)
            setOnClickListener {
                startAssistantLifecycle()
            }
        }

        val btnStop = Button(this).apply {
            text = "Stop Assistant Overlay"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFCF6679.toInt()) // Red accent
            setPadding(30, 40, 30, 40)
            setOnClickListener {
                stopAssistantService()
            }
        }

        // GPLv3 Disclaimer Section
        val gplSection = TextView(this).apply {
            text = "Licensed under GNU GPLv3\nThis is free software: you are free to change and redistribute it. There is absolutely NO WARRANTY."
            textSize = 12f
            setTextColor(0xFF78909C.toInt())
            setPadding(20, 100, 20, 20)
            gravity = android.view.Gravity.CENTER
        }

        mainLayout.addView(headerText)
        mainLayout.addView(subtitleText)
        mainLayout.addView(infoCard)
        mainLayout.addView(TextView(this).apply { layoutParams = spacer })
        mainLayout.addView(btnStart)
        mainLayout.addView(TextView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 30) })
        mainLayout.addView(btnStop)
        mainLayout.addView(gplSection)

        setContentView(mainLayout)
    }

    private fun startAssistantLifecycle() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            checkAndRequestNotificationPermission()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                initiateScreenCaptureRequest()
            }
        } else {
            initiateScreenCaptureRequest()
        }
    }

    private fun initiateScreenCaptureRequest() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }

    private fun stopAssistantService() {
        val stopIntent = Intent(this, AssistantService::class.java).apply {
            action = AssistantService.ACTION_STOP
        }
        startService(stopIntent)
        showToast("Efootballhelp Assistant stopped.")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
