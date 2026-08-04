package com.encatch.androidtester

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared visual language for the tester app, styled after the modern Uber rider app: monochrome
 * black/white palette that inverts cleanly in dark mode, bold typography, pill-shaped buttons
 * and chips (the "Where to?" idiom), and softly rounded flat gray tiles. Pure presentation — no
 * SDK calls live here. Mirrors `encatch-ios-tester/Sources/Theme.swift`.
 */
object TesterTheme {
    /** Uber's safety green — used sparingly for positive/selected states. */
    val Green = Color(0xFF06C167)
    val CornerRadius = 16.dp
}

/** Primary "ink": black in light mode, white in dark mode (Uber's monochrome brand color). */
val androidx.compose.material3.ColorScheme.ink: Color
    @Composable get() = onBackground

/** 8% ink wash used for selected-row highlights. */
val androidx.compose.material3.ColorScheme.inkSoft: Color
    @Composable get() = onBackground.copy(alpha = 0.08f)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF545454),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = TesterTheme.Green,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF6B6B6B),
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color(0xFF000000),
    outline = Color(0xFFDBDBDB),
    error = Color(0xFFC62828),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFB0B0B0),
    onSecondary = Color(0xFF000000),
    tertiary = TesterTheme.Green,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF9A9A9A),
    secondaryContainer = Color(0xFF2A2A2C),
    onSecondaryContainer = Color(0xFFFFFFFF),
    outline = Color(0xFF3A3A3C),
    error = Color(0xFFEF9A9A),
)

/** MaterialTheme wrapper providing the monochrome light/dark color schemes. */
@Composable
fun EncatchTesterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}

// MARK: - Buttons

/** Solid ink pill button: black on white (inverts in dark mode), fully rounded, bold label. */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.background,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 15.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Gray fill pill button with ink label — Uber's secondary action. */
@Composable
fun SecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onBackground,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
    }
}

/** Compact pill chip for preset buttons on the Events tab. */
@Composable
fun ChipButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** Quiet text button for tertiary actions (e.g. "Change API key & setup"). */
@Composable
fun QuietTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

// MARK: - Surfaces & small pieces

/** Flat gray card, Uber-style: no border, no shadow, softly rounded corners. */
@Composable
fun TesterCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TesterTheme.CornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        content = content,
    )
}

/** Bold section title above a card — Uber uses strong headlines, not gray small-caps. */
@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
}

/** Field caption inside a card, above a text field. */
@Composable
fun FieldLabel(text: String, required: Boolean = false) {
    Text(
        if (required) "$text *" else text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/** Flat gray filled text-field, softly rounded like Uber's "Where to?" input. */
@Composable
fun FilledField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    // Inside a TesterCard the surfaceVariant fill would vanish against the card, so nudge the
    // container toward the background color for contrast in both modes.
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.onBackground,
            focusedTextColor = MaterialTheme.colorScheme.onBackground,
            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/** Key–value row used on Settings / detail cards. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Circle avatar with the user's initials — background-colored text on an ink circle. */
@Composable
fun InitialsAvatar(name: String, size: Dp = 40.dp) {
    val initials = name.split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground),
    ) {
        Text(
            initials,
            color = MaterialTheme.colorScheme.background,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

/** Brand mark used on Setup/Login heroes: background-colored glyph on an ink circle, Uber-flat. */
@Composable
fun BrandMark(size: Dp = 56.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground),
    ) {
        Icon(
            Icons.Filled.Email,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.background,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}
