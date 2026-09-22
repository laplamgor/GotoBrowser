package com.antest1.gotobrowser.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.antest1.gotobrowser.R

@Composable
fun ControlLayout(
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onRefreshClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHelpClick: () -> Unit,
    toolbarContent: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Main Toolbar (Adaptive)
        AdaptiveFloatingToolbar(
            visible = visible,
            onVisibleChange = onVisibleChange,
            fab = {
                FloatingActionButton(
                    onClick = onRefreshClick,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                    modifier = Modifier
                        .size(56.dp)
                        .glassyStyle(CircleShape)
                ) {
                    Icon(
                        painterResource(id = R.drawable.refresh_icon), 
                        contentDescription = "Refresh",
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
            content = toolbarContent
        )

        // Top Right Buttons (Settings, Help) - Smaller & Subtle
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onHelpClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painterResource(id = R.drawable.help_icon), 
                        contentDescription = "Help", 
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painterResource(id = R.drawable.settings), 
                        contentDescription = "Settings", 
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
