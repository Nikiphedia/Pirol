package ch.etasystems.pirol.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings

/**
 * T113: Bestaetigen / Unsicher / Ablehnen auf der Karte.
 * Icons und Farben wie in der SpeciesCard (gruen / orange / error); 56 dp Tap-Target (Feld, Handschuhe).
 */
@Composable
fun MapVerifyButtons(
    status: VerificationStatus,
    onVerify: (VerificationStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    val neutralContainer = MaterialTheme.colorScheme.surfaceVariant
    val neutralContent = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        @Composable
        fun RowScope.item(
            target: VerificationStatus, icon: androidx.compose.ui.graphics.vector.ImageVector,
            description: String, activeColor: Color, activeContent: Color
        ) {
            val active = status == target
            Button(
                onClick = { onVerify(target) },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) activeColor else neutralContainer,
                    contentColor = if (active) activeContent else neutralContent
                )
            ) {
                Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp))
            }
        }
        item(VerificationStatus.CONFIRMED, Icons.Filled.Check, AppStrings.get(R.string.ui_bestaetigen), Color(0xFF4CAF50), Color.White)
        item(VerificationStatus.UNCERTAIN, Icons.AutoMirrored.Filled.Help, AppStrings.get(R.string.ui_unsicher), Color(0xFFFF9800), Color.White)
        item(
            VerificationStatus.REJECTED, Icons.Filled.Close, AppStrings.get(R.string.ui_ablehnen),
            MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError
        )
    }
}
