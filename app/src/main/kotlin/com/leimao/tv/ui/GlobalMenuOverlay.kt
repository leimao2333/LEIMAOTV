package com.leimao.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.theme.TvText

/**
 * 全局菜单（HOME-08）：菜单键在**任意页面**都能呼出的覆盖菜单。
 *
 * 四个出口是「随时都该够得着」的通用动作 —— 搜索、源管理、设置、退出。
 * 它跟主页左侧导航的区别：主页导航是「换板块」，这里是「从哪进就从哪回」——
 * 选中任一项并跳转后，返回键回到的是呼出菜单之前那个页面，不改变返回栈语义（RULES §6.4）。
 *
 * 交互：
 * - 菜单键呼出 / 再按收起（toggle）；
 * - 返回键收起；
 * - 呼出时焦点落在「搜索」，方向键在四项间移动，OK 触发；
 * - 选任意一项后菜单先收起，再执行对应动作。
 *
 * 为什么放在 :app：它要拿到 [onExitApp] 与导航这两样「只有组装层才有的东西」，
 * 自身不做业务判断（RULES §5.5）。
 */
@Composable
fun GlobalMenuOverlay(
    open: Boolean,
    onOpenSearch: () -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return

    val firstFocus = remember { FocusRequester() }

    // 初始焦点落在第一个菜单项（RULES §6.2 第 4 条）；菜单刚组合时节点未 attach，
    // 等一帧再请求，避免 requestFocus 抛异常（与播放页控制条同一套时序处理）。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    // 返回键先收菜单，不穿透到下层页面（§6.4：菜单是临时的覆盖层，不是导航栈的一页）
    BackHandler { onDismiss() }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // 半透明遮罩：让用户知道「下面还有画面」，同时明确焦点已经切到菜单
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
            )

            Column(
                modifier =
                    Modifier
                        .width(360.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .padding(24.dp),
            ) {
                Text(
                    text = "菜单",
                    style = TvText.current.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))

                MenuItem(
                    label = "搜索",
                    subtitle = "跨库搜索频道与影视",
                    focusRequester = firstFocus,
                    onClick = {
                        onDismiss()
                        onOpenSearch()
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
                MenuItem(
                    label = "源管理",
                    subtitle = "添加 / 刷新订阅源",
                    onClick = {
                        onDismiss()
                        onOpenSourceManager()
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
                MenuItem(
                    label = "设置",
                    subtitle = "播放 / 刷新 / 关于",
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
                MenuItem(
                    label = "退出",
                    subtitle = "退出磊毛TV",
                    onClick = {
                        onDismiss()
                        onExit()
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    FocusableCard(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        contentPadding =
            PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
    ) {
        Column {
            Text(
                text = label,
                style = TvText.current.label,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = TvText.current.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
