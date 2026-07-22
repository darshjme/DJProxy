//go:build !linux
// +build !linux

// Non-linux stub so the package (and its tests) still compile on the Windows dev box. gomobile
// builds with GOOS=android (which satisfies the `linux` tag), so the .aar always gets the real
// implementation in wgtun.go.
package ovpnsocks

import "fmt"

// StartWireguardTun is android/linux-only (real VpnService tun fd). See wgtun.go.
func StartWireguardTun(fd int, privKeyB64, peerPubB64, presharedB64, endpoint, allowedIpsCsv string, keepalive int) error {
	return fmt.Errorf("wireguard direct-tun requires android/linux")
}

// StopWireguardTun is a no-op off-device.
func StopWireguardTun() {}

// WireguardTunStatus always reports down off-device.
func WireguardTunStatus() string { return "up=false" }
