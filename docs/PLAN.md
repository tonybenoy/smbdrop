# SMB Drop — Product & Design Plan

Nov 14, 2026 · @Tony Benoy

## Overview

SMB Drop is an Android app that makes a home or office SMB share feel like part of the phone: send to it from any app, back up to it automatically, and open files from it anywhere Android shows a file picker. It targets people who own a NAS or a PC share and are tired of abandoned, ad-heavy Play Store clients.

**Goals**

- Sending a file to a share takes two taps from any app's share sheet.
- Uploads never silently fail: every file is queued, resumable, and visible.
- Backup runs on its own under the user's rules (network, charging, folders).
- The share appears in Android's system file picker, so other apps can open and save files on it.
- A calm, modern interface with a liquid-glass look that still works on older and low-end phones.

**Non-goals for v1**

- Cloud services (Drive, Dropbox), FTP, WebDAV or SFTP.
- Two-way folder sync with conflict merging; backup is one-way.
- Media streaming beyond handing a file to the user's video or music app.
- Tablet- and foldable-specific layouts (they work, but are not optimised).

**Platform**: Android 8.0+ (API 26), target the latest SDK. Full glass effects on Android 12+ (API 31); a frosted fallback below that. SMB2 and SMB3 only; SMB1 is not supported for security reasons.

## Users and use cases

Three kinds of user shape the design; every use case below maps to at least one.

| Persona | Setup | What they want |
| --- | --- | --- |
| Home NAS owner | Synology or TrueNAS, one or two phones | Photo backup and quick file drops without Google Photos |
| PC sharer | Windows or Linux PC with a shared folder | Move screenshots, PDFs and downloads to the PC fast |
| Small office / family admin | One NAS, several users with their own logins | Everyone backs up to their own folder; shared folders for documents |

### Sending

- Share one photo, a burst of 200 photos, or a 4 GB video from Gallery, Files, WhatsApp or any app.
- Share text or a link (from a browser) and get it saved as a `.txt` or `.md` note.
- Share to a pinned destination straight from the share sheet (Direct Share), skipping the picker.
- Pick a different folder just this once, without changing the routing rules.
- Share while away from home: the file waits in the queue and uploads when the server is reachable.
- Share the same file twice: the app warns it is already uploaded and offers skip, keep both, or replace.

### Backing up

- Turn on camera backup during onboarding with sensible defaults (home Wi-Fi, photos and videos, by year/month).
- Add other folders: Screenshots, WhatsApp media, Downloads, any folder picked through the system picker.
- Back up only on chosen Wi-Fi networks, only while charging, or only at night.
- First-run backup of 30,000 existing photos: show an estimate, allow "only new from now on", and let it run in batches.
- Free up space: delete phone copies older than N days that are confirmed on the server.
- Pause all backup for a while (travelling, mobile hotspot) with one tap, with optional auto-resume.

### Routing

- Photos and videos go to `/Photos/{yyyy}/{MM}`, PDFs to `/Documents/Inbox`, everything else to `/Drop`.
- Files from a specific app (e.g. a scanner app) go to their own folder.
- No rule matches: fall back to the default destination, or ask every time (user's choice).

### Queue and network

- See what is uploading, waiting, paused, failed and done, with speed and time left.
- Pause and resume one file, a batch, or everything; reorder to send one file first.
- Wi-Fi drops mid-upload: the transfer pauses and resumes from where it stopped.
- Server offline, wrong password, disk full, or permission denied: a clear message with one fix action.

### Finding servers

- First launch: the app scans the network and lists NASes and PCs it finds, with a manual entry option.
- Server's IP changed: the app finds it again by name.
- Server is asleep: send Wake-on-LAN and retry (later milestone).

### Two-way

- Browse a share, search a folder, sort by name, date or size, and view thumbnails for photos.
- Download a file to Downloads, or open it directly in another app.
- Share a file from the server to another app (e.g. a PDF to email).
- In Gmail, Docs or any app, tap "attach" or "open", pick SMB Drop in the system picker, and browse the NAS.
- Save a file from another app directly to the share via the system "save as" picker.
- Create folders, rename, move and delete on the share, with confirmation for deletes.

### Setup, security and devices

- Several servers, several shares per server, and different logins for each.
- Guest (anonymous) shares and domain logins (`DOMAIN\\user`).
- Lock the app with fingerprint; hide passwords from screenshots.
- Move to a new phone: export settings to an encrypted file and import it.
- A family member uses the app with their own login on the same NAS.

### Edge cases to design for

- File names with characters Windows does not allow (`:` `?` `*`), very long paths, emoji names.
- Files changed while uploading, or deleted from the phone before the upload started.
- Server requires SMB signing or encryption; server only supports SMB1 (show a clear explanation).
- Phone in battery saver or manufacturer battery restrictions killing the upload.
- Two phones backing up to the same folder: avoid overwriting by adding the device name.

## Feature specification

Each feature lists what it does and the rules that make it behave well.

### Share target

- Accepts `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for `*/*`, including text and URLs.
- Opens as a glass bottom sheet over the source app, not a full screen; closes the moment files are queued.
- Sheet shows: file count and total size, the destination chosen by routing, a "Change" chip, and a Send button.
- Direct Share shortcuts for the 4 most used destinations.
- Files are copied into the queue by URI permission; if the source app revokes access, the app copies the file to a private cache first (only for files under a size limit, e.g. 200 MB).

### Destinations

- A destination = server + share + folder + login + optional nickname and colour.
- "Test connection" checks reachability, login, write permission, and free space.
- Logins: user/password, guest, and domain. Passwords encrypted with Android Keystore.
- Supports SMB 2.0.2 to 3.1.1, signing and encryption when the server requires them.

### Discovery

- mDNS / DNS-SD (`_smb._tcp`, `_device-info._tcp`) via `NsdManager`.
- WS-Discovery for Windows PCs, and a fallback scan of port 445 on the local /24 subnet.
- Results show name, IP, and device type icon (NAS, Windows, Mac, Linux) where known.
- Servers are remembered by name and MAC address hints, so an IP change is fixed automatically.

### Routing rules

- A rule = conditions (MIME type, extension, source folder, source app, file size) → destination + path template.
- Template tokens: `{yyyy}` `{MM}` `{dd}` `{device}` `{app}` `{name}` `{ext}`.
- Rules are evaluated top to bottom; first match wins; drag to reorder.
- A live preview shows the final path for a sample file while editing a rule.
- Fallback: default destination, or ask every time.

### Upload queue with pause and resume

- Every upload is a persistent queue item with states: Waiting, Uploading, Paused, Waiting for network, Failed, Done.
- Pause and resume per item, per batch, and globally; manual "send now" moves an item to the front.
- Partial uploads are written as `name.ext.smbdrop-part`; on resume the app reads its size and continues from that offset; on completion it renames to the final name.
- Integrity check after upload: size always; optional SHA-256 compare for files under a size limit.
- Automatic retries with back-off (30 s, 2 min, 10 min, then wait for network change).
- Stale part files older than 7 days are cleaned up.

### Network and power rules

- Global and per-backup-source conditions: any network, Wi-Fi only, unmetered only, chosen Wi-Fi networks, charging only, battery above X%, time window.
- Network names need location permission on Android 10+; the app explains this and offers "when server is reachable" as an alternative that needs no permission.
- Respects roaming and data saver; never uses mobile data unless the user allowed it.

### Auto-backup

- Sources: camera, screenshots, any folder picked via the system folder picker.
- New files detected by a periodic job (minimum 15 min) and a live content watcher while the app is active.
- First run: choose "everything" or "only new from now"; shows count and size estimate.
- Duplicate protection by source ID + size + modified time; optional hash.
- Optional "free up space" after N days, only for files verified on the server.
- Per-source routing, or the global rules.

### Two-way: browse and download

- Browse folders with list and grid views, thumbnails for images and video, sort and filter.
- Search the current folder by name (server-side search is not available in SMB).
- Download to Downloads via MediaStore, with the same queue, pause and resume.
- Open with another app, share to another app, create folder, rename, move, delete (with undo for 5 seconds where possible).
- Favourite folders pinned at the top.

### System file picker (DocumentsProvider)

- Each destination appears as a root in Android's picker ("SMB Drop – Home NAS").
- Supports open, create, write, rename, delete, and thumbnails.
- Reads use a proxy file descriptor with read-ahead caching so other apps can seek in large files.
- Slow network: show a loading state in the picker rather than blocking the other app.

### Security and privacy

- Optional biometric lock; sensitive screens block screenshots.
- No analytics by default; crash reporting opt-in.
- Encrypted settings export/import with a user passphrase.

### Settings

- Appearance: system, light, dark; glass intensity (full, reduced, off).
- Notifications: per category (progress, completed, errors, backup summaries).
- Advanced: SMB dialect limits, timeouts, concurrent uploads (1–4), log export for troubleshooting.

## Design language: liquid glass on Android

Glass is used for the app's chrome (navigation, sheets, toolbars, the progress pill), while content (file lists, photos, settings) sits on solid surfaces. This keeps the look modern and light without hurting readability or battery. Liquid glass is Apple's design language, so on Android it is recreated in Jetpack Compose and blended with Material 3 Expressive behaviour, so the app still feels native.

### The glass material

Each glass surface is built from five layers, back to front:

1. **Backdrop blur** of whatever is behind it: 24 dp radius for bars, 32 dp for sheets (Compose `Haze` library or `RenderEffect` on API 31+).
2. **Tint**: white at 16% in light mode, near-black at 32% in dark mode, slightly coloured by the accent.
3. **Grain**: 2% noise texture so the blur does not look flat or band.
4. **Edge highlight**: a 1 dp gradient stroke, bright at the top-left and fading out, which gives the "liquid" rim.
5. **Soft shadow**: large, low-opacity shadow to lift the surface off the content.

| Token | Light | Dark |
| --- | --- | --- |
| Glass tint | white 16% | #0B0D12 at 32% |
| Glass tint, reduced mode | white 88% | #14161C at 92% |
| Edge highlight | white 55% → 0% | white 22% → 0% |
| Surface (content) | #F6F7FB | #0E1015 |
| Text primary | #0E1015 | #F2F4F8 |
| Accent | dynamic (Material You), fallback #3D7BFF | dynamic, fallback #7AA5FF |
| Success / warning / error | #1F9D66 / #C98A00 / #D83A3A | #4CC38A / #F2B33D / #FF6B6B |

### Shape, type and colour

- **Shapes**: 28 dp radius for sheets and cards, fully rounded pills for buttons, chips and the bottom bar.
- **Type**: Roboto Flex (variable). Display 32/40 semibold, title 20/28 medium, body 16/24, label 13/18 medium. Numbers in progress views use tabular figures.
- **Colour**: Material You dynamic colour picks the accent from the wallpaper; a soft gradient "aurora" in the accent hue sits behind the home screen so the glass has something to refract.
- **Icons**: Material Symbols Rounded, weight 400, filled when selected.

### Motion

- Spring-based motion (Material 3 Expressive physics): sheets and the progress pill stretch and settle rather than slide linearly.
- The share sheet morphs from the Send button into the progress pill on the home screen, so the user sees where their files went.
- Progress uses a liquid fill inside the pill; completed items collapse with a short check animation.
- All motion respects the system "remove animations" setting, falling back to fades.

### Key components

- **Floating glass navigation bar**: pill-shaped, 4 tabs, floats 12 dp above the system bar.
- **Glass top bar**: transparent at rest, turns to glass when content scrolls under it.
- **Glass bottom sheets**: share sheet, destination picker, rule editor, conflict prompts.
- **Transfer pill**: compact, always-visible glass pill showing active transfers; tap to expand into the queue.
- **Destination chips**: coloured dot + nickname; used in the share sheet and rules.
- **Status cards**: solid surfaces with a coloured left edge for warnings and errors, each with one action button.

### Accessibility

- Text on glass must hit 4.5:1 contrast; the tint increases automatically over bright or busy backgrounds.
- "Glass intensity" setting (full, reduced, off) and the system high-contrast text setting switch to the reduced tint.
- Touch targets at least 48 dp; layouts tested at 200% font size and with TalkBack.
- Status is never shown by colour alone: every state has an icon and a label.

### Performance and fallbacks

- Real blur only on Android 12+; below that, the reduced (near-opaque) tint is used with the same edge highlight.
- Blur turns off in battery saver and on devices that report low RAM.
- At most three glass surfaces on screen at once; never blur behind individual list items.
- Target: 120 fps scrolling on recent phones, no dropped frames below 60 fps on mid-range ones.

## Screens and flows

The app has four tabs plus the share sheet, which is the screen people will see most. Everything a user does daily is at most two taps from a tab.

### Navigation

- **Home**: transfer status, backup status, quick actions, recent uploads.
- **Browse**: servers and shares, folder browser, favourites.
- **Backup**: backup sources, their conditions and progress.
- **Settings**: destinations, routing rules, network rules, appearance, security, advanced.
- **Share sheet**: opens over other apps; not part of the tab bar.
- **Transfer pill**: floats above the tab bar on every tab whenever something is in the queue.

### Key screens

**Home**

- Top: large status line, e.g. "All backed up · 2 min ago" or "Uploading 12 of 40 · 3 min left".
- Cards: Backup (photos backed up today, next run), Queue (active, paused, failed counts), Servers (online/offline dots).
- Quick actions: Send files, Pause all, Back up now.
- Recent uploads: a grid of thumbnails with destination chips.

**Share sheet**

- Header: thumbnails of the shared files (stacked if many), count and size.
- Destination row: the routed destination and path preview ("Home NAS › Photos › 2026 › 11"), with Change.
- Options row (collapsed): rename pattern, conflict rule, "send now even on mobile data".
- Primary button: Send. Secondary: Add to queue for later.

**Queue** (expands from the transfer pill)

- Segmented filter: Active, Waiting, Failed, Done.
- Each row: thumbnail, name, destination, progress bar, speed, and a pause/resume button.
- Swipe left to cancel, swipe right to send now; batch actions in the top bar.

**Browse**

- Server list with status, then shares, then the folder view.
- Folder view: breadcrumb in the glass top bar, list/grid toggle, sort, search field, and a floating Upload button.
- Long-press for multi-select: download, share, move, rename, delete.

**Backup**

- One card per source with an on/off switch, conditions summary ("Home Wi-Fi · charging"), progress, and last run.
- Source detail: folder, destination, path template, conditions, free-up-space rule, and "Back up now".

**Rules editor**

- Rules as reorderable cards; each card reads like a sentence: "PDFs from Scanner → Documents/Scans".
- Editor sheet with condition chips, destination picker, template field with token chips, and a live path preview.

### Core flows

**First launch (under 60 seconds)**

1. Welcome screen with one line on what the app does and a Get started button.
2. Network scan runs automatically; found servers appear as cards, plus "Enter address".
3. Pick a server → login sheet (user/password, guest, or saved login) → pick a share → pick a folder.
4. Test runs in the background; success shows a check and free space.
5. Offer camera backup with defaults shown; Turn on or Not now.
6. Ask for notification permission with a reason; land on Home.

**Share from another app**

1. User taps Share → SMB Drop (or a Direct Share destination).
2. Sheet opens with the routed destination already chosen.
3. Tap Send → sheet closes, a toast-like glass pill confirms "40 files queued to Home NAS".
4. Progress shows in a notification; tap opens the queue.

**Upload interrupted**

1. Wi-Fi drops → items move to "Waiting for network" and the notification says so.
2. Network returns → upload resumes from the saved offset, no user action.
3. If it fails 3 times → item goes to Failed with the reason and Retry.

**Open a NAS file from another app**

1. In Gmail, tap Attach → Browse → SMB Drop – Home NAS.
2. Browse folders with thumbnails; pick a file.
3. File streams through the provider; large files show progress in the picker.

### Empty, error and permission states

| Situation | What the user sees | Main action |
| --- | --- | --- |
| No servers yet | Illustration + "Let's find your NAS or PC" | Scan network |
| Nothing found on scan | "No servers found on this network" + tips (same Wi-Fi, SMB on) | Enter address |
| Server offline | Server card dimmed, "Offline since 14:02" | Retry / Wake (if set up) |
| Wrong password | Inline error in the login sheet | Edit login |
| Disk full | Error card with free space shown | Choose another folder |
| SMB1-only server | Plain explanation why it isn't supported | How to enable SMB2 (help link) |
| Notifications denied | Banner on Home explaining progress won't show | Open settings |
| Battery restrictions detected | Banner with phone-brand-specific steps | Fix it |
| Location denied (Wi-Fi names) | Rule falls back to "when server is reachable" | Allow location |
| Empty folder | "This folder is empty" | Upload here |
| Queue empty | "Nothing waiting" with last completed time | Send files |

## Technical architecture

A single-activity Kotlin app with feature modules on top of one SMB core and one transfer engine; every entry point (share sheet, backup, browser, file picker) goes through the same engine so pause, resume and retries behave identically everywhere.

### Stack

- Kotlin, coroutines and Flow; Jetpack Compose with Material 3; Haze for backdrop blur.
- **smbj** for SMB2/3; smbj-rpc for listing shares on a server.
- WorkManager (foreground, `dataSync` type) for transfers and backup scans.
- Room for all app data; DataStore for settings; Android Keystore for secrets.
- Hilt for dependency injection; Coil for thumbnails (with a custom SMB fetcher).
- Gradle version catalogs, Kotlin DSL; GitHub Actions for build, lint and tests.

### Modules

| Module | Responsibility |
| --- | --- |
| `app` | Activities, navigation, glass theme, DI setup |
| `core-smb` | Connections, session pool, auth, read/write streams with offsets, listing, errors mapped to app errors |
| `core-data` | Room database, repositories, DataStore, Keystore wrapper |
| `transfer-engine` | Queue, scheduler, workers, resume logic, retries, integrity checks, notifications |
| `routing` | Rule evaluation and path templates |
| `discovery` | mDNS, WS-Discovery, port-445 scan, server identity matching |
| `backup` | Source scanning, dedupe, free-up-space |
| `conditions` | Network, power and time rules, exposed as a single "can run now" stream |
| `feature-share` | Share sheet activity and Direct Share shortcuts |
| `feature-browse` | Folder browser, file operations, downloads |
| `provider` | DocumentsProvider for the system file picker |
| `designsystem` | Glass material, tokens, components, motion specs |

### Data model

| Table | Key fields |
| --- | --- |
| `Server` | id, name, host, last IP, discovery hints, last seen |
| `Destination` | id, serverId, share, basePath, loginRef, nickname, colour |
| `Login` | id, type (user/guest/domain), username, domain, encrypted secret ref |
| `RoutingRule` | id, order, conditions (JSON), destinationId, pathTemplate, enabled |
| `BackupSource` | id, type, treeUri, destinationId or "use rules", conditions, scan marker, free-up-after days |
| `TransferItem` | id, direction (up/down), sourceUri, targetPath, size, bytesDone, state, error, attempts, batchId, priority |
| `TransferBatch` | id, origin (share/backup/browse), createdAt, label |
| `UploadedFile` | sourceKey (MediaStore id or URI), size, modified, hash (optional), remotePath, uploadedAt |

### Transfer engine

- One long-running foreground worker drains the queue while items exist and conditions allow; it stops itself when the queue is empty.
- Concurrency: 2 transfers by default (configurable 1–4), one SMB session per server reused across files.
- Resume: writes go to the `.smbdrop-part` file; `bytesDone` is saved every 4 MB and on pause; on resume the real part-file size on the server wins.
- Buffer size 1 MB; SMB3 multi-credit large reads/writes when the server supports them.
- Errors are classified (network, auth, permission, disk full, file gone, protocol) to decide retry versus fail.

### Discovery

- Runs on demand and when the network changes; results cached per Wi-Fi network.
- Takes a multicast lock only while scanning; subnet scan uses 64 parallel probes with a 300 ms timeout.

### DocumentsProvider

- Document IDs encode destination + path; listings cached for 30 s with invalidation on write.
- `openDocument` uses `StorageManager.openProxyFileDescriptor` with a read-ahead cache; writes go to a temp file then upload through the engine on close.
- Thumbnails generated and cached locally (LRU, 200 MB cap).

## Milestones and roadmap

The build takes about 32–43 evenings, roughly 7–10 weeks at four or five evenings a week, in four phases. Phase 1 already replaces a Play Store SMB app for sending files.

&#91;embedded content: roadmap · 4 phases, 9 milestones, 3 gates\]

Each gate is a real-world test on your own server; a phase is not done until its gate passes. Later ideas not yet scheduled: Wake-on-LAN, remote access over Tailscale or WireGuard, Quick Settings tile and home-screen widget, HEIC-to-JPG conversion, settings export.

## Testing, risks and open questions

Testing against real servers early is the single biggest protection against the problems that sank other SMB apps.

### Test setup

- **Servers**: Samba in Docker (with SMB signing/encryption on and off), Windows 11 share, the user's own NAS; later Synology DSM and TrueNAS if available.
- **Phones**: one Pixel (stock Android), one Samsung, one Xiaomi or OnePlus (aggressive battery management), one Android 8–11 device for the no-blur fallback.
- **Automated**: unit tests for routing, templates, conditions and resume logic; integration tests for `core-smb` against the Docker Samba; Compose UI tests for the share sheet and onboarding; screenshot tests for light/dark and glass modes.

### Test scenarios

- [ ] Upload 1 file, 200 photos, and a 4 GB video; verify sizes and hashes.
- [ ] Turn Wi-Fi off mid-upload, reboot the phone mid-upload, kill the app mid-upload; all resume.
- [ ] Wrong password, read-only share, full disk, server off, SMB1-only server.
- [ ] File names with emoji, `:` `?`, 255-character names, deep paths.
- [ ] Backup 10,000 existing photos; confirm no duplicates after a second scan.
- [ ] Open and save files through the system picker from Gmail, Google Docs and a PDF editor.
- [ ] TalkBack pass, 200% font size, reduced glass, dark mode.
- [ ] Battery saver on; background limits on Samsung and Xiaomi.

### Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| smbj incompatibility with a specific NAS setting | Uploads fail for some users | Test in milestone 1; clear error mapping; log export |
| Manufacturer battery killers stop transfers | Backups stall silently | Foreground worker, detection banner, brand-specific instructions |
| Blur performance on mid-range phones | Janky UI | Limit glass surfaces, auto-fallback, frame-time monitoring |
| DocumentsProvider blocking other apps on slow networks | Other apps freeze | Async loading states, timeouts, caching |
| Play Store policy changes (storage, foreground services) | Forced rework | Use standard APIs only; foreground service type declared and justified |
| Wi-Fi names needing location permission | Users refuse | "Server reachable" alternative needs no permission |

### Open questions

- [ ] Which server is the main target: Synology, TrueNAS, Windows or Linux PC?
- [ ] Personal use only, or publish on the Play Store (affects privacy policy, support and monetisation)?
- [ ] Free and open source, or paid / one-time unlock?
- [ ] Is remote access (Tailscale/WireGuard) needed in v1, or later?
- [ ] App name: keep "SMB Drop" or choose another?
