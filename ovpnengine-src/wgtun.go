//go:build linux
// +build linux

// wgtun.go — WireGuard DIRECT-TUN mode (the fast lane). Instead of the double userspace stack
// (hev tun2socks → local SOCKS5 → gVisor netstack → WARP), wireguard-go drives the app's REAL
// VpnService tun fd directly: read packet from tun → encrypt → one UDP send. No gVisor, no TCP
// re-origination, no SOCKS framing, no loopback hops. This is the same shape as the official
// wireguard-android backend (tun.CreateUnmonitoredTUNFromFD), which the pinned core @20210424
// already ships for exactly this purpose.
//
// Ownership contract (core seam): Kotlin builds the VpnService tun (addAddress = the WG interface
// address, MTU 1420, routes 0.0.0.0/0, addDisallowedApplication(self) so the WG UDP socket
// egresses OUTSIDE the tun — the app already does this), then hands Go the fd via
// ParcelFileDescriptor.detachFd(). Go OWNS the fd from that moment; StopWireguardTun (or a failed
// start) closes it. hev tun2socks must NOT be started in this mode — the tun belongs to WireGuard.
//
// GOOS note: gomobile builds with GOOS=android, which satisfies the `linux` build tag, so this
// file is included in the .aar; wgtun_stub.go keeps the package compiling on the Windows dev box.
package ovpnsocks

import (
	"fmt"
	"net"
	"os"
	"strings"
	"sync"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

var (
	wgTunMu  sync.Mutex
	wgTunDev *device.Device
)

// closeFd releases a detached tun fd on the failure paths — Go owns it from the moment Kotlin
// calls detachFd(), so every early return must close it or it leaks an open tun.
func closeFd(fd int) { _ = os.NewFile(uintptr(fd), "tun").Close() }

// StartWireguardTun brings up WireGuard directly on a real tun fd (VpnService.establish().detachFd()).
// Addresses/DNS/routes/MTU are the VpnService.Builder's job on the Kotlin side — Go only needs the
// crypto + peer parameters (keys are STANDARD BASE64, as in a .conf):
//
//	fd            – detached tun file descriptor; Go takes ownership (closed on stop/failure)
//	privKeyB64    – [Interface] PrivateKey
//	peerPubB64    – [Peer] PublicKey
//	presharedB64  – [Peer] PresharedKey ("" if none — WARP has none)
//	endpoint      – [Peer] Endpoint (host:port; hostnames resolved here for a clear error)
//	allowedIpsCsv – [Peer] AllowedIPs ("" => full tunnel 0.0.0.0/0,::/0)
//	keepalive     – PersistentKeepalive seconds (0 => 25, good for NAT/mobile)
//
// Idempotent-guarded; never panics (gomobile seam). On any error the fd is closed so the caller
// can simply re-establish.
func StartWireguardTun(fd int, privKeyB64, peerPubB64, presharedB64, endpoint, allowedIpsCsv string, keepalive int) error {
	wgTunMu.Lock()
	defer wgTunMu.Unlock()
	if wgTunDev != nil {
		closeFd(fd)
		return fmt.Errorf("wgtun already running")
	}

	privHex, err := keyB64ToHex(privKeyB64)
	if err != nil {
		closeFd(fd)
		return fmt.Errorf("private_key: %w", err)
	}
	pubHex, err := keyB64ToHex(peerPubB64)
	if err != nil {
		closeFd(fd)
		return fmt.Errorf("peer public_key: %w", err)
	}

	// Resolve a hostname endpoint here (clear error instead of a silent handshake stall).
	epHost, epPort, err := net.SplitHostPort(strings.TrimSpace(endpoint))
	if err != nil {
		closeFd(fd)
		return fmt.Errorf("bad endpoint %q: %w", endpoint, err)
	}
	if net.ParseIP(epHost) == nil {
		ips, e := net.LookupIP(epHost)
		if e != nil || len(ips) == 0 {
			closeFd(fd)
			return fmt.Errorf("resolve endpoint %q: %w", epHost, e)
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

	var sb strings.Builder
	fmt.Fprintf(&sb, "private_key=%s\n", privHex)
	fmt.Fprintf(&sb, "public_key=%s\n", pubHex)
	if presharedB64 != "" {
		pskHex, e := keyB64ToHex(presharedB64)
		if e != nil {
			closeFd(fd)
			return fmt.Errorf("preshared_key: %w", e)
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

	// Real tun from the VpnService fd — the official wireguard-android path in this exact core.
	tunDev, _, err := tun.CreateUnmonitoredTUNFromFD(fd)
	if err != nil {
		closeFd(fd)
		return fmt.Errorf("tun from fd: %w", err)
	}

	// NewStdNetBind, NOT NewDefaultBind: the default linux bind sets privileged socket options
	// (SO_MARK) → EPERM in the app sandbox. StdNetBind works unprivileged, and because the app
	// excludes itself from its own tun, this UDP socket egresses directly (no routing loop).
	dev := device.NewDevice(tunDev, conn.NewStdNetBind(), device.NewLogger(device.LogLevelError, "wgtun "))
	if err = dev.IpcSet(sb.String()); err != nil {
		dev.Close() // closes the tun (and our fd) too
		return fmt.Errorf("ipcset: %w", err)
	}
	if err = dev.Up(); err != nil {
		dev.Close()
		return fmt.Errorf("wg up: %w", err)
	}
	wgTunDev = dev
	return nil
}

// StopWireguardTun tears the direct-tun WireGuard device down (closing the tun fd). Idempotent.
func StopWireguardTun() {
	wgTunMu.Lock()
	defer wgTunMu.Unlock()
	if wgTunDev != nil {
		wgTunDev.Close()
		wgTunDev = nil
	}
}

// WireguardTunStatus reports the live peer state as "key=value" lines the controller can poll to
// confirm the handshake instead of guessing with a sleep:
//
//	up=true
//	last_handshake_sec=<unix seconds, 0 until first handshake>
//	rx_bytes=<n>
//	tx_bytes=<n>
//
// Returns "up=false" when the device is not running. Never panics.
func WireguardTunStatus() string {
	wgTunMu.Lock()
	dev := wgTunDev
	wgTunMu.Unlock()
	if dev == nil {
		return "up=false"
	}
	raw, err := dev.IpcGet()
	if err != nil {
		return "up=false\nerror=" + err.Error()
	}
	out := []string{"up=true"}
	for _, line := range strings.Split(raw, "\n") {
		switch {
		case strings.HasPrefix(line, "last_handshake_time_sec="):
			out = append(out, "last_handshake_sec="+line[len("last_handshake_time_sec="):])
		case strings.HasPrefix(line, "rx_bytes="), strings.HasPrefix(line, "tx_bytes="):
			out = append(out, line)
		}
	}
	return strings.Join(out, "\n")
}
