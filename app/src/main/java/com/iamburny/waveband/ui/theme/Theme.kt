package com.iamburny.waveband.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.iamburny.waveband.R
import com.iamburny.waveband.data.Accent
import com.iamburny.waveband.data.DisplayMode

val Manrope = FontFamily(
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/** Text on accent-coloured surfaces, in both themes. */
val OnAccent = Color(0xFF14110A)

/** Accent swatch: [night] is the accent itself; [day] is its darker variant used for accent text on light backgrounds. */
fun Accent.colours(): Pair<Color, Color> = when (this) {
    Accent.AMBER -> Color(0xFFFFB547) to Color(0xFF8F5300)
    Accent.TEAL -> Color(0xFF4FD1C5) to Color(0xFF0F6B63)
    Accent.BLUE -> Color(0xFF7CA8FF) to Color(0xFF2950A8)
    Accent.CORAL -> Color(0xFFFF7A59) to Color(0xFFA33A1E)
}

/**
 * Colour tokens of the head-unit design. Where screens of the design use different values for
 * the same role, each gets its own token (for example [nowCard] on Now Playing, [card] elsewhere).
 */
@Immutable
data class DabColors(
    val isNight: Boolean,
    val bg: Color,
    val rail: Color,
    /** Divider right of the nav rail; null for none. */
    val railLine: Color?,
    val text: Color,
    val muted: Color,
    val sub: Color,
    val accent: Color,
    /** Accent used for text and icons on the page background. */
    val accentText: Color,
    val nowCard: Color,
    val card: Color,
    val btn: Color,
    val presetsBtn: Color,
    val chip: Color,
    /** Background of the selected (playing) preset tile. */
    val selectedBg: Color,
    /** Background of the playing row in the station list. */
    val selectedRowBg: Color,
    /** 3dp border of unselected tiles and rows; null for none. */
    val idleBorder: Color?,
    /** 2dp border of settings cards; null for none. */
    val cardBorder: Color?,
    val emptyBorder: Color,
    val signalOff: Color,
    val placeholder: Color,
    val placeholderText: Color,
    val invBg: Color,
    val invFg: Color,
    val track: Color,
    val outline: Color,
    val segBg: Color,
    val segFg: Color,
    val knobOff: Color,
)

/**
 * Background of selected items. The design gives it for the amber accent; other accents get the
 * same treatment: a light wash of the accent over [base].
 */
private fun tint(accent: Accent, amber: Color, base: Color, fraction: Float): Color =
    if (accent == Accent.AMBER) amber else lerp(base, accent.colours().first, fraction)

fun nightColors(accent: Accent): DabColors {
    val (base, _) = accent.colours()
    return DabColors(
        isNight = true,
        bg = Color(0xFF0E1116), rail = Color(0xFF151A21), railLine = null,
        text = Color(0xFFE8ECF2), muted = Color(0xFFAEB7C4), sub = Color(0xFFC9D1DC),
        accent = base, accentText = base,
        nowCard = Color(0xFF1A2029), card = Color(0xFF161B22), btn = Color(0xFF222934),
        presetsBtn = Color(0xFF1A2029), chip = Color(0xFF1A2029),
        selectedBg = tint(accent, Color(0xFF2A2416), Color(0xFF0E1116), 0.12f),
        selectedRowBg = tint(accent, Color(0xFF1E1B14), Color(0xFF0E1116), 0.06f),
        idleBorder = null, cardBorder = null, emptyBorder = Color(0xFF3A4452),
        signalOff = Color(0xFF3A4452), placeholder = Color(0xFF1E2530), placeholderText = Color(0xFF8D97A6),
        invBg = Color(0xFFE8ECF2), invFg = Color(0xFF0E1116),
        track = Color(0xFF2A323E), outline = Color(0xFF3A4452), segBg = Color(0xFF0E1116), segFg = Color(0xFFC9D1DC),
        knobOff = Color(0xFFAEB7C4),
    )
}

fun dayColors(accent: Accent): DabColors {
    val (base, dark) = accent.colours()
    return DabColors(
        isNight = false,
        bg = Color(0xFFF3F5F8), rail = Color(0xFFFFFFFF), railLine = Color(0xFFE1E5EB),
        text = Color(0xFF10141A), muted = Color(0xFF4A5361), sub = Color(0xFF2E3540),
        accent = base, accentText = dark,
        nowCard = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF), btn = Color(0xFFE4E8EE),
        presetsBtn = Color(0xFFE4E8EE), chip = Color(0xFFE4E8EE),
        selectedBg = tint(accent, Color(0xFFFFF1D6), Color.White, 0.18f),
        selectedRowBg = tint(accent, Color(0xFFFFF1D6), Color.White, 0.18f),
        idleBorder = Color(0xFFE1E5EB), cardBorder = Color(0xFFE1E5EB), emptyBorder = Color(0xFFB4BBC6),
        signalOff = Color(0xFFC5CBD4), placeholder = Color(0xFFE4E8EE), placeholderText = Color(0xFF4F5866),
        invBg = Color(0xFF10141A), invFg = Color(0xFFFFFFFF),
        track = Color(0xFFD5DAE1), outline = Color(0xFFB4BBC6), segBg = Color(0xFFE4E8EE), segFg = Color(0xFF2E3540),
        knobOff = Color(0xFF6B7482),
    )
}

val LocalDabColors = staticCompositionLocalOf { nightColors(Accent.AMBER) }

object Dab {
    val colors: DabColors
        @Composable get() = LocalDabColors.current
}

/** Manrope text style; sizes follow the design's px values (1px = 1dp on the 160 dpi head unit). */
fun dabText(
    size: Int,
    weight: FontWeight = FontWeight.Medium,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = TextStyle(fontFamily = Manrope, fontSize = size.sp, fontWeight = weight, letterSpacing = letterSpacing, lineHeight = lineHeight)

@Composable
fun isNight(mode: DisplayMode): Boolean = when (mode) {
    DisplayMode.AUTO -> isSystemInDarkTheme()
    DisplayMode.DAY -> false
    DisplayMode.NIGHT -> true
}

@Composable
fun DabTheme(night: Boolean, accent: Accent, content: @Composable () -> Unit) {
    val colors = if (night) nightColors(accent) else dayColors(accent)
    val scheme = if (night) {
        darkColorScheme(primary = colors.accent, onPrimary = OnAccent, background = colors.bg, surface = colors.card, onSurface = colors.text)
    } else {
        lightColorScheme(primary = colors.accentText, onPrimary = Color.White, background = colors.bg, surface = colors.card, onSurface = colors.text)
    }
    CompositionLocalProvider(LocalDabColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
