package top.yukonga.mishka.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import top.yukonga.mishka.domain.repository.SubscriptionRepository
import top.yukonga.mishka.viewmodel.AppProxyViewModel
import top.yukonga.mishka.viewmodel.BackupViewModel
import top.yukonga.mishka.viewmodel.ConnectionViewModel
import top.yukonga.mishka.viewmodel.DnsQueryViewModel
import top.yukonga.mishka.viewmodel.AutomationViewModel
import top.yukonga.mishka.viewmodel.ExternalControlViewModel
import top.yukonga.mishka.viewmodel.HomeViewModel
import top.yukonga.mishka.viewmodel.LogViewModel
import top.yukonga.mishka.viewmodel.MetaSettingsViewModel
import top.yukonga.mishka.viewmodel.NetworkSettingsViewModel
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.mishka.viewmodel.ProviderViewModel
import top.yukonga.mishka.viewmodel.ProxyViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel

/**
 * ViewModel 装配。单 Activity 应用，ViewModel 生命周期与进程一致，用 single；
 * 组合根（MainActivity）从 Koin 取图后透传给 App，屏幕保持参数化，无需 koinViewModel。
 */
val viewModelModule = module {
    single {
        HomeViewModel(
            serviceController = get(),
            overrideStore = get(),
            connectionManager = get(),
            latencyTester = get(),
            systemInfo = get(),
            getActiveSubscriptionId = { get<SubscriptionRepository>().getActive()?.id },
            activeSubscription = get<SubscriptionRepository>().activeSubscription,
            onLiveProviderInfo = get<SubscriptionRepository>()::setLiveProviderInfo,
        )
    }
    single { SubscriptionViewModel(get(), get(), get(), get(), androidContext()) }
    single { OverrideProfileViewModel(get(), get(), get(), androidContext()) }
    single {
        ProxyViewModel(
            selectionDao = get(),
            getActiveUuid = { get<SubscriptionRepository>().getActive()?.id },
            storage = get(),
        )
    }
    single { AppProxyViewModel(get(), get(), get()) }
    single { NetworkSettingsViewModel(get()) }
    single { MetaSettingsViewModel(get()) }
    single { ExternalControlViewModel(get()) }
    single { AutomationViewModel(get()) }
    single { LogViewModel() }
    single { ProviderViewModel() }
    single { ConnectionViewModel() }
    single { DnsQueryViewModel() }
    single { BackupViewModel(get(), get(), androidContext()) }
}
