package com.example.cmpfirst

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.shared.Greeting
import kotlinx.coroutines.launch

@Composable
fun App() {
    val greeting = remember { Greeting() }
    val count by remember { greeting.counterFlow() }.collectAsState(initial = 0)

    val scope = rememberCoroutineScope()
    var echoInput by remember { mutableStateOf("") }
    var echoResult by remember { mutableStateOf("…") }
    var echoPending by remember { mutableStateOf(false) }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.ime)
                .padding(24.dp)
                .padding(bottom = 200.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "KMP ↔ Compose Multiplatform",
                style = MaterialTheme.typography.titleLarge,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("GREET() — SYNC", style = MaterialTheme.typography.labelSmall)
                    Text(greeting.greet("CMP"), style = MaterialTheme.typography.bodyLarge)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("COUNTERFLOW() — KOTLIN FLOW", style = MaterialTheme.typography.labelSmall)
                    Text("$count", style = MaterialTheme.typography.bodyLarge)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("DELAYEDECHO() — SUSPEND FUN (2S)", style = MaterialTheme.typography.labelSmall)
                    OutlinedTextField(
                        value = echoInput,
                        onValueChange = { echoInput = it },
                        placeholder = { Text("Type something…") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                echoPending = true
                                echoResult = greeting.delayedEcho(echoInput, 2000)
                                echoPending = false
                            }
                        },
                        enabled = !echoPending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (echoPending) "Waiting 2s…" else "Send")
                    }
                    Text(echoResult, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
