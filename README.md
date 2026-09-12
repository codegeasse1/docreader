# DocReader

[![Build APK](https://github.com/codegeasse1/docreader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/codegeasse1/docreader/actions/workflows/build-apk.yml)

An open-source, WPS-Office-style document reader for Android — Kotlin + Jetpack
Compose. PDF viewing uses Android's built-in `PdfRenderer` (no native build);
all editing uses **PDFBox-Android**.

MIT licensed.

## Download the APK (no build needed)

1. Open the **Actions** tab: <https://github.com/codegeasse1/docreader/actions>
2. Pick the newest green **Build APK** run.
3. Under **Artifacts**, download **`docreader-release-signed-apk`**
   (or `docreader-debug-apk`).
4. Unzip it — inside is `app-release.apk` / `app-debug.apk`. Copy it to your
   phone and install it (enable "Install unknown apps" for the app you install
   it from).

> **First install of an older build?** The very first artifacts were unsigned
> and Android rejected them with
> `INSTALL_PARSE_FAILED_NO_CERTIFICATES`. Builds from now on are signed with a
> keystore the CI generates and commits (see *Signing* below), so they install
> normally. If you previously installed a debug build, uninstall it once before
> installing the release build (different signing key).

To publish the APKs on a Release page, push a tag:

```bash
git tag v1.2 && git push origin v1.2
```

## Changelog

### 1.5 — annotation polish, auto-hiding reader bars, PDF/image export

- **Export as PDF** and **export as image**: the reader's tools sheet now has
  *Export as PDF (annotations included)*, *Export this page as an image (PNG)*
  and *Export all pages as images (PNG)*; the annotation editor has an
  *Export as PDF* action plus a one-tap page-image export. Every export lands
  on the preview-before-save screen first (the all-pages export goes to a
  folder you pick and reports how many images it wrote).
- **Annotation editor**: white was added to the palette, a **Size** control
  (−/+/Reset with a live preview) drives the pen, highlighter, underline and
  strike widths, a new **Eraser** tool rubs out whatever it touches (with its
  own size control), and text notes are now **boxless** — they read as if they
  were printed on the page, both in the editor and in the exported PDF.
- **Reader**: the top and bottom bars hide themselves so they never interrupt
  reading — tap a page to bring them back, they fade out after 3 s of no
  interaction, and scrolling hides them without the scroll counting as a tap.

### 1.4 — merge / zoom / note-editor fixes
- **Merge PDFs** no longer fails with "Could not create the file"
  (`PDFMergerUtility` in legacy mode, with a manual import fallback) and a
  failed operation now reports the real reason.
- The **text-note editor** no longer jitters or shrinks the note as you type;
  `A+`/`A-`/`Reset` scale the selected note's box and text together, dragging
  needs an explicit tap-to-select first, and two-finger pinch pans/zooms.
- **Reader zoom** is smooth: bitmaps re-render only when the zoom crosses a
  bucket, the previous bitmap stays on screen while the sharper one loads, and
  panning uses raw deltas.

### 1.3 — preview before saving, and a real page builder
- Every operation now writes to a cache file and shows a **full-screen preview**
  (page thumbnails, size, suggested name) with **Save** (only then is the
  destination picked), **Share** and **Discard**.
- **New PDF** page builder: blank pages and/or gallery images, reorderable.
- **Add image pages** appends image pages to an existing PDF.
- Text notes gained a size (`fontSize`, `A-`/`A+`/`Reset`).

### 1.2 — "no more dummy UI"
Every control on every screen is now actually wired; the leftover
placeholder chrome was removed.

- **Removed** the fake profile avatar/initials badge and the "Cloud" entry in
  the create sheet — neither had any backing feature.
- **Home quick actions** (Open / Annotate / Convert / Fill Form / Sign / Scan)
  all launch their real flow instead of doing nothing.
- **Create a PDF** sheet: Blank PDF, **Scan** (camera capture + gallery pages →
  new PDF), Photos → PDF, Documents (pick several PDFs → merge), Open a PDF.
- **Scan to PDF** works end-to-end: full-resolution capture through a
  `FileProvider` cache URI, thumbnail review, remove/clear, "Save as PDF".
- **Back navigation** now pops an in-app back stack (`BackHandler`) — pressing
  back inside a document returns to the previous screen instead of exiting.
- **Annotate**: text notes are live, draggable and tappable-to-edit, and the
  **Move** tool hit-tests and drags pen/highlight/underline/strike shapes.
- **Reader zoom** anchors to the pinch focal point (and to the viewport centre
  for the +/- buttons) instead of snapping to the top-left corner.

## Features

**Viewing**
- Continuous page view built on `PdfRenderer`, pinch-to-zoom and zoom
  buttons (both anchored on the pinch focal point), go-to-page dialog, page
  thumbnails grid.
- Encrypted PDFs prompt for the password (decrypted through PDFBox first).
- Recent files + starred files, persistent across launches.
- Bookmarks (per document), annotation list, in-document **text search with
  highlights**, text/HTML export, and **export as PDF or as PNG images** (with
  annotations baked in).
- The reading chrome (top/bottom bars) auto-hides: tap a page to show it, it
  fades after 3 s of inactivity and stays hidden while you scroll.
- Registers for `ACTION_VIEW` on `application/pdf`, so "Open with DocReader"
  works from any file manager.

**Creating**
- **Blank PDF**, **Scan to PDF** (camera capture and/or gallery photos, one
  page each), **Photos → PDF**, and **merge** several PDFs into one.

**Editing** (all use PDFBox-Android)
- **Annotate**: freehand pen, highlight, underline, strike-through, and
  draggable/editable text notes, with colour palette (red/yellow/green/blue/
  purple/black/**white**), a **Size** control for stroke width, an **Eraser**,
  undo, a **Move** tool for repositioning any annotation, and an export that
  bakes the annotations into a real PDF copy (or a PNG per page).
- **Organize pages**: rotate, reorder, delete, then save; extract a page range;
  split every page into its own file inside a chosen folder.
- **Merge** several PDFs into one.
- **Compress** by rasterising pages to JPEG and rebuilding the document
  (great for scans; text stops being selectable).
- **Set / remove password** (AES-128 standard protection).
- **Fill AcroForm fields** — text fields, checkboxes, radio/choice dropdowns,
  with optional flattening.
- **Digital signature** — draw a signature, and the app applies a real
  detached CMS/PKCS#7 signature with a fresh self-signed RSA-2048 /
  SHA-256 certificate (BouncyCastle), optionally stamping the visible
  signature onto a page.
- **Print** through the Android print framework.

## Architecture

```
app/src/main/java/com/perchance/docreader/
├── DocReaderApp.kt          # Application; initialises PDFBoxResourceLoader
├── MainActivity.kt          # navigation (sealed Screen) + ACTION_VIEW handling
├── data/
│   ├── RecentStore.kt       # recents + starred, SharedPreferences/JSON
│   ├── BookmarkStore.kt     # per-document bookmarks
│   └── AnnotationStore.kt   # per-document annotations (display space)
├── pdf/
│   ├── PdfDocumentHandle.kt # PdfRenderer wrapper (+ password decryption)
│   ├── Annotations.kt       # AnnKind / Overlay model + palette
│   └── PdfOps.kt            # every PDFBox operation (merge, split, sign, …)
└── ui/
    ├── HomeScreen.kt        # dashboard, recents, quick actions, create/scan sheet
    ├── ReaderScreen.kt      # viewer, search, thumbnails, bookmarks, tools
    ├── FileMenuScreen.kt    # file actions (merge, split, compress, print, …)
    ├── AnnotateScreen.kt    # pen/highlight/underline/strike/text editor
    ├── OrganizeScreen.kt    # rotate/reorder/delete/extract/split
    ├── FillFormScreen.kt    # AcroForm filling
    ├── SignScreen.kt        # signature pad + digital signing
    ├── AnnotationOverlay.kt # draws annotations on top of a page
    ├── PdfPrint.kt          # PrintDocumentAdapter
    └── Common.kt            # BusyOverlay, DocumentGate, SAF launchers
```

Annotations are stored in **display space** (normalized 0..1 relative to the
rendered page), so the same data draws correctly at any zoom and maps cleanly
onto unrotated PDF user space when exported.

## Signing

`build-apk.yml` generates `keystore/release.jks` + `keystore.properties` on the
first run and commits them, so every build afterwards is signed with the same
key (required for Android to install it). The password is public on purpose —
fine for a hobby/test build. For a production app, move the keystore into
GitHub Secrets and reference it from the workflow instead.

## Build

Requirements: JDK 17, Android SDK 34. ~4 GB RAM is enough.

```bash
gradle wrapper          # one-time, creates ./gradlew
./gradlew assembleDebug # app/build/outputs/apk/debug/app-debug.apk
```

This scaffold was written in a workspace with no Android SDK, so it is
**compiled only by GitHub Actions**, not locally.

## Roadmap

- [x] PDF view, zoom, page counter, recents, starred
- [x] Thumbnails, bookmarks, in-document text search, text/HTML export
- [x] Annotate (pen, highlight, underline, strike, text, undo, list)
- [x] Merge / split / rotate / reorder / delete pages
- [x] Set/remove password, print
- [x] Fill AcroForm fields
- [x] Digital signature
- [x] Compress
- [x] Create PDF (blank / photos / scan-to-PDF) and merge
- [ ] PDF → Word/Excel conversion (server-side; local fidelity is poor)
- [ ] Office docs (.docx/.xlsx/.pptx) viewing (WebView + HTML renderer)
- [ ] OCR (scan-to-PDF exists, but without text recognition)
