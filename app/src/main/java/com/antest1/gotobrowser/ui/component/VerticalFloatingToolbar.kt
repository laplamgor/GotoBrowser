package com.antest1.gotobrowser.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


private const val RevealStripTopLayoutId = "reveal_strip_top"
private const val RevealStripBottomLayoutId = "reveal_strip_bottom"
private const val BarLayoutId = "bar"

/**
 * A vertical floating toolbar docked to the left edge of its parent.
 *
 * Behavior:
 *  - When [visible] is true, the bar is docked near the left edge, separated
 *    from the screen edge by [margin].
 *  - When [visible] is false, the bar rests off-screen to the left, and a thin
 *    strip along the left edge can be swiped to the right to reveal it.
 *  - The bar can be dragged horizontally: its horizontal position follows the
 *    finger. Dragging it far enough to the left hides it; otherwise it snaps
 *    back to the docked position.
 *  - The bar height wraps its content, up to [barHeightFraction] of the parent
 *    height. If the content exceeds that, the bar becomes scrollable.
 *  - Only the bar (and the reveal strips while hidden) consume touch input, so
 *    the rest of the parent keeps receiving interaction while the bar is shown.
 *  - The reveal strips physically avoid/dodge the WebView area to completely avoid blocking clicks/touches.
 */
@Composable
fun VerticalFloatingToolbar(
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    barWidth: Dp = 56.dp,
    barHeightFraction: Float = 0.7f,
    revealWidth: Dp = 24.dp,
    margin: Dp = 8.dp,
    contentSpacing: Dp = 6.dp,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = Color(0xCC666666),
    elevation: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val barWidthPx = with(density) { barWidth.toPx() }
    val marginPx = with(density) { margin.toPx() }
    val coroutineScope = rememberCoroutineScope()

    // Horizontal position when the bar is fully docked (left edge + margin) and
    // when it is fully hidden off-screen to the left.
    val dockedX = marginPx
    val hiddenX = -barWidthPx
    val triggerX = (dockedX + hiddenX) / 2f

    val currentVisible by rememberUpdatedState(visible)
    val currentOnVisibleChange by rememberUpdatedState(onVisibleChange)

    val offsetX = remember { Animatable(if (visible) dockedX else hiddenX) }

    val settleSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    // Decide whether the bar should stay open or close, based on how far it was dragged.
    fun settle() {
        val shouldShow = offsetX.value > triggerX
        if (shouldShow != currentVisible) {
            currentOnVisibleChange(shouldShow)
        } else {
            coroutineScope.launch {
                offsetX.animateTo(
                    targetValue = if (shouldShow) dockedX else hiddenX,
                    animationSpec = settleSpec
                )
            }
        }
    }

    // Animate the bar in/out whenever visibility is changed from the outside
    // (e.g. tapping the background area).
    LaunchedEffect(visible) {
        offsetX.animateTo(
            targetValue = if (visible) dockedX else hiddenX,
            animationSpec = settleSpec
        )
    }

    // A custom layout is used here instead of BoxWithConstraints so that the
    // parent's max height can be read without incurring a subcomposition pass.
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            // Thin strips along the left edge used to swipe the bar open while hidden.
            // Sized and positioned dynamically to completely avoid/dodge the centered WebView area.
            if (!visible) {
                val createStrip = @Composable { id: String ->
                    Box(
                        modifier = Modifier
                            .layoutId(id)
                            .width(revealWidth)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragStart = { coroutineScope.launch { offsetX.stop() } },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        coroutineScope.launch {
                                            offsetX.snapTo(
                                                (offsetX.value + dragAmount).coerceIn(hiddenX, dockedX)
                                            )
                                        }
                                    },
                                    onDragEnd = { settle() },
                                    onDragCancel = { settle() }
                                )
                            }
                    )
                }
                createStrip(RevealStripTopLayoutId)
                createStrip(RevealStripBottomLayoutId)
            }

            Surface(
                modifier = Modifier
                    .layoutId(BarLayoutId)
                    .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                    .width(barWidth)
                    // Swallow taps that land on the bar so they do not fall through
                    // to the background toggle.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    // Horizontal drag: the bar follows the finger and can be dragged off-screen left.
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { coroutineScope.launch { offsetX.stop() } },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                coroutineScope.launch {
                                    offsetX.snapTo(
                                        (offsetX.value + dragAmount).coerceIn(hiddenX, dockedX)
                                    )
                                }
                            },
                            onDragEnd = { settle() },
                            onDragCancel = { settle() }
                        )
                    },
                shape = shape,
                color = containerColor,
                shadowElevation = elevation
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(contentSpacing, Alignment.CenterVertically),
                    content = content
                )
            }
        }
    ) { measurables, constraints ->
        val maxBarHeight = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight * barHeightFraction).roundToInt()
        } else {
            Constraints.Infinity
        }

        // Calculate WebView geometry bounds (15:9 aspect ratio centered).
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()
        val ratio = 1200f / 720f

        val webViewWidth: Float
        val webViewHeight: Float
        if (containerWidth / containerHeight > ratio) {
            // Screen is wider than 15:9 (Landscape) -> WebView takes full height, padded on left/right edges
            webViewHeight = containerHeight
            webViewWidth = containerHeight * ratio
        } else {
            // Screen is narrower than 15:9 (Portrait) -> WebView takes full width, padded on top/bottom edges
            webViewWidth = containerWidth
            webViewHeight = containerWidth / ratio
        }

        val webViewLeft = (containerWidth - webViewWidth) / 2f
        val webViewTop = (containerHeight - webViewHeight) / 2f
        val webViewBottom = webViewTop + webViewHeight

        val revealWidthPx = with(density) { revealWidth.toPx() }
        val overlapsHorizontally = webViewLeft < revealWidthPx

        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val placeables = measurables.map { measurable ->
            when (measurable.layoutId) {
                BarLayoutId -> measurable.measure(
                    looseConstraints.copy(maxHeight = maxBarHeight)
                )
                RevealStripTopLayoutId -> {
                    // In portrait mode, only cover the top black bar area. In landscape, cover full screen height since it's safely inside the left black bar margin.
                    val heightCap = if (overlapsHorizontally) webViewTop.roundToInt().coerceAtLeast(0) else constraints.maxHeight
                    measurable.measure(looseConstraints.copy(maxHeight = heightCap))
                }
                RevealStripBottomLayoutId -> {
                    // In portrait mode, only cover the bottom black bar area. In landscape, 0 height.
                    val heightCap = if (overlapsHorizontally) (constraints.maxHeight - webViewBottom.roundToInt()).coerceAtLeast(0) else 0
                    measurable.measure(looseConstraints.copy(maxHeight = heightCap))
                }
                else -> measurable.measure(looseConstraints)
            }
        }

        val width = constraints.maxWidth
        val height = constraints.maxHeight
        layout(width, height) {
            measurables.zip(placeables).forEach { (measurable, placeable) ->
                when (measurable.layoutId) {
                    BarLayoutId -> {
                        placeable.place(x = 0, y = (height - placeable.height) / 2)
                    }
                    RevealStripTopLayoutId -> {
                        placeable.place(x = 0, y = 0)
                    }
                    RevealStripBottomLayoutId -> {
                        if (overlapsHorizontally) {
                            placeable.place(x = 0, y = webViewBottom.roundToInt())
                        } else {
                            placeable.place(x = 0, y = 0)
                        }
                    }
                }
            }
        }
    }
}
