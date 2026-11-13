# Internationalization & Localization for OwnTV

> **Status:** approved implementation plan, **v2**. Phases 0-3 are implemented; Phase 4 remains planned.

> **Current Phase 4 policy (supersedes older planning statements below):** `tools/i18n/locales.json`
> contains the source, hidden en-GB override, original translated set, and 19 catalogue-only
> community requests. Catalogue-only languages intentionally start at 0% with no resource directory,
> are unshipped and absent from the picker, and become eligible for explicit manual packaging/picker
> promotion at 70%. Visible community rows show coverage only below 100%. See
> `tools/i18n/README.md` for the authoritative workflow and mappings.
> Written 2026-07-27 against AGP 9.2.1, Compose BOM 2026.05.00 (compose-ui 1.11.1), minSdk
> 26 / targetSdk 36. Revised through 2026-08-04, including the Phase 4a fallback and coverage policy.
> v2 changes the localisation *boundary*, not the verified Android mechanics.
>
> **For the implementing agent:** phases are in strict priority order and each is independently shippable. Do not reorder. Phase 0's guardrails are what make Phase 1's ~1,600-string extraction a ratchet instead of a hope, and Phase 3 must land before any non-Latin locale ships.
>
> Phase 0a is gated by nothing and should land first on its own; it fixes a live file-corruption bug. Phases 0b-0d carry the structural work.
>
> Every technical claim in the "Verified mechanics", locale-qualifier and APK-size sections was checked against the actual dependency jars, platform sources and built APK on this machine, **not** inferred. Several are counter-intuitive and contradict most blog guidance (`stringResource` reads `LocalResources`, not `LocalConfiguration`; `resources.arsc` is stored uncompressed; pseudolocales live on the build type). Re-verify before deviating.
>
> **Line numbers:** references were verified against the working tree at `399aef7`. Where a reference is marked `⟨verify⟩` the file is correct but the line number is known or suspected to have drifted; re-locate the symbol before editing. Do not trust a `⟨verify⟩` number as-is.
>
> **Version-sensitive syntax:** anything touching the AGP variant API, `androidResources`, Compose locals or `LocaleManager` must be re-verified against the project's actual AGP 9.2.1 / compose-ui 1.11.1 Gradle API before code is written. This document states intent and shape, not guaranteed-compiling DSL.

---

## Major architectural changes (v2)

v2 replaces the "make every layer able to speak the user's language" model with "**only the presentation layer speaks the user's language**". Concretely, this plan now:

1. **Removes the application-wide `UiText` abstraction.** No `Res` / `Plural` / `Raw` / `Join` hierarchy, no `resolve(context)`, no `asString()`, and no replacement generic message wrapper under another name.
2. **Removes the process-wide mutable `LocaleContextProvider`.** No global `@Volatile var current: Context`, no service-locator string resolution.
3. **Replaces non-Compose text builders with semantic application state plus presentation mapping.** Producers emit facts, counts, identifiers and raw external text; Compose words them.
4. **Allows only named final non-Compose renderers.** `CompanionHtml`, download notifications, existing system toasts/chooser UI, and the legacy Android TV launcher resolve through locale-wrapped contexts at render or publish time. This is not a general resource-resolution permission.
5. **Localises all OwnTV-authored Android TV launcher prose**, but gives the low-usage legacy launcher no special locale-change republish path. Existing entries update on the next normal publisher run.
6. **Removes Android 13 / API 33 system per-app-language support** and every piece of reconciliation logic that existed to serve it.
7. **Uses a SharedPreferences-backed `LocaleStore` as the single locale authority**, because the locale must be read *synchronously* from `attachBaseContext`. No DataStore key, no mirror, no dual write.
8. **Keeps the verified Android locale runtime**: Application and Activity wrapping, `Locale.setDefault`, `LocaleList.setDefault`, the four Compose locals, and script-family Activity recreation where the verified Compose limitation requires it.
9. **Replaces the unsafe `rememberSaveable` profile-gate proposal** with configuration-only retention in an Activity-scoped ViewModel that does **not** use `SavedStateHandle`.
10. **Makes the error migration independently compilable and shippable**, through an additive transitional API (preferred) or one atomic vertical slice.
11. **Preserves debug pseudolocales when release locale filtering is enabled**, and verifies them in the built debug APK rather than trusting the build flag.
12. **Compares the hardcoded-literal baseline against the pull request merge base**, not the previous commit.
13. **Defines an explicit `locales.json` schema** separating runtime tags, Android qualifiers, Weblate codes, packaging status, picker visibility and tier.
14. **Retains the established 24-language translated set and adds 19 catalogue-only requests.**
The authoritative catalogue also contains the hidden en-GB regional override; catalogue presence is
not the same as packaging or picker visibility. New requests remain unshipped until the explicit
70% readiness and manual-promotion policy is satisfied.

Everything not listed above is carried forward from v1 unchanged, including the APK measurement method, RTL rules, font rules, date/number rules, plural rules, Weblate configuration and the pseudolocale QA sweep. APK sizing applies to explicitly packaged qualifiers, not every catalogue entry.

---

## The governing architecture

OwnTV's application, domain, repository, Worker and persistence code communicate **semantic facts, values, counts, identifiers and raw external content**. They do not produce resolved translated sentences merely because they run outside Compose. **Compose resolves everything ultimately rendered by Compose.** A small named set of final renderers resolves the user-facing output that Android or the companion server renders outside Compose.

```text
Repositories / Workers / ViewModels
              │
              │ semantic state, counts, identifiers, raw values
              ▼
         Compose UI
              │
              │ stringResource / pluralStringResource
              ▼
     Final localised display text
```

The approved non-Compose rendering boundaries:

```text
Semantic state / current locale
              │
      ┌───────┼──────────┬───────────────┐
      ▼       ▼          ▼               ▼
 Companion  Download   Existing       Legacy Android
   HTML    notification toasts and     TV launcher
 renderer   renderer   chooser UI       publisher
      │       │          │               │
      └───────┴──────────┴───────────────┘
              │ locale-wrapped Context
              ▼
      Final localised output
```

The split the whole plan turns on:

```text
Application layer:
    What happened?
    What values exist?
    What stable identifier or raw message was received?

Presentation layer:
    How should those facts be worded in the current locale?
```

**Running outside Compose is not itself a reason to participate in localisation.** A repository or Worker still emits semantic data. The distinction is whether a class is the final renderer. `DownloadNotifications`, the existing system-toast/chooser sites, `CompanionHtml`, and the legacy launcher publisher really do hand final text to Android or a browser, so they are explicit presentation boundaries. This does not justify a process-wide mutable context, a generic message wrapper, or resource access in arbitrary background code.

---

## Context

OwnTV ships worldwide via GitHub releases with no server component, and its audience (IPTV on Android TV) skews heavily non-English: MENA, LATAM, Europe, South and Southeast Asia. Today the app has **zero** i18n infrastructure. `app/src/main/res/values/strings.xml` contains exactly one entry (`app_name`), and every other user-facing string is a hardcoded Kotlin literal across 261 files / ~60k lines.

Measured scope (unchanged by v2, see the note below):

| | Count |
|---|---|
| Unique translatable literals in `@Composable` code | ~970 |
| User-facing literals currently produced **outside** `@Composable` and requiring semantic-state refactoring or explicit non-Compose presentation handling (ViewModels, error mappers, enums, sync text, companion HTML, notifications, toasts, chooser UI, launcher) | ~400 |
| **Total unique strings to extract** | **~1,400 - 1,600** |
| Existing `UiText` / `StringProvider` abstraction | none |
| D-pad key interception sites needing RTL review | 24 (12 need mirroring) |
| `Text` with `maxLines` but no `overflow` | 40 |
| `basicMarquee` usages | 0 |

**Do not reduce the 1,400-1,600 figure because `UiText` is gone.** Removing `UiText` changes *where* a sentence is resolved, not *how many* sentences need translating. Launcher prose, notification text, system toasts and chooser titles remain in scope.

Outcome: source English is guaranteed complete; the established translated locales are maintained
through Weblate, while 19 requested locales are catalogue-only with no seed resources. Missing
community translations fall back to English, coverage is reported in CI, and visible community rows
show a badge only below 100%. A community locale below 70% cannot be packaged or selected; reaching
70% creates eligibility for an explicit reviewed catalogue promotion rather than an automatic release
change. The build still mechanically prevents regressions back into hardcoded English and rejects
malformed localized values.

---

## Architecture decisions

**1. Android string resources are the single runtime and the single source of truth.** No runtime JSON/catalog layer. This buys compile-time `R.string` safety, full CLDR plurals for every supported locale, automatic resource fallback (`pt-BR` -> `pt` -> `values`), automatic RTL layout direction, and native support in every translation platform. A custom catalog would forfeit all of that to buy only over-the-air translation updates, which Weblate plus a release cadence already covers.

**2. In-app locale switching without AppCompat.** `AppCompatDelegate.setApplicationLocales` requires `AppCompatActivity` and an AppCompat theme parent; this app is `ComponentActivity` on `@android:style/Theme.Material.NoActionBar`. It also works by calling `recreate()`, which is what we are avoiding. Most Android TV hardware (Fire OS, Shield, cheap boxes on API 28-33) has no system per-app-language screen at all, so the in-app picker is the primary mechanism. Instead: a `ContextWrapper` in `attachBaseContext` for cold start, `Locale.setDefault()` / `LocaleList.setDefault()` for JVM-level formatting, and CompositionLocal overrides for instant switching.

> Note: appcompat 1.7.1 is **already** on the classpath transitively via `koin-android` and `coil-core-android`. Its `abc_*` resources and ~85 locale folders are in the shipped APK today. This does not change the decision, but it means the locale growth below sits on top of an arsc that already carries significant library i18n, and it is why `localeFilters` earns its place in Phase 0.

**2a. The in-app picker is the sole locale authority on every API level, and OwnTV does not participate in the Android system per-app-language screen.** The framework `LocaleManager.setApplicationLocales()` is API 33+, and this plan's primary target is API 28-33 hardware, so an override layer of our own is required regardless. v1 then proposed a read-only reconcile that adopted a system-set locale at start-up. v2 removes that entirely.

Reason: a read-only reconcile creates two authorities that cannot converge. The system value is only writable by us (which we refuse to do, because that call recreates the Activity unless the app declares `configChanges="locale|layoutDirection"`, and this app declares **no** `configChanges` at all today). So the system value goes stale the moment the user picks a language in-app, and "system wins on detection" then silently reverts their in-app choice on the next launch:

```text
System picker  = German      (set once, never updated again)
OwnTV picker   = French      (user's actual, later choice)
Next launch    → stale German wins
```

There is no non-writing fix for that. The clean resolution is to not advertise the surface at all: no `generateLocaleConfig`, no hand-written `android:localeConfig`, no `LocaleManager` reads, no reconcile, no provenance tracking, no configuration callback that exists only to detect a system-picker change. Android Settings simply shows no per-app-language entry for OwnTV, exactly as it already does on the API 28-32 majority of the target hardware.

The architecture becomes:

```text
OwnTV in-app picker
        ↓
    LocaleStore
        ↓
Application / Activity locale runtime
        ↓
Compose and named final renderers
```

`localeFilters` is **retained**. It is independent of the system language screen: it controls which locale resources are packaged, and it is the lever that strips ~85 dependency locale folders from the APK. Its value is unrelated to whether the app advertises a locale config.

**3. Semantic state, not a translation-message type, for everything produced outside Composables.** ViewModels, Workers and repositories must stop emitting English sentences, but they must not start emitting resource references either. They emit typed state; Compose words it. See "Non-Compose text producers" below for the full classification and the replacement patterns.

**4. Packaging and selection are explicit promotion flags.** Source en-US remains the only
completeness guarantee; the hidden en-GB override is packaged but is not a separate picker choice.
The established translated locales remain packaged/selectable at this head. Catalogue-only requests
remain unshipped and hidden at 0% until a locale reaches 70% and a maintainer manually promotes its
single catalogue entry. Missing keys still fall back to English.

The measurement method was verified empirically against the built APK. **`resources.arsc` is stored uncompressed** (`991228 Stored 991228 0%`), so every byte of locale data lands 1:1 on the APK; there is no deflate discount. The measured cost model from the current arsc is approximately **20 B per (string × locale)** of table structure, plus a UTF-8 string pool at ~35.6 B/string overhead plus payload.

| | |
|---|---|
| structure: 1,600 × ~24 configs × 20 B | ~0.8 MB |
| value pool, Latin to mixed-script payloads | remeasure after each explicit packaging promotion |
| planning allowance for locale growth | **~2-4 MB, to be verified** |

Nothing meaningfully reduces locale resources once packaged: `shrinkResources` removes unreferenced resources but never strips locales; aapt2 sparse encoding requires minSdk >= 32 (we are 26, and every type chunk is confirmed `DENSE`); bundle language splits are N/A for sideloaded APKs. `androidResources.localeFilters` (a `MutableSet<String>` on the DSL) remains the explicit packaging control.

The old 111-locale projection is obsolete. Phase 0 introduces `localeFilters` and records a filtered
baseline; later measurements record only the explicitly packaged qualifiers rather than every catalogue
entry.

**Consequence to watch:** locale growth should be measured after each explicit packaging promotion
because the ABI flavor split was created after truncated-download "parse error" reports. Track
release-asset download failures after the first localized release.

**5. Translation supply chain: existing reviewed resources plus Hosted Weblate.** Do not seed or
machine-translate the 19 new catalogue-only requests. Contributors work from the project overview
across all six components; Weblate quality checks/review and GitHub PR review remain safeguards. The
captain's intended anonymous direct-write policy is non-default relative to Weblate's suggestions-only
anonymous default and carries spam, moderation, audit, and rollback implications. No production
Hosted Weblate settings or secrets are changed by this repository.

> Consequence to accept: strings added in future releases appear in English until community
> translations land. Android's resource fallback is the intended production behavior. The generated
> CI report and picker badge make missing work visible without blocking a release.

**6. Source English is en-US, with a reviewed `values-en-rGB` override.** Canonical `values/` is normalized to en-US spelling; `values-en-rGB` contains the explicit British regional overrides for favourites, colour, catalogue and programme wording. English is the only completeness guarantee. The regional file is packaged but hidden from the picker and is not a separate community translation target.

**7. One SharedPreferences-backed `LocaleStore`, because the locale is bootstrap-critical.** The selected locale must be readable *synchronously* inside `Application.attachBaseContext` and `Activity.attachBaseContext`. DataStore is asynchronous and cannot be read cleanly from those hooks without blocking a lifecycle callback or redesigning startup around a delayed bootstrap plus a possible recreation. Removing the API 33 reconcile removed the *reconciliation* complexity, not the *cold-start* requirement. So this one setting lives in SharedPreferences, alone, with no DataStore key and no mirror. Full treatment in 0b.

**8. Non-Compose resource resolution is limited to named final renderers.** `CompanionHtml`, download notifications, existing system toasts/chooser UI, and the legacy Android TV launcher genuinely hand final text to a browser or Android system UI. Each resolves resources against a locale-wrapped `Context` at render or publish time. None gets a process-wide provider, and no other code may copy the pattern without amending this document.

---

## Non-Compose text producers: classification and replacement

Every site in the codebase that currently builds user-facing English outside a `@Composable` falls into one of eight buckets. Known populations: `SyncProgressText.kt`, `SyncStatus.kt`, `ImportFinalizer.kt`, ViewModel sealed states carrying `String`, label-bearing enums, `core/util/ErrorMessages.kt`, `SettingsSearchEntry` models, positional English literals passed into data-class constructors, progress/summary builders, notifications, system toasts, chooser titles and launcher prose.

### A. Direct Compose literals

A literal that is already inside a Composable, or inside a helper only ever called from one. Replace with `stringResource`, `pluralStringResource`, or `stringArrayResource`. No architecture involved; this is the ~970-literal bulk of Phase 1.

### B. ViewModel, Worker or repository-generated sentences

The producer stops producing a sentence. It produces semantic state: counts, progress values, typed status enums or sealed interfaces, stable error categories, and raw external text where no category exists.

Replace:

```kotlin
fun progressText(
    completed: Int,
    total: Int,
): String = "$completed of $total channels imported"
```

with:

```kotlin
data class ImportProgress(
    val completed: Int,
    val total: Int,
    val itemType: ImportItemType,
)

enum class ImportItemType {
    CHANNEL,
    MOVIE,
    SERIES,
    PROGRAMME,
}
```

Compose selects a **complete sentence** resource:

```kotlin
@Composable
fun ImportProgress.displayText(): String =
    when (itemType) {
        ImportItemType.CHANNEL ->
            stringResource(
                R.string.import_channels_progress,
                completed,
                total,
            )

        ImportItemType.MOVIE ->
            stringResource(
                R.string.import_movies_progress,
                completed,
                total,
            )

        ImportItemType.SERIES ->
            stringResource(
                R.string.import_series_progress,
                completed,
                total,
            )

        ImportItemType.PROGRAMME ->
            stringResource(
                R.string.import_programmes_progress,
                completed,
                total,
            )
    }
```

Note what this preserves: the v1 rule that **grammatical sentences must never be assembled from translated fragments** survives intact, and is in fact easier to hold now. There is no `Join` type to be tempted by. Four whole sentences beat one template with a substituted noun, because the noun's grammatical case, gender and plural agreement differ by language.

Counted quantities take `pluralStringResource`:

```kotlin
data class SyncComplete(
    val channelCount: Int,
)
```

```kotlin
@Composable
fun SyncCompleteText(result: SyncComplete): String =
    pluralStringResource(
        id = R.plurals.sync_channels_complete,
        count = result.channelCount,
        result.channelCount,
    )
```

**Structured metadata is not a sentence.** The existing `listOfNotNull(...).joinToString(" · ")` sites (`SubtitleController.kt:239` ⟨verify⟩, `SeriesViewModel.kt:670` ⟨verify⟩, `ImportFinalizer.kt:24` ⟨verify⟩) produce things like:

```text
Title · Year · Rating
```

That is a metadata list, not prose, and it may be joined at the **final presentation boundary** from already-localised parts. It must not be joined in the ViewModel from English fragments, and the separator must not be part of a translated string.

### C. Display-only enums

Map the enum to resources in the presentation layer. Adding `@StringRes` display metadata onto the enum itself is acceptable **only when the enum already belongs to the UI layer** (`ZoomMode`, `MiniPlayerPosition`, `MainSection`). A domain or repository enum gets a UI-package mapper instead, so that Android resource identifiers never appear in `core/` or `data/`.

### D. Values used for both comparison and display

Split them. See "Comparison keys are not display labels" below. This is the `ChannelGenre.label` case and it is a correctness bug, not a style preference.

### E. Persisted values

Persist:

- Raw provider/server text.
- Stable identifiers.
- Domain values.
- Counts and timestamps.

Never persist:

- Resolved translations.
- Android resource IDs (not stable across builds; a persisted ID outlives the install that created it).
- Presentation message wrappers.
- Locale-specific friendly error text.

### F. Companion HTML

Extract its ~25 strings to Android resources and resolve them at HTML-render time through a locale-wrapped context. See the named-renderer rules below.

### G. Launcher strings

OwnTV-authored launcher prose is presentation text even though the Android TV system renders it. Resolve the row name, row description, fallback media type, season and episode labels through a narrow launcher renderer when the existing publisher runs. Provider titles remain raw external content. Do not add a locale-change-triggered republish path: the legacy launcher is low usage, so existing entries may remain in the previous language until the next normal refresh.

### H. Android system UI rendered by OwnTV

`DownloadNotifications`, the existing system-toast sites in `OwnTVPlayer` and `ExternalPlayerLauncher`, and the external-player chooser are final presentation boundaries. Resolve their fixed OwnTV-authored strings at display time through a locale-wrapped context. Preserve their current behavior; do not add Compose event plumbing solely for internationalization. Any unknown external text passed to one of these renderers remains raw.

### The presentation-mapper allowance

A UI-layer mapper may return `@StringRes` or `@PluralsRes` metadata where that genuinely reduces repetition (a 19-branch `when` returning resource IDs is easier to test than 19 Composables). Constraints:

- It must live in a **presentation** package.
- It must **not** resolve the resource to a `String` before rendering.
- It must **not** be persisted.
- It must **not** leak Android resources into repositories, Workers or domain services.

```kotlin
// ui/... package. Returns metadata, never a resolved String.
fun SyncFailure.messageRes(): Int?
```

Returning `null` means "there is no resource; render the raw text".

---

## Errors: a semantic classifier, migrated atomically

`core/util/ErrorMessages.kt:7-37` ⟨verify⟩ currently maps English substrings of internally-thrown exception messages onto friendly English sentences:

```kotlin
fun friendlySyncError(raw: String?, online: Boolean): String = when {
    !online -> "You appear to be offline. …"
    // … 12 mapped branches matching English needles …
    else -> raw          // arbitrary provider text, no resource id exists
}
```

v1 changed the return type to `UiText`. v2 changes it to a **semantic category**. The mapper becomes a classifier: it decides *what kind of failure this is*, and nothing about wording.

```kotlin
sealed interface FriendlySyncFailure {
    data object Offline : FriendlySyncFailure
    data object Generic : FriendlySyncFailure
    data object Timeout : FriendlySyncFailure
    data object MacNotAuthorised : FriendlySyncFailure
    data object PortalHandshakeFailed : FriendlySyncFailure

    data class Unknown(
        val rawMessage: String,
    ) : FriendlySyncFailure
}
```

```kotlin
fun classifySyncFailure(
    raw: String?,
    online: Boolean,
): FriendlySyncFailure =
    when {
        !online ->
            FriendlySyncFailure.Offline

        raw.isNullOrBlank() ->
            FriendlySyncFailure.Generic

        raw?.contains("timeout", ignoreCase = true) == true ->
            FriendlySyncFailure.Timeout

        raw?.contains(
            "Portal handshake",
            ignoreCase = true,
        ) == true ->
            FriendlySyncFailure.PortalHandshakeFailed

        else ->
            FriendlySyncFailure.Unknown(raw.orEmpty())
    }
```

Compose words it:

```kotlin
@Composable
fun FriendlySyncFailure.displayText(): String =
    when (this) {
        FriendlySyncFailure.Offline ->
            stringResource(R.string.sync_error_offline)

        FriendlySyncFailure.Generic ->
            stringResource(R.string.sync_error_generic)

        FriendlySyncFailure.Timeout ->
            stringResource(R.string.sync_error_timeout)

        FriendlySyncFailure.MacNotAuthorised ->
            stringResource(R.string.sync_error_mac_not_authorised)

        FriendlySyncFailure.PortalHandshakeFailed ->
            stringResource(R.string.sync_error_portal_handshake)

        is FriendlySyncFailure.Unknown ->
            rawMessage
    }
```

Rules carried forward from v1, all still binding:

- **Internal English exception needles are stable comparison keys and stay English forever.** `"MAC may not be authorized"`, `"Portal handshake"`, `"timeout"` are thrown by our own code and matched by our own code. Translating a thrown message silently breaks classification. Document this at both ends (throw site and classifier) and list them as a safe category in `check_hardcoded_strings.py`.
- **Preserve exactly the categories already recognized by `friendlySyncError`; internationalization does not expand error interpretation.** Existing friendly OwnTV output becomes translated. A null or blank message maps to `Generic`. A non-empty message that the current mapper does not recognize remains `Unknown(rawMessage)` byte for byte.
- **Known categories become translated only at presentation time.**
- **Unknown provider/server text remains raw.** No resource exists for arbitrary upstream text and none can.
- **EPG and sync persistence store the original raw exception text.** `EpgSyncWorker.kt:60` ⟨verify⟩ classifies today and `:64` ⟨verify⟩ calls `store.markError(source.id, message)`, which `EpgSourceStore.kt:86` ⟨verify⟩ writes into a DataStore-backed JSON list. `EpgSourceStore.kt:165` ⟨verify⟩ then does `s.lastError?.let { put("err", it) }`, exporting it into the user's backup. So `markError` stores the raw exception text and classification happens at render.
- **Backups store the raw text, not a translated sentence.** A German sentence must never be restored onto an English device, and an already-stored error must not be frozen in whatever language was active when it was written.
- **Resource IDs are never persisted.**

### Independent-shipping requirement

The plan claims every batch is independently compilable and shippable. The error migration must actually satisfy that claim. **Changing only the mapper's return type while leaving later callers on `String` is invalid** and will not compile, and a phase where raw stored exceptions are shown directly to users because the renderer has not migrated yet is not permitted.

**Chosen approach: additive transitional API.**

1. Add `classifySyncFailure(...)` alongside the existing `friendlySyncError(...): String`, which is retained temporarily and marked `@Deprecated` with a pointer to this section.
2. Migrate complete vertical slices one at a time:

```text
producer
→ state type
→ Compose renderer
→ tests
```

3. **Migrate raw EPG persistence and the EPG friendly renderer in the same atomic batch.** These two cannot be split: the moment `markError` starts storing raw text, the renderer must already be classifying it.
4. Delete `friendlySyncError` only after the last caller is migrated. Its deletion is the batch's completion signal.

**Alternative, if the slice count turns out to be small enough to be reviewable in one PR: one atomic batch** updating the semantic failure model, the classifier, every caller, every affected `String` state, the setup and settings renderers, EPG raw persistence, EPG presentation and the tests together. Either approach is acceptable. State which one was taken in the PR description; do not mix them.

---

## Comparison keys are not display labels

Any English value used as a comparison key, parser input, protocol identifier, stable matching needle or persisted identifier **must not be translated in place**.

The live instance is `ChannelGenre.label` at `LiveScreen.kt:775-776` ⟨verify⟩:

```kotlin
add(MetaChip(genre.label, dot = genre.dot, primary = genre != ChannelGenre.OTHER))
if (!categoryName.isNullOrBlank() && categoryName != genre.label) add(MetaChip(categoryName))
```

The second line dedupes the provider's own category name against the genre chip by string equality. Translate `label` and a German device stops matching a provider category of `"News"` against a label of `"Nachrichten"`, so both chips render: the localised genre next to the untranslated English category.

Fix, either shape:

```kotlin
enum class ChannelGenre(
    val canonicalLabel: String,
    @StringRes val displayLabelRes: Int,
)
```

or keep the enum clean and map it to resources in a UI package. Prefer the mapper if `ChannelGenre` is reachable from non-UI code.

Scope note preserved from v1: `label` has exactly these two usages, both in `LiveScreen.kt`. `ChannelGenre.fromCategory` is **not** affected. Its keyword lists are hand-written and already multilingual (`GenreColor.kt:74+` ⟨verify⟩ covers EN/DE/FR/ES/IT/PT/NL/PL/Nordic/TR/AR/EL/SQ) and are independent of `label`. Genre detection and dot colours are untouched; the only breakage is the duplicated chip.

This rule goes at the top of `docs/i18n.md`, verbatim:

> A string used for comparison, parsing or protocol behaviour is a key, not a display label. Never translate it in place.

---

## `CompanionHtml`: a named non-Compose renderer

`CompanionHtml` produces final user-facing HTML served over the local network to the TV owner's phone, tablet or PC, so it must resolve resources outside Compose. It does **not** get a process-wide provider for that privilege.

At render time:

1. Read the current tag from `LocaleStore`.
2. Create a locale-wrapped context via `AppLocale.wrap(applicationContext, tag)`.
3. Resolve the resources.
4. Generate the HTML.

Illustrative structure (exact API may differ):

```kotlin
class CompanionHtmlRenderer(
    private val applicationContext: Context,
    private val localeStore: LocaleStore,
) {
    fun render(): String {
        val localisedContext = AppLocale.wrap(
            applicationContext,
            localeStore.currentTag.value,
        )

        return buildHtml(
            pageTitle = localisedContext.getString(
                R.string.companion_page_title,
            ),
            addSource = localisedContext.getString(
                R.string.companion_add_source,
            ),
            submit = localisedContext.getString(
                R.string.common_submit,
            ),
        )
    }
}
```

Non-negotiable properties:

- Resolves **at render time**, so an in-session language change is reflected on the next page load.
- Uses the **current** locale, never the startup locale.
- Keeps localisation local to the HTML presentation package.
- Uses **no** global service locator.

A narrowly scoped cache keyed by locale tag is acceptable **if** repeated wrapping is measured to be a problem. Do not add one speculatively; `createConfigurationContext` on a page load that already does network I/O is not a plausible bottleneck.

`CompanionServerState.Failed("Enter a valid port between 1 and 65535.")` at `CompanionController.kt:82` ⟨verify⟩ is **not** covered by this exception. That string is rendered by Compose in the settings UI, so it follows bucket B: the sealed state carries a semantic failure (`InvalidPort`), and Compose words it.

---

## Legacy Android TV launcher localisation

The launcher publisher is a real final renderer even though the Android TV system displays its output. Localise all OwnTV-authored launcher prose:

- `"Recent Live"`.
- `"Recently watched live channels"`.
- The fallback `"Movie"` description.
- `"Season %1$d"` and `"Episode %1$d"` in both `TvHomeRepository` and `LauncherRecommendationPlanner`.

Provider movie, show, episode and channel titles remain raw external content.

Use a narrow launcher presentation helper that reads the current `LocaleStore` value, creates a locale-wrapped context at publication time, and supplies the resolved strings to the existing builders. It is an approved final-renderer boundary, not permission for repositories generally to resolve resources.

**Priority decision:** the legacy Android TV launcher is used by a small minority of OwnTV users, and the current publisher does not target the Google TV launcher. Do not introduce a special locale-change observer, coordination worker or immediate republish operation. Existing entries may remain in the previous language until the next normal publisher run. When that run occurs, every newly written OwnTV-authored field uses the current locale.

---

## Phase 0 - Foundation and pre-work fixes

Nothing moves to resources yet. This phase makes the runtime, the vocabulary and the guardrails exist first, so every later phase is protected from day one.

### 0a. Three real bugs found during the audit (fix first, independently committable)

- `player/SubtitleShift.kt:43` and `:60` ⟨verify⟩ - `"%02d:%02d:%02d%c%03d".format(...)` and `"%d:%02d:%02d.%02d".format(...)` pass no `Locale`, so Kotlin's extension uses `Locale.getDefault()`. Java's `Formatter` localises `%d` digits, so on an Arabic, Persian or Nepali device the shifted SRT/ASS file is **written to disk with Arabic-Indic or Devanagari digits** and becomes unparseable. Fix: `String.format(Locale.ROOT, ...)`. This is a live corruption bug today, not a future one.
- The shared button primitive, `StageButton` (`ui/stage/StageComponents.kt`), must ellipsize long labels (`maxLines = 1, overflow = TextOverflow.Ellipsis`), never hard-clip them: German, Finnish and Russian would otherwise lose the end of almost every button. It does.
- `MainActivity.kt` previously kept authentication in plain `remember`, so **any** Activity recreation dropped the user back to the profile gate mid-session. This was live for font-scale/dark-mode changes and became more likely once i18n added deliberate recreation. It is now an Activity-scoped, profile-bound ViewModel state as described immediately below.

#### The profile gate: configuration-only retention, profile-bound, never saveable

**Do not use `rememberSaveable`.** v1 proposed it and that is unsafe. `rememberSaveable` persists through `onSaveInstanceState`, which means the value is restored after **system-initiated process death**, not only after configuration change. A restored authentication id would put a user straight past the profile/PIN gate when Android kills the app in the background and later restores the task, which is precisely the case the gate exists to cover.

Use configuration-only session retention:

- An **Activity-scoped ViewModel**.
- **No `SavedStateHandle`** for the authentication id.
- **No `rememberSaveable`**.
- Store `authenticatedProfileId: Long?`, never a global passed/not-passed boolean.
- Require that id to equal the current active profile whenever a gate is required.
- Clear it before deleting the active profile or switching profiles, and invalidate it on every observed active-id change.
- Resets after process death and after a genuine cold start.

```kotlin
class ProfileGateSessionViewModel : ViewModel() {
    var authenticatedProfileId by mutableStateOf<Long?>(null)
        private set

    fun authenticateProfile(profileId: Long?) { authenticatedProfileId = profileId }
    fun invalidateAuthentication() { authenticatedProfileId = null }
}
```

This works because `ViewModel` instances survive configuration-driven recreation through `ViewModelStore` retention but are cleared when the process dies. Binding the id to the active profile also means authenticating A cannot authorize B after deletion, restore, or automatic profile selection.

> `authenticatedProfileId` is configuration-retained session state, not saveable state. It survives Activity recreation only while it still names the active profile, and resets after process death.

**Audit the neighbouring flags separately; do not give them all the same lifetime.** `MainActivity.kt` holds `addingProfile` and `switchProfileRequested` beside the observed `ProfileLoadState`/active-profile streams. Classify each on its own merits:

| State | Question to answer | Likely home |
|---|---|---|
| `authenticatedProfileId` | Is it an authentication result bound to one active profile? Yes. | Activity-scoped ViewModel, no saved state |
| `addingProfile` | Is it in-flight UI navigation the user would expect to survive a rotation but not a kill? | Same ViewModel, no saved state |
| `switchProfileRequested` | Same question, plus: does it pair with profile-bound authentication such that restoring one without the other is incoherent? | Same ViewModel, no saved state |
| `profileState` / active profile id | Can either asynchronous source prove that the active profile is safe? No; Room must be `Loaded`, and the loaded list must contain the active id. | Pure `shellMayCompose` policy over both observed values |

Do not batch-convert. Each flag gets its own decision recorded in the PR.

### 0b. Locale runtime - new package `core/i18n/`

```text
core/i18n/
├── AppLocale.kt
├── LocaleStore.kt
├── LocalizedContent.kt
└── SupportedLocales.kt
```

There is no `UiText.kt` and no `LocaleContextProvider.kt`. If either file exists in a branch, that branch predates v2.

| File | Responsibility |
|---|---|
| `AppLocale.kt` | Resolves a stored tag to an effective locale list; `""` reads the current device list from `Resources.getSystem().configuration.locales`. `wrap(base, tag)` uses a locale-specific `Configuration` plus `createConfigurationContext`; `applyGlobally(tag)` applies that effective list through `Locale.setDefault()` and `LocaleList.setDefault()` |
| `LocaleStore.kt` | The single locale authority. SharedPreferences-backed. BCP-47 tag, `""` means follow system. Synchronous read for `attachBaseContext`, durable write, observable `StateFlow` |
| `LocalizedContent.kt` | `@Composable` wrapper providing the four locals |
| `SupportedLocales.kt` | Generated from `tools/i18n/locales.json` plus computed coverage: runtime tag, endonym, English name, script, RTL, informational coverage % |

Companion-specific localisation lives with the companion feature, not here:

```text
core/companion/
├── CompanionHtml.kt
├── CompanionHtmlRenderer.kt      # if useful
└── CompanionHtmlLocalizer.kt     # optional, narrowly scoped
```

Do not create classes solely to match that listing. One renderer that reads `LocaleStore` and wraps a context is sufficient.

#### `LocaleStore`: one store, SharedPreferences, durable

Do **not** use a DataStore locale key, DataStore plus a SharedPreferences mirror, dual writes, a mirror repair collector, or any mirror race handling. All of that existed in v1 to reconcile two stores, and there is now only one.

Removing the API 33 system picker eliminated *reconciliation*. It did **not** eliminate the cold-start requirement. The selected locale must still be available synchronously in:

- `Application.attachBaseContext`
- `Activity.attachBaseContext`

DataStore is asynchronous and cannot be read from these synchronous lifecycle hooks without blocking or redesigning startup around a delayed bootstrap and a possible recreation. SharedPreferences is therefore the single source of truth for this one bootstrap-critical setting. It is not a general licence to move settings out of DataStore.

```text
SharedPreferences LocaleStore
        │
        ├── synchronous attachBaseContext read
        ├── language-picker writes
        ├── backup export/import
        └── StateFlow / observable in-process value
```

```kotlin
class LocaleStore(
    private val preferences: SharedPreferences,
    private val applicationContext: Context?, // null for the attachBaseContext read-only instance
) {
    private val _currentTag = MutableStateFlow(readBlocking())

    val currentTag: StateFlow<String> = _currentTag.asStateFlow()

    fun readBlocking(): String =
        SupportedLocales.canonicalTag(
            runCatching { preferences.getString(KEY_UI_LANGUAGE, "") }.getOrNull(),
        ) ?: AppLocale.SYSTEM_DEFAULT_TAG

    suspend fun set(tag: String) {
        val canonical = SupportedLocales.canonicalTag(tag)
            ?: error("Unsupported application locale: ${tag.trim()}")
        val committed = withContext(Dispatchers.IO) {
            preferences.edit()
                .putString(KEY_UI_LANGUAGE, canonical)
                .commit()
        }

        check(committed) {
            "Failed to persist application locale"
        }

        _currentTag.value = canonical
        // The Koin singleton has the Application; the attachBaseContext instance intentionally does
        // not. This updates Locale.getDefault()/LocaleList immediately after an in-session write.
        applicationContext?.let { AppLocale.applyGlobally(canonical) }
    }
}
```

Adjust the API to project conventions, but the plan's **durability requirement stands**: the value is needed on the *next cold start*, so prefer a suspend write using `commit()` off the main thread, so the operation completes only after persistence succeeds. If the implementation chooses `apply()` instead, it must explicitly accept the asynchronous durability behaviour and add a test that exercises write-then-immediate-force-stop.

**Every locale write goes through `LocaleStore`:**

- The in-app picker.
- Reset to system default (`""`).
- Backup import.
- Any settings reset operation.

Backup export reads from `LocaleStore`; backup import calls the same write API. There is no second path.

#### `""` means actively follow the current device locale

The empty stored tag is not "leave the current app locale unchanged". It is a durable instruction to follow the TV's current locale list:

```text
LocaleStore tag = ""
        │
        ▼
Resources.getSystem().configuration.locales
        │
        ├── wrap the current presentation context
        ├── Locale.setDefault(effective primary locale)
        └── LocaleList.setDefault(effective locale list)
```

`Resources.getSystem()` is allowed here only as the source of **device locale metadata**. App strings must never be resolved from it.

This distinction matters after a custom locale has already wrapped the Application. That wrapped Application context can report the previous app locale; it cannot be used to discover what the TV currently uses. Selecting "System default" must immediately apply the current device locale, persist `""`, and continue following later device-locale changes. If none of the device locales has packaged OwnTV resources, ordinary Android resource fallback selects source English.

#### Backup interaction (two steps, down from three)

`SettingsRepository.exportSettings():1126` ⟨verify⟩ serialises an explicit `backupStringKeys` whitelist (`:1085` ⟨verify⟩). With the locale living in `LocaleStore` rather than DataStore, the backup format still needs the field, but the mechanism changes:

1. Include the UI language in the exported payload, read from `LocaleStore.currentTag`. If the existing `backupStringKeys` whitelist is DataStore-keyed, add the locale as an explicit, separately-serialised field rather than pretending it is a DataStore key.
2. `importSettings()` validates the optional tag and returns a pending canonical value; `BackupManager` applies it through `LocaleStore.set(tag)` **after every database/DataStore restore operation and restore-marker cleanup** and before reporting import success. This prevents a script-family recreation from interrupting an in-flight restore.

Everything v1 said about the mirror race (`importSettings` bypassing the mirror, the `OwnTVApp.onCreate` repair collector, the force-stop window) is deleted. There is no mirror, so there is no window and no collector. This is the single largest complexity reduction in v2.

### Verified mechanics (checked against compose-ui 1.11.1 bytecode, AGP 9.2.1 jars and android-36.1 platform sources; do not substitute intuition here)

These findings are unchanged by v2 and are **more** load-bearing than before, because Compose is now the primary string-resolution boundary rather than one of several.

**The four locals, all four required.** `stringResource` / `pluralStringResource` / `stringArrayResource` all read **`LocalResources.current`**, confirmed in `StringResources_androidKt` bytecode. `LocalResources`' computed default reads `LocalConfiguration` *only to register an invalidation dependency* (the value is literally `pop`ped) and then returns `LocalContext.currentValue.resources`. **Providing `LocalConfiguration` alone therefore changes nothing.** `LocalConfiguration` is *not* deprecated in 1.11.1. `LocalResources` is a public `ProvidableCompositionLocal`, so overriding is legal.

```kotlin
CompositionLocalProvider(
    LocalConfiguration provides wrapped.resources.configuration,
    LocalContext provides wrapped,
    LocalResources provides wrapped.resources,
    LocalLayoutDirection provides layoutDirectionFor(locale),
) {
    content()
}
```

- `stringResource` reads `LocalResources`.
- `pluralStringResource` reads `LocalResources`.
- `stringArrayResource` reads `LocalResources`.
- Providing `LocalConfiguration` alone does not switch string resolution.
- `LocalContext` must match `LocalResources`, because `PainterResources` / `VectorResources` / `ColorResources` read **both**; a mismatch breaks `ldrtl`-qualified drawables.
- `LocalLayoutDirection` **must be provided manually**. It comes from `AndroidComposeView.onRtlPropertiesChanged`, i.e. the View system's resolved direction, which an in-composition override never touches. Popups and dialogs do inherit the provided value.

`MainActivity.kt` already has a `CompositionLocalProvider` (for `LocalDensity`) to extend.

**Script-change recreate.** `LocalLocaleList` is `@RestrictTo` and cannot be overridden, so ui-text shaping and CJK font fallback keep following the Activity context. Switching between same-script locales is instant; switching into or out of a different script family triggers exactly one `Activity.recreate()`. Because `LocalContext` is `staticCompositionLocalOf`, a language change already forces full subtree recomposition, still far cheaper than a recreate.

**Both `Application` and `Activity` must wrap.** `Configuration().apply { setLocales(...) }` plus `createConfigurationContext()`; `setLocales` also writes the `SCREENLAYOUT_LAYOUTDIR` bits, so `ldrtl` resolves for free, and `updateFrom` merges locale-only as a delta. Application-only leaves the Activity wrong: `ActivityThread.createBaseContextForActivity` never derives from the Application object.

**Application wrapping is retained deliberately, on a platform ground that survives v2.** v1's stated reason was partly that Koin singletons and Workers resolved strings against the injected Application context (`OwnTVApp.kt:43` passes `androidContext(this@OwnTVApp)`). Under v2 they no longer resolve strings at all, so that reason is gone. The *other* reason is not: `ConfigurationController.handleConfigurationChangedInner` unconditionally calls `updateLocaleListFromAppContext(...)`, which **resets `Locale.getDefault()` to the device locale on every system configuration change** unless the Application context carries your locale. That affects `java.text`, `java.time`, `String.format` and every `Locale.getDefault()` reader in the process, none of which are Compose.

The Application context carries the **startup** locale and does not mutate after a same-script in-session switch. Therefore wrapping at startup is necessary but not sufficient. `OwnTVApp.onConfigurationChanged(newConfig)` must re-read `LocaleStore.readBlocking()` and call `AppLocale.applyGlobally(tag)` after `super.onConfigurationChanged(newConfig)`. This restores the current selected locale after the framework's process-level reset. When `tag == ""`, `applyGlobally` resolves the new device locale list through the system-locale rule above.

Do not remove Application wrapping unless the revised implementation provides an equally verified replacement covering all of:

- Configuration changes.
- `Locale.getDefault()`.
- `LocaleList.getDefault()`.
- Java date and number formatting.
- Process-level locale resets.

Call `Locale.setDefault()` **and** `LocaleList.setDefault()` for `java.text` / `java.time` / `String.format`. Not needed for `DateFormat.getTimeFormat(ctx)`, which reads the context's own configuration. Do not route app strings through `Resources.getSystem()`; it is a process-wide system singleton.

**What `attachBaseContext` wrapping does and does not fix.** It establishes the Application's `Resources` object once, at process start, and `Locale.setDefault()` does not retroactively mutate that object's `Configuration`. General non-presentation code therefore does not resolve strings from the Application. Each approved non-Compose final renderer builds a locale-wrapped context at render or publish time from the current `LocaleStore` value, so it never relies on the startup `Resources` configuration for user-facing text.

Other modified files in 0b: `OwnTVApp.kt`, `MainActivity.kt`, `features/settings/data/SettingsRepository.kt` (locale export/import wiring to `LocaleStore`).

### Gradle: locale filters and debug pseudolocales

```kotlin
android {
    androidResources {
        // Values come from the resourceQualifier field of tools/i18n/locales.json
        // entries with packaged = true. NOT languageTag, NOT weblateCode.
        localeFilters += packagedLocaleQualifiers
    }
    buildTypes {
        debug {
            isPseudoLocalesEnabled = true    // BuildType, NOT androidResources
        }
    }
}
```

`localeFilters` is `val localeFilters: MutableSet<String>` on the DSL (`+=` / `addAll`, never `=`); the `SetProperty<String>` form is the *variant* API, not this one.

**Removed from v1:** `generateLocaleConfig = true`. The app no longer advertises a per-app-language surface (decision 2a), so nothing consumes a generated `<locale-config>`. Do not add a hand-written `android:localeConfig` either. This also removes the requirement for `app/src/main/res/resources.properties` containing `unqualifiedResLocale=en-US`, which existed **only** to satisfy `generateLocaleConfig`. Delete that file unless a separate, verified build behaviour still needs it; if the implementing agent finds AGP 9.2.1 requiring it for another reason, keep it and record the reason here.

Also removed with it: the known upstream non-determinism issue [281825213](https://issuetracker.google.com/issues/281825213) in generated locale-config ordering, and the build failure that occurred when a hand-written `<locale-config>` coexisted with generation. Neither is reachable now.

**`localeFilters` still ships in Phase 0**, for the reason v1 identified and for a second reason v2 adds:

- It strips **library** locale folders at merge time. appcompat 1.7.1 alone contributes ~85 locale
  folders to the APK that ships today. Setting the filter to the explicitly packaged catalogue set
  removes them before OwnTV translations are added. The Phase 0 baseline is recorded; later measurements
  cover only qualifiers promoted for release.
- It is what makes the catalogue packaging flags meaningful. `app/build.gradle.kts` reads only
  `packaged = true` entries and never infers packaging from catalogue membership or coverage. A
  catalogue-only entry therefore cannot add a locale to a release by existing in `locales.json`.

#### Pseudolocales must survive the filter

Locale filtering can strip the generated pseudolocales, because they are ordinary locale configurations as far as resource merging is concerned. `isPseudoLocalesEnabled = true` generates them; `localeFilters` can then remove them, and the result is a debug build where the pseudolocale sweep (Phase 3g) silently does nothing.

Required end state:

```text
Release variants:
    packaged production locales only

Debug variants:
    packaged production locales
    + en-rXA
    + ar-rXB
```

Note the two spellings, which are not interchangeable:

```text
Runtime pseudolocale tags:              en-XA    ar-XB
Android resource/filter qualifiers:     en-rXA   ar-rXB
```

Use the AGP variant API appropriate to the project's actual AGP version to add the debug-only qualifiers to the filter set. **Re-verify the exact AGP 9.2.1 syntax against the installed Gradle API before writing code** - the extension-level `androidResources.localeFilters` is a `MutableSet<String>`, while the per-variant form is a `SetProperty<String>` reached through the variant API, and the two are configured differently.

Do not ship pseudolocales in release builds. Do not show them as normal production entries in the language picker.

**Verification is mandatory and is not "the flag is set".** Add a build check that inspects the **built debug APK** and confirms both pseudolocale configurations are present in the resource table, and a matching check that the release APK contains neither. Enabling `isPseudoLocalesEnabled` alone is not proof; that is precisely the failure mode this section exists to prevent.

### 0c. Resource file layout

Split by area so 1,600-string PRs stay reviewable. Weblate's component-discovery addon picks these up automatically.

```text
values/strings.xml            common actions, buttons, generic errors
values/strings_setup.xml      onboarding, add-source, companion
values/strings_settings.xml   settings, sources, customization, backup and metadata
values/strings_player.xml     player HUD, tracks, diagnostics and subtitles
values/strings_content.xml    home, live, movies, series, epg, search and downloads
values/strings_features.xml   profiles, home rows, search, updates, recovery and launcher copy
values/donottranslate.xml     brand + protocol constants, translatable="false"
```

Key convention: `<area>_<screen>_<element>[_<variant>]`, snake_case: `settings_network_proxy_invalid_port`, `player_error_both_engines_failed`. Shared strings use `common_`.

### 0d. Guardrails (built now so Phase 1 is a ratchet, not a hope)

#### `tools/i18n/check_hardcoded_strings.py` - an explicit literal inventory ratchet

The original design matched `Text(` / `text =` / `label =` / `contentDescription =`. Those four patterns describe Composable call sites, but the scope table counts **~400 user-facing literals produced outside Composables**, which is exactly the population the patterns cannot see. Two confirmed misses:

| Site | Literal | Matched by the four patterns? |
|---|---|---|
| `SettingsScreen.kt:591` ⟨verify⟩ | `SettingsSearchEntry("Profile", "Profiles", "viewers kids mode pin lock account", ...)` | no, positional args to a data class |
| `CompanionController.kt:82` ⟨verify⟩ | `CompanionServerState.Failed("Enter a valid port between 1 and 65535.")` | no, sealed-state constructor arg |

Add enum constants, HTTP page bodies, notification text and repository-built strings to that list and the guard is blind to most of what Phase 1 must extract. A guard that reports clean while ~400 strings stay hardcoded is worse than no guard, because it is the thing the plan cites as making extraction "a ratchet instead of a hope".

Mechanism: **a complete occurrence-aware inventory of every Kotlin string literal, divided between two explicit reviewed files.** The tokenizer walks all `.kt` files under `app/src/main/java` but does not guess whether a literal is SQL, JSON, a log message or UI copy.

- `tools/i18n/hardcoded_baseline.txt` is the existing translation debt and may only shrink.
- `tools/i18n/safe_literals.txt` holds technical literals that are intentionally not translated. Every entry has a category whose reason is documented in the manifest header.

The union of both files must exactly equal the current source inventory. Identity includes file, normalised content and occurrence count. File plus content alone is insufficient: if `"Try again"` is already present once, adding a second occurrence in the same file must change the count and fail CI. Each Phase 1 batch deletes occurrences from the hardcoded baseline. At the end of Phase 1 that baseline is empty; technical literals remain explicit in the safe manifest.

This replaces custom SQL grammar, JSON receiver discovery and Kotlin call-position inference. Those mini-parsers were complex while still unable to prove user intent. If a new technical literal is intentional, classify it explicitly with `classify-safe`; otherwise move user-visible copy to Android resources. Revisit UAST lint only if explicit-manifest maintenance becomes a demonstrated bottleneck.

#### Boundary rule (replaces v1's `getString()` receiver check)

v1's CI rule was:

> `getString()` must use `LocaleContextProvider.current`.

That rule is deleted along with the provider. It is replaced by a **location** rule, which is what it was really trying to express:

> User-facing resources may be resolved only in approved presentation-layer locations. Non-presentation code emits semantic state, identifiers, counts or raw external values.

**Allowed to resolve resources:**

| Location | Why |
|---|---|
| Composable functions | The primary boundary; reads live wrapped `LocalResources` |
| Compose presentation helpers | `@Composable` extension functions on semantic state |
| UI-layer resource mappers | May return `@StringRes` / `@PluralsRes` metadata; must not resolve to `String` |
| `CompanionHtmlRenderer` / a narrowly scoped companion localiser | Final HTML renderer |
| `DownloadNotifications` | Final Android notification renderer; resolves the channel and fixed notification text at build time |
| Existing system-toast and chooser presentation sites in `OwnTVPlayer` / `ExternalPlayerLauncher` | Final Android UI renderers; preserve existing behavior without generic plumbing |
| Narrow launcher presentation helper used by `TvHomeRepository` / `LauncherRecommendationPlanner` | Final legacy Android TV launcher publisher; resolves only OwnTV-authored prose |
| Locale bootstrap and runtime code (`core/i18n/`) | Builds the wrapped contexts everything else uses |
| Tests | Assert on resolved output deliberately |

**Not allowed to hold resolved user-facing text:**

| Location | Why |
|---|---|
| Repositories | Emit domain values, not sentences |
| Workers | Emit progress and typed results; a Worker may invoke the approved notification renderer but does not word notification text itself |
| Persistence stores | Persisted translations and resource IDs both outlive their validity |
| Sync engines | Emit counts and typed failures |
| Domain services | Language-free by definition |
| Import/export business logic | Backup contents must be locale-neutral |
| Generic dependency-injection singletons | No stable notion of "current screen locale" |
| Background controllers that are not final renderers | If it does not render, it does not word |

#### Safe categories, each with its reason

Category reasons live in the header of `safe_literals.txt` and are validated by the checker:

| Category | Reason it is never translated |
|---|---|
| Log tags and log messages | Read by developers and bug reporters, not users; English keeps issue reports greppable |
| `donottranslate` brand and protocol constants | Product identity and wire values |
| BCP-47 language tags | Identifiers consumed by the locale runtime itself |
| MIME values | Protocol constants |
| JSON field names | Wire format; translating breaks parsing on the other side |
| SQL | Query syntax |
| Regex | Pattern syntax |
| URLs and paths | Addressing, not prose |
| DataStore / SharedPreferences key names | Persisted identifiers; a renamed key silently loses user data |
| Stable English comparison needles in `ErrorMessages.kt` | Matched against internally-thrown exception text; translating either side breaks classification |

Identifier spelling or nearby syntax is not proof of any category. `sign-in`, `retry_later`, SQL-looking text, JSON-looking text, and assertion messages all enter the hardcoded baseline unless their exact path/text/count is explicitly present in `safe_literals.txt`.

Do **not** blanket-exempt `require`, `check` or `error` messages. Existing code sometimes catches those exceptions and renders `exception.message`, for example backup parsing and update failures. During extraction:

1. If the message can reach users, replace it with semantic failure state and translate at the final presentation boundary.
2. If it is confirmed developer-only, classify that exact literal in `safe_literals.txt` under `assertion`.

No automatic call-graph analysis is required. The manifest diff is the reviewed proof.

#### `tools/i18n/validate_strings.py`

Placeholder parity against source, `'` `%` `&` `<` escaping, duplicate keys, `translatable="false"` keys leaking into translations, and plurals carrying the quantities that locale actually requires. It also computes the **informational per-locale coverage report** from `tools/i18n/locales.json` and the resource tree. Missing community keys do not affect the exit code; present-but-invalid localized values do.

#### `.github/workflows/i18n.yml` - baseline ratchet against the merge base

A **separate, lightweight** workflow. The repo deliberately restricts `android.yml` to `main` and `v*` tags, so this must not piggyback on it.

The v1 check compared the committed baseline against `HEAD^`. **That is bypassable in a multi-commit pull request**: a baseline can grow in commit 2 and stay unchanged in commit 5, so the final commit's comparison against its immediate parent passes while the PR as a whole regressed.

Required invariant:

```text
PR-head baseline ⊆ pull-request merge-base baseline
```

The workflow must:

- Run on `pull_request`.
- Fetch sufficient Git history, or explicitly fetch the base branch.
- Calculate the merge base between the PR head and the target branch.
- Read the baseline from that merge-base commit.
- Fail if the current baseline contains any entry absent from the base baseline.
- **Separately** fail if a new unsafe literal is added to code and hidden by adding it to the baseline. These are two distinct failures: the first catches growth, the second catches a developer "fixing" the guard by feeding it.

```yaml
on:
  pull_request:

jobs:
  i18n:
    steps:
      - uses: actions/checkout@v7
        with:
          ref: ${{ github.event.pull_request.head.sha }}
          fetch-depth: 0
```

```bash
git fetch origin "${GITHUB_BASE_REF}"
BASE_SHA="$(git merge-base HEAD "origin/${GITHUB_BASE_REF}")"

python3 tools/i18n/check_hardcoded_strings.py verify-ci --base-sha "$BASE_SHA"
```

The checker reads the baseline from that merge-base commit and compares it with the working tree. If the literal tokenizer's extraction semantics change, the generated baseline carries an explicit `scanner-version` marker and the reviewed `tools/i18n/baseline_migration.json` must pin the old/new versions. The introducing migration PR must not change `app/src/main` (the workflow enforces this); land application changes separately, so `--bootstrap` cannot hide a new UI literal behind a scanner-version bump. The migration runs exact bootstrap verification once, and subsequent PRs return to the merge-base ratchet. This prevents scanner artifacts from being reported as code regressions without creating a permanent bypass.

If the workflow also runs on protected-branch pushes, define the non-PR comparison source **explicitly** (for example, the previous release tag, or the commit the branch protection last verified). Do not let it silently fall back to `HEAD^`, which reintroduces the bug this section fixes.

---

## Phase 1 - String extraction (the bulk)

One PR per batch, ordered by user-visible impact. English only; no translations yet. Feature-first ordering is kept where practical.

Every batch uses one or more of the eight extraction categories:

1. Direct Compose resource replacement.
2. Semantic-state refactoring for ViewModels and Workers.
3. Presentation mapping for statuses and enums.
4. Canonical/display splitting for comparison keys.
5. Raw persistence plus render-time classification.
6. Companion HTML extraction.
7. Launcher presentation extraction.
8. Named Android system-UI renderer extraction.

### Batch 1. Shared primitives, then the error classifier as an atomic slice

Split into 1a and 1b so each half compiles and ships on its own.

**1a. `ui/components/*`.** Pure category 1. Direct `stringResource` replacement across the shared primitives. No architecture change, no callers to migrate. Lands independently.

**1b. Error classification, one atomic vertical slice.** Categories 2 and 5 together.

Using the **additive transitional API** chosen above:

1. Add `classifySyncFailure(...)` and `FriendlySyncFailure` next to the existing `friendlySyncError(...): String`, which stays temporarily.
2. Migrate each consuming slice end to end: producer, state type, Compose renderer, tests.
3. **EPG raw persistence and the EPG friendly renderer migrate in the same commit.** `EpgSyncWorker` starting to store raw text and `EpgScreen` starting to classify it are one change, not two. There must be no intermediate state in which stored raw exception text reaches the user unclassified.
4. Delete `friendlySyncError` when the last caller is gone.

Contracts to document at both ends: the English needles in `ErrorMessages.kt` are load-bearing comparison keys and stay English forever; the mapper's *output* is what becomes a resource; the mapper's *input* never does.

### Batch 2. `features/setup/`

`SetupWizard`, `AddSourceScreen`, `AddSourceChooserScreen`, `SetupViewModel`. First-run flow, highest impact for a new non-English user. Categories 1 and 2: `SetupViewModel`'s status sentences become semantic state.

### Batch 3. `features/shell/`

`Sidebar`, `TopBar`, `OwnTVShell`, and `components/SettingsScreen.kt` (1,997 lines, 68 literals, the single largest file), plus `ShellViewModel.MainSection` enum labels. Categories 1 and 3. `MainSection` is a UI-layer enum, so `@StringRes` display metadata on the enum is acceptable here.

Include the `SettingsSearchEntry` block (`SettingsScreen.kt:591-647` ⟨verify⟩). Those are positional English literals in a data class, invisible to a pattern-based guard, and they are user-facing search terms: both the display title and the keyword list need resources, and the keyword list needs a translator comment explaining that it is a search keyword bag, not prose.

### Batch 4. `features/settings/*`

Sub-screens plus `SettingsViewModel` (18 literals, 4 sealed state types carrying `String`) plus the ~10 label-bearing enums in `SettingsRepository.kt`. Categories 1, 2 and 3.

The four sealed state types carrying `String` are the canonical bucket-B population: each becomes a semantic state with a Compose renderer. `CompanionServerState.Failed` (`CompanionController.kt:82` ⟨verify⟩) belongs to this batch, not to the companion HTML batch, because it is rendered by Compose.

The `SettingsRepository.kt` enums are domain-side, so they get UI-package mappers rather than `@StringRes` fields.

### Batch 5. `features/home` / `live` / `movies` / `series` / `search`

Plus their ViewModels, `HomeRow`, and `GenreColor` (19 genre labels). Categories 1, 2 and 4.

This is where the canonical/display split lands: `ChannelGenre.label` stays the untranslated comparison key used at `LiveScreen.kt:776` ⟨verify⟩, and display goes through `displayLabelRes` or a UI mapper at `:775` ⟨verify⟩. Ship the split and the regression test together.

### Batch 6. `features/epg/`

`EpgViewModel` (16 literals, the heaviest ViewModel). Categories 1 and 2. Note that the EPG *error* path already migrated in batch 1b; this batch is the remaining EPG UI text.

### Batch 7. `player/`

`PlayerHud`, `OwnTVPlayer` (~35 error strings), `ExoSubtitleEngine`, `LivePreviewEngine`, `PlayerDiagnostics`, `AudioNowPlayingBar`, `ZoomMode`, `MiniPlayerPosition`, and `core/player/ExternalPlayerLauncher.kt`. Categories 1, 3 and 8. `ZoomMode` and `MiniPlayerPosition` are UI enums and may carry `@StringRes` directly. Preserve the existing system-toast and chooser behavior; resolve fixed OwnTV messages at display time through the current-locale context. Do not add Compose event plumbing solely for this migration.

### Batch 8. `features/downloads` / `subtitles` / `profiles` / `customize` / `update`

Plus `core/subtitles/` and `core/download/DownloadNotifications.kt`. Categories 1, 2, 3 and 8. The download notification channel name is localized. The renderer caches the last effective locale/name key process-wide, calls `createNotificationChannel` on the first notification and after a locale change, and lets Android update the existing channel's visible name without a locale observer.

### Batch 9. `core/sync/` text builders

`SyncProgressText.kt`, `SyncStatus.kt`, `ImportFinalizer.kt`. **This batch is entirely category 2** and is the largest single piece of semantic-state refactoring in the plan. These files exist only to build English sentences; after this batch they either disappear or become pure state factories.

Heavy `<plurals>` work here (`"N channels"`, `"N movies"`, `"N programmes"`, `"N warning(s)"`), all resolved via `pluralStringResource` in Compose against `ImportProgress` / `SyncComplete`-style state. The `joinToString(" · ")` site in `ImportFinalizer.kt:24` ⟨verify⟩ is structured metadata and moves to the presentation boundary; it does not become a translated template.

`SyncStatus.kt:142-144` ⟨verify⟩ currently concatenates sentence fragments. Each becomes a full parameterised sentence resource selected by a semantic status, never a runtime concatenation.

### Batch 10. `core/companion/CompanionHtml.kt`

The remote-facing web UI (~25 strings). Category 6. Extract to resources and route through the render-time locale-wrapped context described above. This batch introduces `CompanionHtmlRenderer` or equivalent.

### Batch 11. Legacy Android TV launcher, lowest priority

`core/tv/` and `core/launcher/`. Category 7. Extract every OwnTV-authored launcher field listed in the launcher section and resolve it through the narrow publisher-time presentation helper. Provider titles remain raw. This batch is intentionally last because the legacy Android TV launcher has low usage and does not cover the Google TV launcher. Do not add a language-change observer or special republish path; verify only that the next normal publication uses the current locale.

### Authoring rules, documented in `docs/i18n.md`

- Always positional (`%1$s`), never bare `%s`. Translators must be able to reorder.
- **One string per sentence. Never concatenate fragments.** The codebase currently does this in ~10 places (`BackupViewModel.kt:100,125` ⟨verify⟩, `SetupViewModel.kt:379` ⟨verify⟩, `SyncStatus.kt:142-144` ⟨verify⟩, `OwnTVPlayer.kt:2751` ⟨verify⟩) and each becomes a full parameterised sentence. Structured metadata joins (`Title · Year · Rating`) are the documented exception and happen at the presentation boundary only.
- **A string used for comparison, parsing or protocol behaviour is a key, not a display label. Never translate it in place.**
- `<plurals>` for anything counted, never `"s"`-suffix branching (`EpgViewModel.kt:448` ⟨verify⟩, `SyncManager.kt:130` ⟨verify⟩, `DownloadStatusStrip.kt:56` ⟨verify⟩). Verified: Android resolves quantities through bundled ICU (`PluralRules.forLocale`) on API 24+, so full CLDR rules for Arabic's 6 forms, Polish's 4 and Russian's 3 are free, with nothing to hand-implement. Exactly six valid keywords: `zero one two few many other`. **`other` is mandatory in every `<plurals>`** or lookup throws `NotFoundException` at runtime; `validate_strings.py` must enforce it. The plural rule object is derived from *that `Resources` instance's* configuration, which is a second independent reason `LocalResources` must be the wrapped one, or Compose picks quantities using the device locale's rules against your overridden strings.
- `translatable="false"` for brand and protocol strings.
- `<xliff:g>` around placeholders and a preceding `<!-- Translators: ... -->` comment on any
  non-obvious key. Weblate shows both to translators.
- Never persist a resolved translation or a resource ID.

---

## Phase 2 - Language picker

New `features/settings/LanguageSettingsScreen.kt`, wired in through the existing settings pattern rather than any new navigation: add `LANGUAGE` to the private `SettingsTab` enum (`SettingsScreen.kt:100` ⟨verify⟩), one `SettingsRow(... onClick = { open(SettingsTab.LANGUAGE) })` under a new "General" group near Profile, and a matching `SettingsSearchEntry` in the block at `SettingsScreen.kt:591-647` ⟨verify⟩ so it is reachable from settings search.

Write path:

```text
Language picker
      │
      ▼
LocaleStore.set(tag)
      │
      ├── durably persist SharedPreferences
      ├── update StateFlow
      ├── apply Locale.setDefault / LocaleList.setDefault
      └── update the Compose locale context
```

There is no DataStore observation and no system-picker reconciliation on this path.

Twenty-five rows including "System default" still require deliberate D-pad design, so the useful picker affordances are preserved:

- "System default" pinned first, written as the empty tag `""`, then A-Z by **English name**; each row still shows the native endonym alongside it.
- Each row shows endonym, English name and an informational coverage badge, sourced from generated
  `SupportedLocales.kt`. The badge never disables a row or promises completeness.
- Reuse of the existing `ui/components/SearchBar.kt` for filtering. Alphabet-bucket jumping is optional at this size and should be added only if device testing shows it helps.
- Endonyms must render with `FontFamily.SansSerif`, never the bundled Lora (see Phase 3a).
- Same-script switches apply instantly with no restart; script-family changes trigger the single documented Activity recreation.
- D-pad reachability: every control focusable, no horizontal-only affordances.

**The picker shows entries explicitly enabled for release**, where `packaged = true` **and**
`pickerVisible = true`. Coverage is not part of that predicate. Every source/community language is
enabled even while incomplete, and missing keys fall back to English. The en-GB regional override and
pseudolocales are not normal picker entries.

---

## Phase 3 - Locale correctness hardening

Must land **before** the first non-Latin locale ships, or the first Arabic user sees tofu in a mirrored, clipped UI. Unchanged from v1 except where noted; none of these depend on the v2 boundary change.

**3a. Fonts.** `ui/theme/PopupTheme.kt` overrides all 15 typography slots with bundled Lora, which has no CJK, Arabic, Hebrew, Indic or Thai glyphs. Add `popupFontFamily()` returning Lora only for Latin/Cyrillic/Greek scripts and `FontFamily.SansSerif` otherwise. Patch `features/live/LiveScreen.kt:822` ⟨verify⟩ (`PopupFontFamily`) and `player/AudioNowPlayingBar.kt:184,198,204` ⟨verify⟩. The `AudioNowPlayingBar` track title and subtitle carry **arbitrary user and provider content**, so those two use SansSerif unconditionally regardless of UI locale. Also add a CSS fallback stack to `core/companion/CompanionHtml.kt:25` ⟨verify⟩, which serves the same Lora file to the phone.

**3b. Dates.** Replace hardcoded patterns with `DateFormat.getBestDateTimePattern(locale, skeleton)`: `EpgScreen.kt:269` ⟨verify⟩ (`"EEE d MMM"`), `LiveScreen.kt:847` ⟨verify⟩, `SettingsScreen.kt:1191,1214` ⟨verify⟩ (which also hardcodes 24-hour, ignoring the user's system setting). Extend the existing `ui/format/TimeFormat.kt`; it already has the right locale-aware pattern via `DateFormat.getTimeFormat(context)`. The 12/24-hour choice follows the **system** preference, not the UI language.

**3c. Numbers.** Split every `.format(` site into DISPLAY (locale default is correct: `CountBadge.kt:42` ⟨verify⟩, durations, ratings) and PROTOCOL/PERSISTENCE (must force `Locale.ROOT`: `SettingsScreen.kt:825` ⟨verify⟩ accent hex, `MovieHash.kt:27` ⟨verify⟩, plus the `SubtitleShift.kt` fix from Phase 0a). Add a grep gate for unlocalised `%d` on persistence paths. Note `Pin.kt:28` ⟨verify⟩ and `TvHomeRepository.kt:611` ⟨verify⟩ use `%02x`, which Java's `Formatter` does **not** localise; those are safe as-is.

**3d. RTL D-pad.** Governing rule: **media transport stays physical, navigation mirrors.**

- Keep physical (do not touch): `PlayerHud.kt:714-715` and `:759-760` ⟨verify⟩ (seek), `TrailerPlayerScreen.kt:135-136` ⟨verify⟩.
- Mirror: `PlayerHud.kt:221` ⟨verify⟩ (drawer open), `ChannelListOverlay.kt:76` ⟨verify⟩ (drawer dismiss), `EpgScreen.kt:686,687` ⟨verify⟩, `AddSourceScreen.kt:503-504` ⟨verify⟩, `SettingsScreen.kt:991-992,1045-1046` ⟨verify⟩, `AudioNowPlayingBar.kt:152-153` ⟨verify⟩.
- `focusProperties { right = ... }` / `{ left = ... }` at `EpgScreen.kt:643` ⟨verify⟩ and `ProfileComponents.kt:114,119` ⟨verify⟩ switch to the layout-direction-aware `start` / `end` equivalents.
- New `core/i18n/RtlKeys.kt` helper resolving `Key.DirectionLeft/Right` against `LocalLayoutDirection`.

**3e. RTL layout.**

- `player/MiniPlayerLayout.kt:39-44` ⟨verify⟩ - the user picks a *physical* corner ("Top left"), so the mapping to `Alignment.TopStart`/`TopEnd` must invert under RTL to keep "Top left" visually left.
- `features/home/HomeScreen.kt:764` ⟨verify⟩ - mirroring `offset(x=)` fed by an absolute `rect.left`.
- `SettingsScreen.kt:1004,1063` ⟨verify⟩ - hue/saturation knobs over `Brush.horizontalGradient`; force those two Boxes to LTR (a colour spectrum has no reading direction).
- `features/epg/GuideCore.kt:104-140` ⟨verify⟩ - the EPG strip is a raw `Canvas` with no layout-direction awareness, so it stays LTR while its parent `Row` mirrors, desyncing the axis from the strip. Force the whole guide grid to LTR; a broadcast timeline is inherently left-to-right.
- `PlayerHud.kt:719-777` ⟨verify⟩ - progress bars currently mirror, which would fill the video timeline right-to-left and contradict the non-mirroring seek keys. Force LTR.

**3f. Text expansion.** Add `overflow = TextOverflow.Ellipsis` to the 40 `maxLines`-without-overflow sites (worst-placed: `Sidebar.kt:248,255,280,381` ⟨verify⟩, `TopBar.kt:168,197,218,233` ⟨verify⟩). Add `basicMarquee()` on focus to nav/rail/card titles; there are currently **zero** marquee usages in the app. Re-check the fixed widths in `ui/theme/Dimens.kt` and the ~15 hard `Modifier.width()` wrapping `Text` against the pseudolocale run.

**3g. Pseudolocale QA.** Build debug with pseudolocales and walk every screen: `en-XA` (accented, ~+30% length) exposes clipping and unextracted strings at a glance; `ar-XB` (bidi) exposes RTL breakage. This sweep is only meaningful if the debug APK actually contains `en-rXA` and `ar-rXB`; see the Gradle section, and run the APK check first.

---

## Phase 4 - Translation infrastructure

**4a. Align fallback policy and contribution readiness.** English is the only guaranteed-complete
language. Missing community keys are reported but do not fail; present values remain structurally
validated. Keep the generated coverage metric and suppress the badge at exactly 100%. A community
locale below 70% cannot be packaged or selected, while reaching 70% only permits an explicit manual
catalogue promotion. The 19 requested locales are intentionally not seeded or machine-translated.

The historical seed plan is retained in [`docs/i18n-phase4a-seed-translations.md`](./i18n-phase4a-seed-translations.md)
for context and is not a request to create new resource directories.

**4b. Weblate.** Connect hosted.weblate.org to the GitHub repo (GPL-3 qualifies for the free libre tier). Configure **bidirectional** sync: push via PR on commit, plus the GitHub webhook so manual edits pushed to `main` flow back into Weblate. Enable the *Cleanup translation files*, *Squash Git commits*, and built-in Android format-check addons.

**A single filemask cannot cover the six split files.** A Weblate filemask takes **exactly one** `*`, and for Android monolingual resources that `*` is the language segment. `values-*/strings*.xml` is therefore invalid (two wildcards), and a component's base file must be one concrete monolingual file, so `values/%s.xml` cannot stand in for six different filenames. Written naively, the component would not validate.

Use the component-discovery addon already chosen in 0c, configured to capture **both** the language and the component filename:

```text
File format:        Android String Resource
Match regex:        app/src/main/res/values-(?P<language>[^/]*)/(?P<component>strings[^/]*)\.xml
Component name:     {{ component }}
Base file:          app/src/main/res/values/{{ component }}.xml
```

Discovery then creates six components (`strings`, `strings_setup`, `strings_settings`, `strings_player`, `strings_content`, `strings_features`), each correctly paired with its own base file in `values/`. Two consequences of the regex to check on first run:

- `donottranslate.xml` does not start with `strings`, so the pattern excludes it structurally rather than relying on a separate exclusion rule.
- `values-b+sr+Latn` contains `+`, which `[^/]*` matches fine, but confirm Weblate maps that directory to the `sr_Latn` language code rather than creating a junk language.

Weblate is configured from the `weblateCode` field of `locales.json`, never from `languageTag` or `resourceQualifier`. Weblate uses underscored codes (`pt_BR`, `zh_Hans`), Android uses `r`-prefixed regions, and the runtime uses BCP-47. These three namespaces overlap enough to be confused and differ enough to break silently.

The discovery regex captures the Android directory segment, so add **project-level Weblate language aliases** for every catalogue entry where that captured segment does not identify the intended Weblate language. Generate or document the aliases from `resourceQualifier -> weblateCode`; do not maintain a second hand-written mapping. Required initial examples:

```text
pt:pt_BR
es:es
```

Verify the direction and case against the hosted Weblate project before import. The first component-discovery run must demonstrate that `values-pt` appears as Portuguese (Brazil), `values-es` as Spanish (Spain), and every regional/script directory maps to its intended `weblateCode`.

Severity note preserved from v1: this is third-party service configuration, surfaced immediately by Weblate's own validation when the component is created, and it blocks nothing before 4b.

### Catalogue schema (implemented in Phase 0)

`tools/i18n/locales.json` is the single authoritative catalogue. **It must not treat runtime BCP-47 tags, Android resource qualifiers and translation-platform codes as interchangeable.** v1 used one undifferentiated identifier for all three, which cannot work: the runtime tag `pt-BR` must map to the Android qualifier `pt` and the Weblate code `pt_BR` simultaneously.

The file is created in **Phase 0**, because Phase 0's build already consumes it for `localeFilters`.
Its contents are authoritative: source en-US, the hidden en-GB regional override, the established
translated entries, and the 19 catalogue-only requests. Packaging and picker visibility are explicit
fields. Catalogue-only entries are false for both flags and have no resource directories; they become
eligible for reviewed promotion only at 70% coverage.

Fields, at minimum:

```text
id
languageTag
resourceQualifier
resourceDirectory
weblateCode
englishName
endonym
script
rtl
tier
packaged
pickerVisible
```

```json
{
  "id": "pt-BR",
  "languageTag": "pt-BR",
  "resourceQualifier": "pt",
  "resourceDirectory": "values-pt",
  "weblateCode": "pt_BR",
  "englishName": "Portuguese (Brazil)",
  "endonym": "Português (Brasil)",
  "script": "Latn",
  "rtl": false,
  "tier": 1,
  "packaged": true,
  "pickerVisible": true
}
```

Catalogue-only requested languages are the intentional exception to the older no-prepopulation
rule: they record verified runtime/Android/Weblate metadata without any seed resources. A future
language outside the requested set still enters the catalogue only with a concrete request, verified
metadata, and explicit packaging/picker state. Coverage is computed, never stored.

The schema must handle the cases where the three namespaces genuinely diverge:

```text
Runtime pt-BR      → Android qualifier pt
Runtime id         → Android qualifier in
Runtime sr-Latn    → Android qualifier b+sr+Latn
```

**Consumers, each reading exactly one field:**

| Consumer | Field |
|---|---|
| `localeFilters` in `build.gradle.kts` | `resourceQualifier` |
| Language picker rows | `languageTag`, `endonym`, `englishName` |
| Weblate component configuration and project language aliases | `resourceQualifier`, `weblateCode` |
| Generated resource directory paths | `resourceDirectory` |
| `SupportedLocales.kt` | `languageTag`, `endonym`, `englishName`, `script`, `rtl`, plus computed coverage |
| RTL layout decisions and QA matrix | `rtl`, `script` |

Feeding a runtime BCP-47 tag directly into `localeFilters` is a bug, and it is the specific bug this schema exists to prevent.

#### Coverage is generated, never stored

Do **not** maintain a coverage percentage by hand in `locales.json`. Coverage is derived from the actual resources:

```text
values/*.xml
+
values-<locale>/*.xml
        │
        ├── validate_strings.py ── CI report
        └── gen_supported_locales.py ── SupportedLocales.kt ── picker badge
```

Both tools must use the same suffixed source and localized key sets. The generator freshness check
prevents the picker badge from silently becoming stale; the validator test proves the CI report and
badge use the same numerator and denominator. Neither consumer turns that percentage into a release
threshold.

#### Packaging and picker rules

```text
All catalogue entries:
    metadata known

packaged = true:
    included by localeFilters

pickerVisible = true:
    shown in the in-app picker

Community locale release:
    packaged = true
    pickerVisible = true
    coverage = informational
```

`pickerVisible = true` must imply `packaged = true`. The reverse is allowed (a locale can be packaged for testing before it is offered). A validation test enforces the implication.

Pseudolocales stay **outside** the production catalogue. If they need metadata at all, put it in a separate debug-only configuration; a separate file is preferable to a `debugOnly` flag inside the production catalogue, because the production catalogue is what feeds release packaging.

#### Locale directory qualifiers

Verified against the res folders Google actually ships in `androidx.compose.ui:ui:1.11.1`. **`b+` is only needed for a script subtag or a numeric/3-character region.** Elsewhere `values-<lang>[-r<REGION>]` compiles to the identical `ResTable_config`, so over-applying `b+` buys nothing.

```text
values              # default, en-US source
values-zh-rCN / values-zh-rTW / values-zh-rHK   # mirror androidx exactly; zero-risk
values-pt           # Brazilian  <-- bare pt, matching Google's own convention
values-pt-rPT       # European
values-es           # Castilian
values-es-rUS       # Latin American (androidx's form)
values-sr           # Cyrillic
values-b+sr+Latn    # Latin -- b+ mandatory, script subtag
values-nb  values-tl  values-iw  values-in  values-ji   # NOT no/fil/he/id/yi
```

The legacy codes follow Java's own normalisation (`Locale("he").getLanguage()` returns `"iw"`); androidx ships `values-iw` and `values-in`, confirming it. This is exactly why `languageTag` and `resourceQualifier` are separate fields: the runtime tag is `he` / `id` / `yi`, the resource qualifier is `iw` / `in` / `ji`.

**Trap to avoid:** since Android 7.0 the resolver ascends to the parent *and then descends to a sibling child*, so naming Brazilian `values-b+pt+BR` lets a `pt-PT` device land on Brazilian strings. Putting the primary variant in the bare `values-pt` / `values-es` makes every `pt-*` / `es-*` device resolve deterministically.

### Catalogue set: established translations plus requested community backlog

The following numbered list is the established translated/selectable set inherited from the
historical Phase 4 work. The catalogue additionally contains the hidden en-GB regional override and
19 requested catalogue-only entries. Those requests are deliberately absent from resources and picker
rows until manual promotion.

1. Arabic
2. Portuguese - Brazil
3. Chinese - Simplified
4. Chinese - Traditional
5. Czech
6. Danish
7. Dutch
8. French
9. German
10. Italian
11. Japanese
12. Korean
13. Norwegian
14. Polish
15. Portuguese - European
16. Russian
17. Spanish - Latin America
18. Spanish - Spain
19. Swedish
20. Turkish
21. Malayalam
22. Hindi
23. Bangla

The 19 catalogue-only additions are: Bulgarian (`bg`), Croatian (`hr`), Estonian (`et`), Persian
(`fa`), Finnish (`fi`), Greek (`el`), Hebrew (`he`), Hungarian (`hu`), Indonesian (`id`), Latvian
(`lv`), Lithuanian (`lt`), Malay (`ms`), Romanian (`ro`), Serbian Cyrillic (`sr`), Slovak (`sk`),
Slovenian (`sl`), Thai (`th`), Ukrainian (`uk`), and Vietnamese (`vi`). They have no resource
folders or seed files and are not selectable or packaged at this head.

#### Canonical mapping

Use this mapping unless verification against the repository, the Android resource resolver or the Weblate configuration proves an adjustment is required. Any adjustment must preserve distinct runtime, Android and Weblate fields.

| Display name | Runtime language tag | Android resource directory | Android qualifier | Notes |
|---|---|---|---|---|
| English | `en-US` | `values/` | `en` for filtering | Canonical source language |
| Arabic | `ar` | `values-ar` | `ar` | RTL |
| Portuguese - Brazil | `pt-BR` | `values-pt` | `pt` | Bare Portuguese is intentionally the Brazilian primary variant |
| Portuguese - European | `pt-PT` | `values-pt-rPT` | `pt-rPT` | European regional override |
| Chinese - Simplified | `zh-CN` | `values-zh-rCN` | `zh-rCN` | Simplified Chinese |
| Chinese - Traditional | `zh-TW` | `values-zh-rTW` | `zh-rTW` | Primary Traditional Chinese target |
| Czech | `cs` | `values-cs` | `cs` | |
| Danish | `da` | `values-da` | `da` | |
| Dutch | `nl` | `values-nl` | `nl` | |
| French | `fr` | `values-fr` | `fr` | |
| German | `de` | `values-de` | `de` | |
| Italian | `it` | `values-it` | `it` | |
| Japanese | `ja` | `values-ja` | `ja` | Non-Latin font path |
| Korean | `ko` | `values-ko` | `ko` | Non-Latin font path |
| Norwegian | `nb` | `values-nb` | `nb` | Norwegian Bokmål is the application target |
| Polish | `pl` | `values-pl` | `pl` | Full Polish plural handling |
| Russian | `ru` | `values-ru` | `ru` | Full Russian plural handling |
| Spanish - Latin America | `es-US` | `values-es-rUS` | `es-rUS` | Preserve the original verified AndroidX-compatible representation unless re-verification establishes another project-wide choice |
| Spanish - Spain | `es-ES` | `values-es` | `es` | Bare Spanish is the Castilian primary variant |
| Swedish | `sv` | `values-sv` | `sv` | |
| Turkish | `tr` | `values-tr` | `tr` | Locale-sensitive casing tests |
| Malayalam | `ml` | `values-ml` | `ml` | Malayalam-script pilot and first manual flow check |
| Hindi | `hi` | `values-hi` | `hi` | Devanagari-script follow-up flow check |
| Bangla | `bn` | `values-bn` | `bn` | Bangla-script follow-up flow check |

**English is the source language, even though its catalogue tier remains 1.** Its guarantee means:

- Every source key exists in `values/`.
- Placeholder and plural validation passes.
- Canonical source wording is en-US.
- The small `values-en-rGB` override may remain for regional spelling.
- British English is **not** a separate picker target unless the product later exposes it separately.

**Chinese - Traditional means `zh-TW` for the initial language set.** Do not silently add `zh-HK` as
a second target. Add it later only through the same explicit user/contributor-request process as any
other optional locale.

#### Tier table

| Tier | Locales | CI/release rule |
|---|---|---|
| Source | `en-US` | Complete source key set and structural validation required |
| Established community | Existing translated catalogue entries | Coverage is computed; present invalid values fail; explicit packaging flags remain authoritative |
| Catalogue-only backlog | `bg`, `hr`, `et`, `fa`, `fi`, `el`, `he`, `hu`, `id`, `lv`, `lt`, `ms`, `ro`, `sr`, `sk`, `sl`, `th`, `uk`, `vi` | No resource directory or seed; computes 0%; unshipped/hidden until 70% and manual promotion |
| Regional source override | `en-GB` | Intentionally partial, packaged, hidden from the picker, and excluded from the community backlog |

#### Translation validation

`MissingTranslation` remains informational. Do not promote it to an error: English is the only
complete source, and missing community values are valid Android fallback behavior.

`validate_strings.py` parses every resource file, reports per-locale coverage against `locales.json`,
and validates the entries that are present. Missing community keys change the report but not the exit
code.

For every localized resource that exists, `validate_strings.py` must require:

- Required CLDR plural quantities for that locale.
- Mandatory `other` in every `<plurals>`.
- Placeholder count and positional-index parity with source.
- Valid XML and correct escaping.
- No `translatable="false"` leakage into translations.
- No translation-only key.
- No empty localized value.
- Valid leading and trailing whitespace.

There is no release review-state file or 100% completeness gate. A catalogue-only 0% locale is
valid without resources, but a community locale below 70% must remain unshipped and hidden. At 70%
maintainers manually promote the single catalogue entry; coverage never mutates Gradle packaging.
This distinction is intentional: a missing key falls back to English, while an empty or malformed
localized key overrides the fallback and is a bug.

Source English validation must additionally check:

- Duplicate keys.
- Positional placeholders (no bare `%s`).
- Plural validity.
- Escaping.
- `translatable` versus non-translatable placement.
- Required preceding translator comments where this plan mandates them.

When an English edit changes meaning, add a new resource key. Reusing the old key can leave a stale
community translation overriding the new meaning with no file-level signal. Cosmetic spelling or
punctuation changes may retain the key.

#### Packaging rollout

Phase 0 packaged only:

```text
en
en-rGB
```

Existing translated entries retain their explicit packaging/picker flags and `SupportedLocales.kt`
is regenerated from the catalogue and resource tree. The 19 catalogue-only requests remain
unpackaged and hidden at 0%; missing values fall back to English. A locale becomes eligible for
release only after the report reaches 70% and a maintainer manually promotes its catalogue flags.

**Before a locale is promoted for a multilingual release:**

- the locale has at least 70% computed coverage and an approved manual flag promotion;
- generated picker coverage matches the current resource tree and the CI report;
- every present localized value passes structural validation;
- missing values are confirmed to fall back to English; and
- the representative manual QA matrix below is completed.

#### Representative manual QA matrix

| QA purpose | Required language |
|---|---|
| Source/default behaviour | English |
| RTL, Arabic plurals and Arabic glyphs | Arabic |
| Latin text expansion | German |
| Additional long Latin or Cyrillic strings | Portuguese - European or Russian |
| Slavic plurals | Polish and Russian |
| Simplified CJK glyphs and layout | Chinese - Simplified |
| Traditional CJK glyphs and layout | Chinese - Traditional |
| Japanese shaping and fallback | Japanese |
| Korean shaping and fallback | Korean |
| Malayalam shaping and fallback | Malayalam |
| Devanagari conjuncts, vowel marks and fallback | Hindi |
| Bangla conjuncts, vowel signs and fallback | Bangla |
| Latin American regional wording | Spanish - Latin America |
| European regional wording | Spanish - Spain |
| Portuguese fallback separation | Portuguese - Brazil and Portuguese - European |
| Locale-sensitive casing | Turkish |
| Scandinavian endonym and layout handling | Norwegian, Danish or Swedish |

Automated structural validation covers every present localized value. A complete manual walkthrough is
not required for every locale on every pull request, but release smoke testing must include the
representative matrix plus targeted checks for the remaining community targets.

**4c. Explicit promotion.** Packaging is not inferred from catalogue membership. After a locale
reaches 70%, a reviewed change promotes its `tier`, `packaged`, and `pickerVisible` fields, regenerates
`SupportedLocales.kt`, and validates the resource tree. Gradle consumes only packaged qualifiers;
the picker applies the generated readiness check. en-GB remains hidden and missing values fall back
to English.

**4d. Later validator and supply-chain hardening.** Any source-drift hashes, Weblate-state ingestion,
split status storage, or richer reporting remain informational unless product policy is explicitly
changed. Do not recreate a 100% community-language gate. A semantic English wording change gets a new
resource key so an old translation cannot silently override a new meaning.

**4e. APK delta.** Build `standardRelease` before and after the Phase 4a flag flip, record the actual APK and
`resources.arsc` delta in [`docs/i18n-apk-size-baseline.md`](./i18n-apk-size-baseline.md), and keep
`localeFilters` as the one-line rollback lever.

### Phase 1 review hardening shipped with the extraction

The Phase 1 implementation also pins the lifecycle and presentation invariants that are easy to document incorrectly:

- profile launch state distinguishes Room `Loading` from `Loaded(empty)`, and the shell requires a loaded profile matching the persisted active id plus a completed PIN decision;
- Compose receives a localized resource context whose base still unwraps to the host Activity, preserving themes, WebView/window services, display-mode matching and Activity launches;
- locale restore accepts only canonical catalogue tags, reports invalid optional backup values, applies a valid locale only after restore marker cleanup, and updates process `Locale` defaults through the singleton `LocaleStore`;
- every counted message uses Android `<plurals>` (with `PluralsCandidate` fatal in lint), while compact sync values remain display-only and raw quantities drive plural selection;
- player toasts, rewind metadata, stream diagnostics, playback history, update failures and companion HTML remain semantic until their current-locale renderer; HTML declares effective `lang` and `dir`;
- the source tree is en-US, the reviewed en-GB overrides are explicit, the generated catalogue is fresh, and the filtered APK baseline is recorded in [`docs/i18n-apk-size-baseline.md`](./i18n-apk-size-baseline.md).

These are covered by JVM/i18n regression tests and Android-test compilation/instrumentation fixtures; no review claim relies solely on a comment or a successful happy-path build.

---

## Verification

Build and static checks (commands to run, not run automatically):

```bash
./gradlew :app:assembleStandardDebug
python3 tools/i18n/validate_strings.py --report text  # structural checks + informational coverage
python3 tools/i18n/check_hardcoded_strings.py  # literal baseline, may only shrink
./gradlew :app:lintStandardDebug               # MissingTranslation remains informational
```

End-to-end on a real device or TV emulator, per the reproduce-before-you-believe rule.

### A. Instant Compose switch

Settings -> Language -> Deutsch. Every visible Compose string changes, with no unnecessary Activity recreation. The player keeps playing if open.

### B. Cold-start persistence

Force-stop and relaunch. The SharedPreferences-backed locale is applied from the **first real UI frame** (no English flash, no post-hoc correction).

### C. Script-family recreation

Switch between a Latin locale and Arabic or a CJK locale, and confirm **only the intended** Activity recreation occurs: none for same-script switches, exactly one for a script-family change.

### D. Same-script stability and System default

1. Start in English and switch to German without recreation.
2. Trigger a system configuration change such as font scale or dark mode.
3. Confirm Compose and `Locale.getDefault()` remain German; the startup locale must not return.
4. Set the TV language to French, then choose OwnTV's "System default".
5. Confirm OwnTV immediately changes to French and persists `""`, not `"fr"`.
6. Change the TV language to Spanish while OwnTV follows system.
7. Confirm the next configuration callback applies Spanish.
8. Repeat with a TV language outside the 24 supported languages and confirm OwnTV falls back to source English.

### E. Configuration-only profile-gate retention

1. Pass the profile/PIN gate.
2. Trigger an Activity recreation through a configuration change (font scale, dark mode, or a script-family language switch).
3. Confirm the session remains past the gate.
4. Simulate or test process death and task restoration (`adb shell am kill`, or Don't Keep Activities plus a background kill).
5. Confirm authentication is required again.

This proves the flag lives in an Activity-scoped ViewModel without saved-state restoration. If step 5 comes back already past the gate, something reintroduced `rememberSaveable` or `SavedStateHandle`.

### F. Named non-Compose renderers

1. Start the app in English.
2. Switch to Deutsch **without restarting**.
3. Open the companion page from another device.
4. Confirm the generated HTML is German.
5. Trigger an existing `OwnTVPlayer` system toast and an `ExternalPlayerLauncher` fallback/chooser; confirm fixed OwnTV text is German.
6. Start a download; confirm the notification uses German and the existing notification channel's visible name updates when the renderer runs.
7. Let the normal legacy Android TV launcher publisher run; confirm newly published OwnTV-authored row, description, media type, season and episode prose is German.
8. Confirm a language change does **not** start a special launcher republish operation; old entries may stay in the previous language until the next normal run.

If newly rendered output is English, that renderer captured a startup context instead of wrapping at render or publish time.

### G. Backup restore, single-store durability

1. Export a backup with a selected locale.
2. Restore it on another install.
3. **Wait for the locale write operation to report completion.**
4. Force-stop immediately.
5. Relaunch and confirm the restored locale.

This tests durability of the single store. It is not a mirror-synchronisation test, because there is no mirror.

### H. Persisted error language

1. Trigger a known EPG/sync failure while German is active.
2. Confirm the **raw** exception is persisted.
3. Switch to English.
4. Confirm the UI now displays the English friendly mapping for that stored failure.
5. Export a backup and confirm the raw original exception text is what appears in the stored data.

### I. Pseudolocale packaging

Verify the **debug APK** contains and can switch to:

```text
en-XA
ar-XB
```

Confirm release APKs do not include them as supported production locales, and that they never appear in the language picker.

### J. Baseline workflow

Create a multi-commit test PR where an **early** commit grows `hardcoded_baseline.txt` and the final commit leaves it untouched. Confirm CI still fails, because the comparison is against the merge base and not `HEAD^`.

Also add a second occurrence of an already-baselined literal in the same file and confirm CI fails. Put a visible error inside `error("...")` and confirm it is not automatically exempt. Then classify a confirmed developer-only assertion in `safe_literals.txt` under `assertion`, with its exact path, text, count and reason, and confirm the check passes.

### K. Existing QA, retained

- **RTL** - switch to العربية. Nav rail, sidebar and dialogs mirror; the EPG timeline and player progress bar do **not**; D-pad Left still rewinds in the player but now opens the drawer from the correct side; no tofu boxes anywhere, including popup menus and the audio now-playing bar.
- **Expansion** - switch to Deutsch or another long-Latin locale and walk Settings, Setup and the player HUD. Every button ellipsizes; nothing hard-clips.
- **Pseudolocale sweep** - debug build in `en-XA`; any string still rendering in plain unaccented English is an unextracted literal. Then `ar-XB` for bidi.
- **Subtitle regression** - set the device to Arabic, shift a subtitle in the player, and inspect the written `.srt` on disk: timestamps must be ASCII digits.
- **Font and tofu checks** - every script in the initial language set renders with real glyphs in
  popups, the language picker endonym column and the audio now-playing bar.
- **Companion glyphs** - confirm the remote page renders non-Latin scripts without tofu, i.e. the CSS fallback stack works alongside the Lora file it serves.
- **Date and number checks** - confirm dates follow the UI locale, the 12/24-hour choice follows the **system** setting, display numbers localise and protocol/persistence numbers stay `Locale.ROOT`.
- **APK size and memory** - the measurements in the block below, on real 1 GB hardware.

Size and memory for the explicitly packaged build (catalogue-only entries are excluded):

```bash
unzip -v app/build/outputs/apk/standard/release/*.apk | grep resources.arsc   # expect Stored, 0%
ls -l app/build/outputs/apk/standard/release/*.apk                           # track against the filtered Phase 0 baseline
adb shell dumpsys meminfo tv.own.owntv                                       # on a 1GB box, post-switch
```

The arsc is mmap'd so it is not resident, but the native `ResStringPool` still decodes strings on access and maintains native tables. The old ~190k-string estimate applied to the abandoned 111-locale scope and is not relevant to the current explicit-packaging policy. Measure the actual APK and runtime memory on real 1 GB hardware before each materially expanded localized release.

### Verification steps deleted in v2

- API 33 system-picker tests.
- System/app picker reconciliation tests.
- Generic Worker and global-context string-resolution tests. Named final renderers have the focused tests above.
- Koin `LocaleContextProvider` tests.
- DataStore/SharedPreferences mirror-synchronisation tests.
- Generic `UiText.resolve()` tests.

---

## Unit tests

### `LocaleStore`

- Synchronous startup read returns the persisted tag.
- `""` means follow system, and is a valid persisted value distinct from "unset".
- `""` resolves the current device locale list rather than the already-wrapped Application locale.
- Selecting `""` immediately applies the device locale; an unsupported device locale falls back to source English.
- Write is durable: the value is readable after a simulated process restart.
- Observable state updates on write.
- Backup import writes through the same API as the picker.
- Reset to system default writes `""` through the same API.
- Failed `commit()` is surfaced, not swallowed.
- A same-script custom-locale switch followed by `OwnTVApp.onConfigurationChanged` re-applies the current store value rather than the startup locale.

### Profile gate

- `authenticatedProfileId` survives configuration recreation through the Activity-scoped ViewModel only while it equals the active profile.
- Authentication does **not** restore after process death.
- Deleting authenticated A and automatically selecting PIN-locked B leaves authentication null/mismatched, so B cannot enter the shell without a fresh PIN.
- The profile-bound authentication state uses neither `SavedStateHandle` nor `rememberSaveable`. Assert this structurally if possible (no saved-state key exists) rather than only behaviourally.

### Semantic error classifier

- Every stable English needle maps to the expected semantic category.
- Null and blank messages map to `Generic`.
- The recognized categories are exactly those in the current `friendlySyncError`; internationalization adds no new external-error interpretation.
- Unknown errors preserve the **exact** raw message, byte for byte.
- Offline short-circuits ahead of needle matching.

### Presentation mapping

- Every semantic status maps to the correct resource identifier or Compose output.
- Plural paths use the correct plural resource and the correct count argument.

Prefer pure mapping functions where possible, so the assertion is on an identifier rather than on rendered text:

```kotlin
fun SyncFailure.messageRes(): Int?
```

in a UI package, returning `null` for raw unknown text.

### Persistence

Verify persisted errors contain raw text, and specifically **not**:

- A translated sentence.
- A resource ID.
- A semantic presentation object.

### `ChannelGenre`

Canonical comparison labels are unchanged across locales. This is a cheap regression test for the "comparison keys are never translated" rule, which otherwise fails silently as a duplicated chip.

### Baseline

The current baseline is a subset of the **merge-base** baseline.

- Adding a duplicate occurrence of existing normalized content in the same file fails.
- `require`, `check` and `error` messages are included by default.
- A developer-only assertion is exempt only through an exact, reasoned `assertion` entry in `safe_literals.txt`.

### Named non-Compose renderers

- Companion HTML, notifications, existing system toasts/chooser UI and launcher publication resolve fixed OwnTV text using the current locale at render time.
- Unknown external text remains byte-for-byte raw.
- Rebuilding a download notification updates the existing channel's localized visible name.
- Normal launcher publication uses the current locale, but a locale change does not trigger a special republish.

### Locale catalogue

Validate `locales.json`:

- Unique `id` values.
- Unique runtime `languageTag` values where uniqueness is required.
- Valid Android `resourceQualifier` values.
- `resourceDirectory` corresponds to `resourceQualifier`.
- Valid `tier` values.
- `pickerVisible = true` implies `packaged = true`.
- `locales.json` is the authoritative catalogue and contains the established entries plus exactly the
  19 requested catalogue-only language tags; no speculative entries are added.
- Every requested catalogue-only entry has verified runtime, Android, Weblate, script, RTL, tier,
  packaging, and visibility metadata.
- No manually stored coverage number exists in the file; coverage is computed.
- Generated Weblate aliases map Android directory qualifiers to the intended `weblateCode`, including `pt -> pt_BR` and `es -> es`.

---

## Review amendment index

Reconciles the 2026-07-28 review with the 2026-07-29 (v2) architectural decisions. Where v2 supersedes a v1 amendment, both are shown so the reasoning is not re-litigated.

| # | Finding | Resolution (current) | Section |
|---|---|---|---|
| 1 | `setApplicationLocales()` contradicts the no-recreation contract | Never call it. **v2 goes further:** read-only reconciliation created stale competing authorities and silently lost the user's in-app choice, so OwnTV does not advertise or support the Android system per-app-language picker at all. Generated locale config and all reconciliation removed. The in-app picker is the sole authority | Arch 2a, 0b, Gradle |
| 2 | Application-backed resources stay on the startup locale | **v2 supersedes `LocaleContextProvider`.** General non-presentation code does not resolve user-facing resources. Compose uses live wrapped resources. Named final non-Compose renderers create locale-specific contexts at render time. `OwnTVApp.onConfigurationChanged` re-applies the current store value so process defaults cannot revert to the startup locale | Governing architecture, 0b, named renderers |
| 3 | Phase 0 would advertise ~85 untranslated dependency locales | `localeFilters` ships in Phase 0, fed from `locales.json` `resourceQualifier`, starting at `en` + `en-rGB`. The `generateLocaleConfig` half is dropped with the system picker; the filter is retained for APK size and dependency-locale control | 0b Gradle, 4c |
| 4 | Error mapper cannot return `@StringRes Int` | **v2 supersedes `UiText`.** Use a semantic classifier returning `FriendlySyncFailure`. Migrate full vertical slices through an additive API or one atomic batch. Raw EPG persistence and friendly EPG rendering ship together, so no phase exposes raw exceptions to users | Errors section, Phase 1 batch 1b |
| 5 | Hardcoded-string guard misses its own stated scope | Occurrence-aware literal baseline ratchet with explicit classification in `safe_literals.txt`. Duplicate content in one file is counted, and assertion messages require a reviewed `assertion` entry with exact path, text and count rather than blanket exemption. The `getString()` receiver rule is replaced by a location/boundary rule | 0d |
| 6 | Tiered `MissingTranslation` is not expressible in lint | Missing community keys are valid fallback and coverage is informational. `validate_strings.py` reports coverage and gates only malformed present values; lint stays informational | 4a, 4d |
| 7 | Weblate filemask cannot map six split files | Component discovery captures both `language` and `component`; generated project language aliases map Android qualifiers such as `pt` and `es` to `pt_BR` and `es` from `locales.json` | 4b |
| 8 | Backup mirror race on import | **Dissolved by v2.** There is no mirror. One SharedPreferences-backed `LocaleStore`, one write path, awaited by import | 0b |
| 9 | `ChannelGenre.label` is display text and comparison key at once | `label` stays canonical and untranslated; display goes through `displayLabelRes` or a UI mapper. Regression test retained | Comparison keys, Phase 1 batch 5 |
| 10 | `rememberSaveable` could restore an authenticated flag after process death | Activity-scoped ViewModel without `SavedStateHandle`; configuration-only retention | 0a |
| 11 | One undifferentiated locale identifier cannot serve runtime, Android resources and Weblate | Distinct schema fields for runtime tag, Android qualifier and directory, Weblate code, packaging, picker visibility and tier. Coverage computed from resources | 4c |
| 12 | DataStore plus a SharedPreferences mirror introduces dual-write and race complexity | One SharedPreferences-backed `LocaleStore`, because the locale is required synchronously in `attachBaseContext` | 0b |
| 13 | `localeFilters` can strip generated pseudolocales | Add `en-rXA` and `ar-rXB` to debug variant filters only, and verify the built debug APK contains both | Gradle |
| 14 | Previous-commit baseline comparison is bypassable in multi-commit PRs | Compare PR head against the pull-request merge base; fetch the base revision in CI | 0d |
| 15 | Launcher localisation was used to justify broad non-Compose string resolution | Localise all OwnTV-authored launcher prose through a narrow publisher-time renderer. Because the legacy Android TV launcher has low usage and does not cover Google TV, do not add a special locale-change republish path | Launcher section, Phase 1 batch 11 |
| 16 | Final user-facing notifications, toasts and chooser UI were excluded by the Compose-only boundary | Treat existing system UI sites as named final renderers using current-locale wrapped contexts. Preserve behavior and avoid generic resource access | Governing architecture, Phase 1 batches 7-8 |
| 17 | `""` had no defined source for the real device locale after the Application was custom-wrapped | Read `Resources.getSystem().configuration.locales` only as device locale metadata; immediately apply it for System default and continue following device changes | 0b, Phase 2 |
| 18 | Null/blank sync failures became `Unknown("")` and rendered nothing | Add `FriendlySyncFailure.Generic`; preserve exactly the current mapper's recognized categories and pass through only non-empty unmapped external text | Errors section |
| 19 | A file-plus-content baseline misses a second identical literal | Baseline is a multiset keyed by file, normalized content and occurrence count or ordinal | 0d |
| 20 | The plan promised 100+ languages but only activated the initial set | The catalogue now records the established set plus 19 requested catalogue-only languages. Add other optional locales only after a concrete user/contributor request; package/select only after the 70% manual-promotion policy | Context, 4a-4d |

Corrections to the original review, recorded so they are not re-litigated:

- Its closing claim that the plan is not implementation-ready until findings 1-4 are resolved is **too broad**. Phase 0a is untouched by all of them, is independently committable, and fixes a live subtitle-file corruption bug. It ships first.
- Its finding 9 rationale was narrower than first credited in triage: `ChannelGenre.fromCategory` does **not** depend on `label`, so genre detection and dot colours are not at risk. Only the `LiveScreen.kt` dedupe breaks.

---

## Architecture summary

```text
 Resources.getSystem locales
       only when tag == ""
               │
               ▼
        SharedPreferences
               │
               ▼
          LocaleStore
  synchronous tag + StateFlow
               │
     ┌─────────┴──────────────────┐
     ▼                            ▼
Application / Activity wrap   Named final renderers
     │                        HTML / notifications /
     │                        toasts / chooser / launcher
     ▼
LocalizedContent
four Compose locals
     │
     ▼
 Compose UI
     ▲
     │ semantic state and values
ViewModels / Workers / repositories
```

Governing rules:

1. Application code communicates facts, not translated sentences.
2. Compose resolves everything rendered by Compose.
3. Only the named final non-Compose renderers may resolve resources outside Compose.
4. Resolved translations are never persisted.
5. Resource IDs are never persisted.
6. Stable comparison strings and protocol values are never translated in place.
7. The SharedPreferences-backed `LocaleStore` is the single locale authority.
8. The Android system per-app-language picker is intentionally unsupported.
9. Locale configuration still requires Application and Activity context handling and the four Compose locals.
10. Authentication-passed state survives only configuration recreation, not process death.
11. `""` immediately follows the current device locale list; unsupported device languages fall back to source English.
12. The catalogue is broader than the packaged/selectable set: only explicit flags plus the 70%
readiness boundary determine community release promotion.

---

## Removed complexity

Deleted from the plan in v2, with nothing replacing them:

- The application-wide `UiText` abstraction (`Res` / `Plural` / `Raw` / `Join`, `resolve(context)`, `asString()`).
- The process-wide `LocaleContextProvider` and its `@Volatile` context holder.
- General non-Compose resource resolution; only the named final renderers remain.
- Generic Worker-specific and Koin-specific string-resolution paths. The download notification renderer is a narrow final presentation component.
- Immediate launcher republishing on locale change.
- API 33 system language screen support, `generateLocaleConfig`, and any declared `android:localeConfig`.
- Startup and configuration reconciliation with `LocaleManager`, including "system wins" and locale provenance tracking.
- DataStore locale storage.
- DataStore/SharedPreferences dual writes.
- The mirror repair collector and all mirror race handling.
- `rememberSaveable` for authentication state.
- Previous-commit (`HEAD^`) baseline comparison.
- `resources.properties` with `unqualifiedResLocale=en-US`, unless another verified build behaviour still requires it.

---

## Complexity intentionally retained

Kept because each is load-bearing, verified, and has no cheaper equivalent:

- Synchronous SharedPreferences bootstrap read in `attachBaseContext`.
- Application context wrapping (process-level locale resets on configuration change).
- Reapplying the current `LocaleStore` value from `OwnTVApp.onConfigurationChanged`.
- Activity context wrapping (`createBaseContextForActivity` does not derive from the Application object).
- `Locale.setDefault` and `LocaleList.setDefault` for `java.text` / `java.time` / `String.format`.
- `Resources.getSystem().configuration.locales` as device-locale metadata only when following system.
- The four Compose locals, all four.
- Script-family Activity recreation, because `LocalLocaleList` is `@RestrictTo`.
- RTL navigation rules, and the physical-versus-mirrored control distinction.
- Font and script fallback, including arbitrary user/provider content.
- Locale-aware dates and numbers, with the system 12/24-hour preference respected.
- Protocol-safe `Locale.ROOT` formatting on persistence and wire paths.
- Translation validation (`validate_strings.py`), placeholder parity and full CLDR plurals.
- Weblate component discovery, generated project language aliases and bidirectional sync.
- Locale directory qualifiers, including the legacy `iw` / `in` / `ji` and `b+sr+Latn` cases.
- `localeFilters`, for APK size and dependency-locale control.
- Debug pseudolocales, with an APK-level check that they survived filtering.
- Structural validation for every present localized value, with informational coverage across every
  catalogue locale that has a resource directory, 0% reporting for catalogue-only entries, and
  English fallback for missing keys.
- Named final-renderer handling for companion HTML, notifications, existing system toasts/chooser UI and the low-priority legacy launcher.
- APK-size and memory monitoring on real 1 GB hardware.
- Semantic-state refactoring of the existing non-Compose text builders. This is the one place where v2 adds work relative to v1, and it is deliberate: the cost is paid once, in Phase 1, in exchange for deleting an abstraction and a global from every future feature.

---

Line references in this document were verified against the working tree at `399aef7` unless marked `⟨verify⟩`. Numbers known to have drifted (for example the launcher row constant, now `TvHomeRepository.kt:71`) are marked rather than guessed. Re-locate any `⟨verify⟩` symbol by name before editing it, and update this document when you do.
