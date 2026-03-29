# FireLauncher GitHub Pages Download Site

This folder is now designed to be deployed by GitHub Actions and GitHub Pages, not by manually uploading APKs into the repo.

## What lives here

- `index.html` - static download page
- `launcher-preview.png` - screenshot shown on the page
- `latest.json` - release metadata consumed by the page

## Recommended release flow

1. Push your code changes to `main`.
2. Open the `Release APK and Deploy Pages` workflow in GitHub Actions.
3. Run it with a version label such as `1.1.0-beta`.
4. The workflow will:
   - build `app-release.apk`
   - create or update a GitHub Release
   - upload the APK and `SHA256SUMS.txt`
   - regenerate `ppv_download_site/latest.json`
   - deploy this folder to GitHub Pages

## Required GitHub secrets

Set these in the repository settings before running the workflow:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Optional:

- `TMDB_API_KEY`

## GitHub Pages setup

In the repository settings:

1. Open `Settings -> Pages`
2. Set `Source` to `GitHub Actions`
3. Run the workflow once

## Download hosting model

- The website is served by GitHub Pages.
- The APK itself is served by GitHub Releases.
- `latest.json` tells the page which release asset to link to, plus the current size, date, and SHA-256.

## Notes

- Do not commit new APKs into this folder going forward.
- The workflow removes any stale `*.apk` files from the deployed Pages artifact.
- If this repository is public, rotate any previously committed webhook URLs or secrets immediately.
