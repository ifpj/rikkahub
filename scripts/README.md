# Local Android build (Windows)

Run from the repository root:

```powershell
& .\scripts\build-local.ps1
```

The script installs the web dependencies when needed, then runs the normal
`assembleRelease` task. Use `-SkipWebInstall` after dependencies are installed,
or `-Variant Debug` for a debug build. Build tools and caches stay under the
repository in `.local-tools`, `.android-sdk`, `.gradle-home`, and `.pnpm-store`.

The release signing key is `.local-tools/signing/rikkahub.p12`; its credentials
are in the ignored `local.properties` file. Back up **both files** securely.
Losing either one prevents future updates signed with this key. Neither file
should be committed to Git.

Release APKs are written to `app/build/outputs/apk/release/`. A build signed
with this new key cannot be installed over an APK signed with the previous CI
key. Back up the app data before any uninstall or migration.

For the localization tool, install `uv` once into the project-local tool directory:

```powershell
New-Item -ItemType Directory -Force .local-tools | Out-Null
$env:UV_UNMANAGED_INSTALL = (Join-Path (Resolve-Path .local-tools).Path 'uv')
Invoke-RestMethod 'https://astral.sh/uv/install.ps1' | Invoke-Expression
```

Then run `& .\scripts\uv.ps1 run --directory locale-tui src/main.py --help`.
The wrapper keeps the uv cache and managed Python versions under `.local-tools`
on this drive. These local tools and caches are ignored by Git.
