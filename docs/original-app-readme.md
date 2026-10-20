

/# FatigueSense (UI)

Jetpack Compose UI scaffolding and navigation for the FatigueSense Android app.

## Overview

This project provides a modular, reusable Compose UI with a dark/orange design language inspired by the attached reference. It includes:

- App-level theme with a dark palette and consistent typography.
- A reusable component library (cards, buttons, headers, pills, list items).
- Four primary screens with simple layout stubs.
- Bottom navigation with Compose Navigation.

## Architecture

The UI is separated into four layers:

1. **Theme** — color, typography, and Material3 configuration.
2. **Components** — reusable UI building blocks.
3. **Screens** — page-level composition using components.
4. **Navigation** — routes and bottom nav wiring.

## Project Structure

- `app/src/main/java/com/example/fatiguesense/ui`: Compose app entry point and navigation
- `app/src/main/java/com/example/fatiguesense/ui/components`: Reusable UI components
- `app/src/main/java/com/example/fatiguesense/ui/screens`: Screen-level layouts
- `app/src/main/java/com/example/fatiguesense/ui/theme`: Compose theme
- `app/src/main/java/com/example/fatiguesense/ui/screens/Splashscreen.kt`: Splash/connection landing screen

## App Entry Point

- `FatigueSenseApp()` in `app/src/main/java/com/example/fatiguesense/ui/FatigueSenseApp.kt`
  - Applies `FatigueSenseTheme`.
  - Hosts a `Scaffold` with top bar, bottom bar, and navigation content.

## Navigation

- `AppNavHost` in `app/src/main/java/com/example/fatiguesense/ui/navigation/AppNavigation.kt`
  - Routes:
    - `dashboard`
    - `face_setup`
    - `logs`
    - `settings`
  - `navigateSingleTopTo()` prevents duplicate destinations in the back stack.

## Theme

- `app/src/main/java/com/example/fatiguesense/ui/theme/Color.kt`
  - Dark background and surface colors plus a vivid orange primary.
- `app/src/main/java/com/example/fatiguesense/ui/theme/Type.kt`
  - Defines a compact, readable typography scale.
- `app/src/main/java/com/example/fatiguesense/ui/theme/Theme.kt`
  - `FatigueSenseTheme` wraps Material3 with a dark color scheme.

## Components (Reusable)

All components are defined in `app/src/main/java/com/example/fatiguesense/ui/components/UiComponents.kt`.

- `AppTopBar(title, subtitle)`
  - Simple title and subtitle row with brand styling.
- `AppBottomBar(items, selectedRoute, onSelect)`
  - Bottom navigation bar backed by `BottomBarItem` list.
- `SectionHeader(title)`
  - Section heading with an accent divider.
- `StatCard(label, value, supporting)`
  - Compact stat card for metrics (status, EAR, alerts).
- `ActionButton(text, onClick)`
  - Primary full-width call-to-action button.
- `InfoRow(label, value)`
  - Inline label/value rows for device details.
- `StatusPill(text)`
  - Chip-style status indicator.
- `ListCard(title, subtitle, metric)`
  - Log list item with metric highlight.
- `ScreenSurface(content)`
  - Applies consistent background and surface styling.

## Screens

### Splash / Connect
`app/src/main/java/com/example/fatiguesense/ui/screens/Splashscreen.kt`

- Terminal-style boot backdrop and branding.
- Connection status row with a ping indicator.
- IP address input and connect call-to-action.

### Dashboard
`app/src/main/java/com/example/fatiguesense/ui/screens/DashboardScreen.kt`

- Live status cards (status, EAR, alerts, session).
- Detection values and connection status.
- Primary actions (connect, recalibrate).

### Face Setup
`app/src/main/java/com/example/fatiguesense/ui/screens/FaceSetupScreen.kt`

- Enrollment description.
- Placeholder card for camera preview.
- Multi-step status pills and next-step action.

### Logs
`app/src/main/java/com/example/fatiguesense/ui/screens/LogsScreen.kt`

- Log list with severity-style entries.
- Placeholder entries for alerts and warnings.

### Settings
`app/src/main/java/com/example/fatiguesense/ui/screens/SettingsScreen.kt`

- Device information and controls.
- Camera config blocks.
- Data actions (export, clear logs).

## Dependencies

Key dependencies are declared in `gradle/libs.versions.toml` and used in `app/build.gradle.kts`:

- Jetpack Compose BOM
- Material3
- Compose Navigation
- Compose UI tooling

## Run

```zsh
./gradlew assembleDebug
```

## Notes

- The UI is modular; each screen uses shared components like `StatCard`, `SectionHeader`, and `ActionButton`.
- Navigation is handled with `navigation-compose` and a bottom bar.
- Layouts are placeholders for your data; connect view models and repositories as needed.
