# YouTube playback fixes in this fork

This fork targets the reported pause around 1:00 and the quality selector becoming
unavailable at 480p. YouTube **20.40.45** is in the patch's supported versions.
The first Reel-based build did not resolve the stall in the phone test. The next
revision uses **visionOS 1.04 with a matching visitor identity**. On-device
playback must still be checked after repatching.

## Current playback change

The old Reel flow could return `playabilityStatus: OK` and serve the first media
byte range while returning **HTTP 403** for a later range of the same video.
A successful player response therefore did not establish that the whole video
was playable. This was reproduced against a public test video on October 17,
2026, independently of the phone logs.

The updated visionOS flow follows the client profile and visitor bootstrap used
by [NewPipe Extractor at 8443c91](https://github.com/TeamNewPipe/NewPipeExtractor/tree/8443c91bf4276c326f7b33e30a36d88e0ab94cfe/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube):

- Use visionOS client version `1.04`, device `RealityDevice17,1`, and the native
  visionOS user agent, replacing the old `0.1` profile and Safari user agent.
- Request visitor data from YouTube's `visitor_id` endpoint for that profile,
  then include the returned identity in the player request's client context.
- Keep each visitor identity local to its fetch. Do not forward Android login,
  visitor, or API-format headers into visionOS requests.
- Make visionOS the default for new YouTube settings. Existing installations
  must explicitly select **visionOS**; **Stats for nerds** should show
  **visionOS 1.04** when this client supplies the stream.

The supplied phone log reached the app's approximately 900 KB log-buffer limit
and contained almost entirely UI-filter messages; it did not retain the stream
requests. It cannot independently establish the phone's HTTP failure.

Validation on October 17, 2026: all 19 deterministic checks passed. The live
production-code probe for `aqz-KE-bpKQ`, after a 75-second wait, returned HTTP 200
and exactly 1,024 bytes for the first and later ranges of both 720p60 and 1440p60.
The later offsets were 17,790,686 and 55,947,336 bytes respectively. This is a
media-access check, not confirmation of uninterrupted playback on the phone.

## Source of the fixes

The cloned `main` branch was at `015fe10a2` (May 31, 2026), declaring version 6.1.0.
GitLab's `dev` branch continued receiving fixes after that date. This fork
backports these commits, preserving their authors and commit references:

| Upstream commit | Change |
| --- | --- |
| [a9aeb325](https://gitlab.com/ReVanced/revanced-patches/-/commit/a9aeb325de1160262c4db9b4b60c6c5e39730620) | Switch the initial YouTube default from Android VR to Reel. |
| [6a7ba9fc](https://gitlab.com/ReVanced/revanced-patches/-/commit/6a7ba9fce82bebc2fc5d1f4f5ccb66348b773b08) | Add **Android Reel (no auth)**, use the upstream's working client identity, and make it the default. |
| [e24b90b5](https://gitlab.com/ReVanced/revanced-patches/-/commit/e24b90b5f0399298e948a24a4cea00c9705bcc0d) | Preserve quality selection when the response has one VP9 resolution but several AVC resolutions. |

The quality fix removes the single-resolution VP9 choice only in that specific
case. It preserves complete VP9 quality sets, AVC-only and VP9-only responses,
audio, and AV1. It is enabled for YouTube; YouTube Music retains its format list.

Additional corrections in this fork (October 17, 2026):

- Parse playback status and streaming data as independent protobuf fields. The
  previous `oneof` discarded whichever field appeared first. Request both fields
  from the player endpoint so failed clients can be rejected and fallback tried.
- Retain the original availability rule for the original-audio setting; an
  incidental upstream boolean change would disable it for every spoofed client.
- Preserve immersive-mode capability reporting for both Reel variants.

## Build and use with ReVanced Manager

1. Open this repository's [Build playback patches workflow](https://github.com/FactualExplorer/Repo_vance/actions/workflows/build-fork.yml).
   A push to `main` starts a build; it can also be run manually.
2. After a successful run, download its `playback-patches-<commit>` artifact while
   signed into GitHub. Extract the ZIP and copy the **`.rvp` file** to your phone.
   The ZIP and repository source code are not patch bundles.
3. In a Manager version supporting `.rvp` bundles, open its patch sources/bundles
   list and import the local `.rvp` file. Select **ReVanced Patches (playback fork)**
   for this patching run. Do not apply the same patches from two bundles together.
4. Patch an **unpatched YouTube 20.40.45 APK** from storage with your usual patches,
   including **Spoof video streams** and, for a normal non-root installation,
   **GmsCore support**. Install the resulting APK using your existing Manager
   signing key. Keep your current APK/settings as a rollback option.
5. In patched YouTube, open **Settings → ReVanced → Misc → Spoof video streams**,
   enable spoofing, select **visionOS**, and restart YouTube.
   An existing saved client selection can survive an update, so select this
   explicitly. The label already existed, but this revision changes its client
   profile and request flow. The earlier Reel build is not this revision.

The workflow builds a uniquely versioned `6.1.0-playback.<run number>` bundle and
uploads it as an artifact. It uses GitHub's built-in token for dependency downloads;
it does not need the upstream release signing secrets. The upstream publishing
workflow is restricted to the upstream repository.

## Local development

Run the isolated regression checks with Python 3 and JDK 17 or newer:

```sh
python3 tests/playback/run.py
```

The runner compiles the actual client definitions, HTTP request code, stream
parser and generated protobuf classes. It uses in-memory HTTP responses and
small test-only Android/application service stubs. Dependencies are downloaded
from Maven Central into the ignored `build/` directory.

An optional live smoke test uses the production Java visitor/player/protobuf
flow and reads 1 KB media ranges with an Android user agent:

```sh
python3 tests/playback/run.py --live-video aqz-KE-bpKQ --live-delay-seconds 75
```

This test expects a public video longer than 90 seconds with 720p and 1440p
formats. It checks the beginning and a byte offset estimated to correspond to
75 seconds, optionally after waiting. It does not decode video or exercise
Android's player. It is deliberately excluded from automatic CI because live
YouTube responses depend on network and server conditions.

For a complete bundle, install JDK 17 and an Android SDK with platform 34 and
build tools 34.0.0. Set `ANDROID_HOME` to that SDK. Configure the
`githubPackagesUsername` and `githubPackagesPassword` properties in your private
`~/.gradle/gradle.properties`; GitHub Packages requires a credential with
`read:packages` access. Then run:

```sh
./gradlew :patches:buildAndroid --console=plain -Pversion=6.1.0-playback.local
```

The bundle is written under `patches/build/libs/`. Do not commit credentials or
APK files. The regression runner does not require GitHub credentials or an SDK.

## Phone verification

Play a regular video known to offer 720p or 1080p for at least five minutes.
Check that it passes 1:00 without the long pause, change between two quality
levels after 1:00, and seek forward. Repeat on another video and check Shorts
and a livestream if you use them. Record the client displayed in **Stats for
nerds**, since fallback can select a different client from the preference.

The deterministic local tests do not verify YouTube's current server behavior,
APK bytecode injection, Android playback, or request timing. The optional live
probe verifies media access only from the machine running it. If the symptom persists, note the selected
and actual client, a video link, and the time of the stall. A screen recording
is sufficient to show the symptom; logs are only needed for deeper diagnosis.
