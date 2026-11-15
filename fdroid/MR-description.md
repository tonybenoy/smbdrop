## Abstract for new app inclusion

### Please introduce the functions and usage of your app in summary, and also attach some screenshots.

SMB Drop sends, backs up and opens files on an SMB 2/3 share (NAS, Windows or Samba) from any Android app.

- **Share from any app** — Share → SMB Drop uploads one photo, hundreds of photos or a multi-GB video. Shared text and links are saved as notes.
- **Resumable queue** — every file is visible, can be paused, and continues from where it stopped after Wi-Fi loss, reboot or app kill.
- **Routing rules and bookmarks** — files go to folders chosen by type, extension or source app (with date templates); folders can be bookmarked and picked in one tap.
- **Automatic backup** — camera, screenshots or any folder, with Wi-Fi / charging / battery / chosen-network rules and a custom time window on chosen days; never uploads the same file twice.
- **Browse, copy, keep offline** — thumbnails (photos, PDFs), search, rename / move / delete with undo, server-to-server copy, folders available offline.
- **System file picker** (DocumentsProvider) — the share appears in Android's own file picker.

Screenshots: https://github.com/tonybenoy/smbdrop/tree/main/fastlane/metadata/android/en-US/images/phoneScreenshots
(taken against a throwaway demo server with made-up files).

### Please elaborate why you wrote this app. Please highlight the unique functions it provides over other similar apps in F-Droid.

I own a NAS and wanted sending files to it to take two taps from any app's share sheet, with uploads that never fail silently. To my knowledge the SMB-capable file managers on F-Droid are browsers/managers; SMB Drop adds share-sheet delivery with routing rules, a persistent resumable transfer queue, rule-based automatic backup, offline copies, and a system file-picker provider, with no cloud, accounts or analytics.

## Checklist

### Policy
* [x] The app complies with the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy). All 182 runtime libraries are open source (Apache-2.0 / MIT / BSD / OFL font); no Google Play Services, Firebase, ads or analytics. `fdroid scanner` reports 0 problems.
* [x] I am the original app author.
* [x] The upstream repo contains the metadata in a Fastlane folder structure (`en-US` plus es-ES, de-DE, fr-FR, hi-IN): title, short and full description, icon, feature graphic, 8 phone screenshots and a changelog.

### Docs
* [x] Read the CONTRIBUTING guide, templates, Build Metadata Reference and Quick Start Guide.

### Merge Request Setup
* [x] Title follows "New app: app name".
* [x] The fork is public and the branch is not protected.
* [x] No related fdroiddata / RFP issues.
* [x] One app in this MR.

### Metadata
* [x] `metadata/com.tonybenoy.smbdrop.yml`, valid YAML (`fdroid lint` and `fdroid rewritemeta` clean), LF line endings.
* [x] Nothing that belongs upstream (summary / description / images) is added here; only the one metadata file changed.
* [x] Releases are tagged (`v1.0.1`) and auto-update is enabled (`UpdateCheckMode: Tags`, `AutoUpdateMode: Version`).
* [x] Issue tracker: https://github.com/tonybenoy/smbdrop/issues
* [x] `AuthorName` is set.
* [x] No external repos / submodules.
* [ ] Reproducible Builds: not enabled in this first submission — I have not yet verified bit-for-bit reproducibility, so I would rather the APK be signed with F-Droid's key than claim it. Happy to work on it if you would like it for a later release.
* [x] ABI split not needed (APK is ~7 MB, no native code).
* [x] Only the latest version (1.0.1 / 2) is in the metadata; no disabled versions.
* [x] `commit` is the full hash (`c6a3537e5096404bd9f877d830c42ee59e33f10a`).

### Pipeline
* [x] All pipelines pass (https://gitlab.com/tonybenoy/fdroiddata/-/pipelines/3070061591): schema validation, `fdroid lint`, `fdroid rewritemeta`, `checkupdates`, `fdroid build` (Gradle 8.14.3 accepted), `check apk`, `check source code`, tools checks, git redirect.
* [x] No warnings or errors in the Reports tab.

/label ~"New App"
