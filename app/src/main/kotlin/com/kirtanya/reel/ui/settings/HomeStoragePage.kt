package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.kirtanya.reel.ReelApp
import com.kirtanya.reel.core.homedisk.Badge
import com.kirtanya.reel.core.homedisk.BadgeTone
import com.kirtanya.reel.core.homedisk.DiskTest
import com.kirtanya.reel.core.homedisk.DownloadWhere
import com.kirtanya.reel.core.homedisk.HomeBadges
import com.kirtanya.reel.core.homedisk.HomeDiskState
import com.kirtanya.reel.core.homedisk.RetentionSettings
import com.kirtanya.reel.core.homedisk.TitleWhere
import com.kirtanya.reel.homedisk.TestOutcome
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.GlassIconButton
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.homedisk.AtHomeRow
import com.kirtanya.reel.ui.homedisk.HomeView
import com.kirtanya.reel.ui.homedisk.rememberHomeView
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.launch

/** A page to open from outside Settings (the Download-where sheet, adb); SettingsScreen jumps there. */
object SettingsLink {
    var pending by mutableStateOf<SettingsPage?>(null)
}

/** The Settings row's one line. */
internal fun homeStorageSummary(v: HomeView, isTv: Boolean): String = when {
    !v.setUp -> if (isTv) "Easier to set up on your phone" else "Set up your router's USB disk"
    else -> DiskTest.status(v.disk.state, v.settings.address).substringBefore(" — ").substringBefore(". ")
}

/**
 * Settings → Devices → Home storage (docs/HOME_DISK.md §17.1 phone, §17.2 TV). Everything can be set up
 * before the disk exists; the status says "Not connected" until it does.
 */
internal fun LazyListScope.homeStorageItems(isTv: Boolean) {
    item(key = "home-status") { StatusCard(isTv) }
    item(key = "home-disk") { DiskCard(isTv) }
    if (isTv) item(key = "home-jobs") { TvJobsCard() } else item(key = "home-downloads") { DownloadsCard() }
    item(key = "home-cache") { CacheCard() }
    if (!isTv) item(key = "home-away") { AwayCard() }
}

@Composable
private fun StatusCard(isTv: Boolean) {
    val app = ReelApp.get(LocalContext.current)
    val v = rememberHomeView()
    // Opening the page is one of the moments the disk is checked (§4.5).
    if (com.kirtanya.reel.ui.common.LocalRenderData.current == null) LaunchedEffect(Unit) { app.home.disk.refresh(deep = true) }
    val connected = v.disk.state is HomeDiskState.Connected
    Section(note = if (!v.setUp && isTv) "Easier on your phone: Settings → Home storage, then Send to TV." else null) {
        Text(DiskTest.status(v.disk.state, v.settings.address), style = Tokens.Type.heading, color = if (connected) Tokens.Colors.text else Tokens.Colors.textSecondary)
        val status = DiskTest.status(v.disk.state, v.settings.address)
        v.disk.lastTest?.takeIf { it != status }?.let { Text("Last test: $it", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary) }
        val homeLine = when {
            v.settings.homeFingerprint == null -> "Home network: not saved yet (the first Test at home saves it)"
            v.disk.home?.isHome == true -> "Home network: saved · you're on it now"
            else -> "Home network: saved · you're not on it now"
        }
        Text(homeLine, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
    }
}

@Composable
private fun DiskCard(isTv: Boolean) {
    val context = LocalContext.current
    val app = ReelApp.get(context)
    val v = rememberHomeView()
    val scope = app.appScope
    var address by remember(v.settings.address) { mutableStateOf(v.settings.address) }
    var share by remember(v.settings.share) { mutableStateOf(v.settings.share) }
    var user by remember(v.settings.user) { mutableStateOf(v.settings.user) }
    var password by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmHome by remember { mutableStateOf(false) }
    val testing = v.disk.testing

    fun save() {
        app.home.update { it.copy(address = address.trim(), share = share.trim().trim('/'), user = user.trim()) }
        if (password.isNotEmpty()) {
            app.home.savePassword(password)
            password = ""
        }
    }
    fun test(confirmed: Boolean) {
        save()
        scope.launch {
            when (val o = app.home.disk.test(confirmed)) {
                TestOutcome.NeedsConfirm -> confirmHome = true
                is TestOutcome.Refused -> note = o.line
                is TestOutcome.Done -> note = null
            }
        }
    }

    Section("Disk", note = if (isTv) "Easier on your phone: the TV gets these from it." else "The user and password are the ones set for file sharing on the router.") {
        HomeField("Address", address, { address = it }, KeyboardType.Uri)
        HomeField("Shared folder", share, { share = it }, KeyboardType.Text)
        HomeField("User", user, { user = it }, KeyboardType.Text)
        HomeField(
            "Password", password, { password = it }, KeyboardType.Password, secret = true,
            hint = if (v.passwordSaved) "Saved on this ${if (isTv) "TV" else "phone"}, encrypted. Type to replace it." else "Not saved yet",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            ReelButton(if (testing) "Testing…" else "Test", { test(confirmed = false) }, kind = ButtonKind.Primary, icon = Icons.Rounded.NetworkCheck, enabled = !testing)
            ReelButton("Save", { save(); note = "Saved" }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Check)
            if (!isTv && v.tvPaired) {
                ReelButton("Send to TV", {
                    save()
                    scope.launch { note = app.home.client.sendConfig() }
                }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Send, enabled = v.setUp || password.isNotEmpty())
            }
        }
        note?.let { Text(it, style = Tokens.Type.label, color = Tokens.Colors.accentBright) }
        if (v.settings.homeFingerprint != null) {
            ReelButton("Forget home network", { app.home.disk.forgetHome() }, kind = ButtonKind.Ghost, icon = Icons.Rounded.Delete)
        }
    }

    if (confirmHome) {
        AlertDialog(
            onDismissRequest = { confirmHome = false },
            containerColor = Tokens.Colors.surface1,
            title = { Text("Are you at home?", style = Tokens.Type.heading, color = Tokens.Colors.text) },
            text = {
                Text(
                    "The first Test saves this Wi-Fi as your home network. One Stream only ever signs in to the disk on it, so your password never goes to another network.",
                    style = Tokens.Type.body, color = Tokens.Colors.textSecondary,
                )
            },
            confirmButton = { ReelButton("I'm at home, test", { confirmHome = false; test(confirmed = true) }, kind = ButtonKind.Primary) },
            dismissButton = { ReelButton("Not now", { confirmHome = false }, kind = ButtonKind.Ghost) },
        )
    }
}

@Composable
private fun HomeField(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType, secret: Boolean = false, hint: String? = null) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it, color = Tokens.Colors.textSecondary) } },
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(Tokens.Radius.m),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Tokens.Colors.surface2, unfocusedContainerColor = Tokens.Colors.surface2,
            focusedBorderColor = Tokens.Colors.accentBright, unfocusedBorderColor = Tokens.Colors.hairlineStrong,
            focusedLabelColor = Tokens.Colors.accentBright, unfocusedLabelColor = Tokens.Colors.textSecondary,
        ),
    )
}

@Composable
private fun DownloadsCard() {
    val app = ReelApp.get(LocalContext.current)
    val v = rememberHomeView()
    val s = v.settings
    Section("Downloads", note = "Never allow torrents on a work network: One Stream won't use them there.") {
        Text("When I tap Download", style = Tokens.Type.label, color = Tokens.Colors.text)
        // 4 dp above and below: the 40 dp buttons keep a full 48 dp touch area next to the label and the rows.
        FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            listOf(DownloadWhere.AT_HOME, DownloadWhere.ON_PHONE, DownloadWhere.ASK).forEach { w ->
                ReelButton(if (w == DownloadWhere.ON_PHONE) "On this phone" else w.label, { app.home.update { it.copy(downloadWhere = w) } }, kind = if (s.downloadWhere == w) ButtonKind.Primary else ButtonKind.Ghost)
            }
        }
        ToggleRow("Copy phone downloads to the home disk", "When you're home, finished downloads are copied there too.", s.copyPhoneDownloads) {
            app.home.update { it.copy(copyPhoneDownloads = !it.copyPhoneDownloads) }
        }
        ToggleRow("Download over mobile data", "Off: downloads on this phone wait for Wi-Fi.", s.mobileData) {
            app.home.update { it.copy(mobileData = !it.mobileData) }
        }
        Text("Other Wi-Fi networks: ask first, never at work", style = Tokens.Type.label, color = Tokens.Colors.text)
        val fp = v.network.fingerprint
        val current = s.networks.firstOrNull { it.fingerprint == fp }
        if (v.network.isWifiLike && fp != null && current == null && v.disk.home?.isHome != true) {
            Text("This Wi-Fi is new.", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                ReelButton("Allow this Wi-Fi", { app.home.gate.answer(true) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Check)
                ReelButton("Never here (work)", { app.home.gate.answer(false) }, kind = ButtonKind.Ghost)
            }
        }
        if (s.networks.isEmpty()) Text("None yet. You'll be asked the first time you download on a new Wi-Fi.", style = Tokens.Type.caption, color = Tokens.Colors.textSecondary)
        s.networks.sortedByDescending { it.since }.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                Column(Modifier.weight(1f)) {
                    Text(n.label + if (n.fingerprint == fp) " · this one" else "", style = Tokens.Type.label, color = Tokens.Colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (n.allowed) "Allowed" else "Work · never", style = Tokens.Type.caption, color = if (n.allowed) Tokens.Colors.textSecondary else Tokens.Colors.warn)
                }
                GlassIconButton(Icons.Rounded.Delete, "Forget ${n.label}", { app.home.update { it.forget(n.fingerprint) } })
            }
        }
    }
}

@Composable
private fun CacheCard() {
    val app = ReelApp.get(LocalContext.current)
    val r = rememberHomeView().settings.retention
    fun set(change: (RetentionSettings) -> RetentionSettings) = app.home.update { it.copy(retention = change(it.retention)) }
    Section("Stream cache on the disk", note = "Your library (what you download) is never removed. Only the stream cache is trimmed.") {
        ToggleRow("Keep what the TV plays", "Kept on the disk, so it starts instantly next time.", r.keepTvViewing) { set { it.copy(keepTvViewing = !it.keepTvViewing) } }
        if (r.keepTvViewing) {
            Text("Keep cached titles for", style = Tokens.Type.label, color = Tokens.Colors.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
                RetentionSettings.DAY_CHOICES.forEach { d ->
                    ReelButton(RetentionSettings.daysLabel(d), { set { it.copy(days = d) } }, kind = if (r.days == d) ButtonKind.Primary else ButtonKind.Ghost)
                }
            }
        }
        Text("Use up to ${r.cacheSharePct} % of the disk", style = Tokens.Type.label, color = Tokens.Colors.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
            RetentionSettings.SHARE_CHOICES.forEach { pct ->
                ReelButton("$pct %", { set { it.copy(cacheSharePct = pct) } }, kind = if (r.cacheSharePct == pct) ButtonKind.Primary else ButtonKind.Ghost)
            }
        }
        ToggleRow("Cache when I open a title", "The TV starts caching a title as soon as you open its details.", r.autoCacheDetails) { set { it.copy(autoCacheDetails = !it.autoCacheDetails) } }
    }
}

@Composable
private fun AwayCard() {
    Section("Away from home") {
        Text("Watch your home library from anywhere", style = Tokens.Type.label, color = Tokens.Colors.text)
        Text(
            "Comes with the disk. You'll need the Tailscale app on this phone and on the TV, signed in to your account.",
            style = Tokens.Type.body, color = Tokens.Colors.textSecondary,
        )
    }
}

@Composable
private fun TvJobsCard() {
    val app = ReelApp.get(LocalContext.current)
    val v = rememberHomeView()
    Section("At-home downloads") {
        val jobs = v.tvJobs.sortedByDescending { it.createdAt }
        if (jobs.isEmpty()) Text("None yet. Choose Download → At home on your phone.", style = Tokens.Type.body, color = Tokens.Colors.textSecondary)
        jobs.forEach { j ->
            val badge = HomeBadges.badge(TitleWhere(atHome = j.state), homeSetUp = true) ?: Badge(j.state.name.lowercase(), BadgeTone.PLAIN)
            AtHomeRow(j.label, badge, if (j.active) "Cancel" else null, if (j.active) ({ app.home.agent.cancel(j) }) else null)
        }
    }
}
