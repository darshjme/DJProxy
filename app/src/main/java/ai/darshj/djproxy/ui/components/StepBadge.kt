package ai.darshj.djproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.darshj.djproxy.ui.theme.DjColors

/**
 * The one numbered-step badge used by both Onboarding and Settings instructional flows, so the same
 * "step N / done" instruction never renders in two different badge shapes/colours across surfaces.
 * Canonical style: a cyan CircleShape badge (Onboarding's language, which already supports the
 * done/CheckCircle state Settings' rounded-square version lacked).
 */
@Composable
fun StepBadge(index: Int, done: Boolean, modifier: Modifier = Modifier) {
    if (done) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "done",
            tint = DjColors.Emerald,
            modifier = modifier.size(26.dp),
        )
    } else {
        Text(
            text = index.toString(),
            color = DjColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = modifier
                .size(26.dp)
                .background(DjColors.AccentCyan, CircleShape)
                .padding(top = 2.dp),
        )
    }
}
