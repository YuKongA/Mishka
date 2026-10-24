//go:build android && cmfa

package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/http"
	"github.com/metacubex/http/httptest"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/dns"
	D "github.com/miekg/dns"
)

// 正 TTL 让缺失缓存失效的错误可观测；查询走真实 loopback UDP，而非替换 resolver。
func startLocalSystemDNSServer(t *testing.T, answer string) string {
	t.Helper()
	conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := conn.LocalAddr().String()
	done := make(chan error, 1)
	go func() {
		buffer := make([]byte, 4096)
		for {
			n, peer, err := conn.ReadFrom(buffer)
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					err = nil
				}
				done <- err
				return
			}
			var request D.Msg
			if err := request.Unpack(buffer[:n]); err != nil {
				done <- err
				return
			}
			response := new(D.Msg)
			response.SetReply(&request)
			response.RecursionAvailable = true
			for _, question := range request.Question {
				if question.Qtype == D.TypeA {
					response.Answer = append(response.Answer, &D.A{
						Hdr: D.RR_Header{Name: question.Name, Rrtype: D.TypeA, Class: D.ClassINET, Ttl: 600},
						A:   net.ParseIP(answer),
					})
				}
			}
			packet, err := response.Pack()
			if err == nil {
				_, err = conn.WriteTo(packet, peer)
			}
			if err != nil {
				done <- err
				return
			}
		}
	}()
	t.Cleanup(func() {
		_ = conn.Close()
		if err := <-done; err != nil {
			t.Errorf("local DNS server: %v", err)
		}
	})
	return address
}

func TestAndroidSystemDNSLifecycle(t *testing.T) {
	first := startLocalSystemDNSServer(t, "192.0.2.10")
	second := startLocalSystemDNSServer(t, "192.0.2.20")

	previousDefault := resolver.DefaultResolver
	previousDirect := resolver.DirectHostResolver
	previousProxy := resolver.ProxyServerHostResolver
	previousSystem := resolver.SystemResolver
	systemDNSState.Lock()
	previousServers := slices.Clone(systemDNSState.servers)
	previousInitialized := systemDNSState.initialized
	systemDNSState.Unlock()
	t.Cleanup(func() {
		systemDNSState.Lock()
		dns.UpdateSystemDNS(previousServers)
		dns.FlushCacheWithDefaultResolver()
		systemDNSState.servers = previousServers
		systemDNSState.initialized = previousInitialized
		systemDNSState.Unlock()
		resolver.DefaultResolver = previousDefault
		resolver.DirectHostResolver = previousDirect
		resolver.ProxyServerHostResolver = previousProxy
		resolver.SystemResolver = previousSystem
	})

	rs := dns.NewResolver(dns.Config{
		Main:         []dns.NameServer{{Net: "system"}},
		DirectServer: []dns.NameServer{{Net: "system"}},
		ProxyServer:  []dns.NameServer{{Net: "system"}},
	})
	resolver.DefaultResolver = rs
	resolver.DirectHostResolver = rs.DirectResolver
	resolver.ProxyServerHostResolver = rs.ProxyResolver
	// 独立全局实例有公共 DNS 兜底，测试必须隔离掉它。
	resolver.SystemResolver = dns.NewResolver(dns.Config{Main: []dns.NameServer{{Net: "system"}}}).Resolver
	consumers := []struct {
		name string
		r    resolver.Resolver
	}{
		{"normal", resolver.DefaultResolver},
		{"direct", resolver.DirectHostResolver},
		{"proxy", resolver.ProxyServerHostResolver},
		{"system", resolver.SystemResolver},
	}
	lookup := func(r resolver.Resolver, host string) ([]netip.Addr, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		return r.LookupIPv4(ctx, host)
	}
	assertAnswer := func(host, want string) {
		t.Helper()
		for _, consumer := range consumers {
			got, err := lookup(consumer.r, host)
			if err != nil || len(got) != 1 || got[0].String() != want {
				t.Fatalf("%s lookup(%q) = %v, %v; want %s", consumer.name, host, got, err, want)
			}
		}
	}
	assertEmpty := func(host string) {
		t.Helper()
		for _, consumer := range consumers {
			if got, err := lookup(consumer.r, host); err == nil || len(got) != 0 {
				t.Fatalf("%s lookup after clear = %v, %v; want failure", consumer.name, got, err)
			}
		}
	}
	put := func(body string, want int) {
		t.Helper()
		request := httptest.NewRequest(http.MethodPut, "/dns/system", strings.NewReader(body))
		response := httptest.NewRecorder()
		putSystemDNS(response, request)
		if response.Code != want {
			t.Fatalf("PUT /dns/system %s: status %d, body %s; want %d", body, response.Code, response.Body.String(), want)
		}
	}
	body := func(server string) string { return fmt.Sprintf("[%q]", server) }

	if err := updateSystemDNSCommaList(""); err != nil {
		t.Fatal(err)
	}
	assertEmpty("empty.mishka.invalid")
	if err := updateSystemDNSCommaList(first); err != nil {
		t.Fatal(err)
	}
	const cached = "cached.mishka.invalid"
	assertAnswer(cached, "192.0.2.10")

	for i, malformed := range []string{
		`null`, `[] []`, `["dns.example:53"]`, `["127.0.0.1:0"]`,
		fmt.Sprintf(`["%s", "not-an-address"]`, first),
	} {
		put(malformed, http.StatusBadRequest)
		assertAnswer(fmt.Sprintf("malformed-%d.mishka.invalid", i), "192.0.2.10")
	}
	if err := updateSystemDNSCommaList(first + ",bad"); err == nil {
		t.Fatal("malformed comma list was accepted")
	}
	assertAnswer("malformed-comma.mishka.invalid", "192.0.2.10")

	put(body(second), http.StatusNoContent)
	// 四类消费者复用相同域名，独立直连缓存也必须立刻读到新网络的答案。
	assertAnswer(cached, "192.0.2.20")
	put(`[]`, http.StatusNoContent)
	assertEmpty(cached)
}
