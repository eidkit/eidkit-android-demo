package ro.eidkit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ro.eidkit.app.ui.theme.ElectricBlue
import ro.eidkit.app.ui.theme.SurfaceBorder
import ro.eidkit.app.ui.theme.SurfaceCard

/**
 * Segmented PIN entry. Shows one box per digit so the expected length is immediately obvious.
 * An invisible [TextField] sits behind the boxes to handle focus and keyboard input.
 *
 * @param label     Displayed above the boxes (e.g. "Authentication PIN — 4 digits")
 * @param maxLength Number of boxes = maximum digits accepted
 */
@Composable
fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    focusRequester: FocusRequester? = null,
    onComplete: (() -> Unit)? = null,
    dismissOnComplete: Boolean = false,
    placeholder: String = "",   // kept for API compat
    masked: Boolean = false,    // show • instead of digits (e.g. for demo recordings)
    labelTrailing: (@Composable () -> Unit)? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Always have a requester we can call from the box tap; merge with any external one.
    val internalRequester = remember { FocusRequester() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = label,
                style    = MaterialTheme.typography.labelLarge,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (labelTrailing != null) labelTrailing()
        }
        Spacer(Modifier.height(8.dp))

        Box {
            // Invisible real text field — captures focus/keyboard behind the boxes.
            // Selection colors are transparent so the handle never leaks through the alpha=0 field.
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor          = Color.Transparent,
                    backgroundColor      = Color.Transparent,
                )
            ) {
            TextField(
                value = value,
                onValueChange = { v ->
                    if (v.all(Char::isDigit) && v.length <= maxLength) {
                        onValueChange(v)
                        if (v.length == maxLength) {
                            onComplete?.invoke()
                            if (dismissOnComplete) focusManager.clearFocus()
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction    = imeAction,
                ),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor   = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor   = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor        = Color.Transparent,
                    unfocusedTextColor      = Color.Transparent,
                    cursorColor             = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .alpha(0f)   // visually hidden, still receives input
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .focusRequester(internalRequester)
                    .onFocusChanged { isFocused = it.isFocused },
            )
            } // CompositionLocalProvider

            // Visual boxes drawn on top — tapping any box re-focuses the hidden field
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                    ) { internalRequester.requestFocus() },
            ) {
                repeat(maxLength) { index ->
                    val isFilled  = index < value.length
                    val isNext    = isFocused && index == value.length
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceCard)
                            .border(
                                width = if (isNext) 2.dp else 1.dp,
                                color = when {
                                    isNext   -> ElectricBlue
                                    isFilled -> MaterialTheme.colorScheme.onSurface
                                    else     -> SurfaceBorder
                                },
                                shape = RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isFilled) {
                            Text(
                                text      = if (masked) "•" else value[index].toString(),
                                style     = MaterialTheme.typography.titleMedium,
                                color     = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}
