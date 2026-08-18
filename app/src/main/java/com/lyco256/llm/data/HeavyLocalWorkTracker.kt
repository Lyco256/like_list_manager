package com.lyco256.llm.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reference-counted signal for local work that can noticeably contend with an interactive screen. */
class HeavyLocalWorkTracker {
    private val lock = Any()
    private var activeCount = 0
    private val mutableIsActive = MutableStateFlow(false)

    val isActive: StateFlow<Boolean> = mutableIsActive.asStateFlow()

    suspend fun <T> track(block: suspend () -> T): T {
        begin()
        return try {
            block()
        } finally {
            end()
        }
    }

    suspend fun <T> trackIf(condition: Boolean, block: suspend () -> T): T =
        if (condition) track(block) else block()

    private fun begin() = synchronized(lock) {
        activeCount += 1
        mutableIsActive.value = true
    }

    private fun end() = synchronized(lock) {
        check(activeCount > 0) { "Heavy local work tracker count underflow" }
        activeCount -= 1
        mutableIsActive.value = activeCount > 0
    }
}
