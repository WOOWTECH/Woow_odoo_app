# E2E Production Test Results

> **Date:** 2026-03-23 10:52:35
> **Device:** dew_p_global (SDK 35)
> **Result:** **21 passed, 3 failed** out of 24

| # | Result | Test |
|---|--------|------|
| | PASS | E2E-01a: FCM push sent for chatter message (200 OK) |
| | PASS | E2E-01b: Notification appeared in shade |
| | PASS | E2E-01c: Tapping notification opens app |
| | PASS | E2E-02a: FCM push sent for Discuss DM (200 OK) |
| | PASS | E2E-02b: Discuss notification appeared in shade |
| | PASS | E2E-03a: FCM push sent for @mention (200 OK) |
| | PASS | E2E-03b: @Mention notification appeared in shade |
| | PASS | E2E-04a: FCM push sent for activity (200 OK) |
| | PASS | E2E-04b: Activity notification appeared in shade |
| | PASS | E2E-05a: FCM push sent with contacts deep link |
| | PASS | E2E-05b: Deep link opened app with Odoo content |
| | PASS | E2E-06a: App goes to main screen (app lock not enabled — enable in Settings to test) |
| | PASS | E2E-06b: LifecycleEventEffect code present (verified in unit tests) |
| | PASS | E2E-07a: Brand preset colors section visible |
| | PASS | E2E-07b: 10 accent colors section visible |
| | FAIL | E2E-07c: HEX input field (#RRGGBB) available |
| | PASS | E2E-07d: Apply color → dialog closes, returns to Settings |
| | FAIL | E2E-08a: 简体中文 option found in language picker |
| | FAIL | E2E-09a: Clear Cache button found |
| | PASS | E2E-10a: FCM push with Chinese content sent |
| | PASS | E2E-10b: Sender name '陳小明' visible in notification |
| | PASS | E2E-10c: Message preview visible in notification |
| | PASS | E2E-11a: 3 chatter notifications posted (4 found) |
| | PASS | E2E-11b: Notifications grouped by 'chatter' event type (4 in group) |

