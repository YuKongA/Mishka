package main

import (
	"context"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/chi"
	"github.com/metacubex/http"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	mihomoDNS "github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/hub/route"
	D "github.com/miekg/dns"
)

var currentRootDNS atomic.Pointer[rootDNSServer]

func init() {
	// 路由始终存在，VPN 与订阅导入进程不能被误认成 ROOT DNS 已就绪。
	route.Register(func(r chi.Router) {
		r.Get("/dns/root", func(w http.ResponseWriter, r *http.Request) {
			server := currentRootDNS.Load()
			if server == nil || !server.Ready() {
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			}
			w.WriteHeader(http.StatusNoContent)
		})
	})
}

func rootDNSOption(cfg *config.Config) {
	// 普通 DNS listener 不得争抢本进程拥有的透明端口，reload 时也必须覆盖。
	cfg.DNS.Listen = ""
	cfg.DNS.EnhancedMode = C.DNSMapping
	// IPv6 地址可能被核心的可用性检查移除，不能仅相信用户开关仍返回 AAAA。
	if cfg.General.Tun.Enable && len(cfg.General.Tun.Inet6Address) == 0 {
		cfg.DNS.IPv6 = false
	}
}

func validateRootDNSResolver() error {
	// Invalid 返回 nameserver 列表非空，与方法名相反，不能据名称反转判断。
	if resolver.DefaultService == nil || resolver.DefaultResolver == nil || !resolver.DefaultResolver.Invalid() {
		return fmt.Errorf("ROOT DNS requires an enabled DNS resolver with nameservers")
	}
	return nil
}

type rootDNSListenConfig interface {
	Listen(context.Context, string, string) (net.Listener, error)
	ListenPacket(context.Context, string, string) (net.PacketConn, error)
}

type rootDNSSockets struct {
	udp net.PacketConn
	tcp net.Listener
}

func bindRootDNSFamily(lc rootDNSListenConfig, family, address string) (rootDNSSockets, error) {
	udp, err := lc.ListenPacket(context.Background(), "udp"+family, address)
	if err != nil {
		return rootDNSSockets{}, fmt.Errorf("bind ROOT DNS UDP%s: %w", family, err)
	}
	tcp, err := lc.Listen(context.Background(), "tcp"+family, address)
	if err != nil {
		udp.Close()
		return rootDNSSockets{}, fmt.Errorf("bind ROOT DNS TCP%s: %w", family, err)
	}
	return rootDNSSockets{udp: udp, tcp: tcp}, nil
}

type rootDNSServer struct {
	ctx         context.Context
	cancel      context.CancelFunc
	resolverMu  sync.RWMutex
	mu          sync.Mutex
	closing     bool
	failed      bool
	listening   bool
	connections map[*rootDNSConn]struct{}
	servers     []*D.Server
	done        []chan struct{}
	errors      chan error
	closeOnce   sync.Once
}

// 每次查询都读取当前 resolver，不能把启动时的 Service 固定在 listener 内。
type rootDNSService struct {
	owner *rootDNSServer
}

func (s rootDNSService) ServeMsg(_ context.Context, msg *D.Msg) (*D.Msg, error) {
	s.owner.resolverMu.RLock()
	defer s.owner.resolverMu.RUnlock()
	if err := validateRootDNSResolver(); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(s.owner.ctx, resolver.DefaultDNSTimeout)
	defer cancel()
	return resolver.ServeMsg(ctx, msg)
}

func (s *rootDNSServer) fail(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closing || s.failed {
		return
	}
	s.failed = true
	if err == nil {
		err = fmt.Errorf("listener stopped unexpectedly")
	}
	s.errors <- fmt.Errorf("serve ROOT DNS: %w", err)
	s.cancel()
}

func (s *rootDNSServer) readFailure(err error) {
	if err == nil {
		return
	}
	if temporary, ok := err.(net.Error); ok && temporary.Temporary() {
		return
	}
	// miekg 在返回错误前会等待 handler；必须先撤销 ready 并取消正在进行的查询。
	s.fail(err)
}

type rootDNSListener struct {
	net.Listener
	owner *rootDNSServer
}

func (l rootDNSListener) Accept() (net.Conn, error) {
	conn, err := l.Listener.Accept()
	if err != nil {
		l.owner.readFailure(err)
		return conn, err
	}
	l.owner.mu.Lock()
	if l.owner.closing {
		l.owner.mu.Unlock()
		// Accept 可能先于 listener 关闭拿到 socket，但晚于停机标记返回。
		conn.Close()
		return nil, net.ErrClosed
	}
	tracked := &rootDNSConn{Conn: conn, owner: l.owner}
	if l.owner.connections == nil {
		l.owner.connections = make(map[*rootDNSConn]struct{})
	}
	l.owner.connections[tracked] = struct{}{}
	l.owner.mu.Unlock()
	return tracked, nil
}

type rootDNSConn struct {
	net.Conn
	owner *rootDNSServer
}

func (c *rootDNSConn) Close() error {
	err := c.Conn.Close()
	c.owner.mu.Lock()
	delete(c.owner.connections, c)
	c.owner.mu.Unlock()
	return err
}

type rootDNSReader struct {
	D.Reader
	owner *rootDNSServer
}

func (r rootDNSReader) ReadUDP(conn *net.UDPConn, timeout time.Duration) ([]byte, *D.SessionUDP, error) {
	msg, session, err := r.Reader.ReadUDP(conn, timeout)
	r.owner.readFailure(err)
	return msg, session, err
}

func startRootDNS(port int) (*rootDNSServer, error) {
	if err := validateRootDNSResolver(); err != nil {
		return nil, err
	}
	sockets, err := listenRootDNS(port)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	server := &rootDNSServer{ctx: ctx, cancel: cancel, errors: make(chan error, 1)}
	handler := &mihomoDNS.Server{}
	handler.SetService(rootDNSService{owner: server})
	for _, socket := range sockets {
		// 保留原生 *net.UDPConn，miekg 才会通过 pktinfo 把原目标地址作为回复源。
		// 本机 DNS 由 RootDnsApplier DNAT 到该 loopback 端口，避免和 tethering dnsmasq 抢 53。
		server.servers = append(server.servers,
			&D.Server{
				PacketConn: socket.udp, Handler: handler.UDPHandler(), UDPSize: D.MaxMsgSize,
				DecorateReader: func(reader D.Reader) D.Reader { return rootDNSReader{Reader: reader, owner: server} },
			},
			&D.Server{Listener: rootDNSListener{Listener: socket.tcp, owner: server}, Handler: handler.TCPHandler()},
		)
	}
	started := make([]chan struct{}, len(server.servers))
	for i, dnsServer := range server.servers {
		started[i] = make(chan struct{})
		done := make(chan struct{})
		server.done = append(server.done, done)
		ready := started[i]
		dnsServer.NotifyStartedFunc = func() { close(ready) }
		go func() {
			defer close(done)
			server.fail(dnsServer.ActivateAndServe())
		}()
	}
	// 所有启动 goroutine 都完成初始化或退出后才能关停，避免 Shutdown 先于 Activate。
	for i, ready := range started {
		select {
		case <-ready:
		case <-server.done[i]:
		}
	}
	server.mu.Lock()
	failed := server.failed
	server.listening = !failed
	server.mu.Unlock()
	if failed {
		server.Close()
		return nil, <-server.errors
	}
	currentRootDNS.Store(server)
	return server, nil
}

func (s *rootDNSServer) Ready() bool {
	s.mu.Lock()
	listening := s.listening && !s.closing && !s.failed
	s.mu.Unlock()
	if !listening || !s.resolverMu.TryRLock() {
		return false
	}
	defer s.resolverMu.RUnlock()
	return validateRootDNSResolver() == nil
}

func (s *rootDNSServer) Reload(apply func() error) error {
	// 排空当前查询后再替换 mihomo 全局 resolver，保留已绑定的透明 socket。
	s.resolverMu.Lock()
	defer s.resolverMu.Unlock()
	if err := apply(); err != nil {
		return err
	}
	return validateRootDNSResolver()
}

func (s *rootDNSServer) Close() {
	s.closeOnce.Do(func() {
		s.mu.Lock()
		s.closing = true
		// 移交连接集合后，连接自身的 Close 不再修改该集合，锁外遍历不会竞争。
		connections := s.connections
		s.connections = nil
		s.mu.Unlock()
		currentRootDNS.CompareAndSwap(s, nil)
		s.cancel()
		// Shutdown 只设读 deadline；先关闭 TCP，才能打断客户端不读取响应造成的写阻塞。
		for conn := range connections {
			conn.Close()
		}
		// 同时唤醒 UDP 读取，再等待所有请求和服务循环退出。
		var shutdown sync.WaitGroup
		for _, server := range s.servers {
			shutdown.Add(1)
			go func() {
				defer shutdown.Done()
				_ = server.Shutdown()
				// 初始化失败时 Shutdown 尚未接管底层 socket。
				if server.PacketConn != nil {
					server.PacketConn.Close()
				}
				if server.Listener != nil {
					server.Listener.Close()
				}
			}()
		}
		shutdown.Wait()
		for _, done := range s.done {
			<-done
		}
	})
}
