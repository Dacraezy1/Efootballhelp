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

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service that handles the screen capture stream and overlay canvas lifecycle.
 * Combines MediaProjection with a custom WindowManager overlay for low-latency visual analysis.
 */
class AssistantService : Service() {

    companion object {
        private const val TAG = "AssistantService"
        private const val CHANNEL_ID = "efootball_help_service_channel"
        private const val NOTIFICATION_ID = 2026
        private const val FRAME_INTERVAL_MS = 150L // ~7 FPS for CPU safety and thermal health

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        const val ACTION_STOP = "com.efootballhelp.ACTION_STOP"
    }

    private lateinit var windowManager: WindowManager
    private var overlayCanvasView: OverlayCanvasView? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var analysisJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection system callback invoked stop.")
            stopCapture()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        setupOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        if (intent.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        // Android 14+ requires starting foreground BEFORE invoking getMediaProjection
        startForegroundServiceWithNotification()

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            startCapture(resultCode, resultData)
        } else {
            Log.e(TAG, "Invalid MediaProjection token provided to Service.")
            stopSelf()
        }

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val stopIntent = Intent(this, AssistantService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Efootballhelp Active")
            .setContentText("Scanning game graphics and rendering tactical indicators...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Assistant", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gameplay Assistant Running",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the background frame processing and display overlay graphics."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun setupOverlayWindow() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        overlayCanvasView = OverlayCanvasView(this)
        windowManager.addView(overlayCanvasView, params)
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection token generation failed.")
                stopSelf()
                return
            }

            mediaProjection?.registerCallback(mediaProjectionCallback, null)

            // Extract display geometry safely
            val metrics = DisplayMetrics()
            val defaultDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            @Suppress("DEPRECATION")
            defaultDisplay?.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            // Configure ImageReader for RGBA buffer allocations
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GameplayCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            startAnalysisLoop()

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing MediaProjection capture pipeline", e)
            stopSelf()
        }
    }

    private fun startAnalysisLoop() {
        analysisJob = scope.launch {
            while (isActive) {
                val cycleStart = System.currentTimeMillis()
                var currentFrame: Image? = null
                var processedBitmap: Bitmap? = null

                try {
                    currentFrame = imageReader?.acquireLatestImage()
                    if (currentFrame != null) {
                        processedBitmap = convertImageToBitmap(currentFrame)
                        if (processedBitmap != null) {
                            analyzeGameplayFrame(processedBitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during frame ingestion cycle", e)
                } finally {
                    processedBitmap?.recycle()
                    currentFrame?.close() // Return memory immediately to buffer queue
                }

                // Adjust frequency for performance and thermal throttling
                val duration = System.currentTimeMillis() - cycleStart
                val remainingDelay = maxOf(0L, FRAME_INTERVAL_MS - duration)
                delay(remainingDelay)
            }
        }
    }

    /**
     * Converts a raw ImageBuffer with hardware alignments into an Android Bitmap.
     * Extracts byte boundaries from plane 0 directly to prevent memory fragmentation.
     */
    private fun convertImageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        // Allocate bitmap matching physical buffer alignments
        val rawBitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        rawBitmap.copyPixelsFromBuffer(buffer)

        // Strip alignment row-padding to return perfect screen resolution
        return if (rowPadding != 0) {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, image.width, image.height)
            rawBitmap.recycle()
            cropped
        } else {
            rawBitmap
        }
    }

    /**
     * Placeholder visual processing engine simulating image recognition models.
     * Feeds coordinate changes directly to the CanvasView main thread invalidate mechanism.
     */
    private fun analyzeGameplayFrame(bitmap: Bitmap) {
        val screenWidth = bitmap.width.toFloat()
        val screenHeight = bitmap.height.toFloat()

        // Generate synthetic gameplay coordinates over time to show drawing capabilities
        val timeStep = System.currentTimeMillis() / 1200.0

        // Active Player (passing starting point) moves in circular loop
        val activeX = screenWidth / 2f + (Math.cos(timeStep) * (screenWidth / 5f)).toFloat()
        val activeY = screenHeight / 2f + (Math.sin(timeStep) * (screenHeight / 5f)).toFloat()

        // Target receiver runs in a offset angle
        val targetX = activeX + (Math.cos(timeStep * 1.5 + 2.0) * 350f).toFloat()
        val targetY = activeY + (Math.sin(timeStep * 1.5 + 2.0) * 250f).toFloat()

        // Opposition defender runs to intercept the vector
        val opponentX = (activeX + targetX) / 2f + (Math.sin(timeStep * 3) * 110f).toFloat()
        val opponentY = (activeY + targetY) / 2f + (Math.cos(timeStep * 3) * 110f).toFloat()

        // Numerical physics parameters
        val angleRad = Math.atan2((targetY - activeY).toDouble(), (targetX - activeX).toDouble())
        val angleDeg = (Math.toDegrees(angleRad) + 360.0) % 360.0
        val dist = Math.hypot((targetX - activeX).toDouble(), (targetY - activeY).toDouble())
        val powerPercent = (dist / 600.0 * 100.0).coerceIn(10.0, 100.0)

        // Propagate values to the Overlay view
        overlayCanvasView?.updateOverlay(
            pActiveX = activeX,
            pActiveY = activeY,
            pTargetX = targetX,
            pTargetY = targetY,
            pOpponentX = opponentX,
            pOpponentY = opponentY,
            pAngle = angleDeg.toFloat(),
            pStrength = powerPercent.toFloat()
        )
    }

    private fun stopCapture() {
        analysisJob?.cancel()
        analysisJob = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        overlayCanvasView?.clearOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        overlayCanvasView?.let {
            windowManager.removeView(it)
            overlayCanvasView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
