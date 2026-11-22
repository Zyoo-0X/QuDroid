/* Added for QuDroid: switch row with an (i) right beside the switch, and a version comparison that understands
 * pre-releases (1.0.0-beta.5 < 1.0.0-beta.6 < 1.0.0). GPL-3.0; see NOTICE.md. */
package app.aemu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.aemu.R

/**
 * A switch row. The small description is not under the name any more: a white (i) sits right beside the switch and
 * opens it in a dialog. The (i) is white on a small disc in the accent colour so it stays visible in light and dark.
 * Without a description there is no (i). The rest of the row flips the switch.
 */
@Composable
internal fun InfoToggle(title: String, info: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (info.isNotEmpty()) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { open = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Info, "Info", Modifier.size(18.dp), tint = Color.White) }
                }
                Switch(checked = checked, onCheckedChange = onChange)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(Icons.Rounded.Info, null) },
            title = { Text(title) },
            text = { Text(info) },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

/** Version order with pre-releases: 1.0.0-beta.5 < 1.0.0-beta.6 < 1.0.0-rc.1 < 1.0.0 < 1.0.1. */
object UpdateVersion {
    private val num = Regex("""\d+""")
    private val token = Regex("""\d+|[A-Za-z]+""")

    private fun split(v: String): Pair<List<Int>, List<String>> {
        val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = clean.substringBefore('-')
        val pre = if ('-' in clean) clean.substringAfter('-') else ""
        // "build-12" style tags have no leading number: use the first number found
        val nums = (Regex("""\d+(?:\.\d+)*""").find(core)?.value ?: "0").split('.').map { it.toIntOrNull() ?: 0 }
        return nums to token.findAll(pre).map { it.value }.toList()
    }

    /** < 0 when [a] is older than [b], 0 when equal, > 0 when newer. */
    fun compare(a: String, b: String): Int {
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = an.getOrElse(i) { 0 }.compareTo(bn.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (ap.isEmpty() && bp.isEmpty()) return 0
        if (ap.isEmpty()) return 1      // a release is newer than its own beta
        if (bp.isEmpty()) return -1
        for (i in 0 until minOf(ap.size, bp.size)) {
            val x = ap[i]; val y = bp[i]
            val xn = x[0].isDigit(); val yn = y[0].isDigit()
            val c = when {
                xn && yn -> x.toLong().compareTo(y.toLong())
                xn -> -1
                yn -> 1
                else -> x.lowercase().compareTo(y.lowercase())
            }
            if (c != 0) return c
        }
        return ap.size.compareTo(bp.size)
    }

    fun isNewer(latest: String, current: String) = compare(latest, current) > 0
}
