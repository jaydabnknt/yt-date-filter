# YouTube date range filter (ReVanced patch)

Adds a **📅 Date range** button under the YouTube search box. Pick a From/To date and it
searches with YouTube's `after:` / `before:` operators, e.g. `cats after:2020-01-01 before:2020-12-31`.

The patch only hooks `MainActivity.onCreate`, so it should survive YouTube updates.

## Use

1. Every push to `main` builds the bundle in GitHub Actions and publishes a release.
2. Download the `.rvp` file from the latest release.
3. In ReVanced Manager, add it as an extra patch bundle (from local file), then patch YouTube
   with the official patches **plus** "Search date range".

## How it works

- Tap the search bar and type a query; the pill appears under the search box.
- Tap it, choose dates, press **Search**. **Clear** removes the date filter.
