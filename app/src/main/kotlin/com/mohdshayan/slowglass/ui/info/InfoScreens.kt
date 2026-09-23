package com.mohdshayan.slowglass.ui.info

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mohdshayan.slowglass.ui.components.AppTopBar
import com.mohdshayan.slowglass.ui.components.SkeletonBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The same privacy policy that is hosted for the store listing, bundled so it reads offline. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val bg = MaterialTheme.colorScheme.background.toArgb()
    Scaffold(topBar = { AppTopBar("Privacy policy", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(bg)
                    settings.javaScriptEnabled = false
                    settings.allowContentAccess = false
                    loadUrl("file:///android_asset/privacy.html")
                }
            },
            modifier = Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background),
        )
    }
}

private data class Licence(val name: String, val holder: String, val licence: String)

private val bundled = listOf(
    Licence("Michroma", "Copyright 2011 The Michroma Project Authors. Designed by Vernon Adams.", "SIL Open Font License 1.1"),
    Licence("Hanken Grotesk", "Copyright 2021 The Hanken Grotesk Project Authors. Hanken Design Co.", "SIL Open Font License 1.1"),
    Licence("AndroidX, Jetpack Compose, CameraX and Room", "Copyright The Android Open Source Project.", "Apache License 2.0"),
    Licence("Kotlin, kotlinx.coroutines and kotlinx.serialization", "Copyright JetBrains s.r.o. and contributors.", "Apache License 2.0"),
)

@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val ofl by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // The licence file wraps at 70 columns; rejoin those lines so it reflows on a phone.
                context.assets.open("OFL.txt").bufferedReader().use { it.readText() }
                    .replace(Regex("(?<=[a-z,;])\n(?=[a-z(])"), " ")
            }.getOrNull()
        }
    }
    Scaffold(topBar = { AppTopBar("Licences", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .widthIn(max = 680.dp),
        ) {
            bundled.forEachIndexed { i, l ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(vertical = 14.dp)) {
                    Text(l.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(l.holder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(l.licence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("SIL Open Font License 1.1", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            val text = ofl
            if (text == null) {
                SkeletonBlock(Modifier.fillMaxWidth().height(120.dp))
            } else {
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
