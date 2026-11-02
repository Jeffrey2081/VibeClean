# ✨ VibeClean PRO (1Tap Cleaner)

[![100% Vibe Coded](https://img.shields.io/badge/Vibe%20Coded-100%25-ff69b4.svg?style=for-the-badge&logo=sparkles&logoColor=white)](https://github.com/Jeffrey2081/VibeClean)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024--36)-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)
[![APK Size](https://img.shields.io/badge/APK%20Size-~120%20KB-orange.svg?style=for-the-badge)]()

> **100% Vibe Coded.** Engineered end-to-end with pure AI agentic pair-programming, liquid glass aesthetics, low-level Android kernel/sysfs APIs, and zero bloatware.

**VibeClean PRO** is a modern, ultra-lightweight Android utility inspired by 1Tap Cleaner. It combines liquid glassmorphism, zero-friction UI automation, deep system cache clearing, hardware-level flash TRIM, Ahead-of-Time (AOT) ART Dexopt compilation, and specialized Samsung One UI thumbnail purging.

---

## 📸 Screenshots

| Liquid Glass Dashboard | Turbo Shell & Auto-Opt | S26 Gallery Deep Purge | Widgets Suite (3 Widgets) |
|:----------------------:|:----------------------:|:----------------------:|:-------------------------:|
| ![Dashboard](screenshots/screen_liquid_glass_dashboard.png) | ![Turbo](screenshots/screen_turbo_card_scrolled.png) | ![Gallery](screenshots/screen_tab_cleaner_gallery.png) | ![Widgets](screenshots/screen_tab_widgets.png) |

---

## ⚡ Key Features

### 1. 💎 Liquid Glass Design & Fluid Aesthetics
- **Frosted Liquid Glass**: 28px backdrop-filter blur with 200% saturation for deep cosmic reflections.
- **Specular Highlights**: Multi-layer specular glare along card edges with animated liquid sheen wave transitions.
- **Fluid Haptics**: Native vibration feedback paired with crisp, interactive micro-animations.

### 2. ⚡ 1-Tap Auto Optimize (Single-Button Pipeline)
A single prominent hero button that sequentially triggers all high-performance optimizations in one click:
1. **System Cache Trim**: `pm trim-caches 999G` across all packages without touching user logins.
2. **Flash Storage Hardware TRIM**: `sm fstrim` across UFS NAND flash partitions.
3. **Samsung Gallery & Thumbnails Deep Purge**: Completely purges `.thumbnails`, `.thumbdata*`, and MediaStore `.trashed-*` files.
4. **Storage & XML Sweeper**: Removes orphaned GCam XML configs and root crash dump files.
5. **AOT Speed-Profile Compilation**: Compiles user apps ahead-of-time with `speed-profile`.
6. **AOT Native Machine Code Compilation**: Compiles core UI components (One UI Home, System UI, Keyboard, Camera, Gallery, Google Play Services) into machine code (`speed`).
7. **RAM & Process Boost**: Flushes background cache and triggers system garbage collection.

### 3. 🖼️ Samsung Galaxy (S20-S26) & Android Gallery Thumbnail Purge & System Storage Sync
Fixes the notorious Android 11-16 scoped storage issue where thumbnail caches don't get cleared or fail to update in system settings:
- **Volume Marker Whitelisting**: Explicitly ignores un-deletable OS filesystem markers like `.database_uuid` (36 bytes) and `.nomedia` so thumbnail category sizes don't remain stuck at phantom non-zero byte values.
- **Full File Path Mapping**: Replaces truncated preview lists with complete filesystem path tracking, ensuring all thumbnails and orphan previews in `.thumbnails` folders are deleted when selected from the category dropdown or purged via dedicated action.
- **Daemon Unlock & File Truncation**: Force-stops `com.sec.android.gallery3d` and media providers so locked file descriptors on `.thumbdata` files are released; truncates active locked files to 0 bytes so they consume zero storage.
- **MediaStore Database Reconciler**: Runs SQL deletes on `content://media/external/file` and `MediaStore.Images.Media` to eliminate stale thumbnail references.
- **Instant UI Reconciliation & Rescan**: Immediately updates UI sizes to `0 B` upon cleaning and triggers `MediaScannerConnection.scanFile`, broadcasts `MEDIA_SCANNER_SCAN_FILE`, and executes `pm trim-caches 999G` so Android System and Settings -> Storage immediately recalculate and reflect the freed space!

### 4. 🔋 Granular Battery Optimization Suite (With Clear Tradeoffs & Drawbacks)
A customizable battery suite allowing users to selectively choose which optimizations to apply, complete with clear technical explanations and explicit drawbacks for each:

1. **⚡ Quick Doze Mode (Zero-Delay Deep Sleep)**: Tunes `device_idle_constants` (`quick_doze_delay_to=0`) to plunge the CPU into deep sleep immediately on screen-off (~0.2%/hr standby drain).
   - *Drawback*: Non-priority background social notifications may wait until screen-on. Calls, SMS, and alarms are never delayed.
2. **📶 Wi-Fi Background Scanning**: Stops system services and background apps from cycling Wi-Fi hardware to scan for nearby routers (`wifi_scan_always_enabled 0`).
   - *Drawback*: Indoor map positioning may take 1-2 seconds longer when GPS satellites are blocked.
3. **📡 Bluetooth LE Beacon Scanning**: Shuts off continuous low-energy Bluetooth radio beacon polling when Bluetooth is off (`ble_scan_always_enabled 0`).
   - *Drawback*: Un-paired nearby smart accessories or Quick Share popups won't show instant discovery banners until you open Bluetooth.
4. **🔋 Mobile Data Standby on Wi-Fi**: Prevents cellular modem from keeping active data links and sending packets while connected to strong Wi-Fi (`mobile_data_always_on 0`).
   - *Drawback*: Switching over to 4G/5G mobile data when walking out of Wi-Fi range takes 1-2 seconds instead of instantaneous handoff.
5. **🛑 Wakelock Shield (Social Apps)**: Sets standby buckets to `restricted` and blocks background CPU wakelocks (`WAKE_LOCK ignore`, `RUN_IN_BACKGROUND ignore`) for Instagram, Snapchat, Facebook, and TikTok.
   - *Drawback*: Social feeds and stories will not preload while your phone is locked; they refresh when you open the app.
6. **💨 Snappy UI (0.5x Animation Scale)**: Cuts window and transition animation duration in half (`0.5x`), reducing GPU rendering load, battery temperature, and frame drop.
   - *Drawback*: UI transitions are rapid and snappy rather than long, slow cinematic animations.
7. **🛡️ System Error Telemetry**: Disables automatic background packaging and cellular uploading of OS crash dumps to Google/OEM telemetry servers (`send_action_app_error 0`).
   - *Drawback*: If an application crashes, crash logs will not be automatically uploaded to OS developer analytics.
8. **🤖 AI Adaptive Battery Management**: Uses device intelligence to throttle background CPU execution for apps you haven't opened in days (`adaptive_battery_management_enabled 1`).
   - *Drawback*: Rarely-used apps will have background syncing delayed until opened.

### 5. 🔋 True Battery Health State (Not Just Percent)
- Queries Android `BatteryManager.EXTRA_HEALTH` and Linux/Samsung sysfs nodes (`/sys/class/power_supply/battery/health`, `fg_asoc`, `battery_cycle`).
- Accurately displays the true **Battery Health condition**: **Good (Optimal)**, **Normal**, or **Overheated**, rather than confusing it with current battery charge percentage.

### 6. 📱 Material 3 Expressive Multi-Widget Suite (5 Adaptive Widgets)
Engineered with **Material 3 Expressive** geometry and **System Dynamic Color (Monet)** support, automatically adapting to your wallpaper and Android theme across light and dark modes:

1. **System Stats Dashboard (`4 × 2`)**:
   - Comprehensive multi-tile hardware monitor.
   - Shows Free Storage with visual progress bar, RAM Usage %, Battery Health condition with temperature, and Flash TRIM status.
   - Full-width M3 Expressive pill button: `⚡ 1-TAP OPTIMIZE`.
2. **1-Tap Clean & Boost (`2 × 2` / `3 × 2`)**:
   - Squircle M3 Expressive card with Storage and RAM progress tracks.
   - Prominent M3 pill button: `⚡ BOOST NOW` for instant cache flush and memory boost.
3. **Battery & Thermal Health (`2 × 2`)**:
   - Monitors true battery health condition (Good/Optimal), live temperature in °C, and charge level.
   - M3 Tonal action button: `🌙 ACTIVATE QUICK DOZE` to immediately plunge the device into zero-delay deep sleep.
4. **Storage & Cache Gauge (`3 × 2`)**:
   - Dedicated storage gauge displaying available space vs cached junk with visual progress bar.
   - M3 Primary action button: `🧹 CLEAN JUNK CACHE`.
5. **Quick Clean Pill (`2 × 1`)**:
   - Ultra-minimalist Material 3 Expressive pill displaying available storage with a quick-tap BOOST action designed for clean dock setups.

> **💡 Android 12–16 Widget Picker Fix**: All 5 widgets include native `android:previewLayout` and standardized cell bounds (`targetCellWidth`, `targetCellHeight`, `minResizeWidth`, `minResizeHeight`), ensuring Samsung One UI Home, Pixel Launcher, and third-party launchers reliably render the full collection in the widget drawer without dropping or hiding widgets.

### 7. 🤖 Accessibility Auto-Cleaner (Hands-Free Cache Purge)
- Built-in `AutoCacheCleanerService` automatically navigates through all installed apps.
- Opens system App Info → Storage → dynamically detects and taps **"Clear cache"** → returns back to queue the next app.

---

## 🛠️ Architecture & Under the Hood

```mermaid
flowchart TD
    UI["Liquid Glass UI (SPA / WebView)"]
    Bridge["CleanerBridge (JavaScriptInterface)"]
    Engine["CleanerEngine (StorageStatsManager & MediaStore)"]
    Turbo["TurboShellEngine (AOT Pipeline, TRIM & Sysfs)"]
    AutoService["AutoCacheCleanerService (AccessibilityService)"]
    
    W1["System Stats Widget (4x2)"]
    W2["1-Tap Classic Widget (3x2)"]
    W3["Quick Clean Pill Widget (2x1)"]

    UI <-->|Progress & Events| Bridge
    Bridge --> Engine
    Bridge --> Turbo
    Bridge --> AutoService
    
    W1 --> Engine
    W1 --> Turbo
    W2 --> Engine
    W3 --> Engine

    Turbo -->|pm trim-caches| PM["PackageManager"]
    Turbo -->|cmd package compile| ART["Android Runtime (dex2oat)"]
    Turbo -->|sm fstrim| Vold["vold / Storage Daemon"]
    Turbo -->|rm -rf .thumbdata*| Gallery["Samsung Gallery / MediaStore"]
```

---

## 📥 Installation

Download the latest signed APK from GitHub Releases or directly from your `Downloads` directory:

```bash
# Install via ADB:
adb install -r ~/Downloads/VibeClean_v1.0.apk
```

*(Or transfer [`VibeClean_v1.0.apk`](file:///home/jeff/Downloads/VibeClean_v1.0.apk) directly to your Android device and tap to install).*

---

## 🔨 Building from Source

Build the signed APK in under 3 seconds using standard command-line tools:

```bash
git clone https://github.com/Jeffrey2081/VibeClean.git
cd VibeClean
chmod +x build.sh
./build.sh
```

---

## 📜 License

MIT License © 2026 [Jeffrey2081](https://github.com/Jeffrey2081). 100% Vibe Coded with ❤️.
