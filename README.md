# Fingerprint Pulse 0.4 — Native Theme Beta

Samsung Galaxy S23 / One UI 9. Original Samsung enrollment drawing, movement, native scan timing, progress, success checkmark reveal and Done button behavior are preserved. The ring uses colors sampled from the reference recording (right-hand phone at 3.7 seconds). Idle ring is nearly invisible; native fingerprint bitmap is tinted gray at rest and cyan-purple during native scan state 2. Native state 4 completion path is themed blue to purple without changing the success event, check timing or completion geometry. Device-selected font is left untouched, including the percentage and Done button.

Enable only Samsung Biometrics (com.samsung.android.biometrics.app.setting) in LSPosed, reboot, enable Dark mode and add a fingerprint through Settings. No overlay, simulated enrollment or external timers. Disable module and reboot to restore stock. Unsupported fields fall back to original drawing. Completion appearance and live visual match remain unverified on device. The original reference source is unavailable. Debug signing keys can vary; if an update reports a signature mismatch, uninstall the previous module, install this build, re-enable scope and reboot.

Download the FingerprintPulse-beta artifact from the successful GitHub Actions run for version 0.4.
