# Permission & policy declarations (Play Console → App content)

| Permission / feature | Why it is needed | Declaration text |
| --- | --- | --- |
| READ_MEDIA_IMAGES / READ_MEDIA_VIDEO | Core feature: automatic camera/screenshot backup to the user's own server | "SMB Drop's core purpose is backing up the user's photos and videos to their own network share. A one-time photo picker cannot provide ongoing background backup." (Photo & video permissions declaration form) |
| FOREGROUND_SERVICE_DATA_SYNC | Long uploads/downloads must continue with the screen off | Data synchronisation: user-initiated and scheduled file transfers to the user's server, with a visible progress notification |
| POST_NOTIFICATIONS | Transfer progress and errors | Runtime, requested after onboarding |
| ACCESS_FINE_LOCATION (optional) | Android only exposes the Wi-Fi network name to apps with location permission; used for "back up only on these Wi-Fi networks". Not collected or stored | "Used on-device only to read the connected Wi-Fi name for a backup rule. Optional." |
| ACCESS_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE | Server discovery (mDNS) on the local network | — |
| USE_BIOMETRIC | Optional app lock | — |
| MANAGE_DOCUMENTS (provider protection) | Own DocumentsProvider is protected by the system permission | — |

Other forms: Ads = No. Target audience = 13+ (not for children). Content rating questionnaire: utility app, no user-generated content. News / health / finance declarations = No.
Android 15 note: dataSync foreground services have a 6-hour/day limit; WorkManager reschedules automatically.
