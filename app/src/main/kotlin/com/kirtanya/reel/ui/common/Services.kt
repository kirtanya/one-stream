package com.kirtanya.reel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.kirtanya.reel.core.tmdb.Provider
import com.kirtanya.reel.core.tmdb.TmdbImages
import com.kirtanya.reel.ui.theme.Tokens

/** "On your services": streaming-service logos in rings (the reference's story row, made useful). */
@Composable
fun ServicesRow(sidePadding: Dp, size: Dp, onOpen: (Provider) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        RowHeader("On your services", sidePadding)
        FixedFocusRow(sidePadding) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = sidePadding, vertical = Tokens.Space.s),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s),
            ) {
                items(Provider.FEATURED, key = { it.id }, contentType = { "service" }) { p ->
                    RingAvatar(TmdbImages.logo(p.logoPath, "w154"), p.name, size, onClick = { onOpen(p) })
                }
            }
        }
    }
}
