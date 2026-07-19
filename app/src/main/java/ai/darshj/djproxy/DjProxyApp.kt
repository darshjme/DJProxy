package ai.darshj.djproxy

import android.app.Application
import ai.darshj.djproxy.proxy.LocalSocksServer
import ai.darshj.djproxy.proxy.PreflightValidator
import ai.darshj.djproxy.proxy.ProxyDialer
import ai.darshj.djproxy.vpn.CrashCatcher
import ai.darshj.djproxy.vpn.DnsInterceptor
import ai.darshj.djproxy.vpn.LoopbackProxy
import ai.darshj.djproxy.vpn.TunConfig
import ai.darshj.djproxy.vpn.VpnDependencies
import ai.darshj.djproxy.vpn.VpnRuntime

/**
 * The single Wave-2 wiring point (the serial integration layer). It fills in the frozen
 * [VpnDependencies] factories with the concrete lane implementations so the vpn lane can bring the
 * tunnel up without importing the proxy/engine internals directly.
 *
 * This runs in EVERY process the app spawns (main + `:engine`), before any Activity or Service, so
 * the factories are wired whether the tunnel is started from the UI or auto-restarted by always-on.
 *
 * Load-bearing invariant preserved: every factory receives [VpnRuntime.protector] — the ONE
 * protect() seam — so pre-flight validation, the live upstream dialer, and the loopback front all
 * exclude their sockets from the tun through the exact same path.
 */
class DjProxyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Global safety net FIRST (§4.1): any uncaught throwable on any thread is captured for the
        // diagnostic report + routed to the CriticalFailureSink, then delegated to the OS handler.
        CrashCatcher.install(this)
        wireDependencies()
    }

    private fun wireDependencies() {
        // Pre-flight validator: the real connect + handshake + probe on the live dial path.
        VpnDependencies.validatorFactory = { protector -> PreflightValidator(protector) }

        // Live upstream dialer used by the DNS interceptor and the loopback front.
        VpnDependencies.dialerFactory = { config, protector -> ProxyDialer(config, protector) }

        // Loopback SOCKS front the native engine dials; forwards through the shared ProxyDialer and
        // reports connection counts into the published TunnelStats. It also terminates DNS-over-TCP
        // to the in-tun sentinel locally (the OS resolver's TCP fallback for truncated answers) via
        // the same tunnelled DnsInterceptor, so large/truncated records resolve instead of failing.
        VpnDependencies.loopbackProxyFactory = { config, protector ->
            val dns = DnsInterceptor.create(config, ProxyDialer(config, protector))
            val server = LocalSocksServer(
                config = config,
                protector = protector,
                onConnectionOpened = { VpnRuntime.counters.onConnectionOpened() },
                onConnectionClosed = { VpnRuntime.counters.onConnectionClosed() },
                dnsSentinelHost = TunConfig.DNS_SENTINEL,
                dnsResolve = { query -> dns.resolve(query) },
            )
            object : LoopbackProxy {
                override val listenPort: Int get() = server.listenPort
                override fun start() = server.start()
                override fun stop() = server.stop()
            }
        }

        // The native hev-socks5-tunnel engine is owned out-of-process by DjVpnService (RemoteEngine),
        // so a native crash cannot take down the main process or close the tun; nothing to wire here.
    }
}
