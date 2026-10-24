//go:build !android || !cmfa

package main

// 主机侧配置测试仍需编译，但不能谎报 Android 专有状态已经更新。
func applySystemDNS(servers []string) error {
	return errSystemDNSUnsupported
}
