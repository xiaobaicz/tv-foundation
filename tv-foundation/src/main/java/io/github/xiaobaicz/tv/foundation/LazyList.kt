package io.github.xiaobaicz.tv.foundation

import android.os.Bundle
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeLayoutState
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.layout.SubcomposeSlotReusePolicy
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun LazyList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    vertical: Boolean = true,
    align: LazyListAlign = LazyListAlign.BothEdge,
    windowPercent: Float = 0.5f,
    itemPercent: Float = 0.5f,
    anim: Boolean = true,
    item: LazyListScope.() -> Unit,
) {
    val state = state as LazyListStateImpl
    state.item()

    val slotReusePolicy = remember { LazyListSlotReusePolicy() }
    val layoutState = remember { SubcomposeLayoutState(slotReusePolicy) }

    val holder = rememberSaveableStateHolder()

    val policy = remember { LazyListMeasurePolicy(holder, state, slotReusePolicy, layoutState) }

    val coroutineScope = rememberCoroutineScope()

    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    val density = LocalDensity.current

    val modifier = modifier
        .onPlaced {
            state.layoutCoordinates = it
        }
        .onFocusedBoundsChanged func@{
            val focusCoordinates = it ?: return@func
            with(state) {
                density.calculateScroll(
                    vertical,
                    coroutineScope,
                    focusCoordinates,
                    align,
                    windowPercent,
                    itemPercent,
                    isLtr,
                    anim,
                )
            }
        }
        .focusRestorer(policy.defaultFocus)
        .focusGroup()

    SubcomposeLayout(
        state = layoutState,
        modifier = modifier,
    ) { const ->
        with(policy) {
            measure(const, vertical, align, windowPercent, itemPercent)
        }
    }
}

@Composable
private fun LazyListSlot(
    modifier: Modifier,
    pos: Int,
    content: @Composable LazyListItemScope.(Int) -> Unit
) {
    val itemState = remember { LazyListItemState() }
    Box(
        modifier = modifier
            .onFocusChanged {
                itemState.hasFocus = it.hasFocus
            },
    ) {
        itemState.content(pos)
    }
}

sealed interface LazyListItemScope {
    val hasFocus: Boolean
}

private class LazyListItemState : LazyListItemScope {
    override var hasFocus by mutableStateOf(false)
}

private class LazyListMeasurePolicy(
    val holder: SaveableStateHolder,
    val state: LazyListStateImpl,
    val slotReusePolicy: LazyListSlotReusePolicy,
    val layoutState: SubcomposeLayoutState,
) {
    private val itemCache = mutableMapOf<Int, Placeable>()

    val defaultFocus = FocusRequester()

    private fun SubcomposeMeasureScope.items(pos: Int, const: Constraints): Placeable {
        return itemCache.getOrPut(pos) {
            val key = state.key(pos)
            val type = state.type(pos)
            slotReusePolicy.slotTypeMap[key] = type
            subcompose(key) {
                holder.SaveableStateProvider(key) {
                    val modifier = if (pos == state.defaultPos)
                        Modifier.focusRequester(defaultFocus)
                    else
                        Modifier
                    LazyListSlot(modifier, pos, state.content)
                }
            }.map {
                it.measure(const)
            }.first()
        }
    }

    fun SubcomposeMeasureScope.emptyResult(const: Constraints): MeasureResult {
        return layout(const.maxWidth, const.maxHeight) {}
    }

    fun SubcomposeMeasureScope.measure(
        const: Constraints,
        vertical: Boolean,
        align: LazyListAlign,
        windowPercent: Float,
        itemPercent: Float,
    ): MeasureResult {
        if (state.count == 0) return emptyResult(const)
        itemCache.clear()
        return when {
            vertical -> measureByColumn(const, align, windowPercent, itemPercent)
            else -> measureByRow(const, align, windowPercent, itemPercent)
        }
    }

    fun SubcomposeMeasureScope.measureByRow(
        const: Constraints,
        align: LazyListAlign,
        windowPercent: Float,
        itemPercent: Float,
    ): MeasureResult {
        val width = const.maxWidth
        val height = const.maxHeight

        val firstPos = state.firstPos
        val firstItem = items(firstPos, const)
        val keyOffset = if (align.hasAlign()) {
            (width * windowPercent - firstItem.width * itemPercent).roundToInt()
        } else {
            0
        }

        while (true) {
            val pos = state.firstPos
            val offset = state.firstOffset + state.scrollOffset + keyOffset
            val item = items(pos, const)
            val right = offset + item.measuredWidth
            if (right >= 0) break
            if (pos + 1 >= state.count) break
            state.firstPos++
            state.firstOffset += item.measuredWidth
        }

        while (true) {
            val pos = state.firstPos
            val offset = state.firstOffset + state.scrollOffset + keyOffset
            val item = items(pos, const)
            val right = offset + item.measuredWidth
            if (right < 0) break
            if (pos - 1 < 0) break
            state.firstPos--
            state.firstOffset -= item.measuredWidth
        }

        val startEdgeOffset = if (align.hasStartEdge()) {
            val offset = state.firstOffset + state.scrollOffset + keyOffset
            if (offset > 0) {
                state.firstOffset -= offset
                -offset
            } else {
                0
            }
        } else {
            0
        }

        val itemMap = mutableMapOf<Placeable, IntOffset>()
        var pos = state.firstPos
        var offset = state.firstOffset + state.scrollOffset + keyOffset
        var maxRight = 0
        while (pos < state.count) {
            val item = items(pos, const)
            itemMap[item] = IntOffset(offset, 0)
            maxRight = max(maxRight, offset + item.measuredWidth)
            if (offset > width) break
            offset += item.measuredWidth
            pos++
        }

        val endEdgeOffset = if (align.hasEndEdge() && startEdgeOffset == 0) {
            if (maxRight < width) {
                state.firstOffset += width - maxRight
                width - maxRight
            } else {
                0
            }
        } else {
            0
        }

        return layout(width, height) {
            for (entry in itemMap) {
                entry.key.placeRelative(entry.value.x + endEdgeOffset, entry.value.y)
            }
        }
    }

    fun SubcomposeMeasureScope.measureByColumn(
        const: Constraints,
        align: LazyListAlign,
        windowPercent: Float,
        itemPercent: Float
    ): MeasureResult {
        val width = const.maxWidth
        val height = const.maxHeight

        val firstPos = state.firstPos
        val firstItem = items(firstPos, const)
        val keyOffset = if (align.hasAlign()) {
            (height * windowPercent - firstItem.height * itemPercent).roundToInt()
        } else {
            0
        }

        while (true) {
            val pos = state.firstPos
            val offset = state.firstOffset + state.scrollOffset + keyOffset
            val item = items(pos, const)
            val bottom = offset + item.measuredHeight
            if (bottom >= 0) break
            if (pos + 1 >= state.count) break
            state.firstPos++
            state.firstOffset += item.measuredHeight
        }

        while (true) {
            val pos = state.firstPos
            val offset = state.firstOffset + state.scrollOffset + keyOffset
            val item = items(pos, const)
            val bottom = offset + item.measuredHeight
            if (bottom < 0) break
            if (pos - 1 < 0) break
            state.firstPos--
            state.firstOffset -= item.measuredHeight
        }

        val startEdgeOffset = if (align.hasStartEdge()) {
            val offset = state.firstOffset + state.scrollOffset + keyOffset
            if (offset > 0) {
                state.firstOffset -= offset
                -offset
            } else {
                0
            }
        } else {
            0
        }

        val itemMap = mutableMapOf<Placeable, IntOffset>()
        var pos = state.firstPos
        var offset = state.firstOffset + state.scrollOffset + keyOffset
        var maxBottom = 0
        while (pos < state.count) {
            val item = items(pos, const)
            itemMap[item] = IntOffset(0, offset)
            maxBottom = max(maxBottom, offset + item.measuredHeight)
            if (offset > height) break
            offset += item.measuredHeight
            pos++
        }

        val endEdgeOffset = if (align.hasEndEdge() && startEdgeOffset == 0) {
            if (maxBottom < height) {
                state.firstOffset += height - maxBottom
                height - maxBottom
            } else {
                0
            }
        } else {
            0
        }

        return layout(width, height) {
            for (entry in itemMap) {
                entry.key.placeRelative(entry.value.x, entry.value.y + endEdgeOffset)
            }
        }
    }
}

private class LazyListSlotReusePolicy : SubcomposeSlotReusePolicy {
    val slotTypeMap = mutableMapOf<Any?, Any?>()

    override fun areCompatible(slotId: Any?, reusableSlotId: Any?): Boolean {
        if (slotId == reusableSlotId) return true
        return slotTypeMap[slotId] == slotTypeMap[reusableSlotId]
    }

    override fun getSlotsToRetain(slotIds: SubcomposeSlotReusePolicy.SlotIdsSet) {
        val typeCount = mutableMapOf<Any?, Int>()
        val iterator = slotIds.iterator()
        while (iterator.hasNext()) {
            val slot = iterator.next()
            val type = slotTypeMap[slot]
            val count = typeCount.getOrPut(type) { 0 }
            if (count == 5) {
                iterator.remove()
                continue
            }
            typeCount[type] = count + 1
        }
    }
}

interface LazyListScope {
    fun items(
        count: Int,
        key: (Int) -> Any = { it },
        type: (Int) -> Any = { },
        span: (Int) -> Int = { 1 },
        content: @Composable LazyListItemScope.(Int) -> Unit,
    )
}

inline fun <T> LazyListScope.items(
    items: List<T>,
    noinline key: (T) -> Any? = { null },
    noinline type: (T) -> Any = { },
    noinline span: (T) -> Int = { 1 },
    crossinline content: @Composable LazyListItemScope.(T) -> Unit,
) {
    items(
        count = items.size,
        key = { key(items[it]) ?: it },
        type = { type(items[it]) },
        span = { span(items[it]) },
        content = { content(items[it]) }
    )
}

@Composable
fun rememberLazyListState(firstPos: Int = 0, firstOffset: Int = 0): LazyListState {
    return rememberSaveable(saver = LazyListStateImpl.StateSaver) {
        LazyListStateImpl(firstPos, firstOffset)
    }
}

sealed interface LazyListState : ScrollableState {
    val firstPos: Int
    val firstOffset: Int

    suspend fun scrollToItem(pos: Int)
}

private class LazyListStateImpl(
    override var firstPos: Int,
    override var firstOffset: Int,
    val defaultPos: Int = firstPos,
) : LazyListState, LazyListScope {
    var layoutCoordinates: LayoutCoordinates? = null

    var scrollOffset by mutableIntStateOf(0)

    private var currentRect = Rect(Offset.Zero, Size.Zero)

    var count by mutableIntStateOf(0)
    lateinit var key: (Int) -> Any
    lateinit var type: (Int) -> Any
    lateinit var span: (Int) -> Int
    lateinit var content: @Composable LazyListItemScope.(Int) -> Unit

    override var isScrollInProgress: Boolean = false

    private val scrollScope = object : ScrollScope {
        override fun scrollBy(pixels: Float): Float {
            scrollOffset -= pixels.roundToInt()
            scrollOffset += firstOffset
            firstOffset = 0
            return pixels
        }
    }

    override fun dispatchRawDelta(delta: Float): Float = delta

    override suspend fun scroll(
        scrollPriority: MutatePriority,
        block: suspend ScrollScope.() -> Unit
    ) {
        scrollScope.block()
    }

    override suspend fun scrollToItem(pos: Int) {
        firstPos = pos
        firstOffset = 0
        scrollOffset++
        scrollOffset = 0
    }

    fun Density.calculateScroll(
        vertical: Boolean,
        coroutineScope: CoroutineScope,
        focusCoordinates: LayoutCoordinates,
        align: LazyListAlign,
        windowPercent: Float,
        itemPercent: Float,
        isLtr: Boolean,
        anim: Boolean
    ) {
        val layoutCoordinates = layoutCoordinates ?: return
        var coordinates: LayoutCoordinates? = focusCoordinates
        while (coordinates != null) {
            val parent = coordinates.parentLayoutCoordinates
            if (layoutCoordinates == parent) break
            coordinates = parent
        }
        val itemCoordinates = coordinates ?: return

        val rect = layoutCoordinates.localBoundingBoxOf(itemCoordinates)
        if (rect == currentRect) return
        currentRect = rect

        when {
            vertical -> calculateScrollByColumn(
                coroutineScope,
                layoutCoordinates,
                itemCoordinates,
                rect,
                align,
                windowPercent,
                itemPercent,
                anim
            )

            isLtr -> calculateScrollByRow(
                coroutineScope,
                layoutCoordinates,
                itemCoordinates,
                rect,
                align,
                windowPercent,
                itemPercent,
                anim
            )

            else -> calculateScrollByRowRtl(
                coroutineScope,
                layoutCoordinates,
                itemCoordinates,
                rect,
                align,
                windowPercent,
                itemPercent,
                anim
            )
        }
    }

    private fun Density.calculateScrollByColumn(
        coroutineScope: CoroutineScope,
        layoutCoordinates: LayoutCoordinates,
        itemCoordinates: LayoutCoordinates,
        rect: Rect,
        align: LazyListAlign,
        windowPercent: Float,
        itemPercent: Float,
        anim: Boolean
    ) {
        if (align.hasAlign()) {
            val keyTop =
                layoutCoordinates.size.height * windowPercent - itemCoordinates.size.height * itemPercent
            if (rect.top != keyTop) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy(rect.top - keyTop, anim)
                }
                return
            }
        } else {
            if (rect.top < 0) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy(rect.top, anim)
                }
                return
            }
            if (rect.bottom > layoutCoordinates.size.height) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy(rect.bottom - layoutCoordinates.size.height, anim)
                }
                return
            }
        }
    }

    private fun Density.calculateScrollByRow(
        coroutineScope: CoroutineScope,
        layoutCoordinates: LayoutCoordinates,
        itemCoordinates: LayoutCoordinates,
        rect: Rect,
        align: LazyListAlign,
        windowPercent: Float,
        itemPercent: Float,
        anim: Boolean
    ) {
        if (align.hasAlign()) {
            val keyLeft =
                layoutCoordinates.size.width * windowPercent - itemCoordinates.size.width * itemPercent
            if (rect.left != keyLeft) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy(rect.left - keyLeft, anim)
                }
                return
            }
        } else {
            if (rect.left < 0) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy(rect.left, anim)
                }
                return
            }
            if (rect.right > layoutCoordinates.size.width) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy(rect.right - layoutCoordinates.size.width, anim)
                }
                return
            }
        }
    }

    private fun Density.calculateScrollByRowRtl(
        coroutineScope: CoroutineScope,
        layoutCoordinates: LayoutCoordinates,
        itemCoordinates: LayoutCoordinates,
        rect: Rect,
        align: LazyListAlign,
        windowPercent: Float,
        itemPercent: Float,
        anim: Boolean
    ) {
        val width = layoutCoordinates.size.width
        if (align.hasAlign()) {
            val keyRight = width * windowPercent - itemCoordinates.size.width * itemPercent
            if ((width - rect.right) != keyRight) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy((width - rect.right) - keyRight, anim)
                }
                return
            }
        } else {
            if ((width - rect.right) < 0) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy(width - rect.right, anim)
                }
                return
            }
            if ((width - rect.left) > layoutCoordinates.size.width) {
                coroutineScope.coroutineContext.cancelChildren()
                coroutineScope.launch {
                    internalScrollBy((width - rect.left) - layoutCoordinates.size.width, anim)
                }
                return
            }
        }
    }

    private suspend fun Density.internalScrollBy(value: Float, anim: Boolean) {
        when {
            !anim -> scrollBy(value)
            value > 0 -> scrollBy(min(step.toPx(), value))
            else -> scrollBy(max(-step.toPx(), value))
        }
    }

    override fun items(
        count: Int,
        key: (Int) -> Any,
        type: (Int) -> Any,
        span: (Int) -> Int,
        content: @Composable LazyListItemScope.(Int) -> Unit,
    ) {
        this.count = count
        this.key = key
        this.type = type
        this.span = span
        this.content = content
    }

    object StateSaver : Saver<LazyListStateImpl, Bundle> {
        private const val KEY_DEFAULT_POS = "0"
        private const val KEY_FIRST_POS = "1"
        private const val KEY_FIRST_OFFSET = "2"
        private const val KEY_SCROLL_OFFSET = "3"

        override fun restore(value: Bundle): LazyListStateImpl? {
            val defaultPos = value.getInt(KEY_DEFAULT_POS)
            val firstPos = value.getInt(KEY_FIRST_POS)
            val firstOffset = value.getInt(KEY_FIRST_OFFSET)
            val state = LazyListStateImpl(firstPos, firstOffset, defaultPos)
            state.scrollOffset = value.getInt(KEY_SCROLL_OFFSET)
            return state
        }

        override fun SaverScope.save(value: LazyListStateImpl): Bundle? {
            val bundle = Bundle()
            bundle.putInt(KEY_DEFAULT_POS, value.defaultPos)
            bundle.putInt(KEY_FIRST_POS, value.firstPos)
            bundle.putInt(KEY_FIRST_OFFSET, value.firstOffset)
            bundle.putInt(KEY_SCROLL_OFFSET, value.scrollOffset)
            return bundle
        }
    }

    companion object {
        private val step = 50.dp
    }
}

@JvmInline
value class LazyListAlign private constructor(val value: Int) {
    companion object {
        val NoAlign = LazyListAlign(0x000)
        val NoEdge = LazyListAlign(0x100)
        val StartEdge = LazyListAlign(0x101)
        val EndEdge = LazyListAlign(0x110)
        val BothEdge = LazyListAlign(0x111)
    }

    fun hasAlign() = (value and NoEdge.value) == NoEdge.value

    fun hasStartEdge() = (value and StartEdge.value) == StartEdge.value

    fun hasEndEdge() = (value and EndEdge.value) == EndEdge.value
}
