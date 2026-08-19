package io.bluewallet.blueberry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun PeersScreen(store: PeerSocketsStore, onOpenSettings: () -> Unit) {
    var counts by remember { mutableStateOf(store.get()) }
    val uiScope = rememberCoroutineScope()
    DisposableEffect(store) {
        val off = store.subscribe {
            uiScope.launch { counts = store.get() }
        }
        onDispose { off() }
    }
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
    ) {
        Text("Peers")
        Text(formatPeerSockets(counts))
        Text("${counts.known} known")
        Button(onClick = onOpenSettings) { Text("Settings") }
    }
}
