package com.leimao.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 无源引导页（RULES §6.6 降级 2）。
 *
 * 这一页**不设主页中转**：没有可播内容时，「去主页」只是多一次按键。
 * 按返回 = 退出确认，因为用户此刻确实没东西可看。
 */
@Composable
fun AddSourceScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    val addFocus = remember { FocusRequester() }
    val stayFocus = remember { FocusRequester() }

    LaunchedEffect(confirmExit) {
        if (confirmExit) stayFocus.requestFocus() else addFocus.requestFocus()
    }

    BackHandler { confirmExit = true }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有订阅源",
            style = TvText.current.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "添加一个你自己的 M3U / TXT 订阅地址，就能开始看直播。",
            style = TvText.current.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(32.dp))

        if (!confirmExit) {
            FocusableCard(onClick = { }, modifier = Modifier.focusRequester(addFocus)) {
                Text(
                    // 真实入口是 M1 的 LIVE-03，这里先明确告诉用户「还没做」，而不是点了没反应
                    text = "添加订阅源（M1 接入）",
                    style = TvText.current.label,
                )
            }
        } else {
            Text(
                text = "确定退出？",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusableCard(onClick = { confirmExit = false }, modifier = Modifier.focusRequester(stayFocus)) {
                    Text(text = "留在应用", style = TvText.current.label)
                }
                FocusableCard(onClick = onExit) {
                    Text(text = "退出", style = TvText.current.label)
                }
            }
        }
    }
}
