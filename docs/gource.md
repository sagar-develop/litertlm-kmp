# Growth visualisation with Gource

[Gource](https://gource.io/) renders an animated, file-by-file visualisation of a git
repository's history — a "watch the codebase grow" clip. It's a nice companion to
[`ARCHITECTURE.md`](../ARCHITECTURE.md): that file is the *intentional* structure, this is
the *organic* growth over time. Handy for launch posts and talks.

## Install (Windows)

```powershell
winget install acaudwell.Gource
```

This installer needs administrator rights — accept the UAC prompt (an unattended/CI
shell will fail with `0x800704c7`). ffmpeg is also required for video output: on a clean
machine, `winget install Gyan.FFmpeg`.

Gource needs an OpenGL context, so run it on a desktop session (not a headless shell).

## Produce the clip (NativeLM-branded)

Run from the repository root. The colours match the NativeLM palette — warm-dark canvas
`#1C1B1A`, off-white text `#FAF9F6`, sage-green directories `#7FA980`.

```powershell
gource . `
  --title "NativeLM — on-device document chat" `
  --seconds-per-day 0.5 `
  --auto-skip-seconds 1 `
  --max-file-lag 0.1 `
  --hide mouse,filenames,progress `
  --highlight-users `
  --background-colour 1C1B1A `
  --font-colour FAF9F6 `
  --dir-colour 7FA980 `
  --highlight-colour 7FA980 `
  --key `
  --1280x720 `
  --output-framerate 30 `
  --output-ppm-stream - `
  | ffmpeg -y -r 30 -f image2pipe -vcodec ppm -i - `
      -vcodec libx264 -preset slow -pix_fmt yuv420p -crf 20 `
      _session/material/nativelm-growth.mp4
```

A short, fast-paced clip (low `--seconds-per-day`) reads best on LinkedIn / X. For a
longer narrated walkthrough, raise `--seconds-per-day` to ~3–5.

## Focus on the source (optional)

To exclude generated/vendor noise (build outputs, ObjectBox-generated files, session
material) and visualise only hand-written source, drive Gource from a filtered log:

```powershell
git log --pretty=format:user:%aN%n%ct --reverse --raw --encoding=UTF-8 `
  --no-renames -- lib/src sample-app/src docs `
  > gource.log
gource gource.log --title "NativeLM" ...  # same flags as above
```

## Notes

- The output MP4 goes to `_session/material/` (content/marketing material), which is
  git-ignored — the clip is an artifact, not part of the repo.
- To put faces on contributors, drop avatar PNGs (named per git author) in a folder and
  add `--user-image-dir <folder>`.
