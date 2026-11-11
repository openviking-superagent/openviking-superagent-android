// Copyright (c) 2026 Beijing Volcano Engine Technology Co., Ltd.
// SPDX-License-Identifier: AGPL-3.0

package ai.openviking.superagent.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF2F6FDB)
private val BlueLight = Color(0xFF9DBFFF)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0B2F66),
    secondary = Color(0xFF4D6079),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F2),
    onSecondaryContainer = Color(0xFF13202F),
    tertiary = Color(0xFF6B5A8A),
    background = Color(0xFFF6F7FA),
    onBackground = Color(0xFF15181D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15181D),
    surfaceVariant = Color(0xFFE9EDF4),
    onSurfaceVariant = Color(0xFF505967),
    outline = Color(0xFFBFC7D3),
    outlineVariant = Color(0xFFDCE1E9),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF06224F),
    primaryContainer = Color(0xFF17355F),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFB8C7DA),
    onSecondary = Color(0xFF223143),
    secondaryContainer = Color(0xFF34455A),
    onSecondaryContainer = Color(0xFFD9E3F2),
    tertiary = Color(0xFFD5C2F0),
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF242A35),
    onSurfaceVariant = Color(0xFFB7C0CD),
    outline = Color(0xFF5A6473),
    outlineVariant = Color(0xFF333B47),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

@Composable
fun SuperAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
