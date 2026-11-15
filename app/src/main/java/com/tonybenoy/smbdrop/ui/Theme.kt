package com.tonybenoy.smbdrop.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tonybenoy.smbdrop.transfer.Conditions
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.border

val SuccessLight = Color(0xFF1F9D66); val SuccessDark = Color(0xFF4CC38A)
val WarnLight = Color(0xFFC98A00); val WarnDark = Color(0xFFF2B33D)
val ErrLight = Color(0xFFD83A3A); val ErrDark = Color(0xFFFF6B6B)

data class Glass(val realBlur: Boolean, val reduced: Boolean, val off: Boolean, val hazeState: HazeState, val dark: Boolean)
val LocalGlass = compositionLocalOf<Glass> { error("no glass") }

val Shapes28 = RoundedCornerShape(28.dp)
val Pill = RoundedCornerShape(50)

@Composable
fun SmbTheme(theme: String, glassMode: String, language: String = "system", content: @Composable () -> Unit) {
    SideEffect { I18n.lang = language }
    val ctx = LocalContext.current
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val accentLight = Color(0xFF3D7BFF); val accentDark = Color(0xFF7AA5FF)
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = accentDark, background = Color(0xFF0E1015), surface = Color(0xFF0E1015),
            onBackground = Color(0xFFF2F4F8), onSurface = Color(0xFFF2F4F8))
        else -> lightColorScheme(primary = accentLight, background = Color(0xFFF6F7FB), surface = Color(0xFFF6F7FB),
            onBackground = Color(0xFF0E1015), onSurface = Color(0xFF0E1015))
    }
    val am = ctx.getSystemService(ActivityManager::class.java)
    val canBlur = Build.VERSION.SDK_INT >= 31 && !am.isLowRamDevice && !Conditions.powerSave(ctx)
    val hz = remember { HazeState() }
    val g = Glass(realBlur = canBlur && glassMode == "full", reduced = glassMode == "reduced" || (!canBlur && glassMode == "full"),
        off = glassMode == "off", hazeState = hz, dark = dark)
    MaterialTheme(colorScheme = scheme, typography = appTypography()) {
        CompositionLocalProvider(LocalGlass provides g) {
            Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { content() }
        }
    }
}

@Composable fun success() = if (LocalGlass.current.dark) SuccessDark else SuccessLight
@Composable fun warning() = if (LocalGlass.current.dark) WarnDark else WarnLight
@Composable fun danger() = if (LocalGlass.current.dark) ErrDark else ErrLight

/** Glass material: backdrop blur + tint + edge highlight + soft shadow. Falls back to near-opaque tint. */
@Composable
fun Modifier.glass(shape: Shape = Shapes28, blur: Dp = 24.dp, strong: Boolean = false): Modifier {
    val g = LocalGlass.current
    val accent = MaterialTheme.colorScheme.primary
    // "strong" is for surfaces that carry lots of text over arbitrary content (the share card): much more opaque tint.
    val clear = if (g.dark) Color(0xFF0B0D12).copy(alpha = if (strong) 0.96f else 0.32f) else Color.White.copy(alpha = if (strong) 0.97f else 0.16f)
    val solid = if (g.dark) Color(0xFF14161C).copy(alpha = if (strong) 0.98f else 0.92f) else Color.White.copy(alpha = if (strong) 0.98f else 0.88f)
    val edge = Brush.linearGradient(listOf(Color.White.copy(alpha = if (g.dark) 0.22f else 0.55f), Color.Transparent))
    var m = this.shadow(if (g.off) 0.dp else 12.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f)).clip(shape)
    m = if (g.realBlur && !strong) {   // "strong" surfaces (share card) sit in their own window: nothing to blur, so use a solid surface
        m.hazeEffect(g.hazeState, HazeStyle(backgroundColor = MaterialTheme.colorScheme.surface,
            tint = HazeTint(clear.compositeOver(accent.copy(alpha = 0.06f))), blurRadius = blur, noiseFactor = 0.02f))
    } else m.background(solid)
    return m.border(BorderStroke(1.dp, edge), shape)
}


/** Soft aurora gradient in the accent hue behind everything, giving the glass something to refract. */
@Composable
fun Aurora(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val g = LocalGlass.current
    val a = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize().background(bg)) {
        Box(Modifier.fillMaxSize().hazeSource(g.hazeState)) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(a.copy(alpha = if (g.dark) 0.28f else 0.22f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(200f, 100f), radius = 1100f)))
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(1000f, 1800f), radius = 1000f)))
            content()
        }
    }
}

val springSpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

/** Roboto Flex (variable). Display 32/40 semibold, title 20/28 medium, body 16/24, label 13/18 medium. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val RobotoFlex = androidx.compose.ui.text.font.FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        androidx.compose.ui.text.font.Font(
            com.tonybenoy.smbdrop.R.font.roboto_flex, androidx.compose.ui.text.font.FontWeight(w),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
                androidx.compose.ui.text.font.FontVariation.weight(w)),
        )
    }
)

fun appTypography(): Typography {
    val d = Typography()
    fun s(base: androidx.compose.ui.text.TextStyle, size: Int, line: Int, w: Int) =
        base.copy(fontFamily = RobotoFlex, fontSize = size.sp, lineHeight = line.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(w),
            fontFeatureSettings = "tnum")
    return d.copy(
        displayLarge = s(d.displayLarge, 32, 40, 600), displayMedium = s(d.displayMedium, 32, 40, 600), displaySmall = s(d.displaySmall, 28, 36, 600),
        headlineLarge = s(d.headlineLarge, 28, 36, 600), headlineMedium = s(d.headlineMedium, 24, 32, 600), headlineSmall = s(d.headlineSmall, 22, 28, 600),
        titleLarge = s(d.titleLarge, 20, 28, 500), titleMedium = s(d.titleMedium, 18, 24, 500), titleSmall = s(d.titleSmall, 16, 22, 500),
        bodyLarge = s(d.bodyLarge, 16, 24, 400), bodyMedium = s(d.bodyMedium, 14, 20, 400), bodySmall = s(d.bodySmall, 12, 16, 400),
        labelLarge = s(d.labelLarge, 14, 20, 500), labelMedium = s(d.labelMedium, 13, 18, 500), labelSmall = s(d.labelSmall, 11, 16, 500),
    )
}
