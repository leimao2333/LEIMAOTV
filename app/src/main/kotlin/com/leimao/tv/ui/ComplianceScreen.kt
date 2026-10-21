package com.leimao.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.layout.SafeAreaContainer
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 首启合规提示（SYS-09 / RULES §2.4）。
 *
 * 只说三件事，且**必须一次说完**：不提供源、请用你有权访问的源、后果自负。
 * 同意后不走主页，直接重走落点判定（§6.6 降级 1 的最后一句「不得再回主页」）。
 */
@Composable
fun ComplianceScreen(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val agreeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { agreeFocus.requestFocus() }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SafeAreaContainer {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "欢迎使用磊毛TV",
                    style = TvText.current.display,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "本应用是播放器，不提供、不内置、不分发任何直播源或影视源。",
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请使用你有权访问的源，并遵守当地法律法规与内容版权。",
                    style = TvText.current.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FocusableCard(onClick = onAgree, modifier = Modifier.focusRequester(agreeFocus)) {
                        Text(text = "我已了解，继续", style = TvText.current.label)
                    }
                    FocusableCard(onClick = onDecline) {
                        Text(text = "退出", style = TvText.current.label)
                    }
                }
            }
        }
    }
}
