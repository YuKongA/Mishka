//go:build linux || android

package main

import (
	"errors"
	"fmt"
	"net"

	"golang.org/x/sys/unix"
)

func listenRootDNS(port int) ([]rootDNSSockets, error) {
	lc := &net.ListenConfig{}
	ipv4, err := bindRootDNSFamily(lc, "4", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return nil, err
	}
	sockets := []rootDNSSockets{ipv4}
	complete := false
	defer func() {
		if !complete {
			for _, socket := range sockets {
				socket.udp.Close()
				socket.tcp.Close()
			}
		}
	}()

	// 只在内核确实缺少 IPv6 协议族时退回 IPv4；权限、端口冲突等错误必须终止启动。
	fd, err := unix.Socket(unix.AF_INET6, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if errors.Is(err, unix.EAFNOSUPPORT) || errors.Is(err, unix.EPROTONOSUPPORT) {
		complete = true
		return sockets, nil
	}
	if err != nil {
		return nil, fmt.Errorf("probe ROOT DNS IPv6 support: %w", err)
	}
	unix.Close(fd)
	ipv6, err := bindRootDNSFamily(lc, "6", fmt.Sprintf("[::1]:%d", port))
	if err != nil {
		return nil, err
	}
	sockets = append(sockets, ipv6)
	complete = true
	return sockets, nil
}
