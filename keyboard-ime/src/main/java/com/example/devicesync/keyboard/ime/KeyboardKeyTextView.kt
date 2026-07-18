package com.example.devicesync.keyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.widget.TextView

/**
 * Programmatic IME key view with an explicit accessibility click contract.
 *
 * Physical touches can commit on ACTION_DOWN for latency, while accessibility services activate
 * the same key through performClick().
 */
@SuppressLint("ViewConstructor")
internal class KeyboardKeyTextView(context: Context) : TextView(context) {
    private var centeredIcon: Drawable? = null
    private var centeredIconSizePx: Int = 0

    fun setCenteredIcon(drawable: Drawable, sizePx: Int) {
        centeredIcon = drawable
        centeredIconSizePx = sizePx.coerceAtLeast(1)
        setCompoundDrawables(null, null, null, null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val icon = centeredIcon ?: return
        val size = centeredIconSizePx.coerceAtMost(minOf(width, height))
        val left = (width - size) / 2
        val top = (height - size) / 2
        icon.setBounds(left, top, left + size, top + size)
        icon.draw(canvas)
    }

    override fun performClick(): Boolean = super.performClick()
}
