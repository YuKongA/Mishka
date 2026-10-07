package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/component/age"

	"mishka_core/overrides"
)

func TestProviderCachePathsMatchRuntimeVehicles(t *testing.T) {
	workDir := t.TempDir()
	config := []byte(`
proxy-providers:
  hashed:
    type: http
    url: https://example.com/a.yaml
  explicit:
    type: http
    url: https://example.com/b.yaml
    path: ./proxy_providers/b.yaml
  local:
    type: file
    path: ./local.yaml
  outside:
    type: http
    url: https://example.com/out.yaml
    path: /tmp/outside.yaml
rule-providers:
  rules:
    type: http
    url: https://example.com/r.yaml
  geodata:
    type: http
    url: https://example.com/geo.yaml
    path: geoip.metadb
`)
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), config, 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := listProviderCachePaths(workDir, "", "")
	if err != nil {
		t.Fatal(err)
	}
	hashed := "proxies/" + utils.MakeHash([]byte("https://example.com/a.yaml")).String()
	rules := "rules/" + utils.MakeHash([]byte("https://example.com/r.yaml")).String()
	want := []providerCacheEntry{
		{Path: "cache.db"},
		{Path: hashed, URL: "https://example.com/a.yaml"},
		{Path: "proxy_providers/b.yaml", URL: "https://example.com/b.yaml"},
		{Path: rules, URL: "https://example.com/r.yaml"},
	}
	if len(got) != len(want) {
		t.Fatalf("paths = %+v, want %+v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("paths = %+v, want %+v", got, want)
		}
	}
}

func TestProviderCachePathsFollowTransformAndAge(t *testing.T) {
	workDir := t.TempDir()
	secret, public, err := age.GenX25519KeyPair()
	if err != nil {
		t.Fatal(err)
	}
	plain := []byte("proxy-providers:\n  a:\n    type: http\n    url: https://example.com/a.yaml\n    path: original.yaml\n")
	encrypted, err := age.EncryptBytes(plain, public)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), encrypted, 0o600); err != nil {
		t.Fatal(err)
	}
	scriptPath := filepath.Join(workDir, "path.js")
	script := []byte(`function main(c) { c["proxy-providers"].a.path = "from-script.yaml"; return c; }`)
	if err := os.WriteFile(scriptPath, script, 0o600); err != nil {
		t.Fatal(err)
	}
	transformPath := filepath.Join(workDir, "transform.json")
	transform, err := json.Marshal(overrides.Transform{Overrides: []overrides.Spec{{
		Name: "path", Format: overrides.FormatJS, Path: scriptPath,
	}}})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(transformPath, transform, 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := listProviderCachePaths(workDir, transformPath, secret)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, entry := range got {
		if entry.Path == "original.yaml" {
			t.Fatalf("transform did not replace provider path: %+v", got)
		}
		if entry.Path == "from-script.yaml" {
			found = true
			if entry.URL != "https://example.com/a.yaml" {
				t.Fatalf("url = %q, want provider url", entry.URL)
			}
		}
		if entry.Path == "providers/from-script.yaml" {
			t.Fatalf("validation prefetch path leaked into runtime cache list: %+v", got)
		}
	}
	if !found {
		t.Fatalf("transformed path missing: %+v", got)
	}
}

func TestProviderCacheKeepsDistinctURLsForSamePath(t *testing.T) {
	workDir := t.TempDir()
	config := []byte(`
proxy-providers:
  a:
    type: http
    url: https://example.com/a.yaml
    path: shared.yaml
  b:
    type: http
    url: https://example.com/b.yaml
    path: shared.yaml
`)
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), config, 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := listProviderCachePaths(workDir, "", "")
	if err != nil {
		t.Fatal(err)
	}
	urls := map[string]struct{}{}
	for _, entry := range got {
		if entry.Path == "shared.yaml" {
			urls[entry.URL] = struct{}{}
		}
	}
	if _, ok := urls["https://example.com/a.yaml"]; !ok {
		t.Fatalf("missing first url: %+v", got)
	}
	if _, ok := urls["https://example.com/b.yaml"]; !ok {
		t.Fatalf("collapsed distinct url: %+v", got)
	}
}

func TestProviderCacheRelRejectsUnsafe(t *testing.T) {
	denied := []string{
		"",
		"/abs",
		"../x",
		"a/../b",
		"a//b",
		"config.yaml",
		".mishka-provider-cache.json",
		"proxies/../config.yaml",
		"geoip.metadb",
	}
	for _, rel := range denied {
		if safeProviderCacheRel(rel) {
			t.Fatalf("accepted %q", rel)
		}
	}
	for _, r := range []rune{'\\', 0, '\n', '\r'} {
		if safeProviderCacheRel("a" + string(r) + "b") {
			t.Fatalf("accepted rune %q", r)
		}
	}
	if !safeProviderCacheRel("proxies/abc") {
		t.Fatal("rejected safe path")
	}
}
