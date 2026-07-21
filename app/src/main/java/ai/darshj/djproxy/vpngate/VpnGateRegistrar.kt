package ai.darshj.djproxy.vpngate

import android.content.Context
import androidx.startup.Initializer
import ai.darshj.djproxy.store.SharedPreferencesOvpnVault
import ai.darshj.djproxy.vpn.FeatureRegistry
import ai.darshj.djproxy.vpn.LogBus

/**
 * The vpngate lane's single wiring point (mirror of `tor.TorRegistrar`) — an androidx.startup
 * [Initializer]. The platform lane references it from the manifest's `InitializationProvider`
 * `<meta-data>`; androidx.startup constructs it once per process, before any Activity/Service, and it:
 *   1. builds the real [RemoteVpnGateSource] (prefs-backed cache) + the [VpnGateController] and the v2
 *      saved-`.ovpn` vault ([SharedPreferencesOvpnVault], its own private `djproxy_ovpn_vault` prefs),
 *   2. publishes BOTH the controller and the vault into the lane's own [VpnGateGateway] holder (the
 *      servers-tab reads the controller; null → the VPN Gate tab is hidden), and
 *   3. contributes [VpnGateSettingsPanel] via the core `FeatureRegistry.addSettingsPanel` seam.
 *
 * No core file is edited — this is the ONLY point where the lane attaches. Idempotent:
 * [FeatureRegistry.addSettingsPanel] de-dups by id and the holder write is a plain volatile assignment,
 * so a re-run (or a second process) cannot double-register.
 *
 * NOTE for the integrator: the ui/ViewModel must READ `VpnGateGateway.controller` (this single instance)
 * rather than construct its own — the catalog + refresh state live here so the tab and any settings
 * surface stay in sync.
 */
class VpnGateRegistrar : Initializer<VpnGateController> {

    override fun create(context: Context): VpnGateController = attach(context.applicationContext)

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        @Volatile
        private var installed: VpnGateController? = null

        @Synchronized
        fun attach(appContext: Context): VpnGateController {
            installed?.let { return it }
            val source = RemoteVpnGateSource.create(appContext)
            val controller = VpnGateController(source)
            // GUARDED: attach runs in InitializationProvider before Application.onCreate; the vault +
            // panel wiring touch SharedPreferences, so a read fault must not crash the process cold.
            runCatching {
                VpnGateGateway.controller = controller
                // v2: publish the saved-.ovpn vault (its own private prefs file) into the same lane holder.
                VpnGateGateway.ovpnVault = SharedPreferencesOvpnVault.fromContext(appContext)
                FeatureRegistry.addSettingsPanel(VpnGateSettingsPanel(controller))
            }.onFailure { LogBus.w("VpnGate", "lane publish skipped: ${it.message}") }
            installed = controller
            LogBus.i("VpnGate", "VPN Gate lane registered (catalog browser + .ovpn hand-off + saved vault; not a tunnel)")
            return controller
        }
    }
}
