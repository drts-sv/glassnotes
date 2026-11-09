package com.glassnotes.dev

import android.content.Context
import android.widget.Toast

/** Короткая подсказка — одна строка, без очередей и без скролла. */
object Ui {
    fun toast(
        context: Context,
        text: String,
    ) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
