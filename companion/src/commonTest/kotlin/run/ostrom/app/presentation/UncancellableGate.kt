// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A suspension a test releases by hand that does not notice a cancellation: the call goes on when the person has already
 * moved elsewhere (another account, another filter), so a stale answer reaches the view model's race guards.
 */
class UncancellableGate<T> {
    private var continuation: Continuation<T>? = null
    val waiting: Boolean get() = continuation != null

    suspend fun await(): T = suspendCoroutine { continuation = it }

    fun release(value: T) = take().resume(value)

    fun fail(error: Exception) = take().resumeWithException(error)

    private fun take(): Continuation<T> = checkNotNull(continuation) { "nobody is waiting" }.also { continuation = null }
}
