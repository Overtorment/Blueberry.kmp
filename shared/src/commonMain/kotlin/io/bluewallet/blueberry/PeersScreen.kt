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
fun PeersScreen(
    store: PeerSocketsStore,
    headersStore: HeadersProgressStore,
    filtersStore: FiltersProgressStore,
    onOpenSettings: () -> Unit,
) {
    var counts by remember { mutableStateOf(store.get()) }
    var headers by remember { mutableStateOf(headersStore.get()) }
    var filters by remember { mutableStateOf(filtersStore.get()) }
    val uiScope = rememberCoroutineScope()
    DisposableEffect(store) {
        val off = store.subscribe {
            uiScope.launch { counts = store.get() }
        }
        onDispose { off() }
    }
    DisposableEffect(headersStore) {
        val off = headersStore.subscribe {
            uiScope.launch { headers = headersStore.get() }
        }
        onDispose { off() }
    }
    DisposableEffect(filtersStore) {
        val off = filtersStore.subscribe {
            uiScope.launch { filters = filtersStore.get() }
        }
        onDispose { off() }
    }
    Column(
        modifier = Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
    ) {
        Text("Peers")
        Text(formatPeerSockets(counts))
        Text("${counts.known} known")
        Text("Chain tip")
        Text(progressBar(headers.percent, 10))
        Text("${headers.downloaded}/${headers.total}")
        Text("${headers.height} tip")
        if (headers.percent < 100) {
            Text("ETA ${formatEta(headers.etaMs)}")
        }
        Text("Filters DL")
        Text(progressBar(filters.percent, 10))
        Text("${filters.downloaded}/${filters.total}")
        if (filters.percent < 100) {
            Text("ETA ${formatEta(filters.etaMs)}")
        }
        Button(onClick = onOpenSettings) { Text("Settings") }
    }
}
