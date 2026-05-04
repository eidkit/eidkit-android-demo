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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.eidkit.app.R
import ro.eidkit.app.ui.theme.ElectricBlue
import ro.eidkit.app.ui.theme.SurfaceBorder
import ro.eidkit.app.ui.theme.SurfaceCard

/**
 * Segmented PIN entry. Shows one box per digit so the expected length is immediately obvious.
 * An invisible [TextField] sits behind the boxes to handle focus and keyboard input.
 *
 * When [maskable] is true, digits are hidden by default. Each newly typed digit flashes
 * briefly (1 second) before masking. An eye icon lets the user toggle full visibility.
 * A × icon appears (when [onClear] is provided) to empty the field without touching storage.
 *
 * Biometric pre-fill: setting [value] to a multi-character string at once skips the flash
 * (length jump > 1 is treated as programmatic, not manual input).
 *
 * @param label          Displayed above the boxes
 * @param maxLength      Number of boxes = maximum digits accepted
 * @param maskable       Enable masking, eye toggle, and × clear button
 * @param onClear        Optional: called when the user taps ×; caller clears the value
 * @param labelTrailing  Optional extra content placed at the end of the label row
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
    maskable: Boolean = false,
    onClear: (() -> Unit)? = null,
    labelTrailing: (@Composable () -> Unit)? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val internalRequester = remember { FocusRequester() }

    var userVisible by remember { mutableStateOf(false) }
    // Digits at index < maskedUpTo are always masked; digits >= maskedUpTo are visible until timer fires.
    var maskedUpTo by remember { mutableIntStateOf(value.length) }

    // Keyed on value: cancels+relaunches on every change, restarting the 1s window.
    // Single keystroke (+1) → show the new digit(s) until 1s after last keystroke, then mask all.
    // Biometric pre-fill (jump > 1) → mask everything immediately.
    var prevLength by remember { mutableIntStateOf(value.length) }
    LaunchedEffect(value) {
        val cur = value.length
        val singleKeystroke = cur == prevLength + 1
        prevLength = cur
        if (maskable && !userVisible && singleKeystroke) {
            delay(1_000)
        }
        maskedUpTo = cur
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (labelTrailing != null) labelTrailing()
            Spacer(Modifier.weight(1f))
            // Eye toggle
            if (maskable) {
                IconButton(
                    onClick  = { userVisible = !userVisible },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        painter = painterResource(
                            if (userVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                        ),
                        contentDescription = if (userVisible) "Ascunde" else "Arată",
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor     = Color.Transparent,
                    backgroundColor = Color.Transparent,
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
                        .alpha(0f)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .focusRequester(internalRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                )
            }

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
                    val isFilled = index < value.length
                    val isNext   = isFocused && index == value.length
                    val showDot  = maskable && !userVisible && isFilled && index < maskedUpTo

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
                                text      = if (showDot) "•" else value[index].toString(),
                                style     = MaterialTheme.typography.titleMedium,
                                color     = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        } // end Box

        // "Șterge" underlined text link, vertically centred beside the digit boxes
        if (maskable && onClear != null && value.isNotEmpty()) {
            Text(
                text = buildAnnotatedString {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                    append(stringResource(R.string.action_clear))
                    pop()
                },
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication        = null,
                ) {
                    maskedUpTo = 0
                    prevLength = 0
                    onClear()
                },
            )
        }
        } // end Row
    }
}
