package com.omnitrack.app.ui.components

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun DeleteCountdownButton(
    modifier: Modifier = Modifier,
    countdownSeconds: Int = 2,
    onConfirmed: () -> Unit
) {
    require(countdownSeconds > 0) { "countdownSeconds must be positive" }

    var secondsRemaining by remember { mutableIntStateOf(-1) }
    LaunchedEffect(secondsRemaining) {
        if (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining--
        }
    }

    Button(
        onClick = {
            if (secondsRemaining == 0) onConfirmed()
            else if (secondsRemaining < 0) secondsRemaining = countdownSeconds
        },
        enabled = secondsRemaining <= 0,
        modifier = modifier
    ) {
        Text(
            when {
                secondsRemaining < 0 -> "Start $countdownSeconds-second countdown"
                secondsRemaining > 0 -> "Delete available in ${secondsRemaining}s"
                else -> "Delete permanently"
            }
        )
    }
}
