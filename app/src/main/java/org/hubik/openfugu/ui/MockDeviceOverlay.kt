package org.hubik.openfugu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hubik.openfugu.ble.EFuguViewModel
import org.hubik.openfugu.ble.MockDeviceConnection
import org.hubik.openfugu.util.formatHPa

// Handle geometry, shared by the handle itself and the gap in the panel's
// border where the handle attaches (the handle is vertically centered).
private val HandleIconSize = 20.dp
private val HandleVerticalPadding = 16.dp
private val HandleHeight = HandleIconSize + HandleVerticalPadding * 2

/**
 * Floating controls for simulated devices, drawn over every screen while any
 * mock is connected: one vertical pressure slider per simulated device
 * (drag or tap to set pressure, double-tap to zero), a wave toggle per device
 * for hands-free sine patterns, and a shared auto-zero toggle that releases
 * the pressure when the finger lifts (manual mode only — wave is disabled
 * while auto zero is on). Collapsible to a slim handle at the right edge so
 * it never has to block a game.
 */
@Composable
fun MockDeviceOverlay(viewModel: EFuguViewModel) {
    val connections by viewModel.connections.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val mocks = connections.values.filterIsInstance<MockDeviceConnection>()
        .sortedBy { it.address }
    if (mocks.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(true) }
    var autoZero by rememberSaveable { mutableStateOf(true) }
    val panelColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val panelContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Collapse handle. Its right side touches the panel (or the screen
            // edge when collapsed), so the border leaves that edge open.
            Surface(
                color = panelColor,
                contentColor = panelContentColor,
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .borderExceptEnd(borderColor, 1.dp, 12.dp)
            ) {
                Icon(
                    if (expanded) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
                    contentDescription = if (expanded) "Hide simulated device controls"
                        else "Show simulated device controls",
                    modifier = Modifier
                        .padding(vertical = HandleVerticalPadding, horizontal = 2.dp)
                        .size(HandleIconSize)
                )
            }
            if (expanded) {
                // The panel sits flush against the screen edge: square right
                // corners, no border on the right edge.
                Surface(
                    color = panelColor,
                    contentColor = panelContentColor,
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                    // fill = false: take at most the width left next to the
                    // handle, never push past the screen edge — on screens too
                    // narrow for every slider the row scrolls instead. The
                    // border gap makes the handle a seamless part of the panel.
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .borderExceptEnd(borderColor, 1.dp, 12.dp, startEdgeGapHeight = HandleHeight)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            mocks.forEach { mock ->
                                key(mock.address) {
                                    val color = savedDevices
                                        .find { it.address == mock.address }?.colorArgb
                                        ?.let { Color(it.toInt()) }
                                        ?: MaterialTheme.colorScheme.tertiary
                                    MockPressureSlider(
                                        mock = mock,
                                        color = color,
                                        autoZero = autoZero,
                                        onWaveSelected = { autoZero = false }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        FilterChip(
                            selected = autoZero,
                            onClick = {
                                autoZero = !autoZero
                                // Wave and auto zero are mutually exclusive —
                                // enabling either turns the other off. Turning
                                // auto zero on also zeroes everything right away.
                                if (autoZero) mocks.forEach {
                                    it.pattern.value = MockDeviceConnection.Pattern.Manual
                                    it.controlHPa.value = 0.0
                                }
                            },
                            label = { Text("Auto zero", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One vertical pressure fader spanning [MockDeviceConnection.CONTROL_MIN_HPA]..
 * [MockDeviceConnection.CONTROL_MAX_HPA]; the zero line sits at the matching
 * (off-center) height.
 */
@Composable
private fun MockPressureSlider(
    mock: MockDeviceConnection,
    color: Color,
    autoZero: Boolean,
    onWaveSelected: () -> Unit
) {
    val control by mock.controlHPa.collectAsState()
    val pattern by mock.pattern.collectAsState()
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Text(
                " " + mock.address.removePrefix(MockDeviceConnection.ADDRESS_PREFIX),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            formatHPa(control),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(44.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(220.dp)
                .pointerInput(autoZero) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            change.consume()
                            mock.controlHPa.value = sliderValueForY(change.position.y, size.height)
                        },
                        onDragEnd = { if (autoZero) mock.controlHPa.value = 0.0 },
                        onDragCancel = { if (autoZero) mock.controlHPa.value = 0.0 }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { mock.controlHPa.value = 0.0 },
                        onTap = { offset ->
                            mock.controlHPa.value = sliderValueForY(offset.y, size.height)
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val min = MockDeviceConnection.CONTROL_MIN_HPA
                val max = MockDeviceConnection.CONTROL_MAX_HPA
                val trackWidth = 8.dp.toPx()
                val trackLeft = (size.width - trackWidth) / 2f
                val zeroY = (size.height * (max / (max - min))).toFloat()
                val thumbY = (size.height * ((max - control) / (max - min))).toFloat()

                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(trackLeft, 0f),
                    size = androidx.compose.ui.geometry.Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth / 2f)
                )
                // Zero marker
                drawLine(
                    color = trackColor,
                    start = Offset(trackLeft - 6.dp.toPx(), zeroY),
                    end = Offset(trackLeft + trackWidth + 6.dp.toPx(), zeroY),
                    strokeWidth = 1.dp.toPx()
                )
                // Fill from zero to the current value
                drawRoundRect(
                    color = color.copy(alpha = 0.7f),
                    topLeft = Offset(trackLeft, minOf(zeroY, thumbY)),
                    size = androidx.compose.ui.geometry.Size(
                        trackWidth,
                        kotlin.math.abs(zeroY - thumbY)
                    ),
                    cornerRadius = CornerRadius(trackWidth / 2f)
                )
                drawCircle(color = color, radius = 10.dp.toPx(), center = Offset(size.width / 2f, thumbY))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        FilterChip(
            selected = pattern == MockDeviceConnection.Pattern.SineWave,
            onClick = {
                val enablingWave = pattern != MockDeviceConnection.Pattern.SineWave
                mock.pattern.value =
                    if (enablingWave) MockDeviceConnection.Pattern.SineWave
                    else MockDeviceConnection.Pattern.Manual
                // Mutual exclusion: wave switches auto zero off
                if (enablingWave) onWaveSelected()
            },
            label = { Text("Wave", fontSize = 11.sp) }
        )
    }
}

/**
 * Stroke along the top edge, the rounded start corners, and the bottom edge —
 * the end (right) edge stays open, because it touches either the control panel
 * or the screen edge and a line there would double up. A non-zero
 * [startEdgeGapHeight] leaves a vertically centered opening in the start
 * (left) edge, where the collapse handle attaches seamlessly.
 */
private fun Modifier.borderExceptEnd(
    color: Color,
    strokeWidth: Dp,
    cornerRadius: Dp,
    startEdgeGapHeight: Dp = 0.dp
): Modifier =
    drawWithContent {
        drawContent()
        val half = strokeWidth.toPx() / 2f
        val r = cornerRadius.toPx()
        val gap = startEdgeGapHeight.toPx()
        val path = Path().apply {
            moveTo(size.width, half)
            lineTo(r + half, half)
            arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 270f, -90f, false)
            if (gap > 0f) {
                lineTo(half, (size.height - gap) / 2f)
                moveTo(half, (size.height + gap) / 2f)
            }
            lineTo(half, size.height - r - half)
            arcTo(
                Rect(half, size.height - half - 2 * r, half + 2 * r, size.height - half),
                180f, -90f, false
            )
            lineTo(size.width, size.height - half)
        }
        drawPath(path, color, style = Stroke(strokeWidth.toPx()))
    }

/** Map a touch Y inside the track to a pressure value: top = CONTROL_MAX_HPA, bottom = CONTROL_MIN_HPA. */
private fun sliderValueForY(y: Float, heightPx: Int): Double {
    val min = MockDeviceConnection.CONTROL_MIN_HPA
    val max = MockDeviceConnection.CONTROL_MAX_HPA
    val fraction = 1.0 - y / heightPx  // 0 at the bottom, 1 at the top
    return (min + fraction * (max - min)).coerceIn(min, max)
}
