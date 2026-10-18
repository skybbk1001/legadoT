package io.legado.app.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.appDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.produceIn

/**
 * 生命周期/数据库联动的 Flow 扩展(Android 依赖, 留 app)。
 * 纯协程并发扩展已迁 shared 同包 FlowExtensions.kt。
 */
fun <T> Flow<T>.flowWithLifecycleFirst(
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED
): Flow<T> = callbackFlow {
    if (!lifecycle.currentState.isAtLeast(minActiveState)) {
        firstOrNull()?.let {
            send(it)
        }
    }
    lifecycle.repeatOnLifecycle(minActiveState) {
        this@flowWithLifecycleFirst.collect {
            send(it)
        }
    }
    close()
}

fun <T> Flow<T>.flowWithLifecycleAndDatabaseChange(
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    table: String
): Flow<T> = callbackFlow {
    var update = 0
    val channel = appDb.invalidationTracker
        .createFlow(table)
        .conflate()
        .onEach { update++ }
        .produceIn(this)
    lifecycle.repeatOnLifecycle(minActiveState) {
        if (update == 0) {
            channel.receive()
        }
        this@flowWithLifecycleAndDatabaseChange.collect {
            update = 0
            send(it)
        }
    }
    close()
}

fun <T> Flow<T>.flowWithLifecycleAndDatabaseChangeFirst(
    lifecycle: Lifecycle,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    table: String
): Flow<T> = callbackFlow {
    var update = 0
    val isActive = lifecycle.currentState.isAtLeast(minActiveState)
    val channel = appDb.invalidationTracker
        .createFlow(table, emitInitialState = isActive)
        .conflate()
        .onEach { update++ }
        .produceIn(this)
    if (!isActive) {
        firstOrNull()?.let {
            send(it)
        }
    }
    lifecycle.repeatOnLifecycle(minActiveState) {
        if (update == 0) {
            channel.receive()
        }
        this@flowWithLifecycleAndDatabaseChangeFirst.collect {
            update = 0
            send(it)
        }
    }
    close()
}
