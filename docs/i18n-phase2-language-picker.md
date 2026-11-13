# Phase 2 - Language Picker - Implementation Plan

> **Status:** implemented. Written 2026-08-01; policy notes reconciled for Phase 4a on 2026-08-04.
> Depends on Phase 0's `core/i18n/` runtime (landed on `feature/i18n-phase-0`).

## Goal

In-app language picker wired through existing settings navigation. System default plus explicitly
promoted catalogue entries are shown; en-GB and catalogue-only entries remain hidden. A promoted
community locale must be at or above the exact 70% readiness threshold. Same-script switches are
instant; cross-script switches trigger one `Activity.recreate()`.

## Prerequisites (all landed in Phase 0)

- `LocaleStore` (`core/i18n/LocaleStore.kt`) - SharedPreferences-backed single locale authority
- `AppLocale` (`core/i18n/AppLocale.kt`) - context wrapping + process-level Locale/LocaleList defaults
- `LocalizedContent` (`core/i18n/LocalizedContent.kt`) - provides the 4 Compose locals (`LocalResources`, `LocalContext`, `LocalConfiguration`, `LocalLayoutDirection`) that make string resolution follow the selected locale. Also handles script-family detection and triggers `Activity.recreate()` when crossing script boundaries
- `SupportedLocales` (`core/i18n/SupportedLocales.kt`) - generated catalogue from `locales.json`
  plus resource-derived coverage; packaging and picker visibility remain explicit catalogue flags

## First-launch behavior

**With incomplete community translations:** A promoted language remains selectable while it stays at or above the 70% policy boundary. Android
uses each localized value that exists and falls back to source `values/` English for missing keys.
Stored locale defaults to `""` (follow system).

If the device is set to e.g. French, the app auto-launches in French. `""` means follow the device. Android resolves
`values-fr/` via `attachBaseContext` wrapping. A missing French key falls back to source English; an
incomplete community translation does not make the locale unavailable. If the device locale is not
in the catalogue, the ordinary Android fallback chain applies. Catalogue-only requests are metadata
for contributor discovery, not device-language choices until manual promotion.

## Architecture: Write Path

```
LanguageSettingsScreen (new Composable)
    │ user picks locale
    ▼
LanguageSettingsViewModel (new, Activity-scoped)
    │ coroutineScope.launch
    ▼
LocaleStore.set(tag)  (Phase 0, exists)
    ├── SharedPreferences.commit() (durable, off main thread)
    ├── StateFlow update (_currentTag.value = tag)
    └── AppLocale.applyGlobally(tag) (Locale.setDefault + LocaleList)
            │
            ▼
    LocalizedContent (observes currentTag)
        ├── same script → CompositionLocalProvider (instant recomposition)
        └── different script → Activity.recreate() (attachBaseContext wraps new locale)
```

## Files to create / modify

| File | Action | What |
|---|---|---|
| `features/settings/LanguageSettingsScreen.kt` | NEW | Picker UI - searchable list, endonym rows, coverage %, D-pad navigation |
| `features/settings/LanguageSettingsViewModel.kt` | NEW | Thin ViewModel - reads `LocaleStore.currentTag`, calls `LocaleStore.set()`, sorts picker rows |
| `features/shell/components/SettingsScreen.kt` | EDIT | Add `LANGUAGE` to `SettingsTab` enum, add row under new "General" group, add `SettingsSearchEntry`, wire navigation |
| `ui/components/OwnTVIcon.kt` | EDIT | Add `LANGUAGE` enum entry (translate icon - "A" with lines, Google Material standard) |
| `res/values/strings_settings.xml` | EDIT | Add ~12 string resources for picker UI text |
| `di/` (Koin module) | EDIT | Register `LanguageSettingsViewModel` |

## Implementation steps

### Step 1. Add LANGUAGE icon to OwnTVIcon enum

Add `LANGUAGE` to `OwnTVIcon` enum at `ui/components/OwnTVIcon.kt:21-27`. Draw a translate-style glyph (A with lines) via Canvas path - Google's standard language/translate icon. All existing icons are custom Canvas-drawn paths.

### Step 2. Add string resources

Add to `res/values/strings_settings.xml` (~12 strings):

- `settings_language` - "Language" (row title)
- `settings_language_description` - "App display language" (row description)
- `settings_language_system_default` - "System default" (chip and picker row label)
- `settings_language_system_default_description` - "Follows device language" (picker row subtitle)
- `settings_language_search_hint` - "Search languages..." (search bar placeholder)
- `settings_search_keywords_language` - "language locale translation international i18n" (search keywords)
- `settings_language_coverage` - "%1$d%%" (coverage format)
- `settings_group_general` - "General" (new group label)

Add translator comments to each string.

### Step 3. Create LanguageSettingsViewModel

New file `features/settings/LanguageSettingsViewModel.kt`:

```kotlin
import java.util.Locale

class LanguageSettingsViewModel(
    private val localeStore: LocaleStore,
) : ViewModel() {

    val currentTag: StateFlow<String> = localeStore.currentTag

    val pickerRows: List<SupportedLocale> =
        SupportedLocales.pickerRows.sortedBy { it.englishName.lowercase(Locale.ROOT) }

    fun setLocale(tag: String) {
        viewModelScope.launch { localeStore.set(tag) }
    }
}
```

Thin by design. `LocaleStore` does all persistence and process-level locale application. `SupportedLocales.pickerRows` handles the `packaged && pickerVisible` filter.

### Step 4. Create LanguageSettingsScreen composable

New file `features/settings/LanguageSettingsScreen.kt`. Follows existing sub-screen pattern:

- Signature: `fun LanguageSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier)`
- Drawn as a Stage full page (`StageFullPage`), the way every Settings page is
- One row per locale (`LanguageRow`, built on `StageSettingRow`)

Each row layout:
- Radio check indicator (filled teal circle with checkmark when selected, outline when not)
- Endonym text - **must use `FontFamily.SansSerif`**, never bundled Lora (Lora has no CJK/Arabic/Hebrew glyphs; SansSerif uses platform Noto fallback)
- English name text (smaller, secondary color)
- Coverage badge for visible community rows below 100% (right-aligned)

Row ordering:
1. "System default" pinned first (tag = `""`)
2. Separator
3. Remaining rows A-Z sorted by English name

On row click: call `viewModel.setLocale(tag)`. The downstream path through `LocaleStore` -> `LocalizedContent` handles everything else (instant recomposition or script-change recreate).

Search filtering: match query against endonym, English name, and language tag.

D-pad: every row focusable, back exits to settings root. Focus the currently selected row on initial composition.

### Step 5. Wire into SettingsScreen

Edit `features/shell/components/SettingsScreen.kt`:

**5a.** Add `LANGUAGE` to `SettingsTab` enum at line 106:
```kotlin
private enum class SettingsTab { ROOT, LANGUAGE, SOURCES, EPG, PROFILES, ... }
```

**5b.** Add new "General" `GroupLabel` above existing "Profile" group (before line 339). Insert:
```kotlin
GroupLabel(stringResource(R.string.settings_group_general))
```

**5c.** Add `SettingsRow` for language:
```kotlin
SettingsRow(
    tone = TileTone.PRIMARY, icon = OwnTVIcon.LANGUAGE,
    title = stringResource(R.string.settings_language),
    desc = stringResource(R.string.settings_language_description),
    chip = languageChipText(currentTag),
    chipTone = TileTone.PRIMARY,
    onClick = { open(SettingsTab.LANGUAGE) }, showChevron = true,
    modifier = Modifier.focusRequester(rowFocus.getValue(SettingsTab.LANGUAGE)),
)
SectionDivider()
```

The `languageChipText` helper:
- If tag is `""` -> `stringResource(R.string.settings_language_system_default)`
- Otherwise -> `SupportedLocales.all.find { it.languageTag == tag }?.endonym ?: stringResource(R.string.settings_language_system_default)`

This requires reading `LocaleStore.currentTag` in `SettingsScreen`. Get it via `koinViewModel<LanguageSettingsViewModel>()` or directly from the injected `LocaleStore`.

**5d.** Add navigation case in the `when(tab)` block (around line 259-272):
```kotlin
SettingsTab.LANGUAGE -> { LanguageSettingsScreen(onBack = { tab = SettingsTab.ROOT }, modifier = modifier); return }
```

**5e.** Add `SettingsSearchEntry` in the search entries list (around line 596):
```kotlin
SettingsSearchEntry(
    stringResource(R.string.settings_group_general),
    stringResource(R.string.settings_language),
    stringResource(R.string.settings_search_keywords_language),
    OwnTVIcon.LANGUAGE, TileTone.PRIMARY,
    chip = languageChipText(currentTag),
    chipTone = TileTone.PRIMARY,
) { open(SettingsTab.LANGUAGE) },
```

**5f.** Add `FocusRequester` for the new row in the `rowFocus` map (around line 230):
```kotlin
SettingsTab.LANGUAGE to FocusRequester(),
```

### Step 6. Register ViewModel in Koin

Find the settings DI module and add:
```kotlin
viewModel { LanguageSettingsViewModel(get()) }
```

### Step 7. Build, install, test on device

Verify:
- D-pad navigation through all picker rows
- Search filtering works (English name, endonym, tag)
- Same-script switch applies instantly (no flicker, no recreate)
- Cross-script switch triggers exactly one recreate
- "System default" follows device language
- Back navigation returns to settings root
- Chip on settings root updates to reflect selected language
- Focus goes to currently selected row when entering picker
- All explicitly promoted catalogue languages appear; catalogue-only entries and en-GB remain hidden

## Design decisions

**Icon:** Material Translate icon (A with lines) - Google's standard for language settings. Not globe (ambiguous with network/internet).

**Sorting:** System default pinned first, then A-Z by English name. Catalogue-only entries are not rows
until a maintainer promotes them after the readiness check.

**Font:** Endonyms use `FontFamily.SansSerif` unconditionally. English name and coverage badge can use app's normal font.

**Visibility:** Only explicitly promoted `packaged = true AND pickerVisible = true` rows appear. The
source English row is selectable, the packaged en-GB spelling override remains hidden, and
catalogue-only rows remain hidden.

**Readiness and coverage:** A community row must also be at or above 70%; catalogue presence never
packages or selects it. `gen_supported_locales.py` computes coverage from the source and localized
resource key sets and embeds it in `SupportedLocales.kt`. A visible community locale shows its badge
only below 100%; complete rows, source English, system default, and hidden entries show no badge.
Missing keys fall back to English. A single global "Help translate" CTA opens the canonical
project-overview QR/link panel.

**Script-change:** Same-script = instant recomposition via Compose locals. Cross-script = one `Activity.recreate()` because `LocalLocaleList` is `@RestrictTo`. Already handled by `LocalizedContent.sameScriptFamily()`.

## Chip on settings root row

| State | Chip Text | Source |
|---|---|---|
| System default (`""`) | "System default" | `stringResource(R.string.settings_language_system_default)` |
| Explicit locale | Endonym (e.g. "Deutsch") | `SupportedLocales.all.find { it.languageTag == tag }?.endonym` |
| Unknown tag (corrupt prefs) | "System default" | Fallback - `LocaleStore.readBlocking()` normalizes to `""` |

## Out of scope

- Translation generation and policy alignment (Phase 4a)
- Backup export/import of locale (Phase 0b already wired)
- RTL layout fixes (Phase 3)
- Font fallback for non-Latin scripts (Phase 3a)
- Pseudolocale testing (Phase 3g)
- locale-filter APK-size measurement after an explicit packaging promotion (Phase 4e)
