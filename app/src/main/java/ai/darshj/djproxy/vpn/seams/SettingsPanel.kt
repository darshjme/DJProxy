package ai.darshj.djproxy.vpn.seams

import androidx.compose.runtime.Composable

/**
 * Seam (§9.5): a settings section a feature lane contributes. The `ui` lane owns only the settings
 * HOST screen that iterates [ai.darshj.djproxy.vpn.FeatureRegistry.settingsPanels] and renders each
 * panel's [Content] inside a GlassSurface — so location / hotspot / diagnostics ship their own
 * settings UI in their OWN package without editing the ui lane's files.
 */
interface SettingsPanel {
    val id: String
    val title: String
    val order: Int

    @Composable
    fun Content()
}
