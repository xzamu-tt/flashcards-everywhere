# ANKI_CONTRACT_REFERENCE.md

Ground-truth reference for the AnkiDroid `FlashCardsContract` ContentProvider.
Extracted verbatim from:
- `api/src/main/java/com/ichi2/anki/FlashCardsContract.kt` (main branch)
- `api/src/main/java/com/ichi2/anki/api/Ease.kt`
- `api/build.gradle.kts` (authority / permission `buildConfigField`s)
- `AnkiDroid/src/main/AndroidManifest.xml`
- `AnkiDroid/src/main/java/com/ichi2/anki/IntentHandler.kt`

---

## URIs (verbatim constants)

```
AUTHORITY            = "com.ichi2.anki.flashcards"
AUTHORITY (debug)    = "com.ichi2.anki.debug.flashcards"
AUTHORITY_URI        = Uri.parse("content://com.ichi2.anki.flashcards")
```

| Constant                       | Value                                                |
|--------------------------------|------------------------------------------------------|
| `Note.CONTENT_URI`             | `content://com.ichi2.anki.flashcards/notes`          |
| `Note.CONTENT_URI_V2`          | `content://com.ichi2.anki.flashcards/notes_v2`       |
| `Model.CONTENT_URI`            | `content://com.ichi2.anki.flashcards/models`         |
| `Card.CONTENT_URI`             | `content://com.ichi2.anki.flashcards/cards`          |
| `ReviewInfo.CONTENT_URI`       | `content://com.ichi2.anki.flashcards/schedule`       |
| `Deck.CONTENT_ALL_URI`         | `content://com.ichi2.anki.flashcards/decks`          |
| `Deck.CONTENT_SELECTED_URI`    | `content://com.ichi2.anki.flashcards/selected_deck`  |
| `AnkiMedia.CONTENT_URI`        | `content://com.ichi2.anki.flashcards/media`          |

---

## Permissions and manifest

Permission: `com.ichi2.anki.permission.READ_WRITE_DATABASE` (`protectionLevel=dangerous`).
Must be requested at runtime on API 23+.

`<queries>` block (Android 11+) is mandatory or every query returns null:
```xml
<queries>
    <package android:name="com.ichi2.anki"/>
    <package android:name="com.ichi2.anki.debug"/>
    <provider android:authorities="com.ichi2.anki.flashcards"/>
    <provider android:authorities="com.ichi2.anki.debug.flashcards"/>
    <intent><action android:name="com.ichi2.anki.DO_SYNC"/></intent>
</queries>
```

---

## Decks

`Deck` columns: `DECK_ID`, `DECK_NAME`, `DECK_COUNTS` (JSON `[learn,review,new]`),
`OPTIONS` (JSON), `DECK_DYN`, `DECK_DESC`.

There is **no parent_id** column. Deck hierarchy is `"::"`-delimited inside `DECK_NAME`.

`CONTENT_SELECTED_URI` returns the deck currently picked in AnkiDroid; you can also
update it with a `DECK_ID` ContentValues to change the active deck.

---

## Review queue (`ReviewInfo` / `schedule`)

Columns returned: `NOTE_ID`, `CARD_ORD`, `BUTTON_COUNT`, `NEXT_REVIEW_TIMES` (JSON
array of strings), `MEDIA_FILES` (JSON array of filenames).

Selection format is **NOT SQL**; it's a comma-separated `key=?` list parsed by the
provider:
```kotlin
val selection = "limit=?,deckID=?"
val args      = arrayOf("20", deckId.toString())
```
- `deckID = -1` (or omitted) → all decks.
- `limit` defaults to 1 if omitted.

Buried/suspended cards are NOT returned by `schedule`. `BURY` and `SUSPEND` are
write-only signals.

---

## Card content

Fetch front/back HTML via the per-card URI:
```
content://com.ichi2.anki.flashcards/notes/<noteId>/cards/<ord>
```

Columns: `QUESTION`, `ANSWER` (full HTML with `<style>`), `QUESTION_SIMPLE`,
`ANSWER_SIMPLE`, `ANSWER_PURE` (pre-stripped variants).

For WebView rendering use `QUESTION` / `ANSWER`.
For plain-text rendering use `QUESTION_SIMPLE` / `ANSWER_PURE`.

---

## Answering a card

```kotlin
val v = ContentValues().apply {
    put(ReviewInfo.NOTE_ID,    noteId)
    put(ReviewInfo.CARD_ORD,   cardOrd)
    put(ReviewInfo.EASE,       ease)        // 1..4
    put(ReviewInfo.TIME_TAKEN, timeTakenMs)
}
cr.update(ReviewInfo.CONTENT_URI, v, null, null)
```

`Ease` values: `EASE_1` (Again), `EASE_2` (Hard), `EASE_3` (Good), `EASE_4` (Easy).
Always read `BUTTON_COUNT` from the cursor — learning cards return 2 or 3.

Burying / suspending uses the same URI but with `BURY=1` or `SUSPEND=1` instead of
`EASE`. Mutually exclusive with answer fields.

---

## Sync trigger

```kotlin
val i = Intent("com.ichi2.anki.DO_SYNC").apply {
    setPackage("com.ichi2.anki")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
context.startActivity(i)
```
Throttled to once every **2 minutes** by `IntentHandler.INTENT_SYNC_MIN_INTERVAL`.

---

## Gotchas (verbatim from research)

1. **Package visibility (Android 11+).** Without `<queries>`, every `cr.query()`
   returns `null` and `checkSelfPermission` returns DENIED with no error.
2. **Dangerous permission** — requires runtime request.
3. **Debug vs release authority.** Local AnkiDroid debug build uses
   `com.ichi2.anki.debug.flashcards` and a parallel permission name.
4. **`BUTTON_COUNT` is dynamic** — never hard-code 4 buttons.
5. **FSRS / v3 scheduler.** Contract surface unchanged; provider translates
   ease 1..4 internally. `NEXT_REVIEW_TIMES` are pre-formatted strings — never
   parse as durations.
6. **Buried/suspended not surfaced** by `schedule`.
7. **Media bytes not readable** via the provider; `AnkiMedia` is insert-only.
8. **Selector syntax for `schedule`** is `"limit=?,deckID=?"` — not real SQL.
   No `AND`, no quoting. Provider silently falls back to defaults if malformed.
9. **`Note.CONTENT_URI`** uses Anki browser search syntax ("deck:X tag:Y"),
   `Note.CONTENT_URI_V2` uses raw SQL.
10. **`DO_SYNC` throttle** — debounce client-side or AnkiDroid drops the intent.
11. **Provider runs in AnkiDroid's process** — every call is a binder round trip.
    Batch reads with larger `limit`.

---

## License of reference code

`wlky/AnkiDroid-Wear` is GPLv2-only (no "or later" clause). It is
**license-incompatible with GPLv3**. We re-implement from this contract spec
rather than copying its code.
