package com.iamburny.waveband.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Content area the layouts are drawn for, in dp: the 1280×720 design canvas at 160 dpi, less the
// 24 dp status bar and 32 dp navigation bar of the reference head unit.
private const val DESIGN_WIDTH = 1280f
private const val DESIGN_HEIGHT = 664f

/**
 * Scales [content] uniformly so the design area fits the space this composable is given, whatever
 * the screen density. One design dp becomes `min(widthPx / DESIGN_WIDTH, heightPx / DESIGN_HEIGHT)`
 * px; the axis with room to spare gets extra dp, which the layouts' weights and fills absorb. The
 * system font scale is kept on top of that.
 */
@Composable
fun DesignScale(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val system = LocalDensity.current
        val scale = minOf(constraints.maxWidth / DESIGN_WIDTH, constraints.maxHeight / DESIGN_HEIGHT)
            .takeIf { it > 0f } ?: system.density
        val density = remember(scale, system.fontScale) { Density(scale, system.fontScale) }
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}
