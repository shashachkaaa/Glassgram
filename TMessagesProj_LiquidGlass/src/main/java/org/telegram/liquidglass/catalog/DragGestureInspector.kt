package org.telegram.liquidglass.catalog

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastFirstOrNull
import kotlin.math.abs

/**
 * @param claimHorizontal Забирать ли себе горизонтальное движение.
 *
 *   Жест по умолчанию ничего себе не забирает и обрывается, стоит его перехватить
 *   кому-то выше. Для управляющих элементов - тумблера, ползунка, капли в панели - это
 *   неверно: они живут внутри прокручиваемых списков, и список отбирал у них палец, так
 *   что вместо тумблера ехал весь экран.
 *
 *   Забирается только преимущественно горизонтальное движение: все эти элементы
 *   горизонтальные, а вертикальное - это прокрутка, и отнимать её у списка нельзя,
 *   иначе список нельзя будет тянуть, начав с тумблера.
 *
 *   И только после того, как палец ушёл дальше порога различения. Перехваченное
 *   движение отменяет нажатие: забирай мы его с первого же пикселя, кнопка
 *   переставала бы нажиматься от дрожания пальца.
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    claimHorizontal: Boolean = false,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)

        val down = awaitFirstDown(false)
        val drag = initialDown

        onDragStart(down)
        onDrag(drag, Offset.Zero)
        val upEvent =
            drag(
                pointerId = drag.id,
                startPosition = drag.position,
                claimThreshold = if (claimHorizontal) viewConfiguration.touchSlop else Float.MAX_VALUE,
                onDrag = { onDrag(it, it.positionChange()) }
            )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    startPosition: Offset,
    claimThreshold: Float,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        // Куда ведёт палец, считаем от места нажатия, а не по последнему событию:
        // отдельные события мелкие и дрожат, направление по ним не определить
        val total = change.position - startPosition
        if (abs(total.x) > abs(total.y) && abs(total.x) > claimThreshold) {
            change.consume()
        }
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}
