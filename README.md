# MusicScapes

An Android TV / Google TV picture companion that follows music playing on the TV. Includes a screensaver service, artist photographs, scenery, artwork, public-domain space images, online discovery and a recent-picture rotation when music is idle.

Current personal build: **5.14**. Artist photos favour 4K with Full HD allowed. Artist photographs bypass aesthetic grading. Optional Fanart.tv and Flickr connections require your own keys and are inactive by default; Commons discovery works without registration.

## Project

- `src/`: native Android app, rendering, music metadata and image discovery.
- `assets/`: interface, matching engine, starter pictures and local semantic model.
- `res/`, `AndroidManifest.xml`: Android resources and screensaver registration.
- `lib/`, `libs/`: bundled Android runtime dependencies.
- `licenses/`, image credit files and asset licence files: third-party notices.

## Building

Run `build.ps1` in PowerShell with JDK 17, Android platform 34 and Android build tools available. The script accepts `-ToolsRoot` and `-BuildRoot`; its defaults refer to the original development workspace's external `work/android-tools` and `work/musicscapes-build` folders, which are not included in this repository. Inspect the script for the expected tool layout. Personal builds use a locally generated signing key; keep signing keys private.

`configure-artist-sources.ps1` can import provider keys from a private JSON file onto a connected development TV. Use `artist-source-keys.example.json` as the format. Never commit filled-in keys. No provider registration, subscription or keys are included.

The experimental pregraded image catalogue, downloaded image cache, listening history, TV diagnostics, APKs and development tools are outside this repository. The experimental scenery grades have not been connected to the installed selection engine.

## Credits and distribution

See `IMAGE-CREDITS.txt`, `PHOTO-CREDITS.txt`, `licenses/` and asset notices. Images, fonts, model weights and runtime libraries retain their respective licences. Fanart imagery, when configured in the personal app, retains its copyright and is excluded from the open catalogue. This repository does not grant additional rights to third-party material. Store publication remains deferred.
