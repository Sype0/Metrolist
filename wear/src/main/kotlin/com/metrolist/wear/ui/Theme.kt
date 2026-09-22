/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/** Material 3 Expressive for Wear OS; follows the watch's dynamic colours when the system provides them. */
@Composable
fun MetrolistWearTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = remember(context) { dynamicColorScheme(context) ?: ColorScheme() }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
