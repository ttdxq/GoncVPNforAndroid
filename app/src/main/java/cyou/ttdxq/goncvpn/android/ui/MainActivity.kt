package cyou.ttdxq.goncvpn.android.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cyou.ttdxq.goncvpn.android.core.GoncVpnService
import cyou.ttdxq.goncvpn.android.data.SettingsStore
import cyou.ttdxq.goncvpn.android.data.LogRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.firstOrNull
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

class MainActivity : ComponentActivity() {

    private val vpnPrepareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VpnControlScreen(settingsStore, onStartVpn = {
                        prepareVpn()
                    }, onStopVpn = {
                        stopVpnService()
                    })
                }
            }
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            // Use first() to get current value once, instead of collect() which keeps listening
            val p2pSecret = settingsStore.p2pSecret.firstOrNull() ?: ""
            val routeCidrs = settingsStore.routeCidrs.firstOrNull() ?: ""

            val intent = Intent(this@MainActivity, GoncVpnService::class.java).apply {
                action = GoncVpnService.ACTION_START
                putExtra(GoncVpnService.EXTRA_P2P_SECRET, p2pSecret)
                putExtra(GoncVpnService.EXTRA_ROUTE_CIDRS, routeCidrs)
            }
            try {
                // In Android 12+, we must catch potential exceptions if startForegroundService 
                // is called from background (though here it is UI click).
                startForegroundService(intent) 
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to start VPN: ${e.message}", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, GoncVpnService::class.java).apply {
            action = GoncVpnService.ACTION_STOP
        }
        startService(intent)
    }
}

@Composable
fun VpnControlScreen(settingsStore: SettingsStore, onStartVpn: () -> Unit, onStopVpn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val p2pSecret by settingsStore.p2pSecret.collectAsState(initial = "")
    val routeCidrs by settingsStore.routeCidrs.collectAsState(initial = "")
    val logs = remember { mutableStateListOf<String>() }
    
    LaunchedEffect(Unit) {
        LogRepository.logs.collect { log ->
            logs.add(0, log) // Add to top
            if (logs.size > 200) logs.removeRange(200, logs.size)
        }
    }
    
    var secretInput by remember { mutableStateOf(p2pSecret) }
    var cidrsInput by remember { mutableStateOf(routeCidrs) }
    
    // Update local state when flow emits new values (initial load)
    LaunchedEffect(p2pSecret) { if (secretInput.isBlank()) secretInput = p2pSecret }
    LaunchedEffect(routeCidrs) { if (cidrsInput.isBlank()) cidrsInput = routeCidrs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Gonc VPN Config", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = secretInput,
            onValueChange = { 
                secretInput = it
                scope.launch { settingsStore.setP2pSecret(it) }
            },
            label = { Text("P2P Secret Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = cidrsInput,
            onValueChange = { 
                cidrsInput = it
                scope.launch { settingsStore.setRouteCidrs(it) }
            },
            label = { Text("Route CIDRs (one per line)") },
            modifier = Modifier.fillMaxWidth().height(150.dp),
            maxLines = 10
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onStartVpn) {
                Text("Start VPN")
            }
            Button(onClick = onStopVpn, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Stop VPN")
            }
        }
        
        Text("Logs:", style = MaterialTheme.typography.titleMedium)
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                logs.forEach { log ->
                    Text(text = log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}
