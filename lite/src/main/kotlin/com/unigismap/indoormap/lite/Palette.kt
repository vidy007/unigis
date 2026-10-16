package com.unigismap.indoormap.lite

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/** Subset of the Material 3 color roles used by the app. */
class Palette(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiaryContainer: Int,
    val surface: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val isDark: Boolean,
) {
    companion object {
        fun from(context: Context): Palette {
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return dynamic(context, dark) ?: if (dark) Dark else Light
        }

        /**
         * Material You colors (Android 12+). Compiled against API 23, so the system
         * palette is looked up by name instead of android.R constants.
         */
        private fun dynamic(context: Context, dark: Boolean): Palette? {
            if (Build.VERSION.SDK_INT < 31) return null
            val res = context.resources
            fun c(name: String): Int {
                val id = res.getIdentifier("system_$name", "color", "android")
                if (id == 0) throw IllegalStateException(name)
                return context.getColor(id)
            }
            return try {
                if (!dark) Palette(
                    primary = c("accent1_600"), onPrimary = c("accent1_0"),
                    primaryContainer = c("accent1_100"), onPrimaryContainer = c("accent1_900"),
                    secondaryContainer = c("accent2_100"), onSecondaryContainer = c("accent2_900"),
                    tertiaryContainer = c("accent3_100"),
                    surface = c("neutral1_10"), surfaceContainerLow = c("neutral1_10"),
                    surfaceContainer = c("neutral1_50"), surfaceContainerHigh = c("neutral2_50"),
                    surfaceContainerHighest = c("neutral2_100"),
                    onSurface = c("neutral1_900"), onSurfaceVariant = c("neutral2_700"),
                    outline = c("neutral2_500"), outlineVariant = c("neutral2_200"),
                    isDark = false,
                ) else Palette(
                    primary = c("accent1_200"), onPrimary = c("accent1_800"),
                    primaryContainer = c("accent1_700"), onPrimaryContainer = c("accent1_100"),
                    secondaryContainer = c("accent2_700"), onSecondaryContainer = c("accent2_100"),
                    tertiaryContainer = c("accent3_700"),
                    surface = c("neutral1_900"), surfaceContainerLow = c("neutral1_900"),
                    surfaceContainer = c("neutral1_800"), surfaceContainerHigh = c("neutral2_800"),
                    surfaceContainerHighest = c("neutral2_700"),
                    onSurface = c("neutral1_100"), onSurfaceVariant = c("neutral2_200"),
                    outline = c("neutral2_400"), outlineVariant = c("neutral2_700"),
                    isDark = true,
                )
            } catch (e: Exception) {
                null
            }
        }

        val Light = Palette(
            primary = 0xFF006A6A.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
            primaryContainer = 0xFF9CF1F0.toInt(), onPrimaryContainer = 0xFF002020.toInt(),
            secondaryContainer = 0xFFCCE8E7.toInt(), onSecondaryContainer = 0xFF051F1F.toInt(),
            tertiaryContainer = 0xFFD3E4FF.toInt(),
            surface = 0xFFF4FBFA.toInt(), surfaceContainerLow = 0xFFEFF5F4.toInt(),
            surfaceContainer = 0xFFE9EFEE.toInt(), surfaceContainerHigh = 0xFFE3E9E9.toInt(),
            surfaceContainerHighest = 0xFFDDE4E3.toInt(),
            onSurface = 0xFF161D1D.toInt(), onSurfaceVariant = 0xFF3F4948.toInt(),
            outline = 0xFF6F7979.toInt(), outlineVariant = 0xFFBEC9C8.toInt(),
            isDark = false,
        )

        val Dark = Palette(
            primary = 0xFF80D5D4.toInt(), onPrimary = 0xFF003737.toInt(),
            primaryContainer = 0xFF004F4F.toInt(), onPrimaryContainer = 0xFF9CF1F0.toInt(),
            secondaryContainer = 0xFF324B4B.toInt(), onSecondaryContainer = 0xFFCCE8E7.toInt(),
            tertiaryContainer = 0xFF334863.toInt(),
            surface = 0xFF0E1514.toInt(), surfaceContainerLow = 0xFF161D1D.toInt(),
            surfaceContainer = 0xFF1A2121.toInt(), surfaceContainerHigh = 0xFF252B2B.toInt(),
            surfaceContainerHighest = 0xFF2F3636.toInt(),
            onSurface = 0xFFDDE4E3.toInt(), onSurfaceVariant = 0xFFBEC9C8.toInt(),
            outline = 0xFF889392.toInt(), outlineVariant = 0xFF3F4948.toInt(),
            isDark = true,
        )
    }
}
