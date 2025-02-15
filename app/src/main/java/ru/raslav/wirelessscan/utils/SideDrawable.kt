package ru.raslav.wirelessscan.utils

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class SideDrawable(
    strokeColor: Int,
    strokeWidth: Float,
) : Drawable() {

    private val paint = Paint()

    init {
        paint.isAntiAlias = true
        paint.color = strokeColor
        paint.strokeWidth = strokeWidth * 2
    }

    override fun draw(canvas: Canvas) {
        bounds.run { canvas.drawLine(left.toFloat(), top.toFloat(), left.toFloat(), bottom.toFloat(), paint) }
        bounds.run { canvas.drawLine(right.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint) }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}