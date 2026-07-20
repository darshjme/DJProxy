package ai.darshj.djproxy.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DJProxy palette — v8 obsidian. Dark-first, now true black-and-gray: the old blue-black cast is
 * neutralized toward obsidian, and the cyan/violet/indigo brand family is collapsed to a single
 * restrained cool "steel" edge-light. Every token NAME is preserved from v4 so all ~34 consumers
 * recompile untouched — only the VALUES moved. The one reactive colour in the product is now the
 * per-state edge-light/glow on the obsidian orb; status hues (emerald/amber/rose) and Tor purple are
 * kept as functional signal but desaturated toward muted variants (colour is never the sole
 * meaning-carrier — a StageLabel word always accompanies).
 */
object DjColors {
    // Base surfaces — elevation ladder, desaturated toward neutral obsidian (no blue tint).
    val VoidBlack = Color(0xFF060607)      // app background, deepest
    val Charcoal = Color(0xFF0C0D0F)       // base surface
    val CharcoalRaised = Color(0xFF14161A) // raised card / sheet
    val Slate = Color(0xFF20242B)          // highest surface / orb mid-flank
    val HairlineLight = Color(0x1FFFFFFF)
    val HairlineStrong = Color(0x33FFFFFF)

    // Glass — already neutral white-alpha, unchanged.
    val GlassFill = Color(0x14FFFFFF)
    val GlassFillStrong = Color(0x22FFFFFF)
    val GlassBorderTop = Color(0x40FFFFFF)
    val GlassBorderBottom = Color(0x08FFFFFF)

    // Brand accent — the PRIMARY v8 change. The former electric cyan -> indigo rainbow is now a cool
    // near-white -> mid-gray STEEL gradient. Names kept so djBrandTriBrush / DjAccentBrush / DjMonogram
    // / DjBackgroundBrush all go monochrome without touching a single consumer.
    val AccentCyan = Color(0xFFC7D0DA)      // was 0xFF22D3EE — cool near-white steel (edge-light key)
    val AccentCyanDeep = Color(0xFF8B95A2)  // was 0xFF0EA5C7 — mid steel
    val AccentIndigo = Color(0xFF9AA4B0)    // was 0xFF6366F1
    val AccentIndigoDeep = Color(0xFF6B7280) // was 0xFF4338CA

    // Status — retained as functional signal, desaturated toward muted so nothing screams colour on
    // the obsidian field. These drive ONLY the orb per-state glow/rim tint, never the sphere body.
    val Emerald = Color(0xFF5FA98A)         // was 0xFF34D399 — muted sage
    val EmeraldDeep = Color(0xFF3E7A66)     // was 0xFF059669
    val Amber = Color(0xFFC9973F)           // was 0xFFF59E0B — muted ochre
    val AmberDeep = Color(0xFF8A6A2E)       // was 0xFFB45309
    val Rose = Color(0xFFC77883)            // was 0xFFFB7185 — muted brick
    val RoseDeep = Color(0xFF9C4550)        // was 0xFFE11D48

    // Text — already neutral; kept and re-verified for WCAG AA against the (slightly darker) obsidian
    // base steps below. The new Charcoal/VoidBlack are marginally darker than v4, so every text step's
    // contrast can only have risen — TextTertiary stays ≥4.5:1 on Charcoal (~5.7:1) and VoidBlack.
    val TextPrimary = Color(0xFFF4F6F8)
    val TextSecondary = Color(0xFFA7B0BC)
    val TextTertiary = Color(0xFF808A98)
    // On the near-white steel edge-light: 0xFF04121A on 0xFFC7D0DA reads ~14:1 — comfortably dark.
    val TextOnAccent = Color(0xFF04121A)

    // Log levels — LogInfo neutralized off the old sky-blue to steel; warn/error follow muted status.
    val LogDebug = Color(0xFF6B7684)
    val LogInfo = Color(0xFFA7B4C2)         // was 0xFF7DD3FC — neutral steel
    val LogWarn = Color(0xFFC9973F)         // follows muted Amber
    val LogError = Color(0xFFC77883)        // follows muted Rose

    // --- v4 Material 3 Expressive additive tokens (never edit the base values above) ---

    /** Third stop of the (now steel, not rainbow) brand sweep. */
    val AccentViolet = Color(0xFFB4BDC8)    // was 0xFF8B7BF5 — light steel

    /** Tor-mode identity — folded from vivid purple toward a muted graphite-purple. */
    val TorPurple = Color(0xFF8A7FA6)       // was 0xFF9D6BFF
    val TorPurpleDeep = Color(0xFF564B6B)   // was 0xFF6D28D9

    /** Bloom glows for the connect orb only — never used as fills. Recomputed off the new parents. */
    val GlowCyan = AccentCyan.copy(alpha = 0.45f)
    val GlowEmerald = Emerald.copy(alpha = 0.45f)
    val GlowTor = TorPurple.copy(alpha = 0.45f)

    // --- v8 obsidian-orb additive tokens (new; deleting/renaming any token is forbidden) ---

    /** Orb rim falloff — the deepest obsidian, just below VoidBlack, for the sphere's far edge. */
    val ObsidianCore = Color(0xFF07090C)

    /** The single cool steel edge-light the orb/CTA reads directly. Aliased to the brand steel so the
     *  reactive light and the brand sweep are literally the same value. */
    val SteelEdge = Color(0xFFC7D0DA)
    val SteelEdgeDeep = Color(0xFF8B95A2)

    /** Orb key-light highlight — the near-top-left hot stop of the sphere's radial body gradient.
     *  A graphite grey (never a bright white) so the sphere reads glossy-black, not chrome. */
    val OrbHighlight = Color(0xFF3A3F46)
}
