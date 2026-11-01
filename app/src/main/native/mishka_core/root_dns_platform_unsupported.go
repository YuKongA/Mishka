//go:build !linux && !android

package main

import "fmt"

func listenRootDNS(_ int) ([]rootDNSSockets, error) {
	return nil, fmt.Errorf("ROOT DNS transparent sockets require Linux or Android")
}
