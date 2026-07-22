package ovpnsocks

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"testing"
	"time"

	"golang.org/x/net/proxy"
)

// End-to-end: register a free WARP account, bring the WireGuard tunnel up as a local SOCKS5, and
// fetch cloudflare's trace THROUGH it — proving the whole engine path carries real traffic and exits
// via WARP. Run manually:  go test -run TestWarpTunnel -v -count=1
func TestWarpTunnel(t *testing.T) {
	js, err := RegisterWarp()
	if err != nil {
		t.Fatalf("RegisterWarp: %v", err)
	}
	var p struct {
		PrivateKey    string `json:"private_key"`
		Address       string `json:"address"`
		PeerPublicKey string `json:"peer_public_key"`
		Endpoint      string `json:"endpoint"`
	}
	if err := json.Unmarshal([]byte(js), &p); err != nil {
		t.Fatalf("parse profile: %v", err)
	}

	port, err := StartWireguard(p.PrivateKey, p.Address, "1.1.1.1", p.PeerPublicKey, "", p.Endpoint, "0.0.0.0/0", 25)
	if err != nil {
		t.Fatalf("StartWireguard: %v", err)
	}
	defer StopWireguard()
	t.Logf("WireGuard SOCKS5 up on 127.0.0.1:%d", port)

	dialer, err := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", port), nil, proxy.Direct)
	if err != nil {
		t.Fatalf("socks dialer: %v", err)
	}
	client := &http.Client{
		Timeout: 25 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.Dial(network, addr)
			},
		},
	}
	// Give the handshake a moment.
	time.Sleep(3 * time.Second)
	resp, err := client.Get("https://www.cloudflare.com/cdn-cgi/trace")
	if err != nil {
		t.Fatalf("GET through WARP tunnel failed: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	t.Logf("cloudflare trace via WARP tunnel:\n%s", string(body))
}
