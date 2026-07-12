package org.hubik.openfugu.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil

enum class TextAnchor { LEFT, CENTER, RIGHT }

/**
 * Draw one line of text into a canvas, positioned like the native
 * Paint.drawText this replaced: [x] is the anchor point per [anchor] and
 * [baselineY] is the text baseline — call sites kept their original
 * coordinates. Multiplatform-safe (TextMeasurer instead of nativeCanvas).
 */
fun DrawScope.drawCanvasText(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    baselineY: Float,
    sizePx: Float,
    color: Color,
    anchor: TextAnchor = TextAnchor.CENTER,
    bold: Boolean = false,
    maxWidthPx: Float? = null
) {
    val layout = measurer.measure(
        text = AnnotatedString(text),
        style = TextStyle(
            color = color,
            fontSize = sizePx.toSp(),
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = if (maxWidthPx != null) {
            Constraints(maxWidth = ceil(maxWidthPx).toInt().coerceAtLeast(0))
        } else {
            Constraints()
        }
    )
    val left = when (anchor) {
        TextAnchor.LEFT -> x
        TextAnchor.CENTER -> x - layout.size.width / 2f
        TextAnchor.RIGHT -> x - layout.size.width
    }
    drawText(layout, topLeft = Offset(left, baselineY - layout.firstBaseline))
}
