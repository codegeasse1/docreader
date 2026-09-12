# DocReader

[![Build APK](https://github.com/codegeasse1/docreader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/codegeasse1/docreader/actions/workflows/build-apk.yml)

A WPS-Office-style document reader for Android — PDF viewing built on Android's
own `PdfRenderer`, so there is **no third-party PDF library and no native build**.

This is a scaffold generated in a Perchance workspace where no Android SDK is
available, so **it has not been compiled there**. It **is** compiled
automatically on every push by GitHub Actions.

## Download the APK (no local build needed)

1. Open the **Actions** tab: <https://github.com/codegeasse1/docreader/actions>
2. Pick the newest **Build APK** run (a green check means it built).
3. Scroll to **Artifacts** and download **`docreader-debug-apk`**.
4. Unzip it — inside is `app-debug.apk`. Install it on your phone
   (enable "Install unknown apps" for your browser/file manager).

To get a proper **Release** page with the APK attached, push a version tag:

```bash
git tag v1.0 && git push origin v1.0
```

The workflow then attaches the APK to a GitHub Release automatically.

## Roadmap

- [x] **Easy** — PDF view, zoom, page counter, recent files, starred files
- [ ] **Easy** — page thumbnails, bookmarks, in-document text search
- [ ] **Medium** — annotate (text box, pen, highlight, underline, strike), annotation list
- [ ] **Medium** — merge / split / rotate / delete / reorder pages (PDFBox-Android)
- [ ] **Medium** — set/remove password, print
- [ ] **Hard** — fill AcroForm fields, digital signature, compress
- [ ] **Hard** — PDF → Word/Excel conversion (likely server-side)

## What's implemented

- **Home dashboard** — "Welcome" header, search + profile, horizontally scrolling
  quick-action row (Open File / Annotate / Convert / Fill Form / Sign / Scan),
  Recent & Starred tabs, recent-files list with PDF icon, name, size, and a
  per-row menu (Star / Remove / Info), and a `+` FAB opening a bottom sheet
  (Create: PDF, Scan — From: Photos, Documents, Cloud, Browse).
- **PDF reader** — `PdfRenderer`-backed paged viewer: fit-width pages in a lazy
  list, page counter (`n/total`), zoom button (1x / 1.5x / 2x / 3x, pan by
  scrolling horizontally), a tools bottom sheet, and a top bar (outline, search,
  share, more).
- **File menu** — file header, circular actions (Content / Share / Thumbnail /
  Save as), and the action list (Merge, Split, Compressor, Print, Bookmark,
  Password, Organize pages, Annotation list, Star, File info).
- **Persistence** — recent-files list and starred flags in `SharedPreferences`
  (JSON), no Room/DataStore dependency.
- Registers for `ACTION_VIEW` on `application/pdf`, so "Open with" works.

## What is stubbed

Everything beyond *viewing* — merge, split, compress, set password, organise
pages, annotate, sign, fill form, convert, OCR, true text editing — shows a
"coming soon" snackbar. See the feasibility notes below.

## Build

Requirements: Linux/macOS/Windows, Android Studio (or the Gradle CLI),
JDK 17, Android SDK 34. Runs comfortably in ~4 GB RAM (unlike building an
office engine).

**There is no Gradle wrapper in this zip** (it needs a binary JAR that can't be
generated here). Two options:

1. **Android Studio (recommended)** — `File > Open` the `DocReader` folder.
   Android Studio prompts to generate the Gradle wrapper, then press Run.
2. **Gradle CLI** — install Gradle, then from the project root:
   ```bash
   gradle wrapper          # creates ./gradlew (one-time)
   ./gradlew assembleDebug # -> app/build/outputs/apk/debug/app-debug.apk
   ```

The APK lands in `app/build/outputs/apk/debug/`. `adb install` it, or Run from
Android Studio. Test on a **physical device** — an emulator needs a GPU for
usable frame rates.

## Feasibility map (what's easy vs hard)

| Feature | Difficulty | Approach |
|---|---|---|
| View PDF, page nav, zoom | Easy | `PdfRenderer` (done) |
| Recent files, search, bookmarks | Easy | SharedPreferences + `PdfRenderer` (done) |
| Thumbnails | Easy | `PdfRenderer` + grid of small bitmaps |
| Merge / split / organise pages | Medium | PDFBox-Android (`org.apache.pdfbox:pdfbox-android`) |
| Set password / print | Medium | PDFBox-Android encryption; Android Print framework |
| Annotate (ink/highlight/notes) | Medium | Canvas overlay + PDFBox content streams to flatten |
| Fill AcroForm fields | Medium-Hard | PDFBox-Android AcroForm API |
| Digital signature | Hard | BouncyCastle + PDFBox signing API |
| Compress PDF | Medium-Hard | re-encode page rasters, rebuild document |
| **True text editing** (like Word) | **Very hard / impractical** | not a reader problem — needs a full layout engine |
| Convert PDF → Word/Excel | Hard | server-side conversion; local fidelity is poor |
| Office docs (.docx/.xlsx/.pptx) reading | Medium | Apache POI (data) or convert to HTML/PDF, display in WebView |

## Recommended next steps

1. Build locally and confirm the reader opens a large PDF smoothly.
2. Add `pdfbox-android` and implement thumbnails + organise pages (highest value,
   lowest risk).
3. Add an annotation canvas overlay.
4. For .docx/.xlsx viewing, add a WebView + an HTML renderer rather than POI.
5. Only after all of that consider conversion/signing — those usually belong on a server.
