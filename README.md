# ffmobile

A Material 3 Android wrapper around the FFmpeg build you supplied (`ffmpeg`/`ffprobe`
executables + shared libs, arm64‑v8a). Kotlin + Jetpack Compose.

Screens: **Convert**, **Probe**, **Filters**, **Settings**, and an **Advanced** tab (hidden
until you flip "Advanced mode" in Settings) that runs raw arguments straight against the
bundled `ffmpeg` binary.