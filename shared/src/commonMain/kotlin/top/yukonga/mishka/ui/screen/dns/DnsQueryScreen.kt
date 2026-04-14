package top.yukonga.mishka.ui.screen.dns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixShape
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.mishka.data.model.DnsAnswer
import top.yukonga.mishka.viewmodel.DnsQueryViewModel

@Composable
fun DnsQueryScreen(
    viewModel: DnsQueryViewModel,
    onBack: () -> Unit = {},
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val textFieldState = rememberTextFieldState()
    var showCacheDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "DNS 查询",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCacheDialog = true }) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "清除缓存",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = bottomPadding,
            ),
        ) {
            // 输入区域
            item(key = "input") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp, bottom = 6.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    // 域名输入框
                    TextField(
                        state = textFieldState,
                        modifier = Modifier.fillMaxWidth(),
                        label = "域名",
                        useLabelAsPlaceholder = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        onKeyboardAction = {
                            viewModel.setQueryName(textFieldState.text.toString())
                            viewModel.queryDns()
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    // 查询类型选择（3+3 两行全宽）
                    val types = listOf("A", "AAAA", "CNAME", "MX", "TXT", "NS")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        types.take(3).forEach { type ->
                            TextButton(
                                text = type,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.setQueryType(type) },
                                colors = if (uiState.queryType == type) {
                                    ButtonDefaults.textButtonColorsPrimary()
                                } else {
                                    ButtonDefaults.textButtonColors()
                                },
                                minHeight = 36.dp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        types.drop(3).forEach { type ->
                            TextButton(
                                text = type,
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.setQueryType(type) },
                                colors = if (uiState.queryType == type) {
                                    ButtonDefaults.textButtonColorsPrimary()
                                } else {
                                    ButtonDefaults.textButtonColors()
                                },
                                minHeight = 36.dp,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 查询按钮
                    TextButton(
                        text = if (uiState.isQuerying) "查询中..." else "查询",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = textFieldState.text.isNotBlank() && !uiState.isQuerying,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            viewModel.setQueryName(textFieldState.text.toString())
                            viewModel.queryDns()
                        },
                    )
                }
            }

            // 错误
            if (uiState.error.isNotEmpty()) {
                item(key = "error") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp, bottom = 6.dp),
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Text(
                            text = uiState.error,
                            color = androidx.compose.ui.graphics.Color(0xFFE53935),
                        )
                    }
                }
            }

            // 结果区域
            if (uiState.status != null) {
                item(key = "result_title") {
                    SmallTitle(text = "查询结果")
                }

                if (uiState.answers.isEmpty()) {
                    item(key = "no_result") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(
                                text = "无记录",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    item(key = "result_card") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            uiState.answers.forEach { answer ->
                                DnsAnswerItem(answer)
                            }
                        }
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    // 缓存清除 Dialog
    WindowDialog(
        show = showCacheDialog,
        title = "清除缓存",
        onDismissRequest = { showCacheDialog = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = "清除 DNS 缓存",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    viewModel.flushDnsCache()
                    showCacheDialog = false
                },
            )
            TextButton(
                text = "清除 FakeIP 缓存",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    viewModel.flushFakeIp()
                    showCacheDialog = false
                },
            )
        }
    }
}

@Composable
private fun DnsAnswerItem(answer: DnsAnswer) {
    BasicComponent(
        title = answer.data,
        summary = "TTL: ${answer.TTL}s",
        startAction = {
            // 类型 Badge
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .clip(miuixShape(3.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = dnsTypeToString(answer.type),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        },
    )
}

private fun dnsTypeToString(type: Int): String = when (type) {
    1 -> "A"
    2 -> "NS"
    5 -> "CNAME"
    6 -> "SOA"
    15 -> "MX"
    16 -> "TXT"
    28 -> "AAAA"
    33 -> "SRV"
    else -> "TYPE$type"
}
