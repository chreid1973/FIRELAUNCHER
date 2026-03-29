# PPV Admin Tool

This folder contains a browser tool for building the launcher's hosted PPV JSON feeds, plus an optional PHP publish endpoint.

## Files

- `admin.html`: paste the raw daily listing block, preview the parsed events, publish directly, or save clean JSON files.
- `save.php`: optional direct-publish endpoint that overwrites the hosted JSON files in place.

## Basic Workflow

1. Upload `admin.html` and `save.php` to the same live `/ppv/` folder as the JSON files.
2. Edit `save.php` and replace `CHANGE_ME_TO_A_LONG_RANDOM_SECRET` with your own long secret.
3. Open `admin.html` in a browser.
4. Set the feed date and timezone.
5. Paste the combined PPV listing text.
6. Click `Parse Listings`.
7. Review anything shown in `Parse Issues`.
8. Enter the same secret into the page's `Publish Secret` field.
9. Click `Publish Ready Feeds` to overwrite the hosted JSON files directly.
10. If needed, use the `Save` buttons as a manual FTP fallback.

## Supported Input Styles

- `PPV01: 09:30 One Friday Fights 148 Kongchai v Khanzadeh`
- `PPV2 01: 09:00 PDRA Carolina Nationals`

Blank lines are ignored. Lines that do not match the expected pattern are shown as issues instead of being silently dropped.

## Direct Publish Notes

- `save.php` only accepts `ppv1` and `ppv2`.
- It validates `date`, `timezone`, and each event before writing.
- It creates a timestamped backup in `backups/` before overwriting an existing file.
- If your host does not execute PHP in the `/ppv/` folder, direct publish will not work and you should use the local save buttons instead.
