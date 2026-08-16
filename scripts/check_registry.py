#!/usr/bin/env python3
"""
Fail if an APK is missing the KSP-generated Light SDK registry.

The SDK finds the @InitialScreen class through
`com.thelightphone.sdk.generated.LightSdkRegistry`, which its Gradle plugin emits
via KSP and `LightActivity` loads with `Class.forName`. Two things make its
absence nasty:

  * The light-sdk plugin only registers its processor inside
    `pluginManager.withPlugin("com.google.devtools.ksp")`, so dropping
    `alias(libs.plugins.ksp)` from the tool module silently disables generation.
  * Nothing fails at build time. Compilation, lint and unit tests all pass. The
    tool just throws on startup.

v0.1.0 shipped exactly that. This check makes it a build failure instead.

    python3 scripts/check_registry.py path/to/app.apk
"""
import sys
import zipfile

NEEDLE = b"Lcom/thelightphone/sdk/generated/LightSdkRegistry;"


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    with zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if n.endswith(".dex")]
        if not dexes:
            print(f"::error::{apk} contains no dex files", file=sys.stderr)
            return 1
        if any(NEEDLE in z.read(n) for n in dexes):
            print(f"ok: generated LightSdkRegistry present in {', '.join(dexes)}")
            return 0
    print(
        f"::error::{apk} has no generated LightSdkRegistry, so the tool will crash on "
        "startup. Is alias(libs.plugins.ksp) still applied in tool/build.gradle.kts?",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
