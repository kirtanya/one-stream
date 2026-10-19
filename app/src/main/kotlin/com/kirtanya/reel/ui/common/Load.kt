package com.kirtanya.reel.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** Runs [block] when [key] changes; [Retry.invoke] runs it again after a failure. */
class Retry(private val counter: MutableState<Int>) {
    operator fun invoke() { counter.value++ }
}

@Composable
fun <T> rememberLoad(key: Any?, block: suspend () -> T): Pair<Load<T>, Retry> {
    val state = remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    val attempt = remember(key) { mutableStateOf(0) }
    LaunchedEffect(key, attempt.value) {
        state.value = Load.Loading
        state.value = try {
            Load.Ready(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e.message ?: "Something went wrong")
        }
    }
    return state.value to Retry(attempt)
}
