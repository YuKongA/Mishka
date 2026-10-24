//go:build android && cmfa

package main

import "github.com/metacubex/mihomo/dns"

func applySystemDNS(servers []string) error {
	dns.UpdateSystemDNS(servers)
	dns.FlushCacheWithDefaultResolver()
	return nil
}
