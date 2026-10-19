package com.kirtanya.reel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.kirtanya.reel.ui.theme.Tokens

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Tokens.Colors.accent)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(Tokens.Space.xl), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.l)) {
            Text(message, style = Tokens.Type.body, color = Tokens.Colors.textMuted, textAlign = TextAlign.Center)
            ReelButton("Try again", onRetry, icon = Icons.Rounded.Refresh)
        }
    }
}

/** Renders a [Load]: spinner, error with retry, or [content]. */
@Composable
fun <T> LoadView(load: Load<T>, retry: Retry, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(load.message, { retry() }, modifier)
        is Load.Ready -> content(load.value)
    }
}
