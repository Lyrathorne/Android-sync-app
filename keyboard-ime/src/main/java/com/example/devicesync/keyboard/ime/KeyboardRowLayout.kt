package com.example.devicesync.keyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.floor

/** A single keyboard row whose children can never extend beyond its measured width. */
@SuppressLint("ViewConstructor")
internal class KeyboardRowLayout(
    context: Context,
    private val keyHeightPx: Int,
    private val horizontalGapPx: Int,
    private val verticalGapPx: Int,
) : ViewGroup(context) {
    private val weights = mutableListOf<Float>()

    fun addKey(view: View, weight: Float) {
        weights += weight.coerceAtLeast(0.1f)
        addView(view)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val usableWidth = (measuredWidth - paddingLeft - paddingRight - childCount * horizontalGapPx * 2)
            .coerceAtLeast(childCount)
        val totalWeight = weights.sum().coerceAtLeast(0.1f)
        val distributableWidth = (usableWidth - childCount).coerceAtLeast(0)
        val exactWidths = weights.map { distributableWidth * (it / totalWeight) }
        val childWidths = exactWidths.map { 1 + floor(it).toInt() }.toMutableList()
        var remainder = usableWidth - childWidths.sum()
        exactWidths.indices
            .sortedByDescending { exactWidths[it] - floor(exactWidths[it]) }
            .forEach { index ->
                if (remainder > 0) {
                    childWidths[index]++
                    remainder--
                }
            }
        children().forEachIndexed { index, child ->
            val childWidth = childWidths.getOrElse(index) { 1 }
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(keyHeightPx, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(keyHeightPx + verticalGapPx * 2 + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = paddingLeft
        children().forEach { child ->
            x += horizontalGapPx
            child.layout(x, paddingTop + verticalGapPx, x + child.measuredWidth, paddingTop + verticalGapPx + keyHeightPx)
            x += child.measuredWidth + horizontalGapPx
        }
    }

    private fun children(): Sequence<View> = sequence {
        for (index in 0 until childCount) yield(getChildAt(index))
    }
}
