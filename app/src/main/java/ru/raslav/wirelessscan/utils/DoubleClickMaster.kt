package ru.raslav.wirelessscan.utils

import android.view.View
import android.widget.ImageButton

class DoubleClickMaster() : View.OnClickListener {
    var onClickListener: (v: View) -> Unit = {}
    var onDoubleClickListener: (v: View) -> Unit = {}

    private var temporaryDrawableResId = 0
    private var millisClickedFirst = 0L
    var delay = 300L
    set(value) { field = if (value > 0) value else 0 }

    constructor(delay: Long) : this() {
        this.delay = delay
    }

    constructor(onDoubleClickListener: (v: View) -> Unit) : this() {
        this.onDoubleClickListener = onDoubleClickListener
    }

    constructor(temporaryDrawableResId: Int, onDoubleClickListener: (v: View) -> Unit) : this(onDoubleClickListener) {
        this.temporaryDrawableResId = temporaryDrawableResId
    }

    private fun start(v: View): Boolean {
        updateDrawable(v, true)
        millisClickedFirst = System.currentTimeMillis()
        val mcf = millisClickedFirst
        return v.postDelayed({ if (mcf == millisClickedFirst) reset(v) }, delay)
    }

    private fun reset(v: View) {
        millisClickedFirst = 0
        updateDrawable(v, false)
    }

    private fun updateDrawable(v: View, isActivated: Boolean) {
        if (temporaryDrawableResId == 0)
            v.isActivated = isActivated
        else
            (v as? ImageButton)?.setImageResource(temporaryDrawableResId)
    }

    override fun onClick(v: View) {
        if (millisClickedFirst == 0L) {
            start(v)
            onClickListener(v)
        } else {
            reset(v)
            onDoubleClickListener(v)
        }
    }

    fun resetOnClickListener() {
        onClickListener = {}
    }

    fun onClickListener(callback: (v: View) -> Unit): DoubleClickMaster {
        onClickListener = callback
        return this
    }

    fun onDoubleClickListener(callback: (v: View) -> Unit): DoubleClickMaster {
        onDoubleClickListener = callback
        return this
    }
}
