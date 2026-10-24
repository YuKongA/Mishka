package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"unsafe"

	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/component/updater"
	"github.com/metacubex/mihomo/config"
	Const "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/listener"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"go.yaml.in/yaml/v3"
)

// dlopen + dlsym 进入点：解析 -d / -f / --override-json / --secret / --ext-ctl，启动 hub 后阻塞等信号。
// argv[0] 透传 binary 路径作占位；返回值即进程退出码。
//
//export mihomoEntry
func mihomoEntry(argc C.int, argv **C.char) C.int {
	args := make([]string, int(argc))
	if argc > 0 && argv != nil {
		arr := unsafe.Slice(argv, int(argc))
		for i, p := range arr {
			args[i] = C.GoString(p)
		}
	}
	os.Args = args
	return C.int(runMihomo())
}

func runMihomo() int {
	// 独立 FlagSet 避开 mishka_core 其他文件可能注册到 flag.CommandLine 的 flag。
	fs := flag.NewFlagSet("mihomo", flag.ExitOnError)
	var (
		homeDir             string
		configFile          string
		secret              string
		externalController  string
		overrideJSON        string
		transformPath       string
		preferTransformPort bool
		ageSecretKey        string
		vpnGateway          string
		vpnHTTPFD           int
		vpnPlatformSocket   string
		vpnReadyFile        string
		rootDNS             bool
	)
	fs.StringVar(&homeDir, "d", "", "set configuration directory")
	fs.StringVar(&configFile, "f", "", "specify configuration file")
	fs.StringVar(&overrideJSON, "override-json", "", "path to a JSON file whose fields override the parsed RawConfig")
	fs.StringVar(&transformPath, "transform", "", "path to a JSON subscription transform applied before parsing")
	fs.BoolVar(&preferTransformPort, "prefer-transform-mixed-port", false, "let the transformed configuration's mixed-port override the runtime fallback")
	fs.StringVar(&secret, "secret", "", "override RESTful API secret")
	fs.StringVar(&externalController, "ext-ctl", "", "override external controller address")
	fs.StringVar(&ageSecretKey, "age-secret-key", "", "age secret key to decrypt age-armor encrypted configuration")
	fs.Func("system-dns", "comma-separated numeric system DNS socket addresses; empty clears", updateSystemDNSCommaList)
	fs.StringVar(&vpnGateway, "vpn-gateway", "", "VPN Builder IPv4 prefix and optional IPv6 prefix")
	fs.IntVar(&vpnHTTPFD, "vpn-http-fd", -1, "inherited loopback HTTP listener fd")
	fs.StringVar(&vpnPlatformSocket, "vpn-platform-socket", "", "Android VPN platform abstract socket")
	fs.StringVar(&vpnReadyFile, "vpn-ready-file", "", "publish VPN readiness after listeners start")
	fs.BoolVar(&rootDNS, "root-dns", false, "serve transparent ROOT DNS on UDP and TCP port 53")
	if err := fs.Parse(os.Args[1:]); err != nil {
		return 2
	}
	if rootDNS {
		invalid := false
		fs.Visit(func(f *flag.Flag) {
			switch f.Name {
			case "vpn-gateway", "vpn-http-fd", "vpn-platform-socket", "vpn-ready-file":
				invalid = true
			}
		})
		if invalid {
			fmt.Fprintln(os.Stderr, "root-dns cannot be combined with VPN flags")
			return 1
		}
	}

	vpnMode := vpnGateway != ""
	var vpnListener net.Listener
	if vpnHTTPFD >= 0 {
		var err error
		vpnListener, err = takeVPNHTTPListener(vpnHTTPFD)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		defer vpnListener.Close()
		if !vpnMode {
			fmt.Fprintln(os.Stderr, "vpn-http-fd requires vpn-gateway")
			return 1
		}
	}
	if vpnMode && vpnPlatformSocket != "" {
		platform, err := configureVPNPlatform(vpnPlatformSocket)
		if err != nil {
			fmt.Fprintln(os.Stderr, "configure VPN platform:", err)
			return 1
		}
		defer platform.Close()
	}
	if vpnMode && vpnReadyFile != "" {
		if err := os.Remove(vpnReadyFile); err != nil && !os.IsNotExist(err) {
			fmt.Fprintln(os.Stderr, "clear VPN readiness:", err)
			return 1
		}
		defer os.Remove(vpnReadyFile)
	}
	registerSystemDNSRoute()

	// 任何意外走系统 resolver 的代码路径立刻自爆，方便定位（mihomo 应当全程用自己的 DNS 栈）。
	net.DefaultResolver.PreferGo = true
	net.DefaultResolver.Dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		fmt.Fprintln(os.Stderr, "panic: net.DefaultResolver.Dial should never be called")
		os.Exit(2)
		return nil, nil
	}

	if overrideJSON != "" {
		config.OverrideJSONPath = overrideJSON
	}

	// age armor 加密的订阅配置在磁盘上保持加密，运行时用此密钥解密（hub.Parse 内部调
	// config.UnmarshalRawConfig → age.DecryptBytes 读全局密钥）；SIGHUP reload 也持续生效。
	if ageSecretKey != "" {
		age.SetGlobalSecretKeys(ageSecretKey)
	}

	if homeDir != "" {
		if !filepath.IsAbs(homeDir) {
			cwd, _ := os.Getwd()
			homeDir = filepath.Join(cwd, homeDir)
		}
		Const.SetHomeDir(homeDir)
	}

	if configFile == "" {
		configFile = filepath.Join(Const.Path.HomeDir(), Const.Path.Config())
	} else if !filepath.IsAbs(configFile) {
		cwd, _ := os.Getwd()
		configFile = filepath.Join(cwd, configFile)
	}
	Const.SetConfig(configFile)

	if err := config.Init(Const.Path.HomeDir()); err != nil {
		fmt.Fprintln(os.Stderr, "init config dir:", err)
		return 1
	}

	configBytes, err := os.ReadFile(configFile)
	if err != nil {
		fmt.Fprintln(os.Stderr, "read config:", err)
		return 1
	}

	var options []hub.Option
	var vpnTUNFD int
	if rootDNS {
		options = append(options, rootDNSOption)
	}
	if vpnMode {
		option, err := vpnGatewayOption(vpnGateway)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		options = append(options, option)
		options = append(options, func(cfg *config.Config) {
			if vpnTUNFD == 0 {
				vpnTUNFD = cfg.General.Tun.FileDescriptor
			}
			cfg.General.Tun.FileDescriptor = vpnTUNFD
		})
	}
	if externalController != "" {
		options = append(options, hub.WithExternalController(externalController))
	}
	if secret != "" {
		options = append(options, hub.WithSecret(secret))
	}

	if transformPath != "" {
		if configBytes, err = applyTransformFile(configBytes, transformPath, ageSecretKey); err != nil {
			fmt.Fprintln(os.Stderr, "apply transform:", err)
			return 1
		}
		if preferTransformPort {
			// override.run.json 的默认端口只负责兜底；脚本若显式设置端口，
			// 在 Parse 完成后、listener 创建前恢复它，且不重复执行脚本。
			port, portErr := transformedMixedPort(configBytes)
			if portErr != nil {
				fmt.Fprintln(os.Stderr, "inspect transformed mixed-port:", portErr)
				return 1
			}
			if port > 0 {
				options = append(options, func(cfg *config.Config) { cfg.General.MixedPort = port })
			}
		}
	}
	defer func() {
		if vpnTUNFD > 0 {
			_ = syscall.Close(vpnTUNFD)
		}
	}()
	defer executor.Shutdown()
	if err := hub.Parse(configBytes, options...); err != nil {
		fmt.Fprintln(os.Stderr, "Parse config:", err)
		return 1
	}
	var rootServer *rootDNSServer
	var rootErrors <-chan error
	if rootDNS {
		rootServer, err = startRootDNS()
		if err != nil {
			fmt.Fprintln(os.Stderr, "start ROOT DNS:", err)
			return 1
		}
		defer rootServer.Close()
		rootErrors = rootServer.errors
		log.Infoln("[ROOT] Transparent DNS listening on UDP and TCP port 53")
	}
	if vpnMode && !listener.TunListenerReady() {
		fmt.Fprintln(os.Stderr, "VPN TUN listener did not start")
		return 1
	}
	if vpnListener != nil {
		proxy, err := startVPNHTTPListener(vpnListener, tunnel.Tunnel)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		defer proxy.Close()
		log.Infoln("[VPN] System HTTP proxy listening at: %s", proxy.Address())
	}
	if vpnMode && vpnReadyFile != "" {
		if err := os.WriteFile(vpnReadyFile, []byte("ready"), 0600); err != nil {
			fmt.Fprintln(os.Stderr, "publish VPN readiness:", err)
			return 1
		}
	}

	if updater.GeoAutoUpdate() {
		updater.RegisterGeoUpdater()
	}

	termSig := make(chan os.Signal, 1)
	hupSig := make(chan os.Signal, 1)
	signal.Notify(termSig, syscall.SIGINT, syscall.SIGTERM)
	signal.Notify(hupSig, syscall.SIGHUP)

	defer signal.Stop(termSig)
	defer signal.Stop(hupSig)
	for {
		select {
		case <-termSig:
			return 0
		case err := <-rootErrors:
			fmt.Fprintln(os.Stderr, err)
			return 1
		case <-hupSig:
			reload := func() error { return hub.Parse(configBytes, options...) }
			var err error
			if rootServer != nil {
				err = rootServer.Reload(reload)
			} else {
				err = reload()
			}
			if err != nil {
				log.Errorln("Reload config: %s", err.Error())
				if vpnMode || rootDNS {
					return 1
				}
			}
			if vpnMode && !listener.TunListenerReady() {
				fmt.Fprintln(os.Stderr, "VPN TUN listener did not restart")
				return 1
			}
		}
	}
}

func transformedMixedPort(configBytes []byte) (int, error) {
	var config struct {
		MixedPort int `yaml:"mixed-port"`
	}
	if err := yaml.Unmarshal(configBytes, &config); err != nil {
		return 0, err
	}
	if config.MixedPort < 1 || config.MixedPort > 65535 {
		return 0, nil
	}
	return config.MixedPort, nil
}
