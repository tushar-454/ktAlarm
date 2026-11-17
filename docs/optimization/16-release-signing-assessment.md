# Phase 3 — Release signing assessment

## Independently observed identity

`android/app/build.gradle` release explicitly selects `signingConfigs.debug`; its keystore is tracked. No credentials or signing configuration were changed. Both independently built APKs pass `apksigner verify --verbose --print-certs` using APK Signature Scheme v2. Certificate subject: Android Debug. Public certificate SHA-256:

`fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`

This matches Phase 2. Evidence: `android/build/phase3/evidence/{universal,arm64}-certificate.txt`; artifact hashes in 14. Repeatable **local** identity is established. Secure production identity is not. A release build can be non-debuggable while debug-signed; `run-as` restrictions do not fix signing exposure.

A committed debug private identity is unsuitable for trusted distribution: anyone possessing that identity can sign a same-package replacement accepted as an update, subject to normal Android install authorization/version rules. Debug certificates are unsuitable for Play production upload. Do not solve this by silently replacing the key: existing installations may then reject updates and uninstalling would lose alarms/media.

## EAS and installed identity: UNVERIFIED

`eas.json` preview = internal APK, production = AAB, version source = local. Neither profile specifies `credentialsSource`; the [EAS schema](https://docs.expo.dev/eas/json/#credentialssource) defaults to remote credentials. This describes configuration intent, not the certificate on an actual EAS artifact. The connected Expo build-list request for project `9ea747a5-2da3-457d-a036-915e78be149a` failed with **Entity Not Authorized**. No remote artifact or credential was accessed. No device was attached, so no installed identity was obtained. Neither EAS injection nor update compatibility can be inferred from local Gradle alone.

## Secure verification handoff

1. Using an authorized Expo account, open this project's completed Android build and download its APK; record build ID/profile/commit. Optionally use `npx eas-cli@latest build:list --platform android --limit 3`. Do not generate/rotate credentials in response to a prompt.
2. Run `$ANDROID_HOME/build-tools/37.0.0/apksigner verify --verbose --print-certs /path/to/eas.apk` and the same command on the actually distributed APK. Retain public SHA-256 fingerprints only.
3. On a connected test device, run `adb shell pm path com.imtushar454.ktAlerm`. Pull the returned base APK path using `adb pull /returned/path/base.apk /tmp/ktalerm-installed-base.apk`, then verify its certificate. For split installs inspect the base plus relevant split delivery separately.
4. For Play App Signing, compare the installed certificate with Play Console's **app signing** certificate. An AAB/upload certificate may differ legitimately; record the upload certificate separately. For sideloading, require compatible signing identity/lineage before any update. Check package/version/ABI too.
5. Preserve the existing distributed identity. If it is the debug identity, request an explicit signing/migration decision; no replacement key or uninstall is authorized by this report. If a secure identity already exists, have its owner configure its reuse in a separately authorized change and safeguard recovery/access.
6. Only after compatibility is demonstrated, perform the consented update test in report 15. Current versionCode is 1; check installed version before updating. Never use a downgrade flag to bypass version incompatibility. A future store release must use an appropriate increasing versionCode.

No installation/update was attempted. Production signing and data-preserving update certification remain blocking for distribution.
