package ro.eidkit.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Palette ──────────────────────────────────────────────────────────────────

val ElectricBlue   = Color(0xFF2563EB)
val ElectricBlueLight = Color(0xFF60A5FA)
val SurfaceDark    = Color(0xFF0D1117)
val SurfaceCard    = Color(0xFF161B22)
val SurfaceBorder  = Color(0xFF30363D)
val OnSurface      = Color(0xFFE6EDF3)
val OnSurfaceMuted = Color(0xFF8B949E)
val SuccessGreen   = Color(0xFF3FB950)
val ErrorRed       = Color(0xFFF85149)

private val DarkColorScheme = darkColorScheme(
    primary          = ElectricBlue,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFF1D4ED8),
    onPrimaryContainer = Color.White,
    secondary        = ElectricBlueLight,
    onSecondary      = Color(0xFF0D1117),
    background       = SurfaceDark,
    onBackground     = OnSurface,
    surface          = SurfaceCard,
    onSurface        = OnSurface,
    onSurfaceVariant = OnSurfaceMuted,
    outline          = SurfaceBorder,
    error            = ErrorRed,
    onError          = Color.White,
)

@Composable
fun EidKitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = EidKitTypography,
        content     = content,
    )
}
