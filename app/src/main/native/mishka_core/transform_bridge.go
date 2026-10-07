package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/config"

	"mishka_core/overrides"
)

// age 订阅在磁盘上保持密文，变换只能作用于解密后的明文；没有 age 头的内容原样返回。
func decryptConfig(configBytes []byte, ageSecretKey string) ([]byte, error) {
	var keys []string
	if key := strings.TrimSpace(ageSecretKey); key != "" {
		keys = append(keys, key)
	}
	plain, err := age.DecryptBytes(configBytes, keys...)
	if err != nil {
		return nil, fmt.Errorf("decrypt config: %w", err)
	}
	return plain, nil
}

func applyTransformFile(configBytes []byte, transformPath, ageSecretKey string) ([]byte, error) {
	transform, err := overrides.LoadTransform(transformPath)
	if err != nil {
		return nil, err
	}
	plain, err := decryptConfig(configBytes, ageSecretKey)
	if err != nil {
		return nil, err
	}
	return transform.Apply(plain)
}

// transformPath 为空时只解密，不套变换。
func readTransformedConfig(workDir, transformPath, ageSecretKey string) ([]byte, error) {
	raw, err := os.ReadFile(filepath.Join(workDir, "config.yaml"))
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}
	if transformPath == "" {
		return decryptConfig(raw, ageSecretKey)
	}
	return applyTransformFile(raw, transformPath, ageSecretKey)
}

type transformCheck struct {
	Valid bool `json:"valid"`
}

// 与运行时同一套变换与解析流程：保存改动前先在应用内跑一遍，失败时不去重启代理。
//
//export mishkaValidateTransform
func mishkaValidateTransform(cWorkDir, cTransform, cKey *C.char) *C.char {
	return guardString(func() string {
		workDir := C.GoString(cWorkDir)
		// provider 校验同样需要订阅密钥，调用方的 processLock 保证全局密钥串行使用。
		age.SetGlobalSecretKeys(C.GoString(cKey))
		defer age.SetGlobalSecretKeys()
		out, err := readTransformedConfig(workDir, C.GoString(cTransform), C.GoString(cKey))
		if err != nil {
			return "error: " + err.Error()
		}
		rawCfg, err := config.UnmarshalRawConfig(out)
		if err != nil {
			return "error: unmarshal config: " + err.Error()
		}
		patchProvidersPath(rawCfg, filepath.Join(workDir, "providers"))
		cfg, err := config.ParseRawConfig(rawCfg)
		if err != nil {
			return "error: validate config: " + err.Error()
		}
		destroyProviders(cfg)
		data, _ := json.Marshal(transformCheck{Valid: true})
		return string(data)
	})
}
