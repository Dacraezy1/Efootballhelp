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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * A custom view designed to draw overlays over the running eFootball game interface.
 * Implements rendering logic for vector paths, tactical directions, and player targeting circles.
 */
class OverlayCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Paint for tactical dashed arrows
    private val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Vibrant light green
        style = Paint.Style.STROKE
        strokeWidth = 10f
        pathEffect = DashPathEffect(floatArrayOf(25f, 15f), 0f)
        strokeCap = Paint.Cap.ROUND
    }

    // Paint for Arrow heads
    private val arrowheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }

    // Paint for player targeting circles
    private val targetCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600") // Bright yellow
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // Paint for targeting circles around opponents
    private val threatCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // Bright red
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // Paint for text telemetry info
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        style = Paint.Style.FILL
        setShadowLayer(6f, 3f, 3f, Color.BLACK)
    }

    // Paint for transparent background of text overlays
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val arrowPath = Path()

    // Interactive gameplay data
    private var activePlayerX: Float = 0f
    private var activePlayerY: Float = 0f
    private var targetPlayerX: Float = 0f
    private var targetPlayerY: Float = 0f
    private var opponentX: Float = 0f
    private var opponentY: Float = 0f
    private var passAngle: Float = 0f
    private var passStrength: Float = 0f
    private var isAnalyzing: Boolean = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isAnalyzing) {
            // Draw status overlay when no game data is running
            canvas.drawRect(50f, 50f, 600f, 180f, bgPaint)
            canvas.drawText("Efootballhelp: Scanning game...", 80f, 125f, textPaint)
            return
        }

        // Draw system details/stats at the top
        canvas.drawRect(50f, 50f, 750f, 220f, bgPaint)
        canvas.drawText("EF-HELP PIPELINE ACTIVE", 80f, 110f, textPaint.apply { color = Color.parseColor("#00E676") })
        canvas.drawText("Telemetry: Active Target Found", 80f, 175f, textPaint.apply { color = Color.WHITE })

        // 1. Draw opponent threat circle (if detected)
        if (opponentX > 0 && opponentY > 0) {
            canvas.drawCircle(opponentX, opponentY, 70f, threatCirclePaint)
            canvas.drawText("DEFENDER", opponentX - 80f, opponentY - 90f, textPaint.apply { color = Color.parseColor("#FF1744") })
        }

        // 2. Draw active player identifier circle
        if (activePlayerX > 0 && activePlayerY > 0) {
            canvas.drawCircle(activePlayerX, activePlayerY, 90f, targetCirclePaint)
            canvas.drawText("BALL CARRIER", activePlayerX - 110f, activePlayerY - 110f, textPaint.apply { color = Color.parseColor("#FFD600") })
        }

        // 3. Draw passing trajectory arrow to target teammate
        if (activePlayerX > 0 && activePlayerY > 0 && targetPlayerX > 0 && targetPlayerY > 0) {
            // Draw vector line
            canvas.drawLine(activePlayerX, activePlayerY, targetPlayerX, targetPlayerY, vectorPaint)

            // Draw arrowhead indicating trajectory flow
            drawArrowHead(canvas, activePlayerX, activePlayerY, targetPlayerX, targetPlayerY)

            // Target circle around the target player
            canvas.drawCircle(targetPlayerX, targetPlayerY, 80f, targetCirclePaint)
            canvas.drawText("RECEIVER", targetPlayerX - 80f, targetPlayerY - 100f, textPaint.apply { color = Color.parseColor("#00E676") })

            // Display numerical telemetry (Angle & Velocity) directly above the passing line midpoint
            val midX = (activePlayerX + targetPlayerX) / 2
            val midY = (activePlayerY + targetPlayerY) / 2
            val telemetryStr = "Vector: ${String.format("%.1f", passAngle)}° | V_est: ${String.format("%.1f", passStrength)} m/s"

            val textWidth = textPaint.measureText(telemetryStr)
            canvas.drawRect(midX - textWidth / 2 - 20f, midY - 60f, midX + textWidth / 2 + 20f, midY + 10f, bgPaint)
            canvas.drawText(telemetryStr, midX - textWidth / 2, midY - 15f, textPaint.apply { color = Color.WHITE })
        }
    }

    private fun drawArrowHead(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val angle = Math.atan2((toY - fromY).toDouble(), (toX - fromX).toDouble())
        val arrowLength = 40f
        val arrowAngle = Math.toRadians(30.0)

        // Calculate arrow vertices
        val x1 = toX - arrowLength * Math.cos(angle - arrowAngle).toFloat()
        val y1 = toY - arrowLength * Math.sin(angle - arrowAngle).toFloat()
        val x2 = toX - arrowLength * Math.cos(angle + arrowAngle).toFloat()
        val y2 = toY - arrowLength * Math.sin(angle + arrowAngle).toFloat()

        arrowPath.reset()
        arrowPath.moveTo(toX, toY)
        arrowPath.lineTo(x1, y1)
        arrowPath.lineTo(x2, y2)
        arrowPath.close()

        canvas.drawPath(arrowPath, arrowheadPaint)
    }

    /**
     * Updates coordinates from the image analysis loop, forcing UI thread refresh.
     */
    fun updateOverlay(
        pActiveX: Float, pActiveY: Float,
        pTargetX: Float, pTargetY: Float,
        pOpponentX: Float, pOpponentY: Float,
        pAngle: Float, pStrength: Float
    ) {
        this.activePlayerX = pActiveX
        this.activePlayerY = pActiveY
        this.targetPlayerX = pTargetX
        this.targetPlayerY = pTargetY
        this.opponentX = pOpponentX
        this.opponentY = pOpponentY
        this.passAngle = pAngle
        this.passStrength = pStrength
        this.isAnalyzing = true
        postInvalidate()
    }

    /**
     * Clears visual artifacts when service is inactive.
     */
    fun clearOverlay() {
        this.isAnalyzing = false
        postInvalidate()
    }
}
