# ovpnengine — userspace OpenVPN → local SOCKS5 (gomobile)

Embeds ooni/ainghazal minivpn v0.0.3 + wireguard netstack + go-socks5, exposing an OpenVPN
tunnel as a local SOCKS5 so a VPN Gate / OpenVPN server can be used AS a proxy by DJProxy's
existing hev tunnel (same shape as the Tor lane). Output: `app/libs/ovpnsocks.aar`.

## Build the .aar (Windows)
Requires: Go 1.18 (gvisor in the old netstack is pinned `!go1.19`), a 2022-era gomobile,
JDK 11 (gomobile emits `javac -source 1.7`, rejected by JDK 12+), Android NDK r27.

```
GOROOT=<go1.18>  GOPATH=<gopath>  JAVA_HOME=<jdk11>  ANDROID_NDK_HOME=<ndk/27.x>
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20220722155234-aaac322e2105
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20220722155234-aaac322e2105
gomobile init
gomobile bind -target=android/arm64,android/arm,android/386,android/amd64 -androidapi 21 \
  -o <repo>/app/libs/ovpnsocks.aar ./
```

Java API produced: `ovpnsocks.Ovpnsocks.start(String ovpn, String cacheDir): long` (SOCKS5 port,
throws on failure) and `Ovpnsocks.stop()`. Supported ciphers: AES-128/256-CBC, AES-128/256-GCM
+ SHA1/256/512 — servers outside that set won't connect (honest partial).
