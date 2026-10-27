# Real GitHub installation validation

Verified on 2026-10-28 for PR #2399, using the production GitHub client, global
proxy routing, archive importer, installation review, persistent state store,
and QuickJS runtime. GitHub HTTP responses and archive contents were not mocked.

## Public fixture

The owner deleted this fixture after the acceptance run. The following IDs and
commits record that completed run; to repeat it, recreate the same text-only
plugin with both version tags and supply its new repository URL.

- Repository: <https://github.com/Tlaster/flare-plugin-github-e2e-20261028>
- Repository ID: `1436012088`
- `v1.0.0`: `c535ad0a82603cf4efef299b8d5710e16ca87f06`
- `v1.1.0`: `89ee98fb13042b4faef6fd9b045e23a67c9f4b10`

The repository root contains a guest-only, self-contained plugin. Its timeline
returns `GitHub E2E active version <version>`, so the loaded JavaScript can be
distinguished from the desired installation metadata. It needs no account
credentials and makes no plugin HTTP requests.

The first tag was published and installed before the second tag was published.
All application data used for verification was isolated from existing user data.

## Verified process boundaries

Each phase ran in a fresh Gradle test-worker JVM, sharing only the isolated data
directory. Live phases disable both up-to-date skipping and test-result caching.

| Phase | Desired version | Running version | Restart required | Additional assertion |
| --- | --- | --- | --- | --- |
| `install` | `1.0.0` | none | yes | Nothing installed before confirmation |
| `restart-v1` | `1.0.0` | `1.0.0` | no | Actual JavaScript returns `1.0.0` |
| `update` | `1.1.0` | `1.0.0` | yes | Check and cancellation preserve `1.0.0`; confirmation still runs old JavaScript |
| `restart-v2` | `1.1.0` | `1.1.0` | no | Actual JavaScript returns `1.1.0`; no update remains |

The update and final restart used different worker processes (`99601` and
`99672`). Per-phase evidence is written to `<root>/<phase>.txt`, including the
process ID, installed and running versions, tag, commit, and repository URL.

## Desktop acceptance and repairs

A packaged macOS desktop application was used with a dedicated bundle identity,
home directory, cache directory, and preferences directory. The actual management
UI loaded the public tag list, downloaded the package, displayed the installation
review, accepted installation, and showed activation after the first restart.
After publishing `v1.1.0`, checking only offered the update; confirmation displayed
the restart notice. After quitting and reopening the packaged app, the UI showed
`GitHubE2E · 1.1.0`, `Active in this process`, and `GitHub plugins are up to date`,
with no restart notice. The switch and uninstall action remained fully visible
at the default window width.

The real default-width window exposed two layout regressions:

1. The separate GitHub toolbar button squeezed `Install .fpp` into five lines.
   GitHub installation now lives in the existing More menu. A 448 dp content-area
   test verifies a single-line file-install label and opens the GitHub dialog.
2. A long repository URL consumed the caption width and hid the uninstall action.
   The plugin card now reserves space for controls using a weighted text column.
   A regression verifies that update and uninstall remain visible and clickable.

The anonymous API quota was exhausted during desktop verification. The existing
plugin remained active, the failure was displayed, and requests resumed after
GitHub's reported reset time. No GitHub token was injected into the application.

The final ordinary regression run passed 141 plugin JVM tests and 10 selected
desktop tests (the two plugin layout cases and eight proxy settings cases).
The opt-in live test was skipped in that ordinary run. The packaged desktop
build, changed-file Kotlin lint, and whitespace checks also passed.

## Reproduce

Use a fresh, disposable directory and the normal repository JDK/Android SDK
configuration. Keep the same directory for all four phases:

```sh
live_root="$(mktemp -d)"
live_repository="https://github.com/Tlaster/flare-plugin-github-e2e-20261028"
for phase in install restart-v1 update restart-v2; do
  ./gradlew :feature:plugin:jvmTest --tests '*PluginGitHubLiveTest*' \
    "-Pflare.plugin.live.phase=$phase" \
    "-Pflare.plugin.live.repository=$live_repository" \
    "-Pflare.plugin.live.root=$live_root"
done
```

The test skips by default and never makes network requests in the ordinary test
suite. Public API rate limits can fail a live run; respect the reported retry
window before starting again. The fixture deliberately needs no GitHub login,
OAuth flow, or social account.

This validates macOS desktop interaction and the shared JVM installation/runtime
path. It does not claim Android or iOS device/UI acceptance.
