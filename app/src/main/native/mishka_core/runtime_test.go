package main

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/config"
	Const "github.com/metacubex/mihomo/constant"
)

func parseRuntimeConfig(t *testing.T, data []byte, override string) *config.Config {
	t.Helper()
	previousOverride := config.OverrideJSONPath
	t.Cleanup(func() { config.OverrideJSONPath = previousOverride })
	config.OverrideJSONPath = ""
	if override != "" {
		path := filepath.Join(t.TempDir(), "override.json")
		if err := os.WriteFile(path, []byte(override), 0600); err != nil {
			t.Fatal(err)
		}
		config.OverrideJSONPath = path
	}
	cfg, err := config.Parse(data)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { destroyProviders(cfg) })
	return cfg
}

func TestRuntimeTunStack(t *testing.T) {
	tests := []struct {
		name         string
		subscription string
		override     string
		want         Const.TUNStack
	}{
		{"no stack set", "rules: []\n", `{ "tun": { "enable": true } }`, Const.TunMips},
		{"VPN default", "rules: []\n", `{ "tun": { "enable": true, "file-descriptor": 42, "mtu": 9000, "auto-route": false } }`, Const.TunMips},
		{"subscription stack", "tun:\n  stack: system\n", `{ "tun": { "enable": true } }`, Const.TunSystem},
		{"user stack", "rules: []\n", `{ "tun": { "enable": true, "stack": "gvisor" } }`, Const.TunGvisor},
		{"both stacks", "tun:\n  stack: system\n", `{ "tun": { "enable": true, "stack": "gvisor" } }`, Const.TunGvisor},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := parseRuntimeConfig(t, []byte(tt.subscription), tt.override).General.Tun.Stack
			if got != tt.want {
				t.Fatalf("TUN stack = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestRuntimeTunStackWithEncryptedSubscription(t *testing.T) {
	secretKey, publicKey, err := age.GenX25519KeyPair()
	if err != nil {
		t.Fatal(err)
	}
	data, err := age.EncryptBytes([]byte("tun:\n  stack: mixed\n"), publicKey)
	if err != nil {
		t.Fatal(err)
	}
	age.SetGlobalSecretKeys(secretKey)
	t.Cleanup(func() { age.SetGlobalSecretKeys() })
	got := parseRuntimeConfig(t, data, `{ "tun": { "enable": true } }`).General.Tun.Stack
	if got != Const.TunMixed {
		t.Fatal("encrypted subscription's explicit stack was ignored")
	}
}

func TestRuntimeTunCongestionController(t *testing.T) {
	for _, fd := range []int{0, 42} {
		t.Run(fmt.Sprintf("fd=%d", fd), func(t *testing.T) {
			subscription := []byte("tun:\n  enable: true\n  congestion-controller: bbr\n")
			override := fmt.Sprintf(`{ "tun": { "file-descriptor": %d, "mtu": 9000, "auto-route": false } }`, fd)
			got := parseRuntimeConfig(t, subscription, override).General.Tun.CongestionController
			want := "bbr"
			if fd > 0 {
				want = ""
			}
			if got != want {
				t.Fatalf("TUN congestion controller = %q, want %q", got, want)
			}
		})
	}
}
