# Installing BrightSudoku on a Light Phone III

## Via BrightMarket

Easiest route. Open **BrightMarket** on the phone and install BrightSudoku from
there; it handles updates too. If you don't have BrightMarket yet, get it from
[gi-os.github.io/brightmarket-index/browse.html](https://gi-os.github.io/brightmarket-index/browse.html).

## From a release

1. Grab `BrightSudoku-<version>.apk` from
   [Releases](https://github.com/gi-os/BrightSudoku/releases).
2. Enable USB debugging on the phone.
3. `adb install -r BrightSudoku-<version>.apk`

LightOS will warn that the tool isn't signed by Light. That's expected — it's
signed with a personal sideload key. Accept and it installs.

### Automatic updates

Track this repository in [Obtainium](https://github.com/ImranR98/Obtainium) with
the APK filter `BrightSudoku-.*\.apk`.

### About the signing key

Android only accepts an update signed by the same key that installed the app, so
every release here uses one sideload keystore. Two consequences:

- Updates must come from this repository. An APK from anywhere else, including a
  build you make yourself, cannot install over it — you'd have to uninstall
  first, which wipes the board you had going.
- If Light ever signs and distributes this tool officially, that APK will have a
  different signature and will also need a clean install.

Each release publishes the certificate fingerprint and a `.sha256` alongside the
APK if you want to verify what you're installing.

## Building it yourself

This repository is the light-sdk tree with the game in `tool/`, so the SDK builds
from source and there's nothing extra to check out.

```sh
git clone https://github.com/gi-os/BrightSudoku.git
cd BrightSudoku
./gradlew :tool:assembleDebug
./gradlew :tool:testDebugUnitTest      # 46 tests
```

Debug builds are signed with the SDK's committed development key, so they install
fine over each other but not over a release build.

Resolving the SDK's keyboard dependency needs GitHub Packages read access —
the registry will not serve even a public artifact anonymously. Add a token to
`local.properties`:

```
gpr.user=<your github username>
gpr.key=<a PAT with read:packages>
```

CI needs nothing set up: it falls back to the `GITHUB_TOKEN` every Actions run is
given, which is enough for a public package. `GH_PACKAGES_USER` and
`GH_PACKAGES_TOKEN` still take precedence if the repository has them, which is
the escape hatch if the keyboard ever goes private.

## Running against the emulator instead

Set `serverPackage = "com.thelightphone.sdk.emulator"` in `tool/lighttool.toml`,
then follow `docs/system_app` to install the LightOS emulator app as a system app
on an AVD. An AVD close to the real device: 1080×1240, 3.92", API 34, no Google
Play Services.

## Cutting a release

```sh
# bump versionName and versionCode in tool/lighttool.toml first
git tag v1.0.0 && git push origin v1.0.0
```

The release workflow refuses to run if the tag doesn't match `versionName`.
It needs these repository secrets:

| Secret | What it is |
| --- | --- |
| `BRIGHTSUDOKU_KEYSTORE_BASE64` | `base64 -w0 brightsudoku-release.jks`. Optional — without it releases are signed with the SDK development key. |
| `BRIGHTSUDOKU_KEYSTORE_PASSWORD` | keystore password |
| `BRIGHTSUDOKU_KEY_ALIAS` | key alias inside the keystore |
| `GH_PACKAGES_USER` / `GH_PACKAGES_TOKEN` | GitHub Packages read access. Optional — without them the run uses its own `GITHUB_TOKEN`. |
| `INDEX_DISPATCH_TOKEN` | lets a release tell BrightMarket's index about itself instead of waiting for its cron. Optional. |

Generate a keystore once and keep it somewhere safe — losing it means nobody can
update an existing install:

```sh
keytool -genkeypair -v -keystore brightsudoku-release.jks \
  -alias brightsudoku -keyalg RSA -keysize 4096 -validity 10000
```
