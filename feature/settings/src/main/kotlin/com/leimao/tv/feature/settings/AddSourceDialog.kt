package com.leimao.tv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.designsystem.components.TvTextField
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 「添加订阅地址」对话框（LIVE-03）。
 *
 * 只用三样东西：名称、地址、UA。Referer / Basic 认证做成「高级」，是因为
 * 绝大多数源只需要前两项 —— 一上来就摆五个输入框，用户会以为哪里填错了。
 *
 * 输入框用 `BasicTextField` 而不是 Material 的 TextField：tv-material 没有自带文本框，
 * 而 Android TV 的输入法本身是方向键可操作的，所以「遥控器全流程」不依赖触摸（RULES §6.2）。
 */
@Composable
fun AddSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        type: String,
        name: String,
        url: String,
        userAgent: String,
        referer: String,
        basicUser: String,
        basicPass: String,
    ) -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(SourceType.LIVE) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var userAgent by rememberSaveable { mutableStateOf("") }
    var referer by rememberSaveable { mutableStateOf("") }
    var basicUser by rememberSaveable { mutableStateOf("") }
    var basicPass by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val firstFieldFocus = remember { FocusRequester() }
    val urlFieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstFieldFocus.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .width(720.dp)
                    // 高度必须给上限：电视机的可用高度差别很大（模拟器只有 ~250dp），
                    // 内容撑破对话框时 Compose 不会自动滚，而是**直接裁掉**——
                    // 表现就是「按钮不见了，看起来像没做导入按钮」
                    .fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
        ) {
            Text(
                text = "添加订阅地址",
                style = TvText.current.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    if (type == SourceType.VOD) {
                        "影视接口（苹果CMS / TVBox 系采集接口，形如 …/api.php/provide/vod）。"
                    } else {
                        "支持 M3U / M3U8 / TXT。地址应当是你能合法访问的订阅源。"
                    },
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 类型决定后面走哪条链路：直播是「下载播放列表 → 解析频道」，
            // 影视是「调采集接口 → 按分类抓条目」，填错了只会得到一条抓取失败
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "类型",
                    style = TvText.current.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp),
                )
                TypeChip(
                    text = "直播",
                    selected = type == SourceType.LIVE,
                    onClick = { type = SourceType.LIVE },
                )
                Spacer(modifier = Modifier.width(10.dp))
                TypeChip(
                    text = "影视",
                    selected = type == SourceType.VOD,
                    onClick = { type = SourceType.VOD },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 输入区可滚动、按钮固定在底部：投影仪/低端盒子上的字体放大也挤不掉按钮
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
            ) {
                TvTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称（留空则自动命名）",
                    modifier = Modifier.fillMaxWidth().focusRequester(firstFieldFocus),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TvTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "订阅地址（http:// 或 https:// 开头）",
                    modifier = Modifier.fillMaxWidth().focusRequester(urlFieldFocus),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TvTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = "User-Agent（可选，有些源不带就直接 403）",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 高级项默认收起：绝大多数源只需要「名称 + 地址 + UA」，
                // Referer / Basic 认证一摆出来用户会以为哪里填错了（LIVE-06）
                FocusableCard(
                    onClick = { advanced = !advanced },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "高级（Referer / 认证）",
                            style = TvText.current.label,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (advanced) "▲ 收起" else "▼ 展开",
                            style = TvText.current.hint,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (advanced) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TvTextField(
                        value = referer,
                        onValueChange = { referer = it },
                        label = "Referer（可选，防盗链源需要）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TvTextField(
                        value = basicUser,
                        onValueChange = { basicUser = it },
                        label = "认证账号（可选，Basic 认证）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TvTextField(
                        value = basicPass,
                        onValueChange = { basicPass = it },
                        label = "认证密码（可选，只存本机不上传）",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusableCard(
                    // 不要用 enabled = url.isNotBlank() 把它置灰：禁用态在本主题里
                    // 只是一块更暗的方框，用户会以为「根本没有导入按钮」。改成永远可点，
                    // 地址没填就把焦点送回去，比给一个按不动的按钮诚实
                    onClick = {
                        if (url.isBlank()) {
                            urlFieldFocus.requestFocus()
                        } else {
                            onConfirm(type, name, url, userAgent, referer, basicUser, basicPass)
                        }
                    },
                ) {
                    Text(text = "添加并刷新", style = TvText.current.label)
                }
                FocusableCard(onClick = onDismiss) {
                    Text(text = "取消", style = TvText.current.label)
                }
            }
        }
    }
}

/**
 * 类型选择 chip（VOD-03）。
 *
 * 选中态用「文字变色 + 底部短横线」而不是换背景：换背景会和焦点描边撞色，
 * 用户分不清「这个我选了」还是「焦点刚好停在这」。
 */
@Composable
private fun TypeChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    FocusableCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                style = TvText.current.body,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier =
                    Modifier
                        .width(16.dp)
                        .height(2.dp)
                        .background(if (selected) accent else Color.Transparent),
            )
        }
    }
}
