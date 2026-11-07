package social.tbone.ui

import androidx.compose.runtime.Composable

/** Do not compose interactive or accessible app content behind the startup cover. */
@Composable
internal fun StartupGate(starting: Boolean, cover: @Composable () -> Unit, content: @Composable () -> Unit) {
    if (starting) cover() else content()
}
