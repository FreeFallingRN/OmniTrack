package com.omnitrack.app.ui.components

import androidx.compose.animation.splineBasedDecay
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun ProgressWheel(
    entryId: String,
    currentUnit: Double,
    unitName: String,
    enabled: Boolean,
    onUpdateProgress: (Double) -> Unit
) {
    var anchor by remember(entryId) { mutableStateOf(BigDecimal.valueOf(currentUnit)) }
    var offset by remember(entryId) { mutableFloatStateOf(0f) }
    var interacting by remember(entryId) { mutableStateOf(false) }
    var showEditor by remember(entryId) { mutableStateOf(false) }
    var editorText by remember(entryId) { mutableStateOf("") }
    var animationJob by remember(entryId) { mutableStateOf<Job?>(null) }
    val updateProgress by rememberUpdatedState(onUpdateProgress)
    val scope = rememberCoroutineScope()
    val decay = splineBasedDecay<Float>(LocalDensity.current)

    LaunchedEffect(entryId, currentUnit) {
        if (!interacting && !showEditor) {
            anchor = BigDecimal.valueOf(currentUnit)
            offset = 0f
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val slotWidth = maxWidth / 5
        val slotPixels = with(LocalDensity.current) { slotWidth.toPx().coerceAtLeast(1f) }
        val minimumOffset = -(ceil(anchor.toDouble()) * slotPixels)
            .coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()

        fun valueAt(step: Long): BigDecimal? {
            val value = anchor.add(BigDecimal.valueOf(step))
            if (value >= BigDecimal.ZERO) return value
            return if (step.toDouble() == -ceil(anchor.toDouble())) BigDecimal.ZERO else null
        }

        fun settle(velocity: Float = 0f, target: Long? = null, edit: Boolean = false) {
            animationJob?.cancel()
            interacting = true
            animationJob = scope.launch {
                val animation = Animatable(offset.coerceAtLeast(minimumOffset))
                animation.updateBounds(lowerBound = minimumOffset)
                if (target == null && velocity != 0f) {
                    animation.animateDecay(-velocity, decay) { offset = value }
                }
                val step = target ?: (animation.value / slotPixels).roundToLong()
                val targetOffset = (step.toDouble() * slotPixels)
                    .coerceIn(minimumOffset.toDouble(), Float.MAX_VALUE.toDouble()).toFloat()
                animation.animateTo(targetOffset, tween(durationMillis = 180)) { offset = value }
                val selected = valueAt((animation.value / slotPixels).roundToLong())
                if (selected != null && selected.toDouble().isFinite()) {
                    val changed = selected.compareTo(anchor) != 0
                    anchor = selected
                    offset = 0f
                    if (changed) updateProgress(selected.toDouble())
                }
                interacting = false
                if (edit) {
                    editorText = anchor.stripTrailingZeros().toPlainString()
                    showEditor = true
                }
            }
        }

        val centerStep = (offset / slotPixels).roundToLong()
        var velocityTracker by remember(entryId) { mutableStateOf(VelocityTracker()) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clipToBounds()
                .pointerInput(entryId, enabled, slotPixels, minimumOffset) {
                    if (enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                animationJob?.cancel()
                                interacting = true
                                velocityTracker = VelocityTracker()
                            },
                            onDragEnd = { settle(velocityTracker.calculateVelocity().x) },
                            onDragCancel = { settle() },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                offset = (offset - amount).coerceAtLeast(minimumOffset)
                            }
                        )
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(slotWidth)
                    .height(68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            ) {
                Text(
                    text = unitName,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            for (relative in -3..3) {
                val step = centerStep + relative
                val value = valueAt(step) ?: continue
                if (!value.toDouble().isFinite()) continue
                val selected = abs(step.toDouble() - offset / slotPixels) < 0.5
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset { IntOffset(((step.toDouble() * slotPixels) - offset).roundToInt(), 0) }
                        .width(slotWidth)
                        .height(68.dp)
                        .clickable(enabled = enabled) {
                            settle(target = step, edit = selected)
                        }
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = value.stripTrailingZeros().toPlainString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showEditor) {
        val parsed = editorText.toBigDecimalOrNull()
        val number = parsed?.toDouble()
        val valid = parsed != null && parsed >= BigDecimal.ZERO && number != null &&
            number.isFinite() && (parsed.signum() == 0 || number > 0.0)
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("Set $unitName") },
            text = {
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    label = { Text(unitName) },
                    isError = editorText.isNotEmpty() && !valid,
                    supportingText = { if (!valid) Text("Enter a non-negative number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(enabled = valid && enabled, onClick = {
                    if (number != null) {
                        val selected = BigDecimal.valueOf(number)
                        if (selected.compareTo(anchor) != 0) updateProgress(number)
                        anchor = selected
                        offset = 0f
                        showEditor = false
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text("Cancel") }
            }
        )
    }
}
