# PH-Gov Issuance Watch (Android)

Reads the official CSC, DBM, DILG, COA, DOF, BIR and Official Gazette pages directly from
your phone, about every 6 hours (even when the app is closed), and sends a notification when
a new issuance or proclamation appears.

Honest limits
- This project was written but NOT compiled or run on a phone before you received it.
  If the online build fails, send the error text back and it can be fixed.
- Some sites may still refuse both ways of reading them (plain download, then the in-app
  browser). The Updates tab shows "Blocked" for those. Open them with the Open button.
- The first check of each site only saves what is already posted. New items appear from the
  second check on.
- Android decides exactly when background checks run; it can delay them when the battery
  saver is on. "Check all sites now" always works.
- Source pages and what counts as "new" are generic (new links that look like issuances).
  Some pages (DILG, DOF home pages) list news, so they can report news items as new.

## Build the APK online (no programming needed)

1. Make a free account at github.com and create a NEW repository. Choose PRIVATE.
2. Upload everything from this folder to the repository (Add file > Upload files).
   The hidden folder ".github" must be included. If your computer hides it, instead use
   Add file > Create new file, type  .github/workflows/build.yml  as the name, and paste the
   contents of build.yml from this folder.
3. Open the repository's "Actions" tab. Open "Build APK" and press "Run workflow".
   (It also runs by itself when you upload files.)
4. After about 5-10 minutes, open the finished run and download the artifact
   "PH-Gov-Issuance-Watch-apk". Unzip it to get app-debug.apk.
5. Copy app-debug.apk to the phone and install it. Allow "install unknown apps" if asked.
   Uninstall the older copy first. Allow notifications when the app asks.

Every build uses the same signing key (app/debug.keystore), so later builds install over
the previous one. Keep the repository private.

## Build with Android Studio instead
Open this folder in Android Studio, let it sync, then Build > Build APK(s).

## Changing the pages that are checked
Edit app/src/main/java/ph/govwatch/issuance/Sources.java (id, label, address) and rebuild.
The CSC, DBM, DILG, COA, DOF, BIR and Gazette addresses are the ones from the earlier app
(the Gazette ones were found by search but could not be opened to confirm). The LawPhil
address (the 2026 proclamations index) was NOT verified. The status list shows
"Could not load" if an address is wrong.
