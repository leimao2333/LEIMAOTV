package com.leimao.tv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.database.repository.SourceType
import com.leimao.tv.core.designsystem.components.TvModal
import com.leimao.tv.core.designsystem.components.TvTextField
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.focus.requestFocusWhenReady
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 「添加订阅地址」对话框（LIVE-03）。
 *
 * **只填地址**：名称由地址自动生成（见 [sourceNameFromUrl]）—— 电视上打一次字要按几十下
 * 方向键，而用户填的名称基本都是「订阅源」，既没有信息量，又在列表里堆出一排同名卡片。
 * 类型是 chip 不是输入框（直播 / 影视走两条完全不同的链路），但**默认「自动」**（SET-14）：
 * 由 [com.leimao.tv.core.database.repository.SourceTypeGuesser] 按地址判定，
 * 判错了也能在源列表里改，比让用户先猜一个再得到「抓取失败」好。
 * UA 与 Referer / 认证做成可选项，绝大多数源用不到。
 *
 * 输入框走 [com.leimao.tv.core.designsystem.components.TvTextField]：按 OK 唤起**内置键盘**，
 * 不碰系统输入法 —— 电视上的系统输入法会吃掉方向键，遥控器进去就出不来（SET-11）。
 */
@Composable
fun AddSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        type: String,
        url: String,
        userAgent: String,
        referer: String,
        basicUser: String,
        basicPass: String,
    ) -> Unit,
) {
    // 默认「自动」（SET-14）：绝大多数人根本分不清一个地址是播放列表还是采集接口，
    // 而选错的代价是一条能加进去、却永远刷不出内容的死源 —— 这个判断机器一秒就能做
    var type by rememberSaveable { mutableStateOf(SourceType.AUTO) }
    var url by rememberSaveable { mutableStateOf("") }
    // 只用于「地址没填时把焦点送回输入框」；初始焦点靠布局顺序（输入框是第一个可聚焦元素）
    val urlFieldFocus = remember { FocusRequester() }

    var userAgent by rememberSaveable { mutableStateOf("") }
    var referer by rememberSaveable { mutableStateOf("") }
    var basicUser by rememberSaveable { mutableStateOf("") }
    var basicPass by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }

    // 树内弹层没有窗口，也就没有「窗口拿到焦点 → 自动落在第一个可聚焦元素上」这一步：
    // 底层内容被整棵移除后焦点树是空的，不点名的话第一次按键只会用来做初始焦点搜索
    // （表现就是「打开后第一下按什么键都没反应」）。等两帧让布局落定再请求 ——
    // 太早会请求到还没测量的节点上，结果一样
    LaunchedEffect(Unit) {
        // 固定等两帧只是「大多数情况够」：低端盒子上仍会落空，落空就是「打开弹层后
        // 第一下按键没反应」。改成成功与否驱动重试
        urlFieldFocus.requestFocusWhenReady()
    }

    // 树内弹层（SET-12）：不用 Dialog 窗口 —— 窗口拿到焦点的过程会吃掉打开后的
    // 第一次按键（按 OK 没反应，要再按一次才进输入框）。这是平台行为，在 Compose
    // 里改不掉，只能不建窗口。焦点锁靠调用方「弹层打开时不组合底层」
    TvModal {
        Text(
            text = "添加订阅地址",
            style = TvText.current.title,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "粘贴地址即可：直播（M3U / TXT）与影视（苹果CMS 接口）会自动识别。",
            style = TvText.current.hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 输入区可滚动、按钮固定在底部：投影仪/低端盒子上的字体放大也挤不掉按钮
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
        ) {
            // 地址栏是弹层里的第一个可聚焦元素：拿到焦点时自动落在
            // 第一个焦点目标上，这样「打开就能输」，不用再靠 requestFocus 抢
            TvTextField(
                value = url,
                onValueChange = { url = it },
                label = "订阅地址（http:// 或 https:// 开头）",
                modifier = Modifier.fillMaxWidth().focusRequester(urlFieldFocus),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 类型决定后面走哪条链路：直播是「下载播放列表 → 解析频道」，
            // 影视是「调采集接口 → 按分类抓条目」，填错了只会得到一条抓取失败。
            // 放在地址后面是因为遥控器焦点要先进地址栏（见上）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "类型",
                    style = TvText.current.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp),
                )
                TypeChip(
                    text = "自动",
                    selected = type == SourceType.AUTO,
                    onClick = { type = SourceType.AUTO },
                )
                Spacer(modifier = Modifier.width(10.dp))
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
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                // 「自动」认不出来时的落点是直播：直播源失败是可自证的（刷新后 0 个频道），
                // 影视源失败却是静默的。把这句话写出来，用户至少知道兜底去了哪
                text =
                    when (type) {
                        SourceType.AUTO -> "按地址判断；认不出来时按直播处理，可随时在列表里改。"
                        SourceType.VOD -> "影视源只能填采集接口，例如 …/api.php/provide/vod。"
                        else -> "直播源填 M3U / M3U8 / TXT 播放列表地址。"
                    },
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TvTextField(
                value = userAgent,
                onValueChange = { userAgent = it },
                label = "User-Agent（可选，有些源不带就直接 403）",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            // 高级项默认收起：绝大多数源只需要「地址 + UA」，
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
                        onConfirm(type, url, userAgent, referer, basicUser, basicPass)
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
