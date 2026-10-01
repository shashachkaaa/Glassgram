package org.telegram.liquidglass.catalog

import androidx.compose.runtime.withFrameNanos

/**
 * В библиотеке это expect/actual для нескольких платформ. У нас платформа одна,
 * поэтому здесь сразу реализация: ждать кадр умеет сам Compose, и тащить ради
 * этого kotlinx-coroutines-android незачем.
 */
suspend fun awaitFrame() {
    withFrameNanos { }
}
