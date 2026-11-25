package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
)

type stubProxyProvider struct {
	name    string
	vt      P.VehicleType
	version atomic.Uint32
}

func (s *stubProxyProvider) Name() string                                             { return s.name }
func (s *stubProxyProvider) VehicleType() P.VehicleType                               { return s.vt }
func (s *stubProxyProvider) Type() P.ProviderType                                     { return P.Proxy }
func (s *stubProxyProvider) Initial() error                                           { return nil }
func (s *stubProxyProvider) Update() error                                            { return nil }
func (s *stubProxyProvider) Proxies() []C.Proxy                                       { return nil }
func (s *stubProxyProvider) Count() int                                               { return 0 }
func (s *stubProxyProvider) Touch()                                                   {}
func (s *stubProxyProvider) HealthCheck()                                             {}
func (s *stubProxyProvider) Version() uint32                                          { return s.version.Load() }
func (s *stubProxyProvider) HealthCheckURL() string                                   { return "" }
func (s *stubProxyProvider) RegisterHealthCheckTask(string, utils.IntRanges[uint16], string, uint) {
}

type stubRuleProvider struct {
	name string
	vt   P.VehicleType
}

func (s *stubRuleProvider) Name() string                               { return s.name }
func (s *stubRuleProvider) VehicleType() P.VehicleType                 { return s.vt }
func (s *stubRuleProvider) Type() P.ProviderType                       { return P.Rule }
func (s *stubRuleProvider) Initial() error                             { return nil }
func (s *stubRuleProvider) Update() error                              { return nil }
func (s *stubRuleProvider) Behavior() P.RuleBehavior                   { return P.Domain }
func (s *stubRuleProvider) Count() int                                 { return 0 }
func (s *stubRuleProvider) Match(*C.Metadata, C.RuleMatchHelper) bool  { return false }
func (s *stubRuleProvider) Strategy() any                              { return nil }

func readStartupProgressFile(t *testing.T, path string) startupProgress {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read startup progress file: %v", err)
	}
	var got startupProgress
	if err := json.Unmarshal(data, &got); err != nil {
		t.Fatalf("unmarshal %q: %v", string(data), err)
	}
	return got
}

func TestStartupReporterOverwriteTruncatesAndStages(t *testing.T) {
	path := filepath.Join(t.TempDir(), "startup.status")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	r := newStartupReporter(path)
	if r == nil {
		t.Fatal("expected non-nil startupReporter")
	}
	defer r.Close()

	// 先写长内容再写短内容，确认 Truncate 不会留下尾垃圾导致 JSON 解析失败。
	r.handleLog("Can't find GeoIP.dat, start download")
	got := readStartupProgressFile(t, path)
	if got.Stage != stageDownloadingGeo || got.Detail != "GeoIP.dat" {
		t.Fatalf("got %+v, want downloading_geo/GeoIP.dat", got)
	}

	r.SetStage(stageReady, "")
	got = readStartupProgressFile(t, path)
	if got.Stage != stageReady || got.Detail != "" {
		t.Fatalf("got %+v, want ready with empty detail", got)
	}
}

func TestStartupReporterRuleAndSameNameProviders(t *testing.T) {
	path := filepath.Join(t.TempDir(), "startup.status")
	r := newStartupReporter(path)
	if r == nil {
		t.Fatal("expected non-nil startupReporter")
	}
	defer r.Close()

	proxyShared := &stubProxyProvider{name: "shared", vt: P.HTTP}
	compatProxy := &stubProxyProvider{name: "default", vt: P.Compatible}
	ruleA := &stubRuleProvider{name: "alpha", vt: P.HTTP}
	ruleB := &stubRuleProvider{name: "shared", vt: P.HTTP}
	inlineRule := &stubRuleProvider{name: "inline-only", vt: P.Inline}

	cfg := &config.Config{
		Providers: map[string]P.ProxyProvider{
			"shared":  proxyShared,
			"default": compatProxy,
		},
		RuleProviders: map[string]P.RuleProvider{
			"alpha":       ruleA,
			"shared":      ruleB,
			"inline-only": inlineRule,
		},
	}
	r.ConfigOption()(cfg)
	got := readStartupProgressFile(t, path)
	if got.Stage != stageStartingListeners {
		t.Fatalf("after ConfigOption got %+v, want starting_listeners", got)
	}

	// 同名 provider 先命中 proxy-provider 阶段；Version() 升格后推进完成数。
	r.handleLog("Start initial provider shared")
	got = readStartupProgressFile(t, path)
	if got.Stage != stageLoadingProxyProvider || got.Current != 0 || got.Total != 1 || got.Detail != "shared" {
		t.Fatalf("got %+v, want loading_proxy_providers 0/1 detail=shared", got)
	}
	proxyShared.version.Store(1)
	r.pollProxyProviders()
	got = readStartupProgressFile(t, path)
	if got.Stage != stageLoadingProxyProvider || got.Current != 1 || got.Total != 1 || got.Detail != "" {
		t.Fatalf("got %+v, want loading_proxy_providers 1/1 empty detail", got)
	}

	// 再次收到同名 shared（或 alpha）进入 rule-provider 阶段，总数只含 HTTP/File，不含 Inline。
	r.handleLog("Start initial provider shared")
	got = readStartupProgressFile(t, path)
	if got.Stage != stageLoadingRuleProvider || got.Current != 0 || got.Total != 2 || got.Detail != "alpha" {
		t.Fatalf("got %+v, want loading_rule_providers 0/2 detail=alpha", got)
	}

	// alpha 完成后，detail 指向仍在等待的 shared。
	r.onRuleProviderUpdated(ruleA)
	got = readStartupProgressFile(t, path)
	if got.Stage != stageLoadingRuleProvider || got.Current != 1 || got.Total != 2 || got.Detail != "shared" {
		t.Fatalf("got %+v, want loading_rule_providers 1/2 detail=shared", got)
	}

	// shared 下载失败走错误日志同样推进完成数。
	r.handleLog("initial rule provider shared error: timeout")
	got = readStartupProgressFile(t, path)
	if got.Stage != stageLoadingRuleProvider || got.Current != 2 || got.Total != 2 || got.Detail != "" {
		t.Fatalf("got %+v, want loading_rule_providers 2/2 empty detail", got)
	}

	// 进入外部面板下载后，延迟的 rule 回调不能回退阶段。
	r.handleLog("External UI downloading ...")
	got = readStartupProgressFile(t, path)
	if got.Stage != stageDownloadingUI {
		t.Fatalf("got %+v, want downloading_ui", got)
	}
	r.onRuleProviderUpdated(ruleB)
	got = readStartupProgressFile(t, path)
	if got.Stage != stageDownloadingUI {
		t.Fatalf("late rule update overwrote stage: %+v", got)
	}
}

func TestStartupReporterCloseIsIdempotentAndSafeAfterStop(t *testing.T) {
	path := filepath.Join(t.TempDir(), "startup.status")
	r := newStartupReporter(path)
	if r == nil {
		t.Fatal("expected non-nil startupReporter")
	}
	r.SetStage(stageParsing, "")
	r.Close()
	r.Close() // idempotent

	// Close 之后 ConfigOption / SetStage 不得 panic 或重写文件。
	r.ConfigOption()(&config.Config{})
	r.SetStage(stageReady, "")
	got := readStartupProgressFile(t, path)
	if got.Stage != stageParsing {
		t.Fatalf("got %+v after Close, want unchanged parsing", got)
	}
	// 短等确认后台 ticker/goroutine 已完全退出无竞态。
	time.Sleep(20 * time.Millisecond)
}

func TestStartupReporterGeoRuleProgressCountWithoutDetail(t *testing.T) {
	path := filepath.Join(t.TempDir(), "startup.status")
	r := newStartupReporter(path)
	if r == nil {
		t.Fatal("expected non-nil startupReporter")
	}
	defer r.Close()

	cfgYAML := []byte(`
geodata-mode: true
dns:
  enable: true
  enhanced-mode: fake-ip
  fake-ip-filter:
    - "geosite:apple"
  nameserver-policy:
    "geosite:google,youtube": 8.8.8.8
rules:
  - GEOSITE,google,PROXY
  - GEOSITE,!cn,PROXY
  - AND,((GEOSITE,openai),(GEOIP,US)),PROXY
  - GEOIP,LAN,DIRECT
`)
	// 唯一 GeoSite: apple, google, youtube, cn, openai (5)
	// 唯一 GeoIP (geodata-mode=true, 排除 LAN, Verify 含 cn): us, cn (2) -> total = 7
	r.PrepareGeoTotal(cfgYAML, "", "")

	r.handleLog("Load GeoSite rule: cn")
	got := readStartupProgressFile(t, path)
	if got.Stage != stageLoadingGeo || got.Current != 1 || got.Total != 7 || got.Detail != "" {
		t.Fatalf("got %+v, want loading_geo 1/7 with empty detail", got)
	}

	// 重复日志不重复计数
	r.handleLog("Load GeoSite rule: cn")
	got = readStartupProgressFile(t, path)
	if got.Current != 1 || got.Total != 7 || got.Detail != "" {
		t.Fatalf("duplicate geo log incremented count: %+v", got)
	}

	r.handleLog("Load GeoIP rule: us")
	got = readStartupProgressFile(t, path)
	if got.Stage != stageLoadingGeo || got.Current != 2 || got.Total != 7 || got.Detail != "" {
		t.Fatalf("got %+v, want loading_geo 2/7 with empty detail", got)
	}

	// dns.enable=false 时 DNS 段被 patchMishka 替换；redir-host 时 fake-ip-filter 不生效。
	if n := estimateGeoRuleCount([]byte(`
dns:
  enable: false
  enhanced-mode: fake-ip
  nameserver-policy:
    "geosite:google": 8.8.8.8
  fake-ip-filter:
    - "geosite:apple"
rules: []
`), "", ""); n != 0 {
		t.Fatalf("disabled dns estimated %d geo rules, want 0", n)
	}
	if n := estimateGeoRuleCount([]byte(`
dns:
  enable: true
  enhanced-mode: redir-host
  fake-ip-filter:
    - "geosite:apple"
rules: []
`), "", ""); n != 0 {
		t.Fatalf("redir-host fake-ip-filter estimated %d geo rules, want 0", n)
	}
}

func TestStartupReporterMonotonicPhaseAndCloseDrainPreservesReady(t *testing.T) {
	path := filepath.Join(t.TempDir(), "startup.status")
	r := newStartupReporter(path)
	if r == nil {
		t.Fatal("expected non-nil startupReporter")
	}
	defer r.Close()

	proxyA := &stubProxyProvider{name: "p1", vt: P.HTTP}
	ruleA := &stubRuleProvider{name: "r1", vt: P.HTTP}
	ruleB := &stubRuleProvider{name: "r2", vt: P.HTTP}
	r.ConfigOption()(&config.Config{
		Providers:     map[string]P.ProxyProvider{"p1": proxyA},
		RuleProviders: map[string]P.RuleProvider{"r1": ruleA, "r2": ruleB},
	})

	// ConfigOption 之后延迟到达的 Geo / MMDB 日志不得回退阶段。
	r.handleLog("Load MMDB file: /tmp/geoip.metadb")
	r.handleLog("Load GeoSite rule: cn")
	if got := readStartupProgressFile(t, path); got.Stage != stageStartingListeners {
		t.Fatalf("late geo log regressed stage to %+v", got)
	}

	// 规则回调先于滞后的 proxy provider 启动日志到达时，不得回退到 loading_proxy_providers。
	r.onRuleProviderUpdated(ruleA)
	r.handleLog("Start initial provider p1")
	got := readStartupProgressFile(t, path)
	if got.Stage != stageLoadingRuleProvider || got.Current != 1 || got.Total != 2 || got.Detail != "r2" {
		t.Fatalf("lagging proxy log regressed stage to %+v", got)
	}

	// SetStage(ready) 后即便日志通道仍有未消费事件，Close() 排空不得覆盖 ready。
	r.SetStage(stageReady, "")
	log.Infoln("External UI downloading ...")
	log.Infoln("Load MMDB file: /tmp/geoip.metadb")
	r.Close()
	if got := readStartupProgressFile(t, path); got.Stage != stageReady {
		t.Fatalf("Close drain overwrote stageReady with %+v", got)
	}
}
