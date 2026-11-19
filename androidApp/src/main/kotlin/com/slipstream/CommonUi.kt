package com.slipstream

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DetailRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SpeedGraph(
    samples: List<Long>,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    androidx.compose.foundation.Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val max = samples.maxOrNull()?.coerceAtLeast(1) ?: return@Canvas
        val stepX = size.width / (samples.size - 1)
        val path =
            androidx.compose.ui.graphics
                .Path()
        val fill =
            androidx.compose.ui.graphics
                .Path()
        samples.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v.toFloat() / max) * size.height
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo((samples.size - 1) * stepX, size.height)
        fill.close()
        drawPath(fill, fillColor)
        drawPath(
            path,
            lineColor,
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(width = 2.dp.toPx()),
        )
    }
}
