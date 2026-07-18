# Android Media Catalog provider

This document describes the P13 implementation of `media-catalog-v1` and `thumbnails-v1`.

## Data sources and privacy

- Shared photos, videos and audio are queried through `MediaStore.Files` with cursor-level limit/offset. The library is never materialized as one in-memory list.
- Extra user-selected folders are accessed through persisted Storage Access Framework tree grants and `DocumentsContract` cursors.
- A `content://` URI is never serialized. Network `itemId` and `albumId` values are deterministic HMAC identifiers backed by an app-private reference store.
- Only display metadata from `MEDIA_CATALOG_V1.md` is returned. EXIF is not read, so GPS and camera metadata are not exposed.
- Original streams are not opened during browsing or thumbnail list rendering. A URI is resolved only after `catalog.file.download.request`; the existing streaming File Transfer manager then hashes and sends it.

## Permissions

The Settings screen provides three explicit actions:

1. Grant the Android-version-appropriate media permissions.
2. Choose an additional folder with `OpenDocumentTree`; DeviceSync persists only read access.
3. Revoke catalog access. This disables the app-level grant, releases persisted SAF grants, cancels work and clears item references and thumbnails.

On Android 13+ the app requests `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, and `READ_MEDIA_AUDIO`; older versions use `READ_EXTERNAL_STORAGE` with `maxSdkVersion=32`. DeviceSync does not request broad file-management access.

## Paging, changes and cancellation

- Page size is validated to 1–200. Page tokens contain only an opaque encoded offset.
- MediaStore sorting and filtering are performed by the provider query. SAF folders are streamed cursor-by-cursor and intended for document/other category views.
- `ContentObserver` increments the persisted generation. Bursts are debounced for 350 ms and result in `catalog.changed` with `requiresRefresh=true`.
- Query, thumbnail and download jobs are keyed by request/query ID. `catalog.cancel`, disconnect and access revoke cancel them without a late response.
- A maximum of four thumbnail operations runs at once. Encoded results are limited to 512×512 and 256 KiB, and an 8 MiB byte-counted LRU cache is used.

## Failure behavior

- Missing/deleted content returns `ITEM_NOT_FOUND`.
- Revision mismatch after rename or modification returns `ITEM_CHANGED`.
- Cloud-only or temporarily unavailable streams return retryable `ITEM_UNAVAILABLE`.
- Revoked provider permission cancels access, clears derived caches and reports `catalog.permission` plus `PERMISSION_REVOKED`.
- The existing File Transfer size and acceptance policies still apply to original downloads.

## Tests

`MediaCatalogManagerTest` uses a fake source and transport for paging, thumbnails, validation, cancellation, permission denial, incremental refresh and download correlation. `AndroidMediaCatalogSourceInstrumentedTest` creates an app-owned MediaStore test image, queries it and verifies a bounded thumbnail; it never uses the camera.
