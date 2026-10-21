package com.leimao.tv.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText
import com.leimao.tv.core.model.IPTV_ALLOWED_TYPES
import com.leimao.tv.core.model.SearchScope

/**
 * 搜索页（M0 占位）。真正的检索是 M3 的 SEARCH-01~09。
 *
 * 这里刻意先把「边界」写在界面上：允许的类型**直接从 IPTV_ALLOWED_TYPES 读**，
 * 不在这个文件里写死一串中文类型名。原因是 RULES §3.2 明确禁止在 UI 里再存一份白名单 ——
 * 一旦 UI 自己抄一份，改规则时必然漏，而漏掉的那次正好是用户能看见的那次。
 */
@Composable
fun SearchScreen(
    scope: SearchScope,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "搜索",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(16.dp))

        FocusableCard(
            onClick = { },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
        ) {
            Text(
                // SEARCH-06：TV 上走屏幕九宫格键盘，不是系统输入法的软键盘
                text = "输入关键词（M3 接九宫格键盘与跨库检索）",
                style = TvText.current.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        when (scope) {
            SearchScope.IPTV -> {
                Text(
                    text = "直播板块只搜这六类：",
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = IPTV_ALLOWED_TYPES.joinToString(" / ") { it.displayName },
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "短剧、纪录片、资讯、预告片在直播板块搜不到；想看全部类型请切到影视板块搜。",
                    style = TvText.current.hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SearchScope.VOD -> {
                Text(
                    text = "影视板块搜索不限制类型，九类内容都会返回。",
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
        ) {
            Text(
                text = "结果分区（直播频道 / 影视）在 SEARCH-03 落地，默认焦点落在频道区第一条",
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
