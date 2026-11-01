package com.halfblanket.cloudly.ui.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.halfblanket.cloudly.R
import com.halfblanket.cloudly.ui.common.AccountConnector
import com.halfblanket.cloudly.ui.settings.AddAccountDialog
import com.halfblanket.cloudly.ui.theme.CloudlyTheme

@Composable
fun WelcomeScreen(connector: AccountConnector, onPrivacy: () -> Unit) {
    var choose by rememberSaveable { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(600), label = "welcome")

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp).alpha(alpha),
    ) {
        Spacer(Modifier.height(36.dp))
        Box(
            Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(96.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("Cloudly", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Every Google Drive you use, in one calm place.",
            style = MaterialTheme.typography.titleMedium,
            color = CloudlyTheme.extra.muted,
        )
        Spacer(Modifier.height(36.dp))
        Feature(Icons.Rounded.ManageAccounts, "All your accounts", "Connect personal, work and school Drives and switch instantly.")
        Feature(Icons.Rounded.SwapHoriz, "Move between accounts", "Copy or move files from one Drive to another in the background.")
        Feature(Icons.Rounded.Storage, "See where space goes", "Storage for every account, large files and clean-up suggestions.")
        Feature(Icons.Rounded.Lock, "Private by design", "Sign in with Google. No passwords, no Cloudly account, no tracking.")
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { choose = true },
            enabled = !connector.busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (connector.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text("Connect Google account", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = onPrivacy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("How Cloudly handles your data", color = CloudlyTheme.extra.muted)
        }
    }

    if (choose) {
        AddAccountDialog(onConfirm = { choose = false; connector.addAccount(it) }, onDismiss = { choose = false })
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = CloudlyTheme.extra.muted)
        }
    }
}
