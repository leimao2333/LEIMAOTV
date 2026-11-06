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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.leimao.tv.core.designsystem.focus.FocusableCard
import com.leimao.tv.core.designsystem.focus.requestFocusWhenReady
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

    // 四个菜单项各一个 requester。方向键不靠 focusManager.moveFocus：小键盘的数字键码
    // 走的是 onPreviewKeyEvent，在那个时机调 moveFocus 会被焦点事务排队、实测不生效
    // （2026-11-06：菜单里按小键盘 2 焦点纹丝不动）。直接点名请求焦点最实在。
    val requesters = remember { List(MENU_ITEM_COUNT) { FocusRequester() } }
    var focusIndex by remember { mutableIntStateOf(0) }

    // 初始焦点落在第一个菜单项（RULES §6.2 第 4 条）。
    // 必须重试到真的挂上：只等一帧在低配盒子上常常失败，异常被吞掉之后
    // 菜单虽然弹出来了、方向键却完全没有反应（与播放页控制条同一套处理）
    LaunchedEffect(focusIndex) {
        requesters[focusIndex.coerceIn(0, MENU_ITEM_COUNT - 1)].requestFocusWhenReady()
    }

    // 返回键先收菜单，不穿透到下层页面（§6.4：菜单是临时的覆盖层，不是导航栈的一页）
    BackHandler { onDismiss() }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // 1 / 3 = 返回 / 菜单，两套键盘（小键盘 + 主键盘区那排数字）
                            // 都要认：Dialog 有自己的窗口，Activity 的 KeyRemap 管不到这里
                            Key.NumPad1, Key.One, Key.NumPad3, Key.Three, Key.Escape -> {
                                onDismiss()
                                true
                            }

                            Key.NumPad8, Key.Eight -> {
                                focusIndex = (focusIndex - 1).coerceAtLeast(0)
                                true
                            }

                            Key.NumPad2, Key.Two -> {
                                focusIndex = (focusIndex + 1).coerceAtMost(MENU_ITEM_COUNT - 1)
                                true
                            }

                            else -> false
                        }
                    },
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
                    focusRequester = requesters[0],
                    onFocused = { focusIndex = 0 },
                    onClick = {
                        onDismiss()
                        onOpenSearch()
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
                MenuItem(
                    label = "源管理",
                    subtitle = "添加 / 刷新订阅源",
                    focusRequester = requesters[1],
                    onFocused = { focusIndex = 1 },
                    onClick = {
                        onDismiss()
                        onOpenSourceManager()
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
                MenuItem(
                    label = "设置",
                    subtitle = "播放 / 刷新 / 关于",
                    focusRequester = requesters[2],
                    onFocused = { focusIndex = 2 },
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                )
                Spacer(modifier = Modifier.height(10.dp))
                MenuItem(
                    label = "退出",
                    subtitle = "退出磊毛TV",
                    focusRequester = requesters[3],
                    onFocused = { focusIndex = 3 },
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
    onFocused: () -> Unit = {},
) {
    FocusableCard(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                // 焦点落在哪一项要回报给上面：小键盘 8/2 是按索引挪的，
                // 用户用遥控器方向键走过一遍之后，索引得跟上，否则下一次按 2 会跳回原处
                .onFocusChanged { if (it.isFocused) onFocused() }
                // 小键盘 5（九宫格正中）= OK。`clickable` 只认 DPAD_CENTER 与主键盘回车，
                // NumPad5 / NumPadEnter 是另外的键码，不补这一下，菜单里按 5 毫无反应
                .onPreviewKeyEvent { event ->
                    val confirm =
                        event.key == Key.NumPad5 ||
                            event.key == Key.Five ||
                            event.key == Key.NumPadEnter ||
                            event.key == Key.Enter
                    // 用 KeyUp 而不是 KeyDown：跟 `clickable` 的触发时机一致，
                    // 按住不放时不会因为连发 KeyDown 而连点好几下
                    if (event.type == KeyEventType.KeyUp && confirm) {
                        onClick()
                        true
                    } else {
                        false
                    }
                },
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

/** 菜单项个数。小键盘 8/2 按索引挪焦点，超过这个数就到底了。 */
private const val MENU_ITEM_COUNT = 4
