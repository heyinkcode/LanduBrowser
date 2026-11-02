package com.landu.browser.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.text.TextUtils
import com.landu.browser.R
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner

object NativeUi {
    val background = Color.rgb(245, 247, 251)
    val primary = Color.rgb(22, 119, 255)
    val ink = Color.rgb(30, 41, 59)
    val muted = Color.rgb(100, 116, 139)
    val border = Color.rgb(222, 229, 239)

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun column(context: Context, padding: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun surface(context: Context, fill: Int = Color.WHITE, radius: Int = 14): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radius).toFloat()
            setStroke(dp(context, 1), border)
        }

    fun text(context: Context, value: String, size: Float = 14f, color: Int = ink, medium: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            setLineSpacing(dp(context, 3).toFloat(), 1f)
        }

    fun button(context: Context, label: String, filled: Boolean = false, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(context, 48)
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            setTextColor(if (filled) Color.WHITE else ink)
            background = RippleDrawable(
                ColorStateList.valueOf(if (filled) 0x33FFFFFF else 0x181677FF),
                surface(context, if (filled) primary else Color.WHITE, 12),
                null,
            )
            setOnClickListener { action() }
        }

    fun field(context: Context, value: String, hint: String): EditText = EditText(context).apply {
        setText(value)
        this.hint = hint
        textSize = 16f
        setTextColor(ink)
        setHintTextColor(muted)
        background = surface(context, Color.WHITE, 10)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        minHeight = dp(context, 48)
        isSingleLine = true
        id = View.generateViewId()
    }

    fun label(context: Context, title: String, field: EditText): TextView = text(context, title, medium = true).apply {
        labelFor = field.id
    }

    fun check(context: Context, title: String, checked: Boolean): CheckBox = CheckBox(context).apply {
        text = title
        textSize = 14f
        setTextColor(ink)
        isChecked = checked
        minHeight = dp(context, 48)
        buttonTintList = ColorStateList.valueOf(primary)
        setPadding(0, dp(context, 8), dp(context, 8), dp(context, 8))
    }

    fun select(context: Context, values: List<String>, selected: Int = 0): Spinner = Spinner(context).apply {
        adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, values) {
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View =
                (super.getView(position, recycled, parent) as TextView).apply {
                    textSize = 16f
                    setTextColor(ink)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(context, 48)
                    setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 10))
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_down, 0)
                    compoundDrawablePadding = dp(context, 8)
                }

            override fun getDropDownView(position: Int, recycled: View?, parent: ViewGroup): View =
                (super.getDropDownView(position, recycled, parent) as TextView).apply {
                    textSize = 16f
                    setTextColor(ink)
                    minHeight = dp(context, 48)
                    setSingleLine(false)
                    setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
                }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        minimumHeight = dp(context, 48)
        background = surface(context, Color.WHITE, 10)
        setPadding(dp(context, 8), 0, dp(context, 8), 0)
        setSelection(selected)
        id = View.generateViewId()
    }

    fun add(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(parent.context, top)
        })
    }

    fun weighted(parent: LinearLayout, view: View, gap: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(parent.context, gap)
        })
    }

    @Suppress("DEPRECATION")
    fun install(activity: Activity, root: LinearLayout) {
        root.setBackgroundColor(background)
        if (Build.VERSION.SDK_INT >= 30) activity.window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val occupied = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(occupied.left, occupied.top, occupied.right, occupied.bottom)
                WindowInsets.CONSUMED
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets.consumeSystemWindowInsets()
            }
        }
        activity.setContentView(root)
        root.requestApplyInsets()
    }
}
