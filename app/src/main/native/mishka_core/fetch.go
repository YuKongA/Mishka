package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path"
	P "path"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/bbolt"
	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
)

const fetchTimeout = 60 * time.Second

// provider 并发拉取上限，避免十几个源同时打爆远端或本地 mixed-port
const prefetchConcurrency = 5

// JSON-序列化后跨 JNI 边界回 Kotlin，字段必须与 [data.bridge.CoreFetchProgress] 对齐。
type FetchProgress struct {
	Action      string   `json:"action"`
	Args        []string `json:"args"`
	Progress    int      `json:"progress"`
	MaxProgress int      `json:"max"`
}

type FetchResult struct {
	Upload   int64  `json:"upload"`
	Download int64  `json:"download"`
	Total    int64  `json:"total"`
	Expire   int64  `json:"expire"`
	FileName string `json:"fileName"`
}

// 成功返回 JSON FetchResult 的 C.CString；失败返回 "error: ..."；NULL 表示参数非法。
// 调用方必须 mishkaFreeString 释放返回值。进度由 mishkaQueryProgress 轮询。
// userAgent 留空时退回 mishkaCoreInit 设置的全局默认 UA。
//
//export mishkaFetchAndValid
func mishkaFetchAndValid(
	cWorkDir *C.char,
	cURL *C.char,
	force C.int,
	cHttpProxy *C.char,
	cUserAgent *C.char,
	token C.int,
) *C.char {
	return guardString(func() string {
		workDir := C.GoString(cWorkDir)
		rawURL := C.GoString(cURL)
		httpProxy := C.GoString(cHttpProxy)
		userAgent := C.GoString(cUserAgent)

		ctx, cancel := context.WithCancel(context.Background())
		cancelRegistry.Store(int32(token), func() { cancel() })
		defer cancelRegistry.Delete(int32(token))
		defer progressStore.Delete(int32(token))

		result, err := runFetchAndValid(ctx, int32(token), workDir, rawURL, force != 0, httpProxy, userAgent)
		if err != nil {
			return "error: " + err.Error()
		}
		payload, _ := json.Marshal(result)
		return string(payload)
	})
}

func runFetchAndValid(
	ctx context.Context,
	token int32,
	workDir, rawURL string,
	force bool,
	httpProxy string,
	userAgent string,
) (*FetchResult, error) {
	if err := os.MkdirAll(workDir, 0700); err != nil {
		return nil, fmt.Errorf("create workDir: %w", err)
	}
	// 空 UA 退回 mishkaCoreInit 设置的全局默认 —— 用户没显式覆写时仍走 ClashMetaForAndroid
	effectiveUA := strings.TrimSpace(userAgent)
	if effectiveUA == "" {
		effectiveUA = currentUserAgent()
	}

	configPath := P.Join(workDir, "config.yaml")
	result := &FetchResult{}

	if force {
		_ = os.Remove(configPath)
	}
	if _, err := os.Stat(configPath); os.IsNotExist(err) {
		u, err := url.Parse(rawURL)
		if err != nil {
			return nil, fmt.Errorf("parse url: %w", err)
		}
		setProgress(token, FetchProgress{
			Action:      "FetchConfiguration",
			Args:        []string{u.Host},
			Progress:    -1,
			MaxProgress: -1,
		})
		if err := fetchURL(ctx, u, configPath, result, effectiveUA, httpProxy); err != nil {
			return nil, err
		}
	}

	raw, err := os.ReadFile(configPath)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	// age armor 加密的订阅保持加密落盘；UnmarshalRawConfig 内部用全局密钥（由
	// mishkaSetAgeSecretKey 在 fetch 前设置）在内存中解密以校验，运行时再由 mihomo 解密。
	rawCfg, err := config.UnmarshalRawConfig(raw)
	if err != nil {
		return nil, fmt.Errorf("unmarshal config: %w", err)
	}

	patchProvidersPath(rawCfg, workDir)

	prefetchProviders(ctx, token, rawCfg, effectiveUA, workDir, result, httpProxy)

	if ctx.Err() != nil {
		return nil, ctx.Err()
	}

	setProgress(token, FetchProgress{
		Action:      "Verifying",
		Args:        []string{},
		Progress:    0xffff,
		MaxProgress: 0xffff,
	})
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		return nil, fmt.Errorf("validate config: %w", err)
	}
	destroyProviders(cfg)

	return result, nil
}

func setProgress(token int32, p FetchProgress) {
	if bytes, err := json.Marshal(p); err == nil {
		progressStore.Store(token, string(bytes))
	}
}

// mihomo 的 clashHttp 给 Transport 不设 Proxy，其 dialer 也不读 HTTP(S)_PROXY，
// 只设环境变量等于没设——订阅下载会永远直连，需要代理的地址必然失败。
func newFetchClient(proxyURL string) (*http.Client, error) {
	transport := &http.Transport{
		DisableKeepAlives:     runtime.GOOS == "android",
		MaxIdleConns:          100,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}
	if proxyURL != "" {
		p, err := url.Parse(proxyURL)
		if err != nil {
			return nil, fmt.Errorf("parse proxy url: %w", err)
		}
		transport.Proxy = http.ProxyURL(p)
	}
	return &http.Client{Transport: transport}, nil
}

func newFetchRequest(ctx context.Context, u *url.URL, header http.Header) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, err
	}
	for k, vs := range header {
		for _, v := range vs {
			req.Header.Add(k, v)
		}
	}
	if user := u.User; user != nil {
		password, _ := user.Password()
		req.SetBasicAuth(user.Username(), password)
	}
	return req, nil
}

func fetchURL(ctx context.Context, u *url.URL, dest string, result *FetchResult, userAgent, proxyURL string) error {
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" {
		return fmt.Errorf("unsupported scheme %s", u.Scheme)
	}

	subCtx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()

	client, err := newFetchClient(proxyURL)
	if err != nil {
		return err
	}
	req, err := newFetchRequest(subCtx, u, http.Header{"User-Agent": []string{userAgent}})
	if err != nil {
		return err
	}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("http request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("http status %d", resp.StatusCode)
	}

	parseUserinfo(resp.Header.Get("subscription-userinfo"), result)
	result.FileName = dispositionFileName(resp.Header.Get("Content-Disposition"))

	if err := os.MkdirAll(P.Dir(dest), 0700); err != nil {
		return err
	}
	n, err := writeFileAtomic(dest, resp.Body)
	if err != nil {
		return err
	}
	if n == 0 {
		_ = os.Remove(dest)
		return errors.New("empty response body")
	}
	return nil
}

// Content-Disposition 的 filename 常携带订阅名（subconverter / 机场面板惯例）。
// mime.ParseMediaType 原生解码 RFC 5987 的 filename*=UTF-8''<name> 扩展参数。
func dispositionFileName(header string) string {
	if header == "" {
		return ""
	}
	_, params, err := mime.ParseMediaType(header)
	if err != nil {
		return ""
	}
	name := strings.TrimSpace(params["filename"])
	if name == "" {
		return ""
	}
	// 非标准但常见：普通 filename 也被百分号编码；PathUnescape 不动 '+'（QueryUnescape 会转成空格）
	if strings.Contains(name, "%") {
		if decoded, err := url.PathUnescape(name); err == nil && strings.TrimSpace(decoded) != "" {
			name = strings.TrimSpace(decoded)
		}
	}
	// 防路径注入
	name = path.Base(strings.ReplaceAll(name, "\\", "/"))
	if name == "." || name == ".." || name == "/" {
		return ""
	}
	lower := strings.ToLower(name)
	for _, ext := range []string{".yaml", ".yml"} {
		if strings.HasSuffix(lower, ext) {
			name = name[:len(name)-len(ext)]
			break
		}
	}
	return strings.TrimSpace(name)
}

// subscription-userinfo 头格式：upload=xxx;download=yyy;total=zzz;expire=aaa
func parseUserinfo(header string, result *FetchResult) {
	if header == "" {
		return
	}
	for _, segment := range strings.Split(header, ";") {
		kv := strings.SplitN(strings.TrimSpace(segment), "=", 2)
		if len(kv) != 2 {
			continue
		}
		v, err := strconv.ParseInt(kv[1], 10, 64)
		if err != nil {
			continue
		}
		switch strings.ToLower(kv[0]) {
		case "upload":
			result.Upload = v
		case "download":
			result.Download = v
		case "total":
			result.Total = v
		case "expire":
			result.Expire = v
		}
	}
}

// 把每个 provider 的 path 改写成与运行时一致的绝对路径（workDir/proxies/<hash> 或 workDir/rules/<hash>，
// 或用户显式指定的相对路径 workDir/<path>）。Parse 时 mihomo 用 constant.Path.Resolve(provider.path) 解析，
// 绝对路径能保证 JNI in-process 校验与运行时 subprocess 读取完全一致。
func patchProvidersPath(cfg *config.RawConfig, workDir string) {
	forEachProviders(cfg, func(_, _ int, _ string, provider map[string]any, kind string) {
		urlStr, _ := provider["url"].(string)
		pathStr, _ := provider["path"].(string)
		if urlStr == "" && pathStr == "" {
			return
		}
		var fullPath string
		if pathStr != "" {
			if filepath.IsAbs(pathStr) {
				fullPath = pathStr
			} else {
				fullPath = filepath.Join(workDir, pathStr)
			}
		} else {
			fileName := utils.MakeHash([]byte(urlStr)).String()
			fullPath = filepath.Join(workDir, kind, fileName)
		}
		provider["path"] = fullPath
	})
}

func parseProviderHeader(raw any) http.Header {
	if raw == nil {
		return nil
	}
	header := make(http.Header)
	switch m := raw.(type) {
	case map[string]any:
		for k, v := range m {
			addHeaderValues(header, k, v)
		}
	case map[any]any:
		for k, v := range m {
			addHeaderValues(header, fmt.Sprint(k), v)
		}
	case map[string][]string:
		for k, v := range m {
			for _, val := range v {
				header.Add(k, val)
			}
		}
	case map[string]string:
		for k, v := range m {
			header.Add(k, v)
		}
	}
	if len(header) == 0 {
		return nil
	}
	return header
}

func addHeaderValues(header http.Header, key string, val any) {
	if val == nil {
		return
	}
	switch v := val.(type) {
	case string:
		if strings.TrimSpace(v) != "" {
			header.Add(key, v)
		}
	case []any:
		for _, item := range v {
			if s, ok := item.(string); ok && strings.TrimSpace(s) != "" {
				header.Add(key, s)
			}
		}
	case []string:
		for _, s := range v {
			if strings.TrimSpace(s) != "" {
				header.Add(key, s)
			}
		}
	default:
		s := fmt.Sprint(v)
		if strings.TrimSpace(s) != "" {
			header.Add(key, s)
		}
	}
}

func parseProviderSizeLimit(val any) int64 {
	switch v := val.(type) {
	case int:
		return int64(v)
	case int64:
		return v
	case uint:
		return int64(v)
	case uint64:
		return int64(v)
	case float64:
		return int64(v)
	case string:
		n, _ := strconv.ParseInt(v, 10, 64)
		return n
	default:
		return 0
	}
}

func parseProviderInfo(provider map[string]any) (http.Header, int64) {
	header := parseProviderHeader(provider["header"])
	sizeLimit := parseProviderSizeLimit(provider["size-limit"])
	return header, sizeLimit
}

// best-effort：单个下载失败不阻塞 Parse；缺 provider 时 Parse 自己会报错。
func prefetchProviders(ctx context.Context, token int32, cfg *config.RawConfig, userAgent string, workDir string, result *FetchResult, proxyURL string) {
	type item struct {
		key       string
		url       string
		dest      string
		header    http.Header
		sizeLimit int64
		kind      string
	}
	var items []item
	forEachProviders(cfg, func(_, _ int, key string, provider map[string]any, kind string) {
		urlStr, _ := provider["url"].(string)
		dest, _ := provider["path"].(string)
		if urlStr == "" || dest == "" {
			return
		}
		if _, err := os.Stat(dest); err == nil {
			return
		}
		header, sizeLimit := parseProviderInfo(provider)
		items = append(items, item{
			key:       key,
			url:       urlStr,
			dest:      dest,
			header:    header,
			sizeLimit: sizeLimit,
			kind:      kind,
		})
	})

	total := len(items)
	if total == 0 {
		return
	}
	setProgress(token, FetchProgress{
		Action:      "FetchProviders",
		Args:        []string{items[0].key},
		Progress:    0,
		MaxProgress: total,
	})

	var mu sync.Mutex
	rawUserinfos := make(map[string]string)
	var providerInfos []*FetchResult

	// 限流并发：模板订阅十几个 provider 常见，顺序拉取最坏是 N × fetchTimeout。
	// 每个 provider 仍各自计时，进度按完成数推进
	var done atomic.Int32
	var wg sync.WaitGroup
	sem := make(chan struct{}, prefetchConcurrency)
	for _, it := range items {
		if ctx.Err() != nil {
			break
		}
		wg.Add(1)
		go func(it item) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			if ctx.Err() != nil {
				return
			}
			if u, err := url.Parse(it.url); err == nil {
				rawUserinfo, _ := fetchProvider(ctx, u, it.dest, userAgent, it.header, it.sizeLimit, proxyURL)
				if rawUserinfo != "" && it.kind == "proxies" {
					info := &FetchResult{}
					parseUserinfo(rawUserinfo, info)
					mu.Lock()
					rawUserinfos[it.key] = rawUserinfo
					if info.Total > 0 {
						providerInfos = append(providerInfos, info)
					}
					mu.Unlock()
				}
			}
			setProgress(token, FetchProgress{
				Action:      "FetchProviders",
				Args:        []string{it.key},
				Progress:    int(done.Add(1)),
				MaxProgress: total,
			})
		}(it)
	}
	wg.Wait()

	if len(rawUserinfos) > 0 {
		saveSubscriptionInfoToCache(filepath.Join(workDir, "cache.db"), rawUserinfos)
	}

	// 顶层订阅 URL 无 subscription-userinfo 头（模板订阅常见）时，
	// 聚合所有 proxy-provider 的流量数据作为订阅默认流量写入 DB。
	if result != nil && result.Total == 0 && len(providerInfos) > 0 {
		var upload, download, total, expire int64
		for _, pi := range providerInfos {
			upload += pi.Upload
			download += pi.Download
			total += pi.Total
			if pi.Expire > 0 {
				if expire == 0 || pi.Expire < expire {
					expire = pi.Expire
				}
			}
		}
		result.Upload = upload
		result.Download = download
		result.Total = total
		result.Expire = expire
	}
}

func saveSubscriptionInfoToCache(cachePath string, rawUserinfos map[string]string) {
	if len(rawUserinfos) == 0 {
		return
	}
	db, err := bbolt.Open(cachePath, 0600, &bbolt.Options{Timeout: time.Second})
	if err != nil {
		return
	}
	defer db.Close()
	_ = db.Batch(func(t *bbolt.Tx) error {
		bucket, err := t.CreateBucketIfNotExists([]byte("subscriptioninfo"))
		if err != nil {
			return err
		}
		for name, userInfo := range rawUserinfos {
			if err := bucket.Put([]byte(name), []byte(userInfo)); err != nil {
				return err
			}
		}
		return nil
	})
}

// 写临时文件再 rename（同目录 rename 在 ext4/f2fs 上原子）。直写目标路径时进程被杀 /
// 断电 / OOM kill 都会留下截断的非空 YAML，而 prefetch 只按 os.Stat 判存在就跳过，
// 坏文件从此永远不会重下——只能删订阅重导。
//
// 不能用 defer f.Close()：rename 前必须显式 Close 并检查错误，否则写缓冲的错误被吞掉。
func writeFileAtomic(dest string, src io.Reader) (int64, error) {
	tmp, err := os.CreateTemp(path.Dir(dest), path.Base(dest)+".tmp")
	if err != nil {
		return 0, err
	}
	tmpName := tmp.Name()
	fail := func(err error) (int64, error) {
		tmp.Close()
		os.Remove(tmpName)
		return 0, err
	}
	n, err := io.Copy(tmp, src)
	if err != nil {
		return fail(err)
	}
	if err := tmp.Sync(); err != nil {
		return fail(err)
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return 0, err
	}
	if err := os.Chmod(tmpName, 0600); err != nil {
		os.Remove(tmpName)
		return 0, err
	}
	if err := os.Rename(tmpName, dest); err != nil {
		os.Remove(tmpName)
		return 0, err
	}
	return n, nil
}

func fetchProvider(ctx context.Context, u *url.URL, dest string, userAgent string, customHeader http.Header, sizeLimit int64, proxyURL string) (string, error) {
	subCtx, cancel := context.WithTimeout(ctx, fetchTimeout)
	defer cancel()
	header := http.Header{}
	for k, v := range customHeader {
		header[k] = v
	}
	if header.Get("User-Agent") == "" {
		header.Set("User-Agent", userAgent)
	}
	client, err := newFetchClient(proxyURL)
	if err != nil {
		return "", err
	}
	req, err := newFetchRequest(subCtx, u, header)
	if err != nil {
		return "", err
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("status %d", resp.StatusCode)
	}
	rawUserinfo := resp.Header.Get("subscription-userinfo")
	if err := os.MkdirAll(path.Dir(dest), 0700); err != nil {
		return rawUserinfo, err
	}
	var reader io.Reader = resp.Body
	if sizeLimit > 0 {
		reader = io.LimitReader(reader, sizeLimit)
	}
	_, err = writeFileAtomic(dest, reader)
	return rawUserinfo, err
}

func forEachProviders(cfg *config.RawConfig, fn func(index, total int, key string, provider map[string]any, kind string)) {
	total := len(cfg.ProxyProvider) + len(cfg.RuleProvider)
	idx := 0
	for k, v := range cfg.ProxyProvider {
		fn(idx, total, k, v, "proxies")
		idx++
	}
	for k, v := range cfg.RuleProvider {
		fn(idx, total, k, v, "rules")
		idx++
	}
}

func destroyProviders(cfg *config.Config) {
	for _, p := range cfg.Providers {
		if c, ok := any(p).(io.Closer); ok {
			_ = c.Close()
		}
	}
	for _, p := range cfg.RuleProviders {
		if c, ok := any(p).(io.Closer); ok {
			_ = c.Close()
		}
	}
}

func currentUserAgent() string {
	v := mishkaUserAgent.Load()
	if s, ok := v.(string); ok && s != "" {
		return s
	}
	return "Mishka/dev"
}
