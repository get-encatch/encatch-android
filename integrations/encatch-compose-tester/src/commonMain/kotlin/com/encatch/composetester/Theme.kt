package com.encatch.composetester

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * Shared visual language for the tester app, styled after the modern Uber rider app (and mirroring
 * `encatch-ios-tester/Sources/Theme.swift`): monochrome black/white "ink" palette that inverts
 * cleanly in dark mode, bold typography, pill-shaped buttons and chips, and softly rounded flat
 * gray tiles. Pure presentation — no SDK calls live here.
 */

/** Uber's safety green — used sparingly for positive/selected states. */
val UberGreen = Color(0xFF06C167)

val CardCornerRadius = 16.dp

/** Primary "ink": black in light mode, white in dark mode (Uber's monochrome brand color). */
val ColorScheme.ink: Color get() = onBackground

/** Faint ink wash used for selected rows. */
val ColorScheme.inkSoft: Color get() = onBackground.copy(alpha = 0.08f)

private val LightColors = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF545454),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF6B6B6B),
    secondaryContainer = Color(0xFF000000),
    onSecondaryContainer = Color(0xFFFFFFFF),
    outline = Color(0xFFD6D6D6),
    error = Color(0xFFE11900),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF9E9E9E),
    secondaryContainer = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF000000),
    outline = Color(0xFF3A3A3C),
    error = Color(0xFFFF6E5C),
)

@Composable
fun EncatchTesterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

// MARK: Buttons

/** Solid ink pill button: black on white (inverts in dark mode), fully rounded, bold label. */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = cs.ink,
            contentColor = cs.background,
            disabledContainerColor = cs.onBackground.copy(alpha = 0.18f),
            disabledContentColor = cs.background.copy(alpha = 0.85f),
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 15.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** Gray fill pill button with ink semibold label — Uber's secondary action. */
@Composable
fun SecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fullWidth: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = cs.surfaceVariant,
            contentColor = cs.ink,
            disabledContainerColor = cs.surfaceVariant,
            disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        modifier = if (fullWidth) modifier.fillMaxWidth() else modifier,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Compact pill chip for preset buttons on the Events tab. */
@Composable
fun ChipButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(cs.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.ink)
    }
}

/** Quiet full-width text button for tertiary actions (e.g. "Change API key & setup"). */
@Composable
fun QuietTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color, textAlign = TextAlign.Center)
    }
}

// MARK: Surfaces & fields

/** Flat gray card, Uber-style: no border, no shadow, softly rounded, 16dp inner padding. */
@Composable
fun TesterCard(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** Bold section title above a card — Uber uses strong headlines, not gray small-caps. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(horizontal = 2.dp),
    )
}

/** Small bold caption label shown above a text field. */
@Composable
fun FieldLabel(text: String, required: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        if (required) "$text *" else text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
    )
}

/** Flat gray filled text-field, softly rounded like Uber's "Where to?" input — no outline. */
@Composable
fun FilledField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val cs = MaterialTheme.colorScheme
    // A whisper darker than the card gray so fields stay visible when nested inside a TesterCard.
    val fieldBg = cs.onBackground.copy(alpha = 0.06f)
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        placeholder = {
            if (placeholder.isNotEmpty()) {
                Text(placeholder, fontSize = 15.sp, color = cs.onSurfaceVariant)
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = fieldBg,
            unfocusedContainerColor = fieldBg,
            disabledContainerColor = fieldBg,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = cs.ink,
            focusedTextColor = cs.onBackground,
            unfocusedTextColor = cs.onBackground,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

// MARK: Small pieces

/** Key–value row used on Settings / detail cards. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 14.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onBackground,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** Circle avatar with the user's initials — background-on-ink, monochrome. */
@Composable
fun InitialsAvatar(name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val initials = name.split(" ").filter { it.isNotBlank() }.take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(cs.ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, color = cs.background)
    }
}

/** Brand mark used on Setup/Login heroes: background-color glyph on an ink circle, Uber-flat. */
@Composable
fun BrandMark(size: Dp = 56.dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(cs.ink),
        contentAlignment = Alignment.Center,
    ) {
        Text("e", fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.Bold, color = cs.background)
    }
}
