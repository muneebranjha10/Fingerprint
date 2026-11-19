# Fingerprint Pulse — Native Theme Beta 0.3

This version retains Samsung's original enrollment onDraw method, movement, scan scaling, path segment timing, progress, error and completion logic. Only the visual paint, bitmap tint, circular drawing dimensions and percentage typeface are changed for the moving guide in portrait dark mode. The stock fingerprint icon silhouette is preserved. No timer, sensor callback, or simulated progress runs in the enrollment UI.

Enable only Samsung Biometrics (com.samsung.android.biometrics.app.setting) in LSPosed, reboot, enable dark mode, then add a fingerprint through Settings. Disable the module and reboot to restore stock. The app's own screen is instructions only. The video colors are approximated; device compatibility and exact visual match still require on-device validation. Debug builds may have different signing keys across runners, so uninstall the old module first only if the installer reports a signature conflict.

Download the FingerprintPulse-beta artifact from the successful GitHub Actions run corresponding to version 0.3.

0.3: verified that native onDraw uses state 2 for scan progress and state 4 for the completion check. Circle geometry is applied to drawing only and restored after every frame. The percentage font is an approximation, not the original reference font.
