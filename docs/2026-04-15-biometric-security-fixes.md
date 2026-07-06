
---

## Real-Device Verification Run — 2026-04-25

**Device:** Xiaomi 25078PC3EG (dew_p_global), Android 15 (SDK 35), portrait 720x1600
**Server:** Odoo 18 via Cloudflare tunnel, admin/admin login confirmed
**Build:** debug APK from commit `09dffde` installed via `adb install -r`

### uiautomator2 Run Results (V01-V24)

| Range | Result |
|-------|--------|
| V01-V20 (existing) | **18/20 PASS**, 1 fail (V05 pre-existing, unrelated), 1 skip |
| **V21** FLAG_SECURE | **PASS** — confirmed via `dumpsys display` showing `FLAG_SECURE` on the device window |
| **V22** PinScreen keypad | NOT TESTED — App Lock + PIN setup automation blocked by multi-step Compose interaction |
| **V23** Deep-link no-account | **PASS** — `pm clear` + intent → no auto-navigation |
| **V24** bg→fg re-auth | **PASS** — manually confirmed: enabled App Lock + biometric → background app → reopen → biometric screen appeared (NOT WebView). Pressing back exited to home (gate did NOT bypass). |

### Live verification of new features

- **C1 FCM registration:** V20 confirms FCM token retrieved + push notification arrives
- **C2 Deep-link host validation:** V17 + V19 + V23 all pass
- **L6 FLAG_SECURE:** Screenshot capture returned blank during entire session — proof the flag works
- **MainActivity FragmentActivity fix:** Biometric prompt actually fires (was silent no-op before commit a6b4a24 work)

### What needs manual on-device verification

1. V22 PinScreen UI — need to visually confirm 0-9 keypad with no submit button
2. Reduce Motion animation timing
3. Logout → unregister token confirmed via Firebase Console
4. Permanent biometric lockout fallback to PIN
