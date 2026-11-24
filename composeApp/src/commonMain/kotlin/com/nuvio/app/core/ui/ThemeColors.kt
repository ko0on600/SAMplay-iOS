package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color

data class ThemeColorPalette(
    val secondary: Color,
    val secondaryVariant: Color,
    val accentGradient: List<Color> = listOf(secondary),
    val nativeAccentHex: String,
    val onSecondary: Color = Color.White,
    val onSecondaryVariant: Color = Color.White,
    val focusRing: Color,
    val focusBackground: Color,
    val background: Color = Color(0xFF0D0D0D),
    val backgroundElevated: Color = Color(0xFF1A1A1A),
    val backgroundCard: Color = Color(0xFF242424),
    /** A glass theme's lights over the app; the solid themes have none. */
    val glassLights: List<Color> = emptyList(),
)

object ThemeColors {

    val Gold = ThemeColorPalette(
        secondary = Color(0xFFE8A91C),
        secondaryVariant = Color(0xFF9A6200),
        accentGradient = listOf(
            Color(0xFF8A5700),
            Color(0xFFE8A91C),
            Color(0xFFFFF1A8),
            Color(0xFFFFD45C),
            Color(0xFF9A6200),
        ),
        nativeAccentHex = "#FFD45C",
        onSecondary = Color(0xFF111111),
        onSecondaryVariant = Color.White,
        focusRing = Color(0xFFFFD45C),
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0E0B),
        backgroundElevated = Color(0xFF1D1A14),
        backgroundCard = Color(0xFF262116),
    )

    val Jade = ThemeColorPalette(
        secondary = Color(0xFF22D37C),
        secondaryVariant = Color(0xFF0BBF9A),
        accentGradient = listOf(Color(0xFF7BF08D), Color(0xFF22D37C), Color(0xFF0BBF9A)),
        nativeAccentHex = "#7BF08D",
        onSecondary = Color(0xFF111111),
        onSecondaryVariant = Color(0xFF111111),
        focusRing = Color(0xFF7BF08D),
        focusBackground = Color(0xFF153A2C),
        background = Color(0xFF0B0F0D),
        backgroundElevated = Color(0xFF141D18),
        backgroundCard = Color(0xFF16251D),
    )

    val RoseGold = ThemeColorPalette(
        secondary = Color(0xFFEC70A9),
        secondaryVariant = Color(0xFFB75AFF),
        accentGradient = listOf(Color(0xFFB75AFF), Color(0xFFEC70A9), Color(0xFFFFB37A)),
        nativeAccentHex = "#FFB37A",
        onSecondary = Color(0xFF111111),
        onSecondaryVariant = Color.White,
        focusRing = Color(0xFFFFB37A),
        focusBackground = Color(0xFF442037),
        background = Color(0xFF100C0F),
        backgroundElevated = Color(0xFF1F161D),
        backgroundCard = Color(0xFF281A24),
    )

    val ArcticBlue = ThemeColorPalette(
        secondary = Color(0xFF3185F5),
        secondaryVariant = Color(0xFF4D55E8),
        accentGradient = listOf(Color(0xFF4DE3FF), Color(0xFF3185F5), Color(0xFF4D55E8)),
        nativeAccentHex = "#4DE3FF",
        onSecondary = Color.White,
        onSecondaryVariant = Color.White,
        focusRing = Color(0xFF4DE3FF),
        focusBackground = Color(0xFF172844),
        background = Color(0xFF0B0E14),
        backgroundElevated = Color(0xFF141A24),
        backgroundCard = Color(0xFF161E2A),
    )

    val Graphite = ThemeColorPalette(
        secondary = Color(0xFFAAB2BE),
        secondaryVariant = Color(0xFF687381),
        accentGradient = listOf(Color(0xFFF3F5F7), Color(0xFFAAB2BE), Color(0xFF687381)),
        nativeAccentHex = "#F3F5F7",
        onSecondary = Color(0xFF111111),
        onSecondaryVariant = Color(0xFFFFFFFF),
        focusRing = Color(0xFFF3F5F7),
        focusBackground = Color(0xFF30343A),
        background = Color(0xFF0C0D0F),
        backgroundElevated = Color(0xFF17191D),
        backgroundCard = Color(0xFF20242A),
    )

    val Crimson = ThemeColorPalette(
        secondary = Color(0xFFE53935),
        secondaryVariant = Color(0xFFC62828),
        nativeAccentHex = "#E53935",
        focusRing = Color(0xFFFF5252),
        focusBackground = Color(0xFF3D1A1A),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF241A1A),
    )

    val Ocean = ThemeColorPalette(
        secondary = Color(0xFF1E88E5),
        secondaryVariant = Color(0xFF1565C0),
        nativeAccentHex = "#1E88E5",
        focusRing = Color(0xFF42A5F5),
        focusBackground = Color(0xFF1A2D3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1A1F24),
    )

    val Violet = ThemeColorPalette(
        secondary = Color(0xFF8E24AA),
        secondaryVariant = Color(0xFF6A1B9A),
        nativeAccentHex = "#8E24AA",
        focusRing = Color(0xFFAB47BC),
        focusBackground = Color(0xFF2D1A3D),
        background = Color(0xFF0D0D0F),
        backgroundElevated = Color(0xFF1A1A1E),
        backgroundCard = Color(0xFF1F1A24),
    )

    val Emerald = ThemeColorPalette(
        secondary = Color(0xFF43A047),
        secondaryVariant = Color(0xFF2E7D32),
        nativeAccentHex = "#43A047",
        focusRing = Color(0xFF66BB6A),
        focusBackground = Color(0xFF1A3D1E),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF1A241A),
    )

    val Amber = ThemeColorPalette(
        secondary = Color(0xFFFB8C00),
        secondaryVariant = Color(0xFFEF6C00),
        nativeAccentHex = "#FB8C00",
        focusRing = Color(0xFFFFA726),
        focusBackground = Color(0xFF3D2D1A),
        background = Color(0xFF0F0D0D),
        backgroundElevated = Color(0xFF1E1A1A),
        backgroundCard = Color(0xFF24201A),
    )

    val Rose = ThemeColorPalette(
        secondary = Color(0xFFD81B60),
        secondaryVariant = Color(0xFFC2185B),
        nativeAccentHex = "#D81B60",
        focusRing = Color(0xFFEC407A),
        focusBackground = Color(0xFF3D1A2D),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF241A1F),
    )

    val White = ThemeColorPalette(
        secondary = Color(0xFFF5F5F5),
        secondaryVariant = Color(0xFFE0E0E0),
        nativeAccentHex = "#F5F5F5",
        onSecondary = Color(0xFF111111),
        onSecondaryVariant = Color(0xFF111111),
        focusRing = Color(0xFFFFFFFF),
        focusBackground = Color(0xFF303030),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF222222),
    )

    val Glass = ThemeColorPalette(
        secondary = Color(0xFFDCEBFF),
        secondaryVariant = Color(0xFF9CC4FF),
        accentGradient = listOf(Color(0xFFF2F8FF), Color(0xFFB4D2FF), Color(0xFF8FA8FF)),
        nativeAccentHex = "#DCEBFF",
        onSecondary = Color(0xFF0C1626),
        onSecondaryVariant = Color(0xFF0C1626),
        focusRing = Color(0xFFE6F1FF),
        focusBackground = Color(0xFF2A3A57),
        background = Color(0xFF0A0E17),
        backgroundElevated = Color(0xD81E2738),
        backgroundCard = Color(0xB3212B3E),
        glassLights = listOf(Color(0x483A6BFF), Color(0x388A4DFF)),
    )

    val AuroraGlass = ThemeColorPalette(
        secondary = Color(0xFF5FE8C4),
        secondaryVariant = Color(0xFF38A9D6),
        accentGradient = listOf(Color(0xFF7CFFCB), Color(0xFF4CC9F0), Color(0xFF7B6CFF)),
        nativeAccentHex = "#5FE8C4",
        onSecondary = Color(0xFF06201B),
        onSecondaryVariant = Color(0xFF041A22),
        focusRing = Color(0xFF9AF5E0),
        focusBackground = Color(0xFF1B4640),
        background = Color(0xFF080F12),
        backgroundElevated = Color(0xD81A2B30),
        backgroundCard = Color(0xB31C2E34),
        glassLights = listOf(Color(0x3800E5A0), Color(0x387B6CFF)),
    )

    val NebulaGlass = ThemeColorPalette(
        secondary = Color(0xFFE29BFF),
        secondaryVariant = Color(0xFF9A6CFF),
        accentGradient = listOf(Color(0xFFFF8AD4), Color(0xFFC77DFF), Color(0xFF7B8CFF)),
        nativeAccentHex = "#E29BFF",
        onSecondary = Color(0xFF1E0B2B),
        focusRing = Color(0xFFF3C4FF),
        focusBackground = Color(0xFF3F2A55),
        background = Color(0xFF0E0A14),
        backgroundElevated = Color(0xD82A2038),
        backgroundCard = Color(0xB32C2239),
        glassLights = listOf(Color(0x48B14DFF), Color(0x38FF4D9D)),
    )

    val Midnight = ThemeColorPalette(
        secondary = Color(0xFF7D9BFF),
        secondaryVariant = Color(0xFF4756D6),
        accentGradient = listOf(Color(0xFF9DB8FF), Color(0xFF7D9BFF), Color(0xFF5C6BFF)),
        nativeAccentHex = "#7D9BFF",
        onSecondary = Color(0xFF0A1024),
        focusRing = Color(0xFFB6C8FF),
        focusBackground = Color(0xFF1C2747),
        background = Color(0xFF080B14),
        backgroundElevated = Color(0xFF121726),
        backgroundCard = Color(0xFF181E31),
    )

    val Sunset = ThemeColorPalette(
        secondary = Color(0xFFFF7A59),
        secondaryVariant = Color(0xFFC9436B),
        accentGradient = listOf(Color(0xFFFFB36B), Color(0xFFFF6F61), Color(0xFFD6418C)),
        nativeAccentHex = "#FF7A59",
        onSecondary = Color(0xFF2B0E08),
        focusRing = Color(0xFFFFC2A1),
        focusBackground = Color(0xFF45211F),
        background = Color(0xFF100B0D),
        backgroundElevated = Color(0xFF1E1518),
        backgroundCard = Color(0xFF281B1F),
    )

    val Mint = ThemeColorPalette(
        secondary = Color(0xFF4FD8B0),
        secondaryVariant = Color(0xFF1C8F86),
        accentGradient = listOf(Color(0xFFA8FFDF), Color(0xFF4FD8B0), Color(0xFF1FA6A0)),
        nativeAccentHex = "#4FD8B0",
        onSecondary = Color(0xFF06201B),
        focusRing = Color(0xFFB4FFE6),
        focusBackground = Color(0xFF16382F),
        background = Color(0xFF0A100F),
        backgroundElevated = Color(0xFF151D1B),
        backgroundCard = Color(0xFF1B2623),
    )

    fun getColorPalette(
        theme: AppTheme,
        customColors: CustomThemeColors = CustomThemeColors.Default,
    ): ThemeColorPalette = when (theme) {
        AppTheme.CUSTOM -> customColors.toColorPalette()
        AppTheme.GOLD -> Gold
        AppTheme.JADE -> Jade
        AppTheme.ROSE_GOLD -> RoseGold
        AppTheme.ARCTIC_BLUE -> ArcticBlue
        AppTheme.GRAPHITE -> Graphite
        AppTheme.CRIMSON -> Crimson
        AppTheme.OCEAN -> Ocean
        AppTheme.VIOLET -> Violet
        AppTheme.EMERALD -> Emerald
        AppTheme.AMBER -> Amber
        AppTheme.ROSE -> Rose
        AppTheme.WHITE -> White
        AppTheme.GLASS -> Glass
        AppTheme.AURORA_GLASS -> AuroraGlass
        AppTheme.NEBULA_GLASS -> NebulaGlass
        AppTheme.MIDNIGHT -> Midnight
        AppTheme.SUNSET -> Sunset
        AppTheme.MINT -> Mint
    }
}
