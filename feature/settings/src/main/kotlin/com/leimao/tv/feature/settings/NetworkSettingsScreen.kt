package com.leimao.tv.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.components.TvTextField
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.network.ConcurrencyProfile
import com.leimao.tv.core.model.network.NetworkSettings
import com.leimao.tv.core.model.network.ProxyConfig
import com.leimao.tv.core.model.network.ProxyParse
import com.leimao.tv.core.model.network.TimeoutProfile
import com.leimao.tv.core.model.network.parseProxyAddress

/**
 * 网络设置页（SET-04）：超时、并发、代理。
 *
 * ## 为什么全是档位
 *
 * 让用户在遥控器上填「连接超时 12 秒」是三重折磨：挪十几次方向键、他并不知道 10 与 12 的区别、
 * 填错了整个 App 的网络行为都变了却看不出来。档位把问题换成「你现在是哪种情况」，
 * 选错也只是快慢之差。
 *
 * ## 为什么写着「下次启动生效」
 *
 * 三项都落在 OkHttpClient 的构造参数上，而那个实例已被播放内核与各仓储在构造期抓走。
 * 不写这一句，用户改完发现毫无变化，只会以为设置坏了 —— 而这句话本身就是这个设计的一部分。
 */
@Composable
fun NetworkSettingsScreen(
    settings: NetworkSettings,
    onTimeoutChange: (TimeoutProfile) -> Unit,
    onConcurrencyChange: (ConcurrencyProfile) -> Unit,
    onProxyChange: (ProxyConfig?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "网络",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "这里的改动下次启动 App 后生效。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(20.dp))

        SectionTitle(text = "超时")
        Spacer(modifier = Modifier.height(8.dp))
        TimeoutProfile.entries.forEach { profile ->
            ChoiceRow(
                title = profile.label,
                subtitle = profile.description,
                selected = profile == settings.timeout,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (profile == settings.timeout) Modifier.focusRequester(firstFocus) else Modifier,
                        ),
                onClick = { onTimeoutChange(profile) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = "老是提示「连接超时」就往宽松调；反过来想让失败的源快点暴露，就往快速调。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        SectionTitle(text = "并发")
        Spacer(modifier = Modifier.height(8.dp))
        ConcurrencyProfile.entries.forEach { profile ->
            ChoiceRow(
                title = profile.label,
                subtitle = profile.description,
                selected = profile == settings.concurrency,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onConcurrencyChange(profile) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = "影响搜索与影视目录刷新。被源限流（总提示 HTTP 4xx）就往保守调。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        ProxyBlock(
            current = settings.proxy,
            onApply = onProxyChange,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * 代理区块。
 *
 * 单独拆出来是为了让「输入框里的草稿」和「已保存的值」两个状态关在一起：
 * 用户改了地址但没按保存就退出，看到的下次还是旧值 —— 这与「保存」这个按钮的语义一致，
 * 不需要额外的「未保存改动」提示。
 */
@Composable
private fun ProxyBlock(
    current: ProxyConfig?,
    onApply: (ProxyConfig?) -> Unit,
) {
    // 草稿跟着已保存的值初始化，但之后由用户输入主导（不能被外部重建覆盖掉正在打字的半截地址）
    var draft by remember { mutableStateOf(current?.address.orEmpty()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }

    SectionTitle(text = "代理")
    Spacer(modifier = Modifier.height(8.dp))
    TvTextField(
        value = draft,
        onValueChange = {
            draft = it
            // 一动手就把上一次的结论清掉：留着「已保存」会让人以为新填的也存好了
            notice = null
        },
        label = "代理地址（留空 = 直连）",
        placeholder = "192.168.1.2:7890",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))

    FocusableCard(
        onClick = {
            when (val parsed = parseProxyAddress(draft)) {
                is ProxyParse.Ok -> {
                    onApply(parsed.config)
                    draft = parsed.config.address
                    notice = "已保存 ${parsed.config.address}，下次启动生效"
                    noticeIsError = false
                }

                ProxyParse.Direct -> {
                    onApply(null)
                    notice = "已改为直连，下次启动生效"
                    noticeIsError = false
                }

                is ProxyParse.Invalid -> {
                    notice = parsed.reason
                    noticeIsError = true
                }
            }
        },
    ) {
        Text(
            text = "保存代理",
            style = TvText.current.label,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text =
            notice ?: "当前：${current?.address ?: "直连"}。" +
                "只影响本 App 的抓取与播放，不改系统代理；暂不支持需要账号密码的代理。",
        style = TvText.current.hint,
        color =
            if (noticeIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}
