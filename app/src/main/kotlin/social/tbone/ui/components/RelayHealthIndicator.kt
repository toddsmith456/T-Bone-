package social.tbone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun RelayHealthDot(
    connected: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val health = relayHealth(connected, total)
    val color = when (health) {
        RelayHealth.OFFLINE -> Color(0xFFF44336)
        RelayHealth.LOW -> Color(0xFFFF9800)
        RelayHealth.MEDIUM -> Color(0xFFFFEB3B)
        RelayHealth.HIGH -> Color(0xFF4CAF50)
    }
    val label = when (health) {
        RelayHealth.OFFLINE -> "No relays connected"
        RelayHealth.LOW -> "Less than one-third of relays connected: $connected of $total"
        RelayHealth.MEDIUM -> "Between one-third and three-quarters of relays connected: $connected of $total"
        RelayHealth.HIGH -> "More than three-quarters of relays connected: $connected of $total"
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label. Open relay settings." },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}
