package com.encatch.androidtester

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.encatch.android.EncatchInlineFormView
import com.encatch.core.Encatch

/** Centered hero used on Setup/Login/EditProfile: brand mark, bold title, secondary caption. */
@Composable
private fun HeroHeader(title: String, caption: String, avatar: (@Composable () -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        if (avatar != null) avatar() else BrandMark()
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
fun SetupScreen(
    initialEnvironment: TesterEnvironment,
    onContinue: (environment: TesterEnvironment, apiKey: String, formId: String, interceptorFormId: String) -> Unit,
) {
    var environment by remember { mutableStateOf(initialEnvironment) }
    var apiKey by remember { mutableStateOf("") }
    var formId by remember { mutableStateOf("") }
    var interceptorFormId by remember { mutableStateOf("") }

    Column(
        Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeroHeader(
            title = "Encatch Tester",
            caption = "Enter your API key and default form id. Saved locally on this device — the same build works for any tester or environment.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Environment")
            TesterCard {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    TesterEnvironment.entries.forEachIndexed { index, env ->
                        SegmentedButton(
                            selected = environment == env,
                            onClick = { environment = env },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = TesterEnvironment.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.background,
                                inactiveContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                inactiveContentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                            icon = {},
                        ) { Text(env.label, fontWeight = FontWeight.SemiBold) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "${environment.apiBaseUrl} · ${environment.webHost}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Credentials")
            TesterCard {
                FieldLabel("API key", required = true)
                Spacer(Modifier.height(6.dp))
                FilledField(apiKey, { apiKey = it }, placeholder = "en_dev_…")
                Spacer(Modifier.height(14.dp))
                FieldLabel("Default form id (feedback config)", required = true)
                Spacer(Modifier.height(6.dp))
                FilledField(formId, { formId = it }, placeholder = "form id")
                Spacer(Modifier.height(14.dp))
                FieldLabel("Interceptor test form id (optional)")
                Spacer(Modifier.height(6.dp))
                FilledField(interceptorFormId, { interceptorFormId = it }, placeholder = "form id")
            }
        }

        PrimaryPillButton(
            text = "Save & continue",
            onClick = { onContinue(environment, apiKey.trim(), formId.trim(), interceptorFormId.trim()) },
            enabled = apiKey.isNotBlank() && formId.isNotBlank(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun LoginScreen(
    savedUsers: List<TestUser>,
    onSelectUser: (TestUser) -> Unit,
    onSaveNewUser: (TestUser) -> Unit,
    onEditProfile: (String) -> Unit,
    selectedUsername: String?,
    onIdentify: () -> Unit,
    onChangeSetup: () -> Unit,
) {
    var showNewUserForm by remember { mutableStateOf(false) }
    var newUsername by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    var newDisplayName by remember { mutableStateOf("") }

    Column(
        Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeroHeader(
            title = "Log in",
            caption = "Mock login — calls Encatch.identifyUser(username). Saved users are local to this tester, independent of the SDK.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Saved users")
            TesterCard {
                if (savedUsers.isEmpty()) {
                    Text(
                        "No saved users yet — add one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                savedUsers.forEach { user ->
                    val selected = selectedUsername == user.username
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.inkSoft
                                else MaterialTheme.colorScheme.secondaryContainer,
                            )
                            .clickable { onSelectUser(user) }
                            .padding(10.dp),
                    ) {
                        InitialsAvatar(name = user.displayName.ifBlank { user.username })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.username, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            if (user.displayName.isNotBlank() || user.email.isNotBlank()) {
                                Text(
                                    listOf(user.displayName, user.email).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.ink
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                if (!showNewUserForm) {
                    QuietTextButton("+ New user", onClick = { showNewUserForm = true }, color = MaterialTheme.colorScheme.ink)
                } else {
                    FilledField(newUsername, { newUsername = it }, placeholder = "Username")
                    Spacer(Modifier.height(10.dp))
                    FilledField(newEmail, { newEmail = it }, placeholder = "Email")
                    Spacer(Modifier.height(10.dp))
                    FilledField(newDisplayName, { newDisplayName = it }, placeholder = "Display name")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryPillButton(
                            text = "Save user",
                            onClick = {
                                onSaveNewUser(TestUser(newUsername.trim(), newEmail.trim(), newDisplayName.trim()))
                                showNewUserForm = false
                                newUsername = ""; newEmail = ""; newDisplayName = ""
                            },
                            enabled = newUsername.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            fillWidth = false,
                        )
                        QuietTextButton("Cancel", onClick = { showNewUserForm = false }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selectedUsername != null) {
                SecondaryPillButton("Edit profile before sign in", onClick = { onEditProfile(selectedUsername) })
            }
            PrimaryPillButton("Identify user", onClick = onIdentify, enabled = selectedUsername != null)
            QuietTextButton("Change API key & setup", onClick = onChangeSetup)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun EditProfileScreen(
    username: String,
    initialEmail: String,
    initialDisplayName: String,
    onSave: (email: String, displayName: String) -> Unit,
    onBack: () -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    var displayName by remember { mutableStateOf(initialDisplayName) }

    Column(
        Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeroHeader(
            title = "Edit profile",
            caption = "@$username",
            avatar = { InitialsAvatar(name = displayName.ifBlank { username }, size = 64.dp) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Profile traits")
            TesterCard {
                FieldLabel("Email")
                Spacer(Modifier.height(6.dp))
                FilledField(email, { email = it }, placeholder = "name@example.com")
                Spacer(Modifier.height(14.dp))
                FieldLabel("Display name")
                Spacer(Modifier.height(6.dp))
                FilledField(displayName, { displayName = it }, placeholder = "Display name")
            }
        }

        PrimaryPillButton("Save & identify", onClick = { onSave(email.trim(), displayName.trim()) })
        QuietTextButton("Back", onClick = onBack)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun HomeScreen(
    userName: String?,
    lastEvent: String,
    interceptorFormId: String?,
    onShowModalForm: () -> Unit,
    onShowPrefilledForm: () -> Unit,
    onShowInterceptorForm: (String) -> Unit,
    onEditProfile: () -> Unit,
) {
    LaunchedEffect(Unit) {
        Encatch.trackScreen("Home")
        Encatch.trackEvent("home_viewed")
    }

    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (!userName.isNullOrBlank()) {
            TesterCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(name = userName, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Signed in as",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(userName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Edit profile",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.ink,
                        modifier = Modifier.clickable(onClick = onEditProfile).padding(4.dp),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Last SDK event")
            TesterCard {
                Text(
                    lastEvent,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Forms")
            TesterCard {
                PrimaryPillButton("Show Form", onClick = onShowModalForm)
                Spacer(Modifier.height(10.dp))
                SecondaryPillButton("Show Form (prefilled)", onClick = onShowPrefilledForm)
                if (!interceptorFormId.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    SecondaryPillButton("Show Form (interceptor test)", onClick = { onShowInterceptorForm(interceptorFormId) })
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private val TRACK_EVENT_PRESETS = listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed")
private val TRACK_SCREEN_PRESETS = listOf("/home", "/dashboard", "/settings", "/dashboard/encatch-test")

/** Wrapping chip row for the preset buttons, plus a custom-value field with an inline action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetCard(
    presets: List<String>,
    fieldLabel: String,
    fieldPlaceholder: String,
    fieldValue: String,
    onFieldChange: (String) -> Unit,
    actionLabel: String,
    onPreset: (String) -> Unit,
    onAction: () -> Unit,
) {
    TesterCard {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset -> ChipButton(preset) { onPreset(preset) } }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        FieldLabel(fieldLabel)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledField(fieldValue, onFieldChange, placeholder = fieldPlaceholder, modifier = Modifier.weight(1f))
            SecondaryPillButton(
                text = actionLabel,
                onClick = onAction,
                enabled = fieldValue.isNotBlank(),
                fillWidth = false,
            )
        }
    }
}

@Composable
fun EventsScreen(onTrackEvent: (String) -> Unit, onTrackScreen: (String) -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Events") }
    var customEvent by remember { mutableStateOf("test_event") }
    var customScreen by remember { mutableStateOf("/dashboard/encatch-test") }

    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("trackEvent presets")
            PresetCard(
                presets = TRACK_EVENT_PRESETS,
                fieldLabel = "Custom event",
                fieldPlaceholder = "event_name",
                fieldValue = customEvent,
                onFieldChange = { customEvent = it },
                actionLabel = "Fire",
                onPreset = onTrackEvent,
                onAction = { onTrackEvent(customEvent.trim()) },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("trackScreen presets")
            PresetCard(
                presets = TRACK_SCREEN_PRESETS,
                fieldLabel = "Custom screen",
                fieldPlaceholder = "/path",
                fieldValue = customScreen,
                onFieldChange = { customScreen = it },
                actionLabel = "Track",
                onPreset = onTrackScreen,
                onAction = { onTrackScreen(customScreen.trim()) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Flat info banner card: ink info glyph + secondary caption. */
@Composable
private fun InfoCard(text: String) {
    TesterCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.ink,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Rounded flat-gray container around the SDK's inline slot view. */
@Composable
private fun InlineSlot(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TesterTheme.CornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        content()
    }
}

@Composable
fun InlineExactScreen(exactFormId: String, onShowExact: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("InlineExact") }
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        InfoCard("Claims \"$exactFormId\" — only renders inline when that exact form id is shown.")
        PrimaryPillButton("Show Exact Form (renders inline below)", onClick = onShowExact)
        // Only one tab's screen is composed at a time in this app (a plain when-based nav, not an
        // IndexedStack), so there's no risk of an offstage tab's inline slot stealing registration —
        // unlike Flutter's tester, no enabled-gating is needed here.
        InlineSlot {
            AndroidView(
                factory = { context -> EncatchInlineFormView(context).apply { formId = exactFormId } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun InlineAnyScreen(onShowWildcard: (String) -> Unit, onTriggerFallback: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("InlineAny") }
    var wildcardFormId by remember { mutableStateOf("") }

    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        InfoCard("Catches any form id not exactly claimed elsewhere.")
        TesterCard {
            FieldLabel("Form id")
            Spacer(Modifier.height(6.dp))
            FilledField(wildcardFormId, { wildcardFormId = it }, placeholder = "form id")
            Spacer(Modifier.height(12.dp))
            PrimaryPillButton(
                "Show Form (renders inline below)",
                onClick = { onShowWildcard(wildcardFormId.trim()) },
                enabled = wildcardFormId.isNotBlank(),
            )
            QuietTextButton(
                "Trigger unmatched form → modal fallback",
                onClick = onTriggerFallback,
                color = MaterialTheme.colorScheme.ink,
            )
        }
        InlineSlot {
            AndroidView(
                factory = { context -> EncatchInlineFormView(context).apply { formId = null } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun SettingsScreen(
    prefs: TesterPrefs,
    onSetLocale: () -> Unit,
    onSetCountry: () -> Unit,
    onChangeSetup: () -> Unit,
) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Settings") }

    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Current configuration")
            TesterCard {
                InfoRow("Environment", prefs.environment.label)
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline)
                InfoRow("Form id", prefs.formId ?: "—")
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline)
                InfoRow("API base URL", prefs.apiBaseUrl ?: "(default)")
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline)
                InfoRow("Web host", prefs.webHost ?: "(default)")
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline)
                InfoRow("Interceptor form id", prefs.interceptorFormId ?: "(none)")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Localization")
            TesterCard {
                SecondaryPillButton("Set Locale → fr-FR", onClick = onSetLocale)
                Spacer(Modifier.height(10.dp))
                SecondaryPillButton("Set Country → FR", onClick = onSetCountry)
            }
        }

        QuietTextButton("Change API key & setup", onClick = onChangeSetup, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(4.dp))
    }
}

/** Full-screen terminal route (Billing / RouteNotFound): centered glyph, bold title, caption. */
@Composable
private fun TerminalRouteScreen(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    caption: String,
    buttonLabel: String,
    onButton: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryPillButton(buttonLabel, onClick = onButton)
    }
}

@Composable
fun BillingScreen(route: String, onBackToHome: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Billing") }
    TerminalRouteScreen(
        icon = Icons.Filled.ShoppingCart,
        iconTint = MaterialTheme.colorScheme.ink,
        title = "Billing",
        caption = "Reached via CTA app_navigate route: \"$route\"",
        buttonLabel = "Back to home",
        onButton = onBackToHome,
    )
}

@Composable
fun RouteNotFoundScreen(route: String, onGoBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("RouteNotFound") }
    TerminalRouteScreen(
        icon = Icons.Filled.Info,
        iconTint = Color(0xFFE8A33D),
        title = "Route not found",
        caption = "The CTA requested an unmapped route: \"$route\"",
        buttonLabel = "Go back",
        onButton = onGoBack,
    )
}
