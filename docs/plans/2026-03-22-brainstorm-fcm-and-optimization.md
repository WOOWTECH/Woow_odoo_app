# Brainstorm: Woow Odoo App FCM & Optimization Project

> **Date:** 2026-03-22
> **Status:** BRAINSTORMING (Pre-planning)
> **Stakeholders:** Boss (Project Vision), Alan (Engineer), Claude Code (AI Assistant)

---

## Boss's Vision Summary

The boss wants to:
1. Add **Firebase Cloud Messaging (FCM)** push notifications (Odoo server → mobile)
2. Improve **UI/UX** (color picker upgrade, Simplified Chinese language)
3. Fix **security issues** (biometric lock on background→foreground)
4. Add **cache clearing** functionality
5. Build **CI/CD pipeline** for automated releases
6. Ensure **iOS/Android parity** (dual platform)
7. Follow a **PRD documentation workflow** per feature

---

## Critical Questions to Align Before Planning

### Q1: iOS App - Does it exist yet?

The boss mentions "確保 iOS/Android 雙平台功能與品牌視覺 100% 一致". However, the current project (`Woow_odoo_app`) is **Android-only** (Kotlin + Jetpack Compose).

- **Is there an existing iOS version?** If yes, where is the repo?
- **If no iOS version exists**, should we:
  - (A) Build a native iOS app in Swift/SwiftUI (separate codebase)?
  - (B) Convert to a cross-platform framework (KMP / Flutter / React Native)?
  - (C) Focus on Android first, then plan iOS later?

> **Impact:** This is the biggest architectural decision. It affects every task in the plan.

**Answer:**
> I think we focus on Android first then port it into iOS.

---

### Q2: Odoo Server Access & Module Development

The plan requires building an **Odoo backend module** (`woow_fcm_push`) in Python. This involves:

- **Do we have access to the Odoo server codebase?** Where is the Odoo repo?
- **What Odoo version** is the server running? (14, 15, 16, 17, 18?)
- **Do we have a development/staging Odoo instance** to test against?
- **Who manages the Odoo server?** Can we deploy custom modules?
- **Is there an existing Odoo module** for push notifications we can extend (e.g., `mail_push`, `web_push_notification`)?

> **Impact:** Without Odoo server access, we cannot implement FCM token binding, Chatter interception, or push notification triggers.

**Answer:**
> 18, we just run the code base on my local computer. Did you remember that?

---

### Q3: Firebase Project Setup

- **Has a Firebase project been created** for this app?
- **Do we have the `google-services.json`** file?
- **Which Firebase plan?** (Free Spark / paid Blaze) - FCM is free but some features require Blaze
- **Who controls the Firebase Console?** (for API keys, credentials)
- **For iOS:** Do we have an Apple Developer account with APNs certificates configured?

> **Impact:** Firebase SDK integration requires actual credentials. We can scaffold the code but can't test without them.

**Answer:**
> We will setup this later, but we surely use the Free Spark, google-services.json will setup later. 

---

### Q4: Push Notification Scope

The boss wants "Chatter/Discuss 新訊息自動推播". Let's clarify the scope:

- **Which Odoo events should trigger push notifications?**
  - [ ] New message in Chatter (on any record)?
  - [ ] @mention specifically?
  - [ ] New Discuss message (direct message)?
  - [ ] New Discuss channel message?
  - [ ] Assigned activity?
  - [ ] Status change on records (e.g., SO confirmed)?
  - [ ] All of the above?

- **Notification content:** What should the notification show?
  - Just "You have a new message"?
  - Full message preview (sender name + first line)?
  - Action buttons (Reply, Mark Read)?

- **Deep Link targets:** When user taps notification, where to navigate?
  - Open the specific record in WebView (e.g., `SO001`)?
  - Open the Discuss conversation?
  - How to construct the Odoo URL for deep linking?

> **Impact:** Each event type requires a different Odoo model hook (`message_post`, `write`, `create`). More events = more server-side code.

**Answer:**
> All. 

---

### Q5: Biometric Lock Bug - Specific Behavior

The boss says "修復並確保生物辨識解鎖在背景切換至前景時穩定觸發".

- **Is this a known bug?** What's the current behavior?
  - Does the biometric prompt not appear at all?
  - Does it appear but fail?
  - Is it a timing issue (appears too late)?
- **Current implementation:** The app uses `AuthViewModel.requiresAuth` + `isAuthenticated` StateFlow. When the app goes to background, does `isAuthenticated` reset to `false`?
- **Expected behavior:** Every time the app comes from background to foreground, show biometric? Or only after a timeout (e.g., 5 minutes)?

> **Impact:** The fix approach depends on whether this is a lifecycle bug, a state management issue, or a missing feature.

**Answer:**
> Make a test plan for digging out what problem we are facing now.

---

### Q6: Color Picker Upgrade Scope

Currently the app has 10 preset colors. The boss wants "HEX 碼輸入或 RGB 調色盤".

- **Which approach is preferred?**
  - (A) Keep 10 presets + add HEX input field
  - (B) Full color wheel/palette picker (like a design tool)
  - (C) HSL slider-based picker
- **Should custom colors persist** across sessions? (Currently yes, stored in EncryptedPrefs)
- **Should users be able to save favorite colors?**

> **Impact:** A full color picker is significantly more complex than a HEX input field. Recommend option (A) as simplest.

**Answer:**
> @docs/woowtech_claude_brand_prompt_library.pdf we want you to strictly follow the style of this document.

---

### Q7: Simplified Chinese (zh-CN) Scope

Currently the app supports English + Traditional Chinese (zh-TW). The boss wants to add Simplified Chinese.

- **Who will provide the translations?**
  - (A) AI-generated from zh-TW → zh-CN conversion?
  - (B) Professional translator?
  - (C) Use the existing zh-TW and convert programmatically (OpenCC)?
- **Are there terminology differences** beyond character conversion? (e.g., "行動裝置" vs "移动设备")

> **Impact:** zh-TW → zh-CN is mostly character conversion but some terms differ. AI can handle ~95% accurately.

**Answer:**
> (a) I want you generate them
---

### Q8: Cache Clearing - What Exactly?

The boss wants "清除快取" to actually work. Currently there's a `clearCache()` in SettingsViewModel.

- **What should be cleared?**
  - [ ] WebView cache (DOM storage, cookies, HTTP cache)?
  - [ ] Room database data?
  - [ ] Downloaded files / images?
  - [ ] App's internal cache directory only?
- **Should clearing cache log out the user?** (If we clear cookies, WebView session dies)
- **Should we show cache size breakdown** (WebView vs Files vs DB)?

> **Impact:** Clearing WebView cookies will force re-login. Need to decide if that's acceptable.

**Answer:**
> Or first, we do an application uiautomator2 test plan to find out what's not working, then when we locate what's the real problem, we start to fix or implement the feature. Before we are going to fix until we feel confident.

---

### Q9: CI/CD - Release Targets

The boss mentions "APK 與 IPA 檔案". Currently GitHub Actions only builds debug APK.

- **Release signing:** Do we have a release keystore for Android? Where is it stored?
- **App Store / Play Store:** Are we distributing via stores or internal distribution?
- **For iOS:** This requires macOS runners (expensive on GitHub Actions) or Fastlane + Xcode
- **Testing in CI:** Should we add unit tests before release builds?
- **Version bumping:** Manual or automated?

> **Impact:** iOS CI/CD is significantly more complex and expensive than Android.

**Answer:**
> APK first.
---

### Q10: Priority & Timeline

The boss has 5 phases. What's the realistic timeline?

- **Phase 1 (FCM SDK):** Requires Firebase project + Odoo server access. Blocked without them.
- **Phase 2 (Push core):** Requires Phase 1 + Odoo module development. Most complex phase.
- **Phase 3 (UI/UX):** Can be done independently in parallel.
- **Phase 4 (Security/Cache):** Can be done independently in parallel.
- **Phase 5 (CI/CD):** Can be done independently.

**Suggested parallel tracks:**
```
Track A (requires backend): Phase 1 → Phase 2 (sequential, blocked on server access)
Track B (Android only):     Phase 3 + Phase 4 (parallel, can start immediately)
Track C (DevOps):           Phase 5 (independent, can start immediately)
```

- **Which track should we start with?**
- **Is iOS in scope for this iteration or future?**

**Answer:**
> A & B then C , we do the feature first, CI / CD could do later

---

### Q11: Cybrosys Odoo App Comparison

The boss mentions "對標業界標準（如 Cybrosys Odoo App）".

- **Have we analyzed the Cybrosys app's features?** What specific features should we match?
- **Is there a feature comparison document?**
- **Should we download and test the Cybrosys app** to understand what "industry standard" means here?

**Answer:**
> Actually I don't know, find out if that app is opensrc or not?
---

### Q12: Vibe Kanban Integration

The boss mentions using https://vibekanban.com/ for task management.

- **Is the Kanban board already set up?** What's the project URL?
- **Should Claude Code integrate with the Kanban API** for status updates?
- **Or is this just a workflow guideline** (we manage tasks manually)?

**Answer:**
> Ignore this, we just add or plan in the @docs/plan folder and give it a good file name.

---

## My Recommendations (for discussion)

1. **Start with Track B (UI/UX + Security fixes)** - no external dependencies, immediate value
2. **iOS decision first** - this fundamentally changes the architecture approach
3. **Get Firebase + Odoo server access** before planning Phase 1-2 in detail
4. **Simplified Chinese** - can be done quickly with AI conversion from zh-TW
5. **Color picker** - recommend HEX input field (option A) for simplicity
6. **Cache clearing** - clear WebView cache + app cache, but NOT cookies/session
7. **Biometric bug** - need to reproduce the bug first before planning a fix

---

## Next Steps

After answering the questions above, we will:
1. Create a detailed implementation plan per phase
2. Set up task tracking (Vibe Kanban or GitHub Issues)
3. Start coding on the non-blocked tracks

**Please review and answer the questions above. Tag answers with Q1, Q2, etc.**
