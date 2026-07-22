package ovpnsocks

import (
	"context"
	"fmt"
	"net"
	"testing"
	"time"
)

type countingResolver struct{ calls int }

func (c *countingResolver) Resolve(ctx context.Context, name string) (context.Context, net.IP, error) {
	c.calls++
	if name == "bad.example" {
		return ctx, nil, fmt.Errorf("nxdomain")
	}
	return ctx, net.IPv4(93, 184, 216, 34), nil
}

func TestCachedResolverHitAndNegativeSkip(t *testing.T) {
	inner := &countingResolver{}
	r := newCachedResolver(inner)
	ctx := context.Background()

	for i := 0; i < 5; i++ {
		if _, ip, err := r.Resolve(ctx, "ok.example"); err != nil || ip == nil {
			t.Fatalf("resolve: ip=%v err=%v", ip, err)
		}
	}
	if inner.calls != 1 {
		t.Fatalf("want 1 upstream call for cached name, got %d", inner.calls)
	}

	// Negative results must NOT be cached.
	before := inner.calls
	for i := 0; i < 3; i++ {
		if _, _, err := r.Resolve(ctx, "bad.example"); err == nil {
			t.Fatal("want error for bad.example")
		}
	}
	if inner.calls != before+3 {
		t.Fatalf("negative results were cached: %d calls", inner.calls-before)
	}
}

func TestCachedResolverExpiry(t *testing.T) {
	inner := &countingResolver{}
	r := newCachedResolver(inner)
	ctx := context.Background()
	if _, _, err := r.Resolve(ctx, "ok.example"); err != nil {
		t.Fatal(err)
	}
	// Force the entry to be expired.
	r.mu.Lock()
	e := r.m["ok.example"]
	e.exp = time.Now().Add(-time.Second)
	r.m["ok.example"] = e
	r.mu.Unlock()
	if _, _, err := r.Resolve(ctx, "ok.example"); err != nil {
		t.Fatal(err)
	}
	if inner.calls != 2 {
		t.Fatalf("want re-resolve after expiry, got %d calls", inner.calls)
	}
}
