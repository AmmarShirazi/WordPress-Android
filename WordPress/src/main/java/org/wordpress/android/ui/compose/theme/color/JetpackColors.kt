package org.wordpress.android.ui.compose.theme.color

import android.annotation.SuppressLint
import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.graphics.Color

@SuppressLint("ConflictingOnColor")
class JetpackColors : ThemeColors {
    private val green40 = Color(color = 0xff069e08)
    private val green50 = Color(color = 0xff008710)
    private val red50 = Color(color = 0xffd63638)
    private val red30 = Color(color = 0xfff86368)
    private val white = Color(color = 0xffffffff)
    private val black = Color(color = 0xff000000)
    private val darkGray = Color(color = 0xff121212)

    override fun light(): Colors = lightColors(
            primary = green50,
            primaryVariant = green40,
            secondary = green50,
            secondaryVariant = green40,
            background = white,
            surface = white,
            error = red50,
            onPrimary = white,
            onSecondary = white,
            onBackground = black,
            onSurface = black,
            onError = white
    )

    override fun dark(): Colors = darkColors(
            primary = green40,
            primaryVariant = green50,
            secondary = green40,
            secondaryVariant = green50,
            background = darkGray,
            surface = darkGray,
            error = red30,
            onPrimary = black,
            onSecondary = white,
            onBackground = white,
            onSurface = white,
            onError = black
    )
}
