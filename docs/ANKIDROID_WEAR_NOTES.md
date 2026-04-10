# AnkiDroid-Wear research notes

`wlky/AnkiDroid-Wear` is the only known third-party AnkiDroid reviewer client.
**License: GPLv2-only** (no "or later" clause) — license-incompatible with GPLv3.
We **re-implement** the patterns below rather than copy code.

## Patterns worth porting (re-implemented in Kotlin)

### 1. Permission request
Constant: `"com.ichi2.anki.permission.READ_WRITE_DATABASE"`. Request at runtime
through `ActivityCompat.requestPermissions`. Toast on denial; the app cannot work
without it.

### 2. Due-card query
Selection is comma-separated, not SQL:
```kotlin
val selection = if (deckId == -1L) "limit=?" else "limit=?,deckID=?"
val args      = if (deckId == -1L) arrayOf("$limit") else arrayOf("$limit", "$deckId")
contentResolver.query(ReviewInfo.CONTENT_URI, null, selection, args, null)
```
`deckID == -1` means "all decks".

### 3. Per-card content URI
```kotlin
val cardUri = Note.CONTENT_URI.buildUpon()
    .appendPath(noteId.toString())
    .appendPath("cards")
    .appendPath(cardOrd.toString())
    .build()
```
Selection / sortOrder are **ignored** on this URI — pass null.

### 4. Answer submission
```kotlin
ContentValues().apply {
    put(ReviewInfo.NOTE_ID, noteId)
    put(ReviewInfo.CARD_ORD, cardOrd)
    put(ReviewInfo.EASE, ease)        // 1..4
    put(ReviewInfo.TIME_TAKEN, ms)
}
```
`update(ReviewInfo.CONTENT_URI, values, null, null)`. Identification is by the
NOTE_ID + CARD_ORD inside the values, not by the URI.

### 5. JSON-in-a-column
`MEDIA_FILES`, `NEXT_REVIEW_TIMES`, `DECK_COUNTS` come back as JSON-array strings.
Wrap with `JSONArray(...)` immediately on read.

### 6. Empty / error states
```kotlin
when {
    !hasPermission()      -> ReviewState.PermissionDenied
    cursor == null        -> ReviewState.AnkiDroidNotInstalled
    !cursor.moveToFirst() -> ReviewState.NoDueCards
    else                  -> ReviewState.Card(parse(cursor))
}
```

## Bugs in AnkiDroid-Wear we **don't** copy
- `onRequestPermissionsResult` switch with no `break` (fall-through).
- `pullScaledBitmap` sets `inJustDecodeBounds=true` then immediately `false`,
  defeating bounds pre-decode.
- Image/sound extension lists missing webp/svg/ogg/m4a/wav/opus/flac.
- Manifest missing `<uses-permission READ_WRITE_DATABASE>`.
- ContentResolver work on the main thread.

## What we add that the reference doesn't have
- Coroutine + Flow API on `Dispatchers.IO`.
- Sealed `ReviewState` instead of imperative callbacks.
- Sync-throttle awareness (2-minute debounce around `DO_SYNC`).
- Local Room queue so answers can be drained later if AnkiDroid is unreachable.
- Modern image/audio extension list.
