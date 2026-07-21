// Package ovpnsocks embeds a userspace OpenVPN client (ooni/minivpn v0.0.3, gVisor netstack) and
// exposes the tunnel as a local SOCKS5 proxy — so DJProxy can route the whole device through a VPN
// Gate / OpenVPN server exactly the way it already routes through Tor's local SOCKS5. ONE persistent
// tunnel is established; every SOCKS connection is a new netstack socket over that single tunnel
// (this is the key difference from minivpn's stock TunDialer, which re-dials the whole tunnel per
// connection). Bound to gomobile: Start(ovpn, cacheDir) -> port, and Stop().
package ovpnsocks

import (
	"context"
	"fmt"
	"net"
	"os"
	"sync"

	socks5 "github.com/armon/go-socks5"
	"github.com/ainghazal/minivpn/vpn"
	"golang.zx2c4.com/go118/netip"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// OpenDNS resolvers, reached THROUGH the tunnel (so DNS geolocates to the VPN exit, never the phone).
const (
	dns1 = "208.67.222.222"
	dns2 = "208.67.220.220"
	// Fixed netstack MTU. minivpn's own TunDialer uses client.tunnel.mtu-100, but that field is
	// unexported; 1400 is the safe margin under the usual 1500 tun-mtu VPN Gate advertises.
	tunMTU = 1400
)

var (
	mu       sync.Mutex
	listener net.Listener
	vpnConn  net.Conn
)

// nsResolver resolves SOCKS target hostnames through the VPN's netstack DNS client (OpenDNS over the
// tunnel), so a CONNECT to a domain never leaks a lookup to the phone's resolver.
type nsResolver struct{ tnet *netstack.Net }

func (r nsResolver) Resolve(ctx context.Context, name string) (context.Context, net.IP, error) {
	addrs, err := r.tnet.LookupContextHost(ctx, name)
	if err != nil || len(addrs) == 0 {
		return ctx, nil, fmt.Errorf("resolve %q: %w", name, err)
	}
	ip := net.ParseIP(addrs[0])
	if ip == nil {
		return ctx, nil, fmt.Errorf("resolve %q: bad ip %q", name, addrs[0])
	}
	return ctx, ip, nil
}

// Start writes the .ovpn text to cacheDir, brings up ONE OpenVPN tunnel, wires it to a userspace
// gVisor device, and serves a SOCKS5 proxy on 127.0.0.1:<port>. Returns the chosen port. Idempotent-
// guarded: a second Start while running is an error. Never panics (gomobile seam).
func Start(ovpn string, cacheDir string) (int, error) {
	mu.Lock()
	defer mu.Unlock()
	if listener != nil {
		return 0, fmt.Errorf("ovpnsocks already running")
	}

	// minivpn's ParseConfigFile takes a path; the inline <ca>/<cert>/<key> blocks a VPN Gate profile
	// ships mean no side files are needed. Write to app-private cache, parse, then remove.
	f, err := os.CreateTemp(cacheDir, "djp-*.ovpn")
	if err != nil {
		return 0, fmt.Errorf("temp: %w", err)
	}
	path := f.Name()
	if _, err = f.WriteString(ovpn); err != nil {
		f.Close()
		os.Remove(path)
		return 0, fmt.Errorf("write cfg: %w", err)
	}
	f.Close()
	defer os.Remove(path)

	opts, err := vpn.ParseConfigFile(path)
	if err != nil {
		return 0, fmt.Errorf("parse .ovpn: %w", err)
	}

	// Bring the tunnel up ONCE. raw.Dial() returns the vpn Client as a net.Conn that reads/writes raw
	// tunnel packets and reports the pushed local IP via LocalAddr().
	conn, err := vpn.NewRawDialer(opts).Dial()
	if err != nil {
		return 0, fmt.Errorf("vpn connect: %w", err)
	}
	localIP := conn.LocalAddr().String()

	tunDev, tnet, err := netstack.CreateNetTUN(
		[]netip.Addr{netip.MustParseAddr(localIP)},
		[]netip.Addr{netip.MustParseAddr(dns1), netip.MustParseAddr(dns2)},
		tunMTU,
	)
	if err != nil {
		conn.Close()
		return 0, fmt.Errorf("netstack: %w", err)
	}

	// Pump the two halves: netstack tun <-> raw vpn conn (mirrors minivpn's device.Up).
	go pump(func(b []byte) (int, error) { return tunDev.Read(b, 0) }, conn.Write)
	go pump(conn.Read, func(b []byte) (int, error) { return tunDev.Write(b, 0) })

	server, err := socks5.New(&socks5.Config{
		Resolver: nsResolver{tnet},
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return tnet.DialContext(ctx, network, addr)
		},
	})
	if err != nil {
		conn.Close()
		return 0, fmt.Errorf("socks5: %w", err)
	}

	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		conn.Close()
		return 0, fmt.Errorf("listen: %w", err)
	}
	listener = l
	vpnConn = conn
	port := l.Addr().(*net.TCPAddr).Port
	go server.Serve(l)
	return port, nil
}

// Stop tears the proxy + tunnel down. Idempotent; never panics.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if listener != nil {
		listener.Close()
		listener = nil
	}
	if vpnConn != nil {
		vpnConn.Close()
		vpnConn = nil
	}
}

// pump copies one direction until either side errors.
func pump(read func([]byte) (int, error), write func([]byte) (int, error)) {
	b := make([]byte, 4096)
	for {
		n, err := read(b)
		if err != nil {
			return
		}
		if n > 0 {
			if _, err = write(b[:n]); err != nil {
				return
			}
		}
	}
}
