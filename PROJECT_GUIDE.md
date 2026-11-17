# Accounts - Project Guide

Standalone Android app. No PC, no server, no Tailscale.

## Structure
- app/src/main/java/com/accmgr/phone/
  - MainActivity     account list, search, create/edit/delete, open
  - BrowserActivity  tabs, address bar, history button, "Save & close"
  - SettingsActivity cache, history, privacy, backup/share/import, updates, erase all
  - Store            all data: accounts, saved logins, history, cache, backup (encrypted)
  - Ui               theme, buttons, dialogs
  - ShareProvider    lets the app share backup / APK through the share sheet
  - Updater          optional GitHub Releases updates (repo + token in Settings)
- app/keystore.p12   signing key - keep the same so updates install over the old app
- .github/workflows/build.yml  builds the APK and publishes a Release

## How data works
- accounts.json            list of accounts (filesDir)
- sessions/<id>.zip        saved login/web data per account (filesDir)
- history/<id>.json        browsing history per account (filesDir)
- Opening an account: its zip is restored into the WebView, the browser runs in a separate process.
- Save & close: WebView data is zipped back into the account, then the WebView is wiped.
- Clear cache never touches logins. Cache folders are never saved inside sessions.

## Sharing with someone
1. Settings -> Share this app (sends the APK)
2. Settings -> Share backup (password-protected, AES-256-GCM) 
3. On the other phone: install the APK, Settings -> Import backup, enter the password.
