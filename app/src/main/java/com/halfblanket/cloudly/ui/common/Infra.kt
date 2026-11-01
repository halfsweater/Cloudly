package com.halfblanket.cloudly.ui.common

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.halfblanket.cloudly.AppContainer
import com.halfblanket.cloudly.CloudlyApp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

val Context.container: AppContainer get() = (applicationContext as CloudlyApp).container

/** App-wide snackbar messages, emitted from ViewModels and shown by the root scaffold. */
object UiMessages {
    data class Message(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

    private val _flow = MutableSharedFlow<Message>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val flow: SharedFlow<Message> = _flow

    fun show(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _flow.tryEmit(Message(text, actionLabel, action))
    }
}

/** Creates a ViewModel scoped to the current navigation entry, built from the app container. */
@Composable
inline fun <reified VM : ViewModel> cloudlyViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Creates a ViewModel scoped to the activity, shared by every screen (e.g. the file browser state). */
@Composable
inline fun <reified VM : ViewModel> activityViewModel(crossinline create: (AppContainer) -> VM): VM {
    val context = LocalContext.current
    val activity = context.findActivity()
    return viewModel(
        viewModelStoreOwner = activity,
        factory = viewModelFactory { initializer { create(context.container) } },
    )
}

tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> error("No activity in context")
}
