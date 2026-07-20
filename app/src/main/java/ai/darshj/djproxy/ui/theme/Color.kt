package ai.darshj.djproxy.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DJProxy palette — dark-first, no stock Material purple. Two accent families:
 * cyan/indigo for the brand + idle/connecting states, emerald for "safe & connected",
 * amber/rose for warnings and errors. Everything sits on a near-black charcoal base so
 * translucent glass surfaces have room to read as "frosted" rather than muddy.
 */
object DjColors {
    // Base surfaces
    val VoidBlack = Color(0xFF05070A)
    val Charcoal = Color(0xFF0B0F14)
    val CharcoalRaised = Color(0xFF11161D)
    val Slate = Color(0xFF1A212B)
    val HairlineLight = Color(0x1FFFFFFF)
    val HairlineStrong = Color(0x33FFFFFF)

    // Glass
    val GlassFill = Color(0x14FFFFFF)
    val GlassFillStrong = Color(0x22FFFFFF)
    val GlassBorderTop = Color(0x40FFFFFF)
    val GlassBorderBottom = Color(0x08FFFFFF)

    // Brand accent — electric cyan -> indigo
    val AccentCyan = Color(0xFF22D3EE)
    val AccentCyanDeep = Color(0xFF0EA5C7)
    val AccentIndigo = Color(0xFF6366F1)
    val AccentIndigoDeep = Color(0xFF4338CA)

    // Status
    val Emerald = Color(0xFF34D399)
    val EmeraldDeep = Color(0xFF059669)
    val Amber = Color(0xFFF59E0B)
    val AmberDeep = Color(0xFFB45309)
    val Rose = Color(0xFFFB7185)
    val RoseDeep = Color(0xFFE11D48)

    // Text
    val TextPrimary = Color(0xFFF4F6F8)
    val TextSecondary = Color(0xFFA7B0BC)
    // Raised to meet WCAG AA (>=4.5:1) on the charcoal/void base: ~5.5:1 on Charcoal, ~5.8:1 on
    // VoidBlack, still visibly dimmer than TextSecondary. Was 0xFF6B7684 (~4.1:1, failed AA).
    val TextTertiary = Color(0xFF808A98)
    val TextOnAccent = Color(0xFF04121A)

    // Log levels
    val LogDebug = Color(0xFF6B7684)
    val LogInfo = Color(0xFF7DD3FC)
    val LogWarn = Color(0xFFF59E0B)
    val LogError = Color(0xFFFB7185)

    // --- v4 Material 3 Expressive additive tokens (never edit the values above) ---

    /** Third stop of the brand sweep: cyan -> violet -> indigo tri-tone ring. */
    val AccentViolet = Color(0xFF8B7BF5)

    /** Tor-mode identity — distinct from brand cyan and status emerald. */
    val TorPurple = Color(0xFF9D6BFF)
    val TorPurpleDeep = Color(0xFF6D28D9)

    /** Bloom glows for the connect ring only — never used as fills. */
    val GlowCyan = AccentCyan.copy(alpha = 0.45f)
    val GlowEmerald = Emerald.copy(alpha = 0.45f)
    val GlowTor = TorPurple.copy(alpha = 0.45f)
}
