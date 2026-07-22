// WireGuard → local SOCKS5. Sibling to ovpnsocks.go: brings up ONE userspace WireGuard tunnel
// (gVisor netstack, no kernel tun) and serves it as a local SOCKS5 proxy on 127.0.0.1:<port>, so
// DJProxy's existing hev tunnel can route the whole device through ANY WireGuard endpoint — Cloudflare
// WARP, a user's own Oracle/VPS server, or any public WG peer — exactly the way it already routes
// through Tor's local SOCKS5. wireguard-go owns its own UDP socket (conn.NewDefaultBind), so unlike the
// OpenVPN lane there is no raw-conn pump; this is the simplest and most reliable engine mode.
package ovpnsocks

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"strings"
	"sync"

	socks5 "github.com/armon/go-socks5"
	"golang.zx2c4.com/go118/netip"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

var (
	wgMu       sync.Mutex
	wgListener net.Listener
	wgDev      *device.Device
)

// keyB64ToHex converts a standard-base64 WireGuard key (what .conf / WARP profiles carry) to the
// lowercase-hex form the wireguard-go UAPI (IpcSet) requires. Returns error on a malformed key.
func keyB64ToHex(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil {
		return "", fmt.Errorf("bad key b64: %w", err)
	}
	if len(raw) != 32 {
		return "", fmt.Errorf("bad key length %d (want 32)", len(raw))
	}
	return hex.EncodeToString(raw), nil
}

// firstAddr strips a CIDR mask and returns the bare IP ("172.16.0.2/32" -> "172.16.0.2").
func firstAddr(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.IndexByte(s, '/'); i >= 0 {
		return s[:i]
	}
	return s
}

// StartWireguard brings up a userspace WireGuard tunnel and serves it as SOCKS5 on 127.0.0.1:<port>.
// Params mirror a WireGuard profile (keys are STANDARD BASE64, as in a .conf / WARP config):
//   privKeyB64      – [Interface] PrivateKey
//   addressCsv      – [Interface] Address (comma list; first v4 used as the netstack local IP)
//   dnsCsv          – [Interface] DNS (comma list; first used for in-tunnel resolution)
//   peerPubB64      – [Peer] PublicKey
//   presharedB64    – [Peer] PresharedKey ("" if none — WARP has none)
//   endpoint        – [Peer] Endpoint (host:port; a hostname is resolved here)
//   allowedIpsCsv   – [Peer] AllowedIPs (comma list; default full-tunnel if empty)
//   keepalive       – PersistentKeepalive seconds (0 => 25, good for NAT/mobile)
// Returns the chosen SOCKS5 port. Idempotent-guarded; never panics (gomobile seam).
func StartWireguard(privKeyB64, addressCsv, dnsCsv, peerPubB64, presharedB64, endpoint, allowedIpsCsv string, keepalive int) (int, error) {
	wgMu.Lock()
	defer wgMu.Unlock()
	if wgListener != nil {
		return 0, fmt.Errorf("wgsocks already running")
	}

	privHex, err := keyB64ToHex(privKeyB64)
	if err != nil {
		return 0, fmt.Errorf("private_key: %w", err)
	}
	pubHex, err := keyB64ToHex(peerPubB64)
	if err != nil {
		return 0, fmt.Errorf("peer public_key: %w", err)
	}

	// Local interface address (netstack tun). Prefer the first v4 in the Address list.
	var localAddrs []netip.Addr
	for _, a := range strings.Split(addressCsv, ",") {
		a = firstAddr(a)
		if a == "" {
			continue
		}
		ip, e := netip.ParseAddr(a)
		if e != nil {
			continue
		}
		localAddrs = append(localAddrs, ip)
	}
	if len(localAddrs) == 0 {
		return 0, fmt.Errorf("no valid interface Address in %q", addressCsv)
	}

	// DNS servers reached THROUGH the tunnel (so lookups geolocate to the WG exit).
	var dnsAddrs []netip.Addr
	for _, d := range strings.Split(dnsCsv, ",") {
		d = firstAddr(d)
		if d == "" {
			continue
		}
		if ip, e := netip.ParseAddr(d); e == nil {
			dnsAddrs = append(dnsAddrs, ip)
		}
	}
	if len(dnsAddrs) == 0 {
		dnsAddrs = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}

	// Resolve a hostname endpoint to ip:port (wireguard-go can resolve, but doing it here surfaces a
	// clear error instead of a silent handshake stall).
	epHost, epPort, err := net.SplitHostPort(strings.TrimSpace(endpoint))
	if err != nil {
		return 0, fmt.Errorf("bad endpoint %q: %w", endpoint, err)
	}
	if net.ParseIP(epHost) == nil {
		ips, e := net.LookupIP(epHost)
		if e != nil || len(ips) == 0 {
			return 0, fmt.Errorf("resolve endpoint %q: %w", epHost, e)
		}
		epHost = ips[0].String()
	}
	resolvedEndpoint := net.JoinHostPort(epHost, epPort)

	allowed := strings.TrimSpace(allowedIpsCsv)
	if allowed == "" {
		allowed = "0.0.0.0/0,::/0"
	}
	if keepalive <= 0 {
		keepalive = 25
	}

	// Build the wireguard-go UAPI (IpcSet) config. Keys are HEX here.
	var sb strings.Builder
	fmt.Fprintf(&sb, "private_key=%s\n", privHex)
	fmt.Fprintf(&sb, "public_key=%s\n", pubHex)
	if presharedB64 != "" {
		pskHex, e := keyB64ToHex(presharedB64)
		if e != nil {
			return 0, fmt.Errorf("preshared_key: %w", e)
		}
		fmt.Fprintf(&sb, "preshared_key=%s\n", pskHex)
	}
	fmt.Fprintf(&sb, "endpoint=%s\n", resolvedEndpoint)
	fmt.Fprintf(&sb, "persistent_keepalive_interval=%d\n", keepalive)
	for _, cidr := range strings.Split(allowed, ",") {
		cidr = strings.TrimSpace(cidr)
		if cidr != "" {
			fmt.Fprintf(&sb, "allowed_ip=%s\n", cidr)
		}
	}
	ipcConfig := sb.String()

	tunDev, tnet, err := netstack.CreateNetTUN(localAddrs, dnsAddrs, 1420)
	if err != nil {
		return 0, fmt.Errorf("netstack: %w", err)
	}
	// NewStdNetBind (net.ListenUDP), NOT NewDefaultBind: on linux/android NewDefaultBind returns a
	// raw-socket bind that sets privileged socket options (SO_MARK/BINDTODEVICE) → EPERM ("wg up:
	// permission denied") in an unprivileged Android app. StdNetBind goes through the managed socket
	// layer and works in the app sandbox (and the app already excludes itself from its own tun, so this
	// UDP socket to the WG endpoint egresses directly without a routing loop).
	dev := device.NewDevice(tunDev, conn.NewStdNetBind(), device.NewLogger(device.LogLevelError, "wg "))
	if err = dev.IpcSet(ipcConfig); err != nil {
		dev.Close()
		return 0, fmt.Errorf("ipcset: %w", err)
	}
	if err = dev.Up(); err != nil {
		dev.Close()
		return 0, fmt.Errorf("wg up: %w", err)
	}

	server, err := socks5.New(&socks5.Config{
		Resolver: nsResolver{tnet}, // reuse ovpnsocks.go's tunnel-side resolver
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return tnet.DialContext(ctx, network, addr)
		},
	})
	if err != nil {
		dev.Close()
		return 0, fmt.Errorf("socks5: %w", err)
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		dev.Close()
		return 0, fmt.Errorf("listen: %w", err)
	}
	wgListener = l
	wgDev = dev
	port := l.Addr().(*net.TCPAddr).Port
	go server.Serve(l)
	return port, nil
}

// StopWireguard tears the WireGuard proxy + tunnel down. Idempotent; never panics.
func StopWireguard() {
	wgMu.Lock()
	defer wgMu.Unlock()
	if wgListener != nil {
		wgListener.Close()
		wgListener = nil
	}
	if wgDev != nil {
		wgDev.Close()
		wgDev = nil
	}
}
