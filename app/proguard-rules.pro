# Keep AnkiDroid contract constants if reflectively referenced.
-keepnames class com.ichi2.anki.FlashCardsContract$** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
