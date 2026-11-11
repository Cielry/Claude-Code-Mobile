package com.lyuan.cc.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NavAccent = Color(0xFFA8D4FF)
private val NavOnAccent = Color(0xFF0B1B2B)

/**
 * 底部导航：黑色玻璃底 + 浅蓝滑动指示块。
 * 按住时指示块跟随手指横向移动并放大，松开后回到选中的按钮。
 * 放大时可略微超出导航栏外框；文字颜色按指示块实际遮挡的文字范围实时切换。
 */
@Composable
fun GlassNavBar(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val density = LocalDensity.current
    var pressed by remember { mutableStateOf(false) }
    var touchXPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            // 不裁剪子元素：按住的指示块可以稍微突破外框。
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                shape,
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
        ) {
            val count = labels.size.coerceAtLeast(1)
            val itemWidth = maxWidth / count.toFloat()
            val widthPx = constraints.maxWidth.toFloat()
            val itemPx = widthPx / count.toFloat()
            val selectedOffset = itemWidth * selected.coerceIn(0, count - 1).toFloat()
            val fingerOffsetPx = (touchXPx - itemPx / 2f)
                .coerceIn(0f, (widthPx - itemPx).coerceAtLeast(0f))
            val fingerOffset = with(density) { fingerOffsetPx.toDp() }
            val offsetX by animateDpAsState(
                targetValue = if (pressed) fingerOffset else selectedOffset,
                animationSpec = if (pressed) {
                    tween(durationMillis = 60)
                } else {
                    spring(dampingRatio = 0.82f, stiffness = 420f)
                },
                label = "nav-pill",
            )
            // 40dp 行高 * 1.24 = 49.6dp，比 48dp 外框高约 0.8dp，产生轻微突破。
            val pillScale by animateFloatAsState(
                targetValue = if (pressed) 1.24f else 1f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 650f),
                label = "nav-pill-scale",
            )

            // 每个标签文字在屏幕中的实际范围，用于判断指示块是否盖住了文字。
            val labelBounds = remember(count) {
                mutableStateListOf<Rect>().apply { repeat(count) { add(Rect.Zero) } }
            }
            var rowLeftPx by remember { mutableFloatStateOf(0f) }
            val offsetPx = with(density) { offsetX.toPx() }
            val pillLeftPx = rowLeftPx + offsetPx + itemPx * (1f - pillScale) / 2f
            val pillRightPx = pillLeftPx + itemPx * pillScale
            val centerIndex = (with(density) { (offsetX + itemWidth / 2f).toPx() } / itemPx)
                .toInt()
                .coerceIn(0, count - 1)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(count, constraints.maxWidth) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            touchXPx = down.position.x
                            down.consume()
                            try {
                                var pointer = down
                                while (pointer.pressed) {
                                    val event = awaitPointerEvent()
                                    val next = event.changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    touchXPx = next.position.x
                                    if (next.pressed) next.consume()
                                    pointer = next
                                }
                                val index = ((touchXPx / widthPx) * count)
                                    .toInt()
                                    .coerceIn(0, count - 1)
                                onSelect(index)
                            } finally {
                                pressed = false
                            }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = offsetX)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = pillScale
                            scaleY = pillScale
                        }
                        .clip(RoundedCornerShape(22.dp))
                        .background(NavAccent),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .onGloballyPositioned { rowLeftPx = it.boundsInRoot().left },
                ) {
                    labels.forEachIndexed { index, label ->
                        val active = index == selected
                        val textBounds = labelBounds.getOrNull(index) ?: Rect.Zero
                        val covered = if (textBounds.width > 0f) {
                            textBounds.left < pillRightPx && textBounds.right > pillLeftPx
                        } else {
                            index == centerIndex
                        }
                        val textColor by animateColorAsState(
                            targetValue = if (covered) {
                                NavOnAccent
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                            },
                            animationSpec = tween(durationMillis = 80),
                            label = "nav-text",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .semantics {
                                    this.selected = active
                                    role = Role.Tab
                                    onClick(label = label) {
                                        onSelect(index)
                                        true
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = textColor,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.SemiBold
                                else FontWeight.Normal,
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    val bounds = coords.boundsInRoot()
                                    if (labelBounds.getOrNull(index) != bounds) {
                                        labelBounds[index] = bounds
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}