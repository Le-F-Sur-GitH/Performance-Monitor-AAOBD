# Contributing

Thanks for helping. Issues and pull requests are welcome, in English or French.

## Before opening an issue
- Update to the [latest release](https://github.com/Le-F-Sur-GitH/Performance-Monitor-AAOBD/releases/latest).
- For connection problems, open the OBD adapter screen, tap **Copy** next to the log and paste it in the issue.
- Give your phone model, Android version, adapter and vehicle (make, model, engine, year).

## Development setup
- Android Studio (latest stable) or JDK 17 + Android SDK 34.
- `./gradlew testDebugUnitTest assembleDebug` must pass before every push.
- Debug builds simulate values when no adapter is connected. Use the [Desktop Head Unit](tools/dhu/README.md) to see the car screen.

## Branches
- `main`: released code only. Every commit on `main` is a published version.
- `develop`: integration branch, target of pull requests.
- `feat/...`, `fix/...`, `docs/...`, `chore/...`: one branch per topic, created from `develop`.

## Commit messages
[Conventional Commits](https://www.conventionalcommits.org/):
```
feat(obd): read VAG mode 22 PIDs
fix(ble): reconnect after link loss
docs: installation steps
chore(deps): bump AGP
test(parser): multi-ECU frames
```

## Pull requests
- Keep them focused, one topic per PR.
- Add an entry under `## [Unreleased]` in `CHANGELOG.md`.
- New user-facing text goes in `res/values/strings.xml` (English) and, if you can, `res/values-fr/strings.xml`.
- Never commit `local.properties`, `keystore.properties`, keystores or any secret.

## Releasing (maintainer)
1. On `develop`: bump `version.properties`, move the `Unreleased` notes to a new version section in `CHANGELOG.md`, commit `chore(release): vX.Y.Z`.
2. Merge `develop` into `main`.
3. Tag on `main`: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`.
4. The Release workflow builds the signed APK and publishes the GitHub release with the changelog notes.

### Signing secrets (Settings > Secrets and variables > Actions)
| Name | Content |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | release keystore, base64 encoded |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

## Translations
Strings live in `app/src/main/res/values-<lang>/strings.xml`. Copy missing keys from `values/strings.xml` and translate them.
