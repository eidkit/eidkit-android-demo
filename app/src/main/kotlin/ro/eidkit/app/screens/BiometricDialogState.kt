package ro.eidkit.app.screens

/**
 * State for the "save credentials" dialog shown after a successful scan.
 *
 * [saveCan], [savePin], [savePin2] are the user's current toggle selections.
 * [scannedCan], [scannedPin], [scannedPin2] are the values just used for the scan —
 * carried here so the save action can write them without reaching back into Input state.
 * [showPin2Row] controls whether the signing-PIN row appears (Signing screen only).
 */
data class SaveDialogState(
    val scannedCan:  String,
    val scannedPin:  String,
    val scannedPin2: String? = null,
    val saveCan:     Boolean,
    val savePin:     Boolean,
    val savePin2:    Boolean = false,
    val showPin2Row: Boolean = false,
)
