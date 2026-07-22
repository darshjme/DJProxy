package ovpnsocks

import "testing"

// Live smoke test of the Cloudflare WARP registration. Run manually:
//   go test -run TestWarpRegister -v
func TestWarpRegister(t *testing.T) {
	js, err := RegisterWarp()
	if err != nil {
		t.Fatalf("RegisterWarp failed: %v", err)
	}
	t.Logf("WARP profile JSON: %s", js)
	if len(js) < 40 {
		t.Fatalf("suspiciously short profile: %s", js)
	}
}
