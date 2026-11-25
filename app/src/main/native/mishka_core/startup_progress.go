package main

import (
	"encoding/json"
	"io"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/common/observable"
	"github.com/metacubex/mihomo/config"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"go.yaml.in/yaml/v3"
)
const (
	stageTransforming         = "transforming"
	stageParsing              = "parsing"
	stageDownloadingGeo       = "downloading_geo"
	stageLoadingGeo           = "loading_geo"
	stageStartingListeners    = "starting_listeners"
	stageLoadingProxyProvider = "loading_proxy_providers"
	stageLoadingRuleProvider  = "loading_rule_providers"
	stageDownloadingUI        = "downloading_ui"
	stageStartingDNS          = "starting_dns"
	stageReady                = "ready"
)

type startupProgress struct {
	Stage   string `json:"stage"`
	Detail  string `json:"detail,omitempty"`
	Current int    `json:"current,omitempty"`
	Total   int    `json:"total,omitempty"`
}

type startupPhase int

const (
	phaseInit startupPhase = iota
	phaseListeners
	phaseProxyProviders
	phaseRuleProviders
	phaseDownloadingUI
	phaseFinalizing
)

// startupReporter 仅在 runMihomo 启动阶段存活：订阅 mihomo 内部日志流（不受用户
// log-level 过滤）与 RuleUpdateCallback，实时向 --startup-status-file 输出当前
// 阶段与 Provider 加载进度；启动完成后立即 Close，不在运行期留任何后台开销。
type startupReporter struct {
	mu             sync.Mutex
	file           *os.File
	last           startupProgress
	hasLast        bool
	stopped        bool
	phase          startupPhase
	geoTotal       int
	geoLoaded      map[string]bool
	proxyProviders map[string]P.ProxyProvider
	proxyOrder     []string
	proxyStarted   map[string]bool
	proxyDone      map[string]bool
	ruleProviders  map[string]bool
	ruleOrder      []string
	ruleDone       map[string]bool

	sub        observable.Subscription[log.Event]
	ruleCloser io.Closer
	ticker     *time.Ticker
	wg         sync.WaitGroup
	closeOnce  sync.Once
}

func newStartupReporter(path string) *startupReporter {
	if path == "" {
		return nil
	}
	// 原地打开（不 rename）：ROOT 模式下文件由 app UID 预建并保持 0600 属主，
	// root 进程仅覆盖写其内容，Kotlin 侧即可免 su 直接读取与删除。
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE, 0o600)
	if err != nil {
		return nil
	}
	r := &startupReporter{
		file:   f,
		ticker: time.NewTicker(150 * time.Millisecond),
	}
	r.sub = log.Subscribe()
	r.ruleCloser = tunnel.Tunnel.RuleUpdateCallback().Register(r.onRuleProviderUpdated)
	r.wg.Add(1)
	go r.loop()
	return r
}

func (r *startupReporter) loop() {
	defer r.wg.Done()
	for {
		select {
		case ev, ok := <-r.sub:
			if !ok {
				return
			}
			r.handleLog(ev.Payload)
		case <-r.ticker.C:
			r.pollProxyProviders()
		}
	}
}

// ConfigOption 在 ParseWithBytes 完成后、ApplyConfig 前采集待加载的外部 Provider 清单。
func (r *startupReporter) ConfigOption() hub.Option {
	if r == nil {
		return func(*config.Config) {}
	}
	return func(cfg *config.Config) {
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.stopped {
			return
		}
		r.proxyProviders = make(map[string]P.ProxyProvider)
		r.proxyOrder = nil
		r.proxyStarted = make(map[string]bool)
		r.proxyDone = make(map[string]bool)
		for name, pv := range cfg.Providers {
			vt := pv.VehicleType()
			if vt == P.HTTP || vt == P.File {
				r.proxyProviders[name] = pv
				r.proxyOrder = append(r.proxyOrder, name)
			}
		}
		sort.Strings(r.proxyOrder)

		r.ruleProviders = make(map[string]bool)
		r.ruleOrder = nil
		r.ruleDone = make(map[string]bool)
		for name, pv := range cfg.RuleProviders {
			vt := pv.VehicleType()
			if vt == P.HTTP || vt == P.File {
				r.ruleProviders[name] = true
				r.ruleOrder = append(r.ruleOrder, name)
			}
		}
		sort.Strings(r.ruleOrder)

		if r.phase < phaseListeners {
			r.phase = phaseListeners
		}
		r.writeLocked(startupProgress{Stage: stageStartingListeners})
	}
}

// PrepareGeoTotal 在 hub.Parse 前轻扫配置统计唯一 GeoSite/GeoIP 规则数，
// 用于启动阶段展示「加载 Geo 规则 (i/n)」。只反序列化规则与 DNS/Sniffer 片段，跳过 proxies。
func (r *startupReporter) PrepareGeoTotal(configBytes []byte, overrideJSONPath, ageSecretKey string) {
	if r == nil {
		return
	}
	total := estimateGeoRuleCount(configBytes, overrideJSONPath, ageSecretKey)
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.stopped {
		return
	}
	r.geoTotal = total
	r.geoLoaded = make(map[string]bool, total)
}

func (r *startupReporter) SetStage(stage, detail string) {
	if r == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.stopped {
		return
	}
	switch stage {
	case stageDownloadingUI:
		if r.phase < phaseDownloadingUI {
			r.phase = phaseDownloadingUI
		}
	case stageStartingDNS, stageReady:
		r.phase = phaseFinalizing
	}
	r.writeLocked(startupProgress{Stage: stage, Detail: detail})
}

func (r *startupReporter) Close() {
	if r == nil {
		return
	}
	r.closeOnce.Do(func() {
		// 先置 stopped 再排空 r.sub：保证广播器不阻塞，同时避免积压日志覆盖已落盘的 stageReady。
		r.mu.Lock()
		r.stopped = true
		r.mu.Unlock()

		if r.ruleCloser != nil {
			_ = r.ruleCloser.Close()
		}
		// 必须先 UnSubscribe 关闭 channel 再 Wait，保证消费者在关闭前持续排空 logCh 避免阻塞日志广播。
		if r.sub != nil {
			log.UnSubscribe(r.sub)
		}
		r.wg.Wait()
		if r.ticker != nil {
			r.ticker.Stop()
		}
		r.mu.Lock()
		if r.file != nil {
			_ = r.file.Close()
			r.file = nil
		}
		r.proxyProviders = nil
		r.proxyOrder = nil
		r.proxyStarted = nil
		r.proxyDone = nil
		r.ruleProviders = nil
		r.ruleOrder = nil
		r.ruleDone = nil
		r.geoLoaded = nil
		r.mu.Unlock()
	})
}

func (r *startupReporter) handleLog(payload string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.stopped {
		return
	}
	switch {
	case strings.Contains(payload, "Can't find GeoSite.dat, start download") ||
		strings.HasPrefix(payload, "GeoSite.dat invalid, remove and download"):
		if r.phase == phaseInit {
			r.writeLocked(startupProgress{Stage: stageDownloadingGeo, Detail: "GeoSite.dat"})
		}
	case strings.Contains(payload, "Can't find GeoIP.dat, start download") ||
		strings.HasPrefix(payload, "GeoIP.dat invalid, remove and download"):
		if r.phase == phaseInit {
			r.writeLocked(startupProgress{Stage: stageDownloadingGeo, Detail: "GeoIP.dat"})
		}
	case strings.Contains(payload, "Can't find MMDB, start download") ||
		strings.HasPrefix(payload, "MMDB invalid, remove and download"):
		if r.phase == phaseInit {
			r.writeLocked(startupProgress{Stage: stageDownloadingGeo, Detail: "geoip.metadb"})
		}
	case strings.Contains(payload, "Can't find ASN.mmdb, start download") ||
		strings.HasPrefix(payload, "ASN invalid, remove and download"):
		if r.phase == phaseInit {
			r.writeLocked(startupProgress{Stage: stageDownloadingGeo, Detail: "ASN.mmdb"})
		}
	case payload == "Download GeoSite.dat finish" ||
		payload == "Download GeoIP.dat finish" ||
		payload == "Download ASN.mmdb finish":
		if r.phase == phaseInit {
			r.publishGeoProgressLocked()
		}
	case strings.HasPrefix(payload, "Load GeoSite rule: "):
		if r.phase == phaseInit {
			r.recordGeoLoadedLocked("geosite:" + strings.TrimPrefix(payload, "Load GeoSite rule: "))
		}
	case strings.HasPrefix(payload, "Load GeoIP rule: "):
		if r.phase == phaseInit {
			r.recordGeoLoadedLocked("geoip:" + strings.TrimPrefix(payload, "Load GeoIP rule: "))
		}
	case strings.HasPrefix(payload, "Load MMDB file:") ||
		strings.HasPrefix(payload, "Load ASN file:"):
		if r.phase == phaseInit {
			r.publishGeoProgressLocked()
		}
	case strings.HasPrefix(payload, "Start initial provider "):
		if r.phase >= phaseDownloadingUI {
			return
		}
		name := strings.TrimPrefix(payload, "Start initial provider ")
		if _, ok := r.proxyProviders[name]; ok && !r.proxyStarted[name] {
			r.proxyStarted[name] = true
			if r.phase < phaseRuleProviders {
				r.phase = phaseProxyProviders
				r.publishProxyProgressLocked()
			}
		} else if r.ruleProviders[name] {
			r.phase = phaseRuleProviders
			r.publishRuleProgressLocked()
		}
	case strings.HasPrefix(payload, "initial proxy provider "):
		if r.phase >= phaseRuleProviders {
			return
		}
		rest := strings.TrimPrefix(payload, "initial proxy provider ")
		if name := matchProviderErrorName(rest, r.proxyOrder); name != "" {
			r.proxyDone[name] = true
			if r.phase == phaseProxyProviders {
				r.publishProxyProgressLocked()
			}
		}
	case strings.HasPrefix(payload, "initial rule provider "):
		if r.phase >= phaseDownloadingUI {
			return
		}
		rest := strings.TrimPrefix(payload, "initial rule provider ")
		if name := matchProviderErrorName(rest, r.ruleOrder); name != "" {
			r.ruleDone[name] = true
			r.phase = phaseRuleProviders
			r.publishRuleProgressLocked()
		}
	case payload == "External UI downloading ...":
		if r.phase < phaseFinalizing {
			r.phase = phaseDownloadingUI
			r.writeLocked(startupProgress{Stage: stageDownloadingUI})
		}
	}
}

func (r *startupReporter) onRuleProviderUpdated(rp P.RuleProvider) {
	if rp == nil {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.stopped || r.phase >= phaseDownloadingUI {
		return
	}
	name := rp.Name()
	if !r.ruleProviders[name] {
		return
	}
	r.ruleDone[name] = true
	r.phase = phaseRuleProviders
	r.publishRuleProgressLocked()
}

func (r *startupReporter) pollProxyProviders() {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.stopped || r.phase != phaseProxyProviders {
		return
	}
	changed := false
	for name, pv := range r.proxyProviders {
		if !r.proxyDone[name] && pv.Version() > 0 {
			r.proxyDone[name] = true
			changed = true
		}
	}
	if changed {
		r.publishProxyProgressLocked()
	}
}
func (r *startupReporter) recordGeoLoadedLocked(key string) {
	if r.geoLoaded == nil {
		r.geoLoaded = make(map[string]bool)
	}
	r.geoLoaded[key] = true
	r.publishGeoProgressLocked()
}

func (r *startupReporter) publishGeoProgressLocked() {
	cur := len(r.geoLoaded)
	total := r.geoTotal
	if cur > total {
		total = cur
	}
	if cur == 0 {
		r.writeLocked(startupProgress{Stage: stageLoadingGeo})
		return
	}
	r.writeLocked(startupProgress{
		Stage:   stageLoadingGeo,
		Current: cur,
		Total:   total,
	})
}

type geoEstimateConfig struct {
	GeodataMode bool                `yaml:"geodata-mode" json:"geodata-mode"`
	Rules       []string            `yaml:"rules" json:"rules"`
	SubRules    map[string][]string `yaml:"sub-rules" json:"sub-rules"`
	DNS         struct {
		Enable                      bool           `yaml:"enable" json:"enable"`
		EnhancedMode                string         `yaml:"enhanced-mode" json:"enhanced-mode"`
		Fallback                    []string       `yaml:"fallback" json:"fallback"`
		FakeIPFilter                []string       `yaml:"fake-ip-filter" json:"fake-ip-filter"`
		NameServerPolicy            map[string]any `yaml:"nameserver-policy" json:"nameserver-policy"`
		ProxyServerNameserverPolicy map[string]any `yaml:"proxy-server-nameserver-policy" json:"proxy-server-nameserver-policy"`
		FallbackFilter              struct {
			GeoIP     bool     `yaml:"geoip" json:"geoip"`
			GeoIPCode string   `yaml:"geoip-code" json:"geoip-code"`
			GeoSite   []string `yaml:"geosite" json:"geosite"`
		} `yaml:"fallback-filter" json:"fallback-filter"`
	} `yaml:"dns" json:"dns"`
	Sniffer struct {
		ForceDomain    []string `yaml:"force-domain" json:"force-domain"`
		SkipDomain     []string `yaml:"skip-domain" json:"skip-domain"`
		SkipSrcAddress []string `yaml:"skip-src-address" json:"skip-src-address"`
		SkipDstAddress []string `yaml:"skip-dst-address" json:"skip-dst-address"`
	} `yaml:"sniffer" json:"sniffer"`
}

func estimateGeoRuleCount(configBytes []byte, overrideJSONPath, ageSecretKey string) int {
	plain, err := decryptConfig(configBytes, ageSecretKey)
	if err != nil {
		return 0
	}
	var cfg geoEstimateConfig
	// 与 mihomo DefaultRawConfig 保持一致
	cfg.DNS.FallbackFilter.GeoIP = true
	cfg.DNS.FallbackFilter.GeoIPCode = "CN"
	if err := yaml.Unmarshal(plain, &cfg); err != nil {
		return 0
	}
	if overrideJSONPath != "" {
		if data, err := os.ReadFile(overrideJSONPath); err == nil && len(data) > 0 {
			_ = json.Unmarshal(data, &cfg)
		}
	}

	geoSite := make(map[string]bool)
	geoIP := make(map[string]bool)
	addSite := func(raw string) {
		if k := normalizeGeoSiteKey(raw); k != "" {
			geoSite[k] = true
		}
	}
	addIP := func(raw string) {
		if !cfg.GeodataMode {
			return
		}
		if k := normalizeGeoIPKey(raw); k != "" {
			geoIP[k] = true
		}
	}
	scanRuleList := func(lines []string) {
		for _, line := range lines {
			collectGeoFromRuleLine(line, addSite, addIP)
		}
	}

	scanRuleList(cfg.Rules)
	for _, sub := range cfg.SubRules {
		scanRuleList(sub)
	}
	if cfg.DNS.Enable {
		for k := range cfg.DNS.NameServerPolicy {
			collectPrefixedGeo(k, "geosite:", addSite)
		}
		for k := range cfg.DNS.ProxyServerNameserverPolicy {
			collectPrefixedGeo(k, "geosite:", addSite)
		}
		if strings.EqualFold(cfg.DNS.EnhancedMode, "fake-ip") {
			for _, item := range cfg.DNS.FakeIPFilter {
				if !collectPrefixedGeo(item, "geosite:", addSite) {
					collectGeoFromRuleLine(item, addSite, addIP)
				}
			}
		}
		if len(cfg.DNS.Fallback) > 0 {
			if cfg.DNS.FallbackFilter.GeoIP {
				addIP(cfg.DNS.FallbackFilter.GeoIPCode)
			}
			for _, gs := range cfg.DNS.FallbackFilter.GeoSite {
				addSite(gs)
			}
		}
	}
	for _, d := range cfg.Sniffer.ForceDomain {
		collectPrefixedGeo(d, "geosite:", addSite)
	}
	for _, d := range cfg.Sniffer.SkipDomain {
		collectPrefixedGeo(d, "geosite:", addSite)
	}
	for _, a := range cfg.Sniffer.SkipSrcAddress {
		collectPrefixedGeo(a, "geoip:", addIP)
	}
	for _, a := range cfg.Sniffer.SkipDstAddress {
		collectPrefixedGeo(a, "geoip:", addIP)
	}

	// geodata.InitGeoSite/InitGeoIP 首次 Verify 会固定加载一次 "cn"
	if len(geoSite) > 0 {
		geoSite["cn"] = true
	}
	if len(geoIP) > 0 {
		geoIP["cn"] = true
	}
	return len(geoSite) + len(geoIP)
}

func collectPrefixedGeo(raw, prefix string, add func(string)) bool {
	if !strings.HasPrefix(strings.ToLower(raw), prefix) {
		return false
	}
	for _, item := range strings.Split(raw[len(prefix):], ",") {
		add(item)
	}
	return true
}

func collectGeoFromRuleLine(line string, addSite, addIP func(string)) {
	trimmed := strings.TrimSpace(line)
	if trimmed == "" {
		return
	}
	parts := strings.SplitN(trimmed, ",", 3)
	if len(parts) >= 2 {
		switch strings.ToUpper(strings.TrimSpace(parts[0])) {
		case "GEOSITE":
			addSite(parts[1])
			return
		case "GEOIP", "SRC-GEOIP":
			addIP(parts[1])
			return
		}
	}
	// 复合规则 AND/OR/NOT 中可能嵌套 (GEOSITE,xxx) / (GEOIP,xxx) / (SRC-GEOIP,xxx)
	upper := strings.ToUpper(trimmed)
	for _, tag := range []struct {
		prefix string
		isSite bool
	}{
		{"(GEOSITE,", true},
		{"(GEOIP,", false},
		{"(SRC-GEOIP,", false},
	} {
		pos := 0
		for {
			idx := strings.Index(upper[pos:], tag.prefix)
			if idx < 0 {
				break
			}
			start := pos + idx + len(tag.prefix)
			end := strings.IndexAny(trimmed[start:], ",)")
			if end < 0 {
				break
			}
			val := trimmed[start : start+end]
			if tag.isSite {
				addSite(val)
			} else {
				addIP(val)
			}
			pos = start + end
		}
	}
}

func normalizeGeoSiteKey(raw string) string {
	s := strings.TrimSpace(raw)
	if strings.HasPrefix(s, "!") {
		s = strings.TrimSpace(s[1:])
	}
	s = strings.ToLower(s)
	parts := strings.Split(s, "@")
	listName := strings.TrimSpace(parts[0])
	if listName == "" {
		return ""
	}
	var attrs []string
	for _, attr := range parts[1:] {
		if a := strings.TrimSpace(attr); a != "" {
			attrs = append(attrs, a)
		}
	}
	if len(attrs) > 0 {
		return listName + "@" + strings.Join(attrs, ",")
	}
	return listName
}

func normalizeGeoIPKey(raw string) string {
	s := strings.TrimSpace(raw)
	if strings.HasPrefix(s, "!") {
		s = strings.TrimSpace(s[1:])
	}
	s = strings.ToLower(s)
	if s == "" || s == "lan" {
		return ""
	}
	return s
}


func (r *startupReporter) publishProxyProgressLocked() {
	total := len(r.proxyOrder)
	if total == 0 {
		return
	}
	r.writeLocked(startupProgress{
		Stage:   stageLoadingProxyProvider,
		Detail:  firstPendingName(r.proxyOrder, r.proxyDone),
		Current: len(r.proxyDone),
		Total:   total,
	})
}

func (r *startupReporter) publishRuleProgressLocked() {
	total := len(r.ruleOrder)
	if total == 0 {
		return
	}
	r.writeLocked(startupProgress{
		Stage:   stageLoadingRuleProvider,
		Detail:  firstPendingName(r.ruleOrder, r.ruleDone),
		Current: len(r.ruleDone),
		Total:   total,
	})
}

func firstPendingName(order []string, done map[string]bool) string {
	for _, name := range order {
		if !done[name] {
			return name
		}
	}
	return ""
}

func matchProviderErrorName(rest string, order []string) string {
	for _, name := range order {
		if strings.HasPrefix(rest, name+" error: ") {
			return name
		}
	}
	return ""
}

func (r *startupReporter) writeLocked(p startupProgress) {
	if r.file == nil {
		return
	}
	if r.hasLast && r.last == p {
		return
	}
	data, err := json.Marshal(p)
	if err != nil {
		return
	}
	if _, err := r.file.Seek(0, io.SeekStart); err != nil {
		return
	}
	if _, err := r.file.Write(data); err != nil {
		return
	}
	if err := r.file.Truncate(int64(len(data))); err != nil {
		return
	}
	r.last = p
	r.hasLast = true
}
