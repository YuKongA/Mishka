package top.yukonga.mishka.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.mishka.data.model.ConfigPatch
import top.yukonga.mishka.data.model.DnsPatch
import top.yukonga.mishka.data.model.SnifferPatch
import top.yukonga.mishka.data.repository.MihomoRepository
import top.yukonga.mishka.data.repository.OverrideStorageHelper
import top.yukonga.mishka.platform.PlatformStorage

/**
 * 覆写设置 UI 状态。
 * 所有字段均为可空，null 表示"不修改"（使用订阅配置原值）。
 */
data class OverrideUiState(
    // 通用
    val httpPort: Int? = null,
    val socksPort: Int? = null,
    val redirPort: Int? = null,
    val tproxyPort: Int? = null,
    val mixedPort: Int? = null,
    val allowLan: Boolean? = null,
    val ipv6: Boolean? = null,
    val externalController: String? = null,
    val bindAddress: String? = null,
    val logLevel: String? = null,
    // DNS
    val dnsEnable: Boolean? = null,
    val dnsListen: String? = null,
    val dnsIpv6: Boolean? = null,
    val dnsPreferH3: Boolean? = null,
    val dnsUseHosts: Boolean? = null,
    val dnsEnhancedMode: String? = null,
    val dnsNameservers: List<String>? = null,
    val dnsFallback: List<String>? = null,
    val dnsDefaultNameserver: List<String>? = null,
    val dnsFakeIpFilter: List<String>? = null,
    // Meta 特性
    val unifiedDelay: Boolean? = null,
    val geodataMode: Boolean? = null,
    val tcpConcurrent: Boolean? = null,
    val findProcessMode: String? = null,
    // Sniffer
    val snifferEnable: Boolean? = null,
    val snifferForceDnsMapping: Boolean? = null,
    val snifferParsePureIp: Boolean? = null,
    val snifferOverrideDestination: Boolean? = null,
    val snifferForceDomain: List<String>? = null,
    val snifferSkipDomain: List<String>? = null,
)

class OverrideSettingsViewModel(
    private val storage: PlatformStorage,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OverrideUiState())
    val uiState: StateFlow<OverrideUiState> = _uiState.asStateFlow()

    private var repository: MihomoRepository? = null

    init {
        loadFromStorage()
    }

    fun setRepository(repo: MihomoRepository?) {
        repository = repo
    }

    // === 从 PlatformStorage 加载所有覆写设置 ===

    private fun loadFromStorage() {
        val h = OverrideStorageHelper
        _uiState.value = OverrideUiState(
            httpPort = h.readNullableInt(storage, h.KEY_HTTP_PORT),
            socksPort = h.readNullableInt(storage, h.KEY_SOCKS_PORT),
            redirPort = h.readNullableInt(storage, h.KEY_REDIR_PORT),
            tproxyPort = h.readNullableInt(storage, h.KEY_TPROXY_PORT),
            mixedPort = h.readNullableInt(storage, h.KEY_MIXED_PORT),
            allowLan = h.readNullableBoolean(storage, h.KEY_ALLOW_LAN),
            ipv6 = h.readNullableBoolean(storage, h.KEY_IPV6),
            externalController = h.readNullableString(storage, h.KEY_EXTERNAL_CONTROLLER),
            bindAddress = h.readNullableString(storage, h.KEY_BIND_ADDRESS),
            logLevel = h.readNullableString(storage, h.KEY_LOG_LEVEL),
            dnsEnable = h.readNullableBoolean(storage, h.KEY_DNS_ENABLE),
            dnsListen = h.readNullableString(storage, h.KEY_DNS_LISTEN),
            dnsIpv6 = h.readNullableBoolean(storage, h.KEY_DNS_IPV6),
            dnsPreferH3 = h.readNullableBoolean(storage, h.KEY_DNS_PREFER_H3),
            dnsUseHosts = h.readNullableBoolean(storage, h.KEY_DNS_USE_HOSTS),
            dnsEnhancedMode = h.readNullableString(storage, h.KEY_DNS_ENHANCED_MODE),
            dnsNameservers = h.readNullableStringList(storage, h.KEY_DNS_NAMESERVERS),
            dnsFallback = h.readNullableStringList(storage, h.KEY_DNS_FALLBACK),
            dnsDefaultNameserver = h.readNullableStringList(storage, h.KEY_DNS_DEFAULT_NAMESERVER),
            dnsFakeIpFilter = h.readNullableStringList(storage, h.KEY_DNS_FAKEIP_FILTER),
            unifiedDelay = h.readNullableBoolean(storage, h.KEY_UNIFIED_DELAY),
            geodataMode = h.readNullableBoolean(storage, h.KEY_GEODATA_MODE),
            tcpConcurrent = h.readNullableBoolean(storage, h.KEY_TCP_CONCURRENT),
            findProcessMode = h.readNullableString(storage, h.KEY_FIND_PROCESS_MODE),
            snifferEnable = h.readNullableBoolean(storage, h.KEY_SNIFFER_ENABLE),
            snifferForceDnsMapping = h.readNullableBoolean(storage, h.KEY_SNIFFER_FORCE_DNS_MAPPING),
            snifferParsePureIp = h.readNullableBoolean(storage, h.KEY_SNIFFER_PARSE_PURE_IP),
            snifferOverrideDestination = h.readNullableBoolean(storage, h.KEY_SNIFFER_OVERRIDE_DEST),
            snifferForceDomain = h.readNullableStringList(storage, h.KEY_SNIFFER_FORCE_DOMAIN),
            snifferSkipDomain = h.readNullableStringList(storage, h.KEY_SNIFFER_SKIP_DOMAIN),
        )
    }

    // === 更新方法：保存到 PlatformStorage 并可选 patch 运行中的 mihomo ===

    fun updatePort(key: String, value: Int?) {
        OverrideStorageHelper.writeNullableInt(storage, key, value)
        val state = _uiState.value
        _uiState.value = when (key) {
            OverrideStorageHelper.KEY_HTTP_PORT -> state.copy(httpPort = value)
            OverrideStorageHelper.KEY_SOCKS_PORT -> state.copy(socksPort = value)
            OverrideStorageHelper.KEY_REDIR_PORT -> state.copy(redirPort = value)
            OverrideStorageHelper.KEY_TPROXY_PORT -> state.copy(tproxyPort = value)
            OverrideStorageHelper.KEY_MIXED_PORT -> state.copy(mixedPort = value)
            else -> state
        }
        patchIfRunning()
    }

    fun updateBoolean(key: String, value: Boolean?) {
        OverrideStorageHelper.writeNullableBoolean(storage, key, value)
        val state = _uiState.value
        _uiState.value = when (key) {
            OverrideStorageHelper.KEY_ALLOW_LAN -> state.copy(allowLan = value)
            OverrideStorageHelper.KEY_IPV6 -> state.copy(ipv6 = value)
            OverrideStorageHelper.KEY_DNS_ENABLE -> state.copy(dnsEnable = value)
            OverrideStorageHelper.KEY_DNS_IPV6 -> state.copy(dnsIpv6 = value)
            OverrideStorageHelper.KEY_DNS_PREFER_H3 -> state.copy(dnsPreferH3 = value)
            OverrideStorageHelper.KEY_DNS_USE_HOSTS -> state.copy(dnsUseHosts = value)
            OverrideStorageHelper.KEY_UNIFIED_DELAY -> state.copy(unifiedDelay = value)
            OverrideStorageHelper.KEY_GEODATA_MODE -> state.copy(geodataMode = value)
            OverrideStorageHelper.KEY_TCP_CONCURRENT -> state.copy(tcpConcurrent = value)
            OverrideStorageHelper.KEY_SNIFFER_ENABLE -> state.copy(snifferEnable = value)
            OverrideStorageHelper.KEY_SNIFFER_FORCE_DNS_MAPPING -> state.copy(snifferForceDnsMapping = value)
            OverrideStorageHelper.KEY_SNIFFER_PARSE_PURE_IP -> state.copy(snifferParsePureIp = value)
            OverrideStorageHelper.KEY_SNIFFER_OVERRIDE_DEST -> state.copy(snifferOverrideDestination = value)
            else -> state
        }
        patchIfRunning()
    }

    fun updateString(key: String, value: String?) {
        OverrideStorageHelper.writeNullableString(storage, key, value)
        val state = _uiState.value
        _uiState.value = when (key) {
            OverrideStorageHelper.KEY_EXTERNAL_CONTROLLER -> state.copy(externalController = value)
            OverrideStorageHelper.KEY_BIND_ADDRESS -> state.copy(bindAddress = value)
            OverrideStorageHelper.KEY_LOG_LEVEL -> state.copy(logLevel = value)
            OverrideStorageHelper.KEY_DNS_LISTEN -> state.copy(dnsListen = value)
            OverrideStorageHelper.KEY_DNS_ENHANCED_MODE -> state.copy(dnsEnhancedMode = value)
            OverrideStorageHelper.KEY_FIND_PROCESS_MODE -> state.copy(findProcessMode = value)
            else -> state
        }
        patchIfRunning()
    }

    fun updateStringList(key: String, value: List<String>?) {
        OverrideStorageHelper.writeNullableStringList(storage, key, value)
        val state = _uiState.value
        _uiState.value = when (key) {
            OverrideStorageHelper.KEY_DNS_NAMESERVERS -> state.copy(dnsNameservers = value)
            OverrideStorageHelper.KEY_DNS_FALLBACK -> state.copy(dnsFallback = value)
            OverrideStorageHelper.KEY_DNS_DEFAULT_NAMESERVER -> state.copy(dnsDefaultNameserver = value)
            OverrideStorageHelper.KEY_DNS_FAKEIP_FILTER -> state.copy(dnsFakeIpFilter = value)
            OverrideStorageHelper.KEY_SNIFFER_FORCE_DOMAIN -> state.copy(snifferForceDomain = value)
            OverrideStorageHelper.KEY_SNIFFER_SKIP_DOMAIN -> state.copy(snifferSkipDomain = value)
            else -> state
        }
        patchIfRunning()
    }

    // === 将当前覆写设置 patch 到运行中的 mihomo ===

    private fun patchIfRunning() {
        val repo = repository ?: return
        val state = _uiState.value

        // 构建仅包含非 null 字段的 ConfigPatch
        val snifferPatch = if (state.snifferEnable != null || state.snifferForceDnsMapping != null ||
            state.snifferParsePureIp != null || state.snifferOverrideDestination != null ||
            state.snifferForceDomain != null || state.snifferSkipDomain != null
        ) {
            SnifferPatch(
                enable = state.snifferEnable,
                forceDnsMapping = state.snifferForceDnsMapping,
                parsePureIp = state.snifferParsePureIp,
                overrideDestination = state.snifferOverrideDestination,
                forceDomain = state.snifferForceDomain,
                skipDomain = state.snifferSkipDomain,
            )
        } else null

        val dnsPatch = if (state.dnsEnable != null || state.dnsListen != null ||
            state.dnsIpv6 != null || state.dnsPreferH3 != null || state.dnsUseHosts != null ||
            state.dnsEnhancedMode != null || state.dnsNameservers != null || state.dnsFallback != null ||
            state.dnsDefaultNameserver != null || state.dnsFakeIpFilter != null
        ) {
            DnsPatch(
                enable = state.dnsEnable,
                listen = state.dnsListen,
                ipv6 = state.dnsIpv6,
                preferH3 = state.dnsPreferH3,
                useHosts = state.dnsUseHosts,
                enhancedMode = state.dnsEnhancedMode,
                nameserver = state.dnsNameservers,
                fallback = state.dnsFallback,
                defaultNameserver = state.dnsDefaultNameserver,
                fakeIpFilter = state.dnsFakeIpFilter,
            )
        } else null

        val patch = ConfigPatch(
            port = state.httpPort,
            socksPort = state.socksPort,
            redirPort = state.redirPort,
            tproxyPort = state.tproxyPort,
            mixedPort = state.mixedPort,
            allowLan = state.allowLan,
            ipv6 = state.ipv6,
            bindAddress = state.bindAddress,
            logLevel = state.logLevel,
            unifiedDelay = state.unifiedDelay,
            geodataMode = state.geodataMode,
            tcpConcurrent = state.tcpConcurrent,
            findProcessMode = state.findProcessMode,
            sniffer = snifferPatch,
            dns = dnsPatch,
        )

        viewModelScope.launch {
            repo.patchConfig(patch)
        }
    }
}
