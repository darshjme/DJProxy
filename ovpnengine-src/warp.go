// Cloudflare WARP registration — the free, anonymous, always-available public WireGuard endpoint.
// RegisterWarp generates a fresh WireGuard keypair, registers an anonymous WARP account with
// Cloudflare's client API (no signup, no email, unlimited free tier), and returns the resulting
// WireGuard profile fields as JSON. Kotlin persists that JSON once and feeds the fields into
// StartWireguard on every connect (registration is cached; we never re-register per connect).
package ovpnsocks

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"golang.org/x/crypto/curve25519"
)

// The public WARP endpoint + Cloudflare's client API are stable, well-known values (wgcf/warp-go).
const (
	warpAPIBase    = "https://api.cloudflareclient.com/v0a2158"
	warpUserAgent  = "okhttp/3.12.1"
	warpCFVersion  = "a-6.11-2223"
	warpEndpoint   = "engage.cloudflareclient.com:2408"
	warpEndpointV4 = "162.159.192.1:2408"
)

// WarpProfile is the WireGuard profile handed back to Kotlin (keys are standard base64).
type warpProfile struct {
	PrivateKey    string `json:"private_key"`
	Address       string `json:"address"`         // assigned v4/32 (comma may add v6)
	PeerPublicKey string `json:"peer_public_key"`  // Cloudflare's WG public key
	Endpoint      string `json:"endpoint"`         // host:port
}

// genWgKeypair returns (privB64, pubB64) — a clamped Curve25519 WireGuard keypair.
func genWgKeypair() (string, string, error) {
	var priv [32]byte
	if _, err := io.ReadFull(rand.Reader, priv[:]); err != nil {
		return "", "", err
	}
	// WireGuard key clamping.
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return "", "", err
	}
	return base64.StdEncoding.EncodeToString(priv[:]),
		base64.StdEncoding.EncodeToString(pub), nil
}

// RegisterWarp registers a fresh anonymous WARP account and returns a JSON warpProfile string.
// Never panics (gomobile seam); returns an error string on any failure so the ui can surface it.
func RegisterWarp() (string, error) {
	privB64, pubB64, err := genWgKeypair()
	if err != nil {
		return "", fmt.Errorf("keygen: %w", err)
	}

	reqBody, _ := json.Marshal(map[string]interface{}{
		"key":         pubB64,
		"install_id":  "",
		"fcm_token":   "",
		"tos":         time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		"model":       "PC",
		"type":        "Android",
		"locale":      "en_US",
	})

	req, err := http.NewRequest("POST", warpAPIBase+"/reg", bytes.NewReader(reqBody))
	if err != nil {
		return "", fmt.Errorf("warp req: %w", err)
	}
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	req.Header.Set("User-Agent", warpUserAgent)
	req.Header.Set("CF-Client-Version", warpCFVersion)

	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("warp register: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("warp register http %d: %s", resp.StatusCode, string(body))
	}

	// Parse the fields we need. The v0a2158 /reg response is FLAT: `config` sits at the top level
	// (there is no `result` wrapper).
	var out struct {
		Config struct {
			Peers []struct {
				PublicKey string `json:"public_key"`
				Endpoint  struct {
					Host string `json:"host"`
					V4   string `json:"v4"`
				} `json:"endpoint"`
			} `json:"peers"`
			Interface struct {
				Addresses struct {
					V4 string `json:"v4"`
					V6 string `json:"v6"`
				} `json:"addresses"`
			} `json:"interface"`
		} `json:"config"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return "", fmt.Errorf("warp parse: %w (%s)", err, truncate(string(body), 200))
	}
	if len(out.Config.Peers) == 0 {
		return "", fmt.Errorf("warp: no peer in response (%s)", truncate(string(body), 200))
	}

	peer := out.Config.Peers[0]
	endpoint := warpEndpoint
	if peer.Endpoint.Host != "" {
		endpoint = peer.Endpoint.Host
	} else if peer.Endpoint.V4 != "" {
		endpoint = peer.Endpoint.V4
	}
	addr := out.Config.Interface.Addresses.V4
	if addr == "" {
		addr = "172.16.0.2"
	}

	prof := warpProfile{
		PrivateKey:    privB64,
		Address:       addr,
		PeerPublicKey: peer.PublicKey,
		Endpoint:      endpoint,
	}
	js, err := json.Marshal(prof)
	if err != nil {
		return "", err
	}
	return string(js), nil
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}
