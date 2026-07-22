// wgdnscache.go — small positive-only DNS cache in front of the netstack resolver. In the SOCKS5
// lane EVERY new connection pays a full DNS round-trip THROUGH the tunnel (loopback → netstack →
// WARP → 1.1.1.1 and back) before the dial even starts; browsers open dozens of connections per
// page, so this is a large share of perceived slowness. Caching resolved IPs for a short TTL
// removes that per-connection RTT without touching the (pinned) netstack or the working data path.
package ovpnsocks

import (
	"context"
	"net"
	"sync"
	"time"
)

const (
	dnsCacheTTL = 2 * time.Minute // short enough for CDN rotation, long enough to cover a browse session
	dnsCacheCap = 512             // flush-on-full keeps memory bounded without an LRU dependency
)

type dnsEntry struct {
	ip  net.IP
	exp time.Time
}

// socksResolver is the shape armon/go-socks5 expects (NameResolver).
type socksResolver interface {
	Resolve(ctx context.Context, name string) (context.Context, net.IP, error)
}

// cachedResolver wraps a socksResolver with a TTL cache. Negative results are NOT cached — a
// transient in-tunnel timeout must not blackhole a domain for two minutes.
type cachedResolver struct {
	inner socksResolver
	mu    sync.Mutex
	m     map[string]dnsEntry
}

func newCachedResolver(inner socksResolver) *cachedResolver {
	return &cachedResolver{inner: inner, m: make(map[string]dnsEntry, 64)}
}

func (c *cachedResolver) Resolve(ctx context.Context, name string) (context.Context, net.IP, error) {
	now := time.Now()
	c.mu.Lock()
	if e, ok := c.m[name]; ok && now.Before(e.exp) {
		c.mu.Unlock()
		return ctx, e.ip, nil
	}
	c.mu.Unlock()

	ctx2, ip, err := c.inner.Resolve(ctx, name)
	if err != nil || ip == nil {
		return ctx2, ip, err
	}
	c.mu.Lock()
	if len(c.m) >= dnsCacheCap {
		c.m = make(map[string]dnsEntry, 64)
	}
	c.m[name] = dnsEntry{ip: ip, exp: now.Add(dnsCacheTTL)}
	c.mu.Unlock()
	return ctx2, ip, nil
}
