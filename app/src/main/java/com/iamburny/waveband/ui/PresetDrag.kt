package com.iamburny.waveband.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Rearranging preset tiles by dragging one onto another: the two swap places, and dropping on an
 * empty slot moves the preset there. Only tiles that are [droppable] can be dropped on.
 */
@Stable
class PresetDragState(internal val slotCount: Int, internal val droppable: (Int) -> Boolean, private val onSwap: (Int, Int) -> Unit) {
    private val bounds = HashMap<Int, Rect>()

    /** The slot being dragged, or null. */
    var dragging by mutableStateOf<Int?>(null)
        private set
    internal var offset by mutableStateOf(Offset.Zero)
        private set

    /** The slot the dragged tile would land on if dropped now. */
    val target: Int? by derivedStateOf {
        val from = dragging ?: return@derivedStateOf null
        val centre = bounds[from]?.center?.plus(offset) ?: return@derivedStateOf null
        bounds.entries.firstOrNull { (slot, rect) -> slot != from && rect.contains(centre) }?.key?.takeIf(droppable)
    }

    internal fun place(slot: Int, rect: Rect) {
        if (slot != dragging) bounds[slot] = rect
    }

    internal fun start(slot: Int) {
        offset = Offset.Zero
        dragging = slot
    }

    internal fun drag(delta: Offset) {
        offset += delta
    }

    internal fun end(drop: Boolean) {
        val from = dragging
        val to = target
        dragging = null
        offset = Offset.Zero
        if (drop && from != null && to != null) onSwap(from, to)
    }

    internal fun swapWithNeighbour(slot: Int, direction: Int) {
        val other = slot + direction
        if (other in 0 until slotCount && droppable(other)) onSwap(slot, other)
    }
}

@Composable
fun rememberPresetDragState(slotCount: Int, droppable: (Int) -> Boolean = { true }, onSwap: (Int, Int) -> Unit): PresetDragState {
    val canDrop by rememberUpdatedState(droppable)
    val swap by rememberUpdatedState(onSwap)
    return remember(slotCount) { PresetDragState(slotCount, { canDrop(it) }) { from, to -> swap(from, to) } }
}

/**
 * Lets the tile for [slot] be dragged onto another, when it holds a preset ([enabled]). A drag
 * has to start before a long-press would, so a long-press still saves what's playing; a tap
 * still plays the preset, as a drag only starts once the finger has moved.
 */
fun Modifier.presetDrag(state: PresetDragState, slot: Int, enabled: Boolean, label: (Int) -> String): Modifier {
    val lifted = state.dragging == slot
    val tracked = onGloballyPositioned { state.place(slot, it.boundsInRoot()) }
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer {
            if (state.dragging == slot) {
                translationX = state.offset.x
                translationY = state.offset.y
                scaleX = 1.05f
                scaleY = 1.05f
                shadowElevation = 16.dp.toPx()
            }
        }
    if (!enabled) return this.then(tracked)
    return this.then(tracked)
        .semantics {
            customActions = listOfNotNull(
                (slot - 1).takeIf { it >= 0 && state.droppable(it) }?.let { CustomAccessibilityAction("Move to ${label(it)}") { state.swapWithNeighbour(slot, -1); true } },
                (slot + 1).takeIf { it < state.slotCount && state.droppable(it) }?.let { CustomAccessibilityAction("Move to ${label(it)}") { state.swapWithNeighbour(slot, 1); true } },
            )
        }
        .pointerInput(state, slot) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val moved = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                // Held long enough to count as a long-press: that saves instead.
                if (moved.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                state.start(slot)
                state.drag(moved.position - down.position)
                val dropped = try {
                    drag(moved.id) { change ->
                        state.drag(change.positionChange())
                        change.consume()
                    }
                } catch (e: Throwable) {
                    state.end(drop = false)
                    throw e
                }
                state.end(drop = dropped)
            }
        }
}
