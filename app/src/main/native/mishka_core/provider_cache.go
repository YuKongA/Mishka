package main

import (
	"path/filepath"
	"sort"
	"strings"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
)

// 运行时缓存路径必须和 adapter/provider、rules/provider 的 HTTP vehicle 一致。
// 不能复用 patchProvidersPath：那是导入校验把文件改写到 providers/ 的内存补丁，
// 磁盘上的 config.yaml 并不走这条路径。停机回写若按 providers/ 拷，下次启动仍会重拉。
func listProviderCachePaths(workDir, transformPath, ageSecretKey string) ([]string, error) {
	out, err := readTransformedConfig(workDir, transformPath, ageSecretKey)
	if err != nil {
		return nil, err
	}
	rawCfg, err := config.UnmarshalRawConfig(out)
	if err != nil {
		return nil, err
	}
	return providerCacheRelPaths(workDir, rawCfg), nil
}

func providerCacheRelPaths(workDir string, cfg *config.RawConfig) []string {
	seen := make(map[string]struct{})
	add := func(rel string) {
		if rel == "" {
			return
		}
		if _, ok := seen[rel]; ok {
			return
		}
		seen[rel] = struct{}{}
	}
	forEachProviders(cfg, func(_, _ int, _ string, provider map[string]any, kind string) {
		add(httpProviderCacheRel(workDir, kind, provider))
	})
	add("cache.db")
	paths := make([]string, 0, len(seen))
	for rel := range seen {
		paths = append(paths, rel)
	}
	sort.Strings(paths)
	return paths
}

func httpProviderCacheRel(workDir, kind string, provider map[string]any) string {
	if providerString(provider, "type") != "http" {
		return ""
	}
	pathStr := providerString(provider, "path")
	urlStr := providerString(provider, "url")
	var abs string
	switch {
	case pathStr != "":
		if filepath.IsAbs(pathStr) {
			abs = filepath.Clean(pathStr)
		} else {
			abs = filepath.Join(workDir, pathStr)
		}
	case urlStr != "":
		abs = filepath.Join(workDir, kind, utils.MakeHash([]byte(urlStr)).String())
	default:
		return ""
	}
	rel, err := filepath.Rel(workDir, abs)
	if err != nil || rel == "." || !filepath.IsLocal(rel) {
		return ""
	}
	rel = filepath.ToSlash(rel)
	if !safeProviderCacheRel(rel) {
		return ""
	}
	return rel
}

func providerString(provider map[string]any, key string) string {
	value, ok := provider[key].(string)
	if !ok {
		return ""
	}
	return value
}

func safeProviderCacheRel(rel string) bool {
	if rel == "" || hasDeniedProviderCacheRune(rel) || strings.HasPrefix(rel, "/") {
		return false
	}
	base := rel
	for _, part := range strings.Split(rel, "/") {
		if part == "" || part == "." || part == ".." {
			return false
		}
		base = part
	}
	switch base {
	case "config.yaml", "mihomo.log",
		"geoip.metadb", "geoip.db", "geoip.dat", "GeoIP.dat",
		"Country.mmdb", "country.mmdb",
		"geosite.dat", "GeoSite.dat",
		"ASN.mmdb", "asn.mmdb":
		return false
	default:
		return true
	}
}

func hasDeniedProviderCacheRune(rel string) bool {
	for _, r := range rel {
		switch r {
		case '\\', 0, '\n', '\r':
			return true
		}
	}
	return false
}
