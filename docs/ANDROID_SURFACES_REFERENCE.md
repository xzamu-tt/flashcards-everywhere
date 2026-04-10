# Android Ambient Surfaces Reference

Target: Kotlin + Compose, `minSdk 28`, `targetSdk 35`. Verified against current (2025/2026) Android docs.

Gradle (top-level excerpt, app module):

```kotlin
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Glance
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Lifecycle / SavedState for Compose-in-Service
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
}
```

---

## 1. Glance widget

Flashcard widget with front text + 4 grade buttons. Buttons fire a broadcast via `actionSendBroadcast` to a `BroadcastReceiver` that records the grade and then asks the widget to recompose with the next card. Uses `SizeMode.Responsive` for small/medium/large and `GlanceTheme` (Material 3 dynamic color).

### Manifest

```xml
<application ...>
    <!-- Glance widget provider -->
    <receiver
        android:name=".widget.FlashcardGlanceReceiver"
        android:exported="true"
        android:label="@string/widget_label">
        <intent-filter>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent-filter>
        <meta-data
            android:name="android.appwidget.provider"
            android:resource="@xml/flashcard_widget_info" />
    </receiver>

    <!-- Grade action receiver (fires from the widget buttons) -->
    <receiver
        android:name=".widget.GradeActionReceiver"
        android:exported="false" />
</application>
```

### `res/xml/flashcard_widget_info.xml`

```xml
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:targetCellWidth="2"
    android:targetCellHeight="2"
    android:minResizeWidth="110dp"
    android:minResizeHeight="110dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:description="@string/widget_description" />
```

### `FlashcardGlanceReceiver.kt`

```kotlin
class FlashcardGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlashcardGlanceWidget
}
```

### `FlashcardGlanceWidget.kt`

```kotlin
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

object FlashcardGlanceWidget : GlanceAppWidget() {

    private val SMALL  = DpSize(110.dp, 110.dp)
    private val MEDIUM = DpSize(250.dp, 110.dp)
    private val LARGE  = DpSize(250.dp, 250.dp)

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Fetch the current due card. Do slow work here (before provideContent),
        // then provideContent runs on the main glance thread.
        val card = FlashcardRepository.get(context).currentDueCard()

        provideContent {
            GlanceTheme {
                WidgetBody(card)
            }
        }
    }

    @Composable
    private fun WidgetBody(card: Card?) {
        val size = LocalSize.current
        Scaffold(
            backgroundColor = GlanceTheme.colors.background,
            modifier = GlanceModifier.padding(8.dp)
        ) {
            if (card == null) {
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { Text("All caught up ", style = headline()) }
                return@Scaffold
            }

            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = card.front,
                    style = when {
                        size.width >= LARGE.width  -> headline()
                        size.width >= MEDIUM.width -> title()
                        else                       -> body()
                    },
                    maxLines = if (size.height >= LARGE.height) 6 else 3
                )
                Spacer(GlanceModifier.height(8.dp))
                GradeRow(card.id, compact = size.width < MEDIUM.width)
            }
        }
    }

    @Composable
    private fun GradeRow(cardId: Long, compact: Boolean) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GradeButton("Again", 1, cardId, compact)
            Spacer(GlanceModifier.width(4.dp))
            GradeButton("Hard",  2, cardId, compact)
            Spacer(GlanceModifier.width(4.dp))
            GradeButton("Good",  3, cardId, compact)
            Spacer(GlanceModifier.width(4.dp))
            GradeButton("Easy",  4, cardId, compact)
        }
    }

    @Composable
    private fun GradeButton(label: String, grade: Int, cardId: Long, compact: Boolean) {
        val text = if (compact) label.take(1) else label
        androidx.glance.Button(
            text = text,
            onClick = actionSendBroadcast<GradeActionReceiver>(
                parameters = actionParametersOf(
                    GradeActionReceiver.KeyGrade  to grade,
                    GradeActionReceiver.KeyCardId to cardId
                )
            )
        )
    }

    private fun headline() = TextStyle(fontSize = 22.sp(), fontWeight = FontWeight.Bold)
    private fun title()    = TextStyle(fontSize = 18.sp(), fontWeight = FontWeight.Medium)
    private fun body()     = TextStyle(fontSize = 14.sp())
}
```

### `GradeActionReceiver.kt`

```kotlin
class GradeActionReceiver : BroadcastReceiver() {

    companion object {
        val KeyGrade  = ActionParameters.Key<Int>("grade")
        val KeyCardId = ActionParameters.Key<Long>("cardId")
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Glance's actionSendBroadcast encodes ActionParameters into the intent extras
        // using the parameter key's name as the extra key.
        val grade  = intent.getIntExtra("grade", -1)
        val cardId = intent.getLongExtra("cardId", -1L)
        if (grade < 1 || cardId < 0) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FlashcardRepository.get(context).grade(cardId, grade)
                // Force every instance of the widget to recompose.
                FlashcardGlanceWidget.updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
```

### Gotchas

- `provideGlance` runs on a Glance worker; do slow I/O **before** `provideContent { }`, not inside composition.
- `actionSendBroadcast` only reaches a receiver that is declared in your own manifest. Mark it `android:exported="false"`.
- `updatePeriodMillis="0"` — widgets should never self-refresh on a schedule; drive updates from data changes via `updateAll`.
- Lambda `onClick { }` runs in a WorkManager context and cannot launch activities on Android 12+; use `actionStartActivity` if you ever need to open the app.
- If you need dynamic color, wrap in `GlanceTheme { }`. For a static brand palette use `GlanceTheme(colors = ColorProviders(light, dark))`.

---

## 2. Full-screen intent + lockscreen activity

Fire a time-sensitive review reminder that wakes the screen and shows the flashcard above the lockscreen. **Android 14+ restricts `USE_FULL_SCREEN_INTENT`** — only apps whose core function is calling or alarms are granted it by default; everyone else must prompt the user and gracefully degrade.

### Manifest

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<!-- Android 12+: required to launch a full-screen activity from a BroadcastReceiver -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

<application ...>
    <activity
        android:name=".review.LockscreenReviewActivity"
        android:exported="false"
        android:showWhenLocked="true"
        android:turnScreenOn="true"
        android:launchMode="singleTask"
        android:excludeFromRecents="true"
        android:theme="@style/Theme.App.Lockscreen" />
</application>
```

### Channel + notification

```kotlin
object ReviewNotifier {

    private const val CHANNEL_ID = "review_fsi"
    private const val NOTIF_ID   = 1001

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Flashcard reviews",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Time-sensitive study prompts"
                setBypassDnd(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun showReviewFsi(ctx: Context, card: Card) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val fullScreen = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, LockscreenReviewActivity::class.java).apply {
                putExtra("cardId", card.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTap = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, LockscreenReviewActivity::class.java).putExtra("cardId", card.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_card)
            .setContentTitle("Time to review")
            .setContentText(card.front)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentTap)
            .setFullScreenIntent(fullScreen, /* highPriority = */ true)
            .setAutoCancel(true)
            .build()

        // If we do not have FSI permission, Android will just post a heads-up.
        // Check first so you can prompt the user.
        if (!canUseFullScreenIntent(ctx)) {
            // Fall back: show as heads-up only; optionally launch the prompt elsewhere.
        }
        nm.notify(NOTIF_ID, notif)
    }

    fun canUseFullScreenIntent(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // canUseFullScreenIntent() exists on API 34+ (Android 14). Below that,
        // the legacy permission rule applies and the permission is always granted.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nm.canUseFullScreenIntent()
        } else true
    }

    /** Settings deeplink to "Manage full screen intents" for this app. */
    fun openFsiPermissionSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val i = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
```

### `LockscreenReviewActivity.kt`

```kotlin
class LockscreenReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programmatic equivalents of the manifest attributes; call both to
        // handle devices where the manifest attributes are ignored.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        // Ask the keyguard to dismiss if it is non-secure.
        getSystemService(KeyguardManager::class.java)
            .requestDismissKeyguard(this, null)

        val cardId = intent.getLongExtra("cardId", -1L)
        setContent { LockscreenReviewScreen(cardId) }
    }
}
```

### Android 14/15 USE_FULL_SCREEN_INTENT rules (current)

- Permission is **protected / special-access** since Android 14. Declaring it in the manifest is no longer enough.
- On a fresh install to Android 14+, Play Store auto-revokes it unless your app's core function is calling/alarms (you declare this via Play Console's Sensitive Permission Declaration).
- Apps that had it granted under Android 13 or earlier keep it on upgrade.
- Check with `NotificationManager.canUseFullScreenIntent()` (API 34+). **Do not** rely on `checkSelfPermission` — it is not reliable for this permission.
- To prompt the user, launch `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` with `package:<your.app>` data. That lands them on "Special app access > Manage full screen intents".
- Enforcement went live **January 22, 2025**.
- Always degrade gracefully: when denied, the system still posts the notification but ignores `setFullScreenIntent`; treat it as a heads-up.

---

## 3. Heads-up notification with grade actions

No lockscreen takeover — a heads-up card with 4 action buttons that grade without opening the app. Uses `BigPictureStyle` when the card has an image. For tap-to-flip you generally prefer a distinct expanded layout over custom `RemoteViews` because custom notification layouts are collapsed on Android 12+ to the standard template anyway; shown below for completeness.

### Manifest

Same `POST_NOTIFICATIONS` permission as above. No new permissions needed.

### Builder

```kotlin
object ReviewHeadsUp {

    private const val CHANNEL_ID = "review_heads_up"
    private const val NOTIF_ID   = 2002

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Quick review",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Grade cards from the notification shade"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun show(ctx: Context, card: Card) {
        ensureChannel(ctx)

        fun gradeAction(label: String, grade: Int, icon: Int) =
            NotificationCompat.Action.Builder(
                icon, label,
                PendingIntent.getBroadcast(
                    ctx, grade * 31 + card.id.toInt(),
                    Intent(ctx, GradeActionReceiver::class.java).apply {
                        action = "com.example.flashcards.GRADE"
                        putExtra("grade", grade)
                        putExtra("cardId", card.id)
                        putExtra("notifId", NOTIF_ID)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_NONE)
             .setShowsUserInterface(false)
             .build()

        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_card)
            .setContentTitle(card.front)
            .setContentText(card.hint ?: "Tap an action to grade")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(gradeAction("Again", 1, R.drawable.ic_grade_again))
            .addAction(gradeAction("Hard",  2, R.drawable.ic_grade_hard))
            .addAction(gradeAction("Good",  3, R.drawable.ic_grade_good))
            .addAction(gradeAction("Easy",  4, R.drawable.ic_grade_easy))

        card.imagePath?.let { path ->
            val bmp = BitmapFactory.decodeFile(path)
            if (bmp != null) {
                b.setLargeIcon(bmp)
                b.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bmp)
                        .bigLargeIcon(null as Bitmap?) // collapse large icon when expanded
                        .setBigContentTitle(card.front)
                        .setSummaryText(card.hint ?: "")
                )
            }
        }

        NotificationManagerCompat.from(ctx).notify(NOTIF_ID, b.build())
    }
}
```

### Shared `GradeActionReceiver` (also used by Glance)

```kotlin
class GradeActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val grade   = intent.getIntExtra("grade", -1)
        val cardId  = intent.getLongExtra("cardId", -1L)
        val notifId = intent.getIntExtra("notifId", -1)
        if (grade < 1 || cardId < 0) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = FlashcardRepository.get(ctx)
                repo.grade(cardId, grade)

                // Widget refresh
                FlashcardGlanceWidget.updateAll(ctx)

                // Notification refresh: either cancel or show the next card
                if (notifId > 0) {
                    val next = repo.currentDueCard()
                    if (next != null) ReviewHeadsUp.show(ctx, next)
                    else NotificationManagerCompat.from(ctx).cancel(notifId)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
```

### Optional: tap-to-flip with custom RemoteViews

Android 12+ renders custom layouts inside the standard template via `setCustomContentView` / `setCustomBigContentView`. Keep it to a `TextView` and a `Button` for best compatibility:

```kotlin
val flip = RemoteViews(ctx.packageName, R.layout.notif_card_flip).apply {
    setTextViewText(R.id.front, card.front)
    setOnClickPendingIntent(
        R.id.flipBtn,
        PendingIntent.getBroadcast(
            ctx, 99,
            Intent(ctx, FlipReceiver::class.java).putExtra("cardId", card.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    )
}
b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
 .setCustomContentView(flip)
```

### Gotchas

- All `PendingIntent`s must be `FLAG_IMMUTABLE` on Android 12+.
- Do not use `setOngoing(true)` unless you attach a foreground service — Android 14+ will demote it otherwise.
- `IMPORTANCE_HIGH` is required for heads-up, but users can silence your channel; do not assume.
- `BigPictureStyle.bigLargeIcon(null)` collapses the thumbnail when expanded (prettier). The Bitmap overload exists since API 23; on API 31+ there is an `Icon` overload.

---

## 4. AccessibilityService overlay (full flavor)

Detect every foreground app change and draw a Compose overlay via `TYPE_ACCESSIBILITY_OVERLAY`. This window type is **only** usable from an `AccessibilityService` and does **not** require `SYSTEM_ALERT_WINDOW` — that is the entire reason to prefer it over `TYPE_APPLICATION_OVERLAY` in the full flavor.

| | `TYPE_APPLICATION_OVERLAY` | `TYPE_ACCESSIBILITY_OVERLAY` |
|---|---|---|
| Permission | `SYSTEM_ALERT_WINDOW` (user toggle) | None; must be added from a running AccessibilityService |
| Usable from | Any app context | Only from the AccessibilityService's context |
| Survives foreground changes | Yes | Yes, and z-ordered above most system UI |
| Play policy | Restricted | No SAW policy, but accessibility-use policy applies |

### Manifest

```xml
<application ...>
    <service
        android:name=".overlay.CardOverlayService"
        android:exported="true"
        android:label="@string/a11y_label"
        android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
        android:foregroundServiceType="specialUse">
        <intent-filter>
            <action android:name="android.accessibilityservice.AccessibilityService" />
        </intent-filter>
        <meta-data
            android:name="android.accessibilityservice"
            android:resource="@xml/a11y_card_overlay_config" />
    </service>
</application>
```

### `res/xml/a11y_card_overlay_config.xml`

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/a11y_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="200"
    android:settingsActivity=".overlay.OverlaySettingsActivity" />
```

### `CardOverlayService.kt` (Compose + lifecycle owners inside a Service)

```kotlin
class CardOverlayService :
    AccessibilityService(),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    // ---- Lifecycle / SavedState / ViewModel plumbing (required for Compose) ----
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedStateController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store
    // ----------------------------------------------------------------------------

    private lateinit var wm: WindowManager
    private var overlay: ComposeView? = null
    private var currentPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onServiceConnected() {
        // Re-assert filters programmatically (belt and braces).
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOWS_CHANGED
            notificationTimeout = 200
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return                 // ignore self
        if (pkg == currentPackage) return              // debounce
        currentPackage = pkg
        showOverlayForNextCard()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    // ---- Overlay ----
    private fun showOverlayForNextCard() {
        removeOverlay()

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@CardOverlayService)
            setViewTreeSavedStateRegistryOwner(this@CardOverlayService)
            setViewTreeViewModelStoreOwner(this@CardOverlayService)
            // Compose must be re-created per attach because there is no window token reuse.
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                MaterialTheme(colorScheme = dynamicColorSchemeOrFallback(context)) {
                    CardOverlayContent(
                        onDismiss = { removeOverlay() },
                        onGrade   = { grade ->
                            // Fire-and-forget IO; keep overlay visible until repo returns.
                            CoroutineScope(Dispatchers.IO).launch {
                                FlashcardRepository.get(context).grade(/* ... */, grade)
                                withContext(Dispatchers.Main) { removeOverlay() }
                            }
                        }
                    )
                }
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,  // <-- key line
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        wm.addView(view, lp)
        overlay = view
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }
}
```

### Enable deeplink

```kotlin
fun openAccessibilitySettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun isAccessibilityServiceEnabled(ctx: Context, cls: Class<*>): Boolean {
    val flat = Settings.Secure.getString(
        ctx.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val target = ComponentName(ctx, cls).flattenToString()
    return flat.split(':').any { it.equals(target, ignoreCase = true) }
}
```

### Gotchas

- **`TYPE_ACCESSIBILITY_OVERLAY` can only be added from a connected `AccessibilityService` context** — adding it from a normal Service fails with `BadTokenException`. That's the whole reason the pattern works without `SYSTEM_ALERT_WINDOW`.
- You must drive `LifecycleRegistry` manually. Compose composition, `rememberSaveable`, and `viewModel()` all require `ViewTreeLifecycleOwner`, `ViewTreeSavedStateRegistryOwner`, and `ViewTreeViewModelStoreOwner` respectively. Missing any of them produces obscure runtime crashes.
- Call `savedStateController.performAttach()` before `performRestore` — on newer SavedState libraries, `performAttach()` is mandatory.
- `TYPE_WINDOW_STATE_CHANGED` fires very frequently; debounce on `packageName` change like the example.
- Accessibility services on Play are scrutinized; document the user benefit in the service description string and in Play Console, or the listing will be blocked.
- Add `android:foregroundServiceType="specialUse"` on `targetSdk 34+` and declare the property in the manifest if you ever promote the service to foreground.

---

## 5. UsageStatsManager foreground detection (lite flavor)

Play-policy-safe alternative: poll `UsageStatsManager.queryEvents` on a coarse cadence. Only `MOVE_TO_FOREGROUND` events are needed. Requires the user to grant "Usage access" in Settings — it is not a runtime permission.

### Manifest

```xml
<!-- Declared so Play Console recognizes the usage; prompt users at runtime -->
<uses-permission
    android:name="android.permission.PACKAGE_USAGE_STATS"
    tools:ignore="ProtectedPermissions" />
```

### Permission helpers

```kotlin
object UsageAccess {

    fun isGranted(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
```

### Polling pattern (battery-friendly)

The trick to minimizing wake is:

1. Keep a `lastEventTs` cursor so you only query the sliver of time since the last call.
2. Run on a long interval (30s–2min) via `WorkManager` **or** whenever `ACTION_SCREEN_ON`/`USER_PRESENT` broadcasts fire. Do not use an `AlarmManager` exact alarm.
3. Pick the latest `MOVE_TO_FOREGROUND` in the window rather than iterating everything.

```kotlin
class ForegroundAppTracker(private val ctx: Context) {

    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var lastEventTs: Long =
        System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2)
    private var lastPackage: String? = null

    /** Returns the package currently in the foreground, or null if unknown. */
    fun currentForeground(): String? {
        val now = System.currentTimeMillis()
        // queryEvents returns null on API 30+ when the device is locked.
        val events = usm.queryEvents(lastEventTs, now) ?: return lastPackage

        val tmp = UsageEvents.Event()
        var latestTs = lastEventTs
        var latestPkg = lastPackage
        while (events.hasNextEvent()) {
            events.getNextEvent(tmp)
            if (tmp.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                tmp.timeStamp >= latestTs
            ) {
                latestTs  = tmp.timeStamp
                latestPkg = tmp.packageName
            }
        }
        // Advance the cursor past the end of the window to avoid re-processing.
        lastEventTs = now
        lastPackage = latestPkg
        return latestPkg
    }
}
```

### Drive it from WorkManager

```kotlin
class ForegroundPollWorker(appCtx: Context, params: WorkerParameters) :
    CoroutineWorker(appCtx, params) {

    override suspend fun doWork(): Result {
        if (!UsageAccess.isGranted(applicationContext)) return Result.success()
        val pkg = ForegroundAppTracker(applicationContext).currentForeground()
        if (pkg != null && pkg != applicationContext.packageName) {
            FlashcardRepository.get(applicationContext).onForegroundPackage(pkg)
        }
        return Result.success()
    }
}

// In Application.onCreate():
WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "fg-poll",
    ExistingPeriodicWorkPolicy.KEEP,
    PeriodicWorkRequestBuilder<ForegroundPollWorker>(15, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
        )
        .build()
)
```

### Gotchas

- **`queryEvents` returns `null` on Android 11+ when the device is locked.** Handle nulls — don't NPE.
- `queryUsageStats` (not `queryEvents`) is noisier and should not be used for foreground detection.
- The minimum periodic WorkManager interval is 15 minutes. If you genuinely need sub-minute latency, you must escalate to the accessibility service (full flavor).
- `PACKAGE_USAGE_STATS` is a signature/privileged permission on the system side; you cannot request it with `requestPermissions()`. The only path is `Settings.ACTION_USAGE_ACCESS_SETTINGS`.
- Always narrow the query window with `lastEventTs` — querying "last 24h" every tick is the #1 cause of battery regressions with this API.

---

## Permission deeplink intents (cheat sheet)

All should be launched with `FLAG_ACTIVITY_NEW_TASK` when started outside an Activity.

```kotlin
// Usage access (PACKAGE_USAGE_STATS) — lite flavor
Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

// Full-screen intent special access (Android 14+)
Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
    .setData(Uri.parse("package:$packageName"))

// Notification settings for this app (POST_NOTIFICATIONS, channels)
Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

// A specific notification channel
Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)

// Accessibility services list (system-wide; no per-app deeplink exists)
Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

// Draw-over-other-apps (SYSTEM_ALERT_WINDOW) — only if you use TYPE_APPLICATION_OVERLAY
Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    .setData(Uri.parse("package:$packageName"))

// Schedule exact alarms (Android 12+)
Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
    .setData(Uri.parse("package:$packageName"))

// Ignore battery optimizations — only for apps that legitimately need it
Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    .setData(Uri.parse("package:$packageName"))

// Generic app info page as last-resort fallback
Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    .setData(Uri.parse("package:$packageName"))
```

### Sources

- Jetpack Glance overview — https://developer.android.com/develop/ui/compose/glance
- Create a Glance app widget — https://developer.android.com/develop/ui/compose/glance/create-app-widget
- Glance user interaction — https://developer.android.com/develop/ui/compose/glance/user-interaction
- Full-screen intent notifications — https://developer.android.com/develop/ui/views/notifications/time-sensitive
- AOSP: Full-screen intent limits — https://source.android.com/docs/core/permissions/fsi-limits
- Android 14 behavior changes — https://developer.android.com/about/versions/14/behavior-changes-14
- Play Console: foreground service and full-screen intent requirements — https://support.google.com/googleplay/android-developer/answer/13392821
- AccessibilityService guide — https://developer.android.com/guide/topics/ui/accessibility/service
- thbecker/android-accessibility-overlay — https://github.com/thbecker/android-accessibility-overlay
- aug16vcc/AccessibilityServiceWithCompose — https://github.com/aug16vcc/AccessibilityServiceWithCompose
- Compose UI without an Activity (helw.net, 2025) — https://helw.net/2025/08/31/compose-ui-without-an-activity/
- Jetpack Compose inside Android Service — https://www.techyourchance.com/jetpack-compose-inside-android-service/
- UsageStatsManager reference — https://developer.android.com/reference/android/app/usage/UsageStatsManager
- Droidcon: FSI in Android 14 & 15 — https://www.droidcon.com/2025/09/02/full-screen-intent-fsi-notifications-in-android-14-15/
