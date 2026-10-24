package social.tbone.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** App-wide compact-layout switch shared by the feed, navigation and toolbox surfaces. */
val LocalUiDeclutter = staticCompositionLocalOf { false }
