# Android release signing

The public application ID is `tech.transfur.nunarecorder`.

Release `v0.1.0-beta.1` established the permanent signing identity below:

```text
Subject: CN=NunaRecorder, OU=Research, O=Transfur, C=CN
Algorithm: RSA 4096 / SHA256withRSA
Certificate SHA-256: 2ecea8d2860443cca4cea38cbc5c4d906ab93aaa6bb7831d76619a7b214734a9
```

The keystore and its password are not tracked by Git. The initial local backup is stored under the ignored `.release-signing/` directory, and the encrypted values required by CI are configured as these GitHub Actions Secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Back up the keystore and password separately in secure offline locations. Losing this key prevents future GitHub-distributed versions from updating existing installations.

Public release builds intentionally force shared server credentials to empty strings. Signing Secrets are safe to use during the build because the private key is not packaged in the APK; server passwords are not safe to embed in an APK.
