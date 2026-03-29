package com.threehpm.firelauncher

import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

data class AppItem(
    val title: String,
    val subtitle: String,
    val badge: String,
    val packageName: String,
    val launchIntent: Intent,
    val icon: Drawable,
    val banner: Drawable? = null,
    @param:ColorInt val accentColor: Int
)
