package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/metacubex/mihomo/component/resolver"
	mihomoDNS "github.com/metacubex/mihomo/dns"
	D "github.com/miekg/dns"
)

func TestRootDNSBindFailureReleasesUDP(t *testing.T) {
	occupied, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	address := occupied.Addr().String()
	if sockets, err := bindRootDNSFamily(&net.ListenConfig{}, "4", address); err == nil {
		sockets.udp.Close()
		sockets.tcp.Close()
		t.Fatal("ROOT DNS started despite an occupied TCP port")
	}
	// TCP 失败后 UDP 必须立即释放，否则下一次启动仍会撞端口。
	udp, err := net.ListenPacket("udp4", address)
	if err != nil {
		t.Fatalf("failed ROOT DNS startup retained its UDP port: %v", err)
	}
	udp.Close()
}

func TestRootDNSDisabledResolverIsNotReady(t *testing.T) {
	previous := resolver.DefaultService
	resolver.DefaultService = nil
	defer func() { resolver.DefaultService = previous }()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	server := &rootDNSServer{ctx: ctx, cancel: cancel, listening: true}
	if server.Ready() {
		t.Fatal("ROOT DNS reported ready without a DNS service")
	}
	if started, err := startRootDNS(15353); err == nil {
		started.Close()
		t.Fatal("ROOT DNS started without a DNS service")
	}
}

type rootDNSTestListener struct {
	conn      net.Conn
	accepting chan struct{}
	release   chan struct{}
}

func (l *rootDNSTestListener) Accept() (net.Conn, error) {
	close(l.accepting)
	<-l.release
	return l.conn, nil
}

func (l *rootDNSTestListener) Close() error { return l.conn.Close() }

func (l *rootDNSTestListener) Addr() net.Addr { return l.conn.LocalAddr() }

func TestRootDNSCloseInterruptsBlockedTCPWrite(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	server := &rootDNSServer{ctx: ctx, cancel: cancel}
	raw, client := net.Pipe()
	defer raw.Close()
	defer client.Close()
	listener := &rootDNSTestListener{
		conn: raw, accepting: make(chan struct{}), release: make(chan struct{}),
	}
	close(listener.release)
	conn, err := (rootDNSListener{Listener: listener, owner: server}).Accept()
	if err != nil {
		t.Fatal(err)
	}
	writeDone := make(chan struct{})
	writeResult := make(chan error, 1)
	server.done = []chan struct{}{writeDone}
	go func() {
		defer close(writeDone)
		_, err := conn.Write([]byte{1, 2})
		writeResult <- err
	}()
	if err := client.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	// Pipe 无缓冲；读走首字节证明真实 Write 已进入，剩余字节没有读者，不能完成。
	var first [1]byte
	if _, err := io.ReadFull(client, first[:]); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-writeResult:
		t.Fatalf("TCP write completed before shutdown with no reader: %v", err)
	default:
	}
	closed := make(chan struct{})
	go func() {
		server.Close()
		close(closed)
	}()
	select {
	case <-closed:
	case <-time.After(5 * time.Second):
		t.Fatal("ROOT DNS Close waited for a TCP writer instead of closing its connection")
	}
	if err := <-writeResult; !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("blocked TCP write was not interrupted by connection close: %v", err)
	}
	if !errors.Is(ctx.Err(), context.Canceled) {
		t.Fatalf("ROOT DNS shutdown did not cancel resolver context: %v", ctx.Err())
	}
}

func TestRootDNSTCPConnCloseReleasesOwnership(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	server := &rootDNSServer{ctx: ctx, cancel: cancel}
	raw, client := net.Pipe()
	defer raw.Close()
	defer client.Close()
	listener := &rootDNSTestListener{
		conn: raw, accepting: make(chan struct{}), release: make(chan struct{}),
	}
	close(listener.release)
	conn, err := (rootDNSListener{Listener: listener, owner: server}).Accept()
	if err != nil {
		t.Fatal(err)
	}
	server.mu.Lock()
	before := len(server.connections)
	server.mu.Unlock()
	if before != 1 {
		t.Fatalf("accepted TCP connection was not owned: %d connections", before)
	}
	if err := conn.Close(); err != nil {
		t.Fatal(err)
	}
	server.mu.Lock()
	after := len(server.connections)
	server.mu.Unlock()
	if after != 0 {
		t.Fatalf("closed TCP connection retained by ROOT DNS: %d connections", after)
	}
	if ctx.Err() != nil {
		t.Fatalf("closing one TCP connection canceled the resolver: %v", ctx.Err())
	}
	server.Close()
}

func TestRootDNSAcceptRacingCloseRejectsConnection(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	server := &rootDNSServer{ctx: ctx, cancel: cancel}
	raw, client := net.Pipe()
	defer raw.Close()
	defer client.Close()
	listener := &rootDNSTestListener{
		conn: raw, accepting: make(chan struct{}), release: make(chan struct{}),
	}
	accepted := make(chan struct {
		conn net.Conn
		err  error
	}, 1)
	go func() {
		conn, err := (rootDNSListener{Listener: listener, owner: server}).Accept()
		accepted <- struct {
			conn net.Conn
			err  error
		}{conn: conn, err: err}
	}()
	// 底层已取到连接但尚未返回，强制覆盖 owner 停机与 Accept 返回之间的窗口。
	<-listener.accepting
	if err := client.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	server.Close()
	close(listener.release)
	select {
	case result := <-accepted:
		if result.conn != nil || !errors.Is(result.err, net.ErrClosed) {
			t.Fatalf("shutdown accepted a raced TCP connection: conn=%v, err=%v", result.conn, result.err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("raced TCP accept did not finish after shutdown")
	}
	var reply [1]byte
	if _, err := client.Read(reply[:]); !errors.Is(err, io.EOF) {
		t.Fatalf("rejected TCP connection remained open: %v", err)
	}
}

func TestRootDNSMappingRetainsCachedFakeIPLookup(t *testing.T) {
	cfg := parseRuntimeConfig(t, []byte(`
dns:
  enable: true
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16
  nameserver: [1.1.1.1]
rules: []
`), "")
	cached := cfg.DNS.FakeIPPool.Lookup("www.google.com")
	rootDNSOption(cfg)
	mapper := mihomoDNS.NewEnhancer(mihomoDNS.EnhancerConfig{
		EnhancedMode: cfg.DNS.EnhancedMode,
		FakeIPPool:   cfg.DNS.FakeIPPool,
	})
	if mapper.FakeIPEnabled() {
		t.Fatal("ROOT DNS would issue new fake addresses to bypass applications")
	}
	host, ok := mapper.FindHostByIP(cached)
	if !ok || host != "www.google.com" {
		t.Fatalf("cached VPN address lost its destination after ROOT cutover: host=%q, found=%v", host, ok)
	}
}

func TestRootDNSAAAARequiresCapturedIPv6(t *testing.T) {
	packet, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	started, done := make(chan struct{}), make(chan struct{})
	upstream := &D.Server{
		PacketConn:        packet,
		NotifyStartedFunc: func() { close(started) },
		Handler: D.HandlerFunc(func(w D.ResponseWriter, req *D.Msg) {
			reply := new(D.Msg)
			reply.SetReply(req)
			reply.Answer = []D.RR{&D.AAAA{
				Hdr:  D.RR_Header{Name: req.Question[0].Name, Rrtype: D.TypeAAAA, Class: D.ClassINET, Ttl: 30},
				AAAA: net.ParseIP("2001:db8::1"),
			}}
			_ = w.WriteMsg(reply)
		}),
	}
	go func() { defer close(done); _ = upstream.ActivateAndServe() }()
	<-started
	t.Cleanup(func() { _ = upstream.Shutdown(); <-done })
	for _, tt := range []struct {
		name                    string
		tun, captured, wantAAAA bool
	}{
		{"TUN without IPv6 routes", true, false, false},
		{"TUN with IPv6 routes", true, true, true},
		{"TPROXY has no TUN", false, false, true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			cfg := parseRuntimeConfig(t, []byte("ipv6: true\ndns:\n  enable: true\n  ipv6: true\n  nameserver: ["+packet.LocalAddr().String()+"]\nrules: []\n"), "")
			cfg.General.Tun.Enable = tt.tun
			cfg.General.Tun.Inet6Address = nil
			if tt.captured {
				cfg.General.Tun.Inet6Address = []netip.Prefix{netip.MustParsePrefix("fdfe:dcba:9876::1/126")}
			}
			rootDNSOption(cfg)
			service := mihomoDNS.NewService(
				mihomoDNS.NewResolver(mihomoDNS.Config{Main: cfg.DNS.NameServer, IPv6: cfg.DNS.IPv6}),
				mihomoDNS.NewEnhancer(mihomoDNS.EnhancerConfig{IPv6: cfg.DNS.IPv6, EnhancedMode: cfg.DNS.EnhancedMode}),
			)
			query := new(D.Msg)
			query.SetQuestion("captured.example.", D.TypeAAAA)
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			reply, err := service.ServeMsg(ctx, query)
			if err != nil {
				t.Fatal(err)
			}
			if tt.wantAAAA {
				if len(reply.Answer) != 1 || reply.Answer[0].(*D.AAAA).AAAA.String() != "2001:db8::1" {
					t.Fatalf("captured IPv6 answer lost: %v", reply.Answer)
				}
			} else if len(reply.Answer) != 0 {
				t.Fatalf("uncaptured IPv6 address exposed to clients: %v", reply.Answer)
			}
		})
	}
}

func TestRootDNSBindsRequestedLoopbackPort(t *testing.T) {
	probe, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := probe.Addr().(*net.TCPAddr).Port
	probe.Close()
	sockets, err := listenRootDNS(port)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		for _, socket := range sockets {
			socket.udp.Close()
			socket.tcp.Close()
		}
	}()
	want := fmt.Sprintf("127.0.0.1:%d", port)
	if got := sockets[0].udp.LocalAddr().String(); got != want {
		t.Fatalf("ROOT DNS UDP address = %q, want %q", got, want)
	}
}
