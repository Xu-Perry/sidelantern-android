# SideLantern Android

**SideLantern for Android** is a customized Android streaming client based on [Moonlight Android](https://github.com/moonlight-stream/moonlight-android), designed to pair seamlessly with the **SideLantern** host as well as standard [Sunshine](https://github.com/LizardByte/Sunshine) and GameStream hosts.

Turn your Android phone or tablet into a low-latency remote desktop, secondary display, or wireless extended screen.

## Features

### Display Modes & Session Management
- **Primary / Secondary / Wireless Extended Display**: Queries `GET /sidelantern/v1/stream-modes` before connecting to support **Enter Desktop** (`primary`), **Secondary Display Only** (`secondary`), and **Wireless Extended Display** (`extend`), with automatic legacy-host fallback for primary display.
- **Seamless Session Resume**: Desktop and display-mode shortcuts connect directly and resume active sessions without interrupting running host applications.
- **Real Client Device Name**: Sends the device's actual name (RFC 3986 percent-encoded) during pairing instead of a generic placeholder.

### Live In-Stream Controls
- **Manual Video Hot-Switching** (`/sidelantern/v1/manual-video`): Adjust **Resolution** (360p–4K and custom resolutions), **Frame Rate** (30 / 60 / 90 / 120 FPS), and **Bitrate** (0.5–150 Mbps) live without reconnecting.
- **3 Touch Modes with Live Hot-Switching**:
  - **Touchpad**: Relative trackpad cursor, 2-finger scroll/right-click, and optional on-screen gamepad controls.
  - **Touchscreen**: Direct absolute touch with local 2-finger pinch-zoom and pan.
  - **Native Multi-Touch**: Passes full multi-finger touch events directly to Windows via Moonlight/Sunshine's native touch protocol (with automatic fallback on unsupported hosts).
- **Floating Settings Button & Right-Edge Sidebar**: Draggable edge-snapping gear button with idle dimming, right-edge sidebar for live stream controls, statistics overlay toggle, keyboard toggle, and one-tap **Exit Stream** (disconnects without quitting the host app).
- **Portrait & Landscape Streaming**: Freely rotate between portrait and landscape without disconnecting.

### Streaming Keyboard & Custom Shortcuts
- **Docked Shortcut Bar**: Horizontal scrollable toolbar above the soft keyboard with lockable `Win` / `Ctrl` / `Alt` / `Shift` modifiers, `Esc` / `Tab` / `Del`, shortcuts, and a quick hide-keyboard button.
- **Built-In & Custom Shortcuts**: Includes 8 common desktop shortcuts plus an in-sidebar editor to create, edit, and delete custom key combinations.

### Bidirectional File Transfer
- **Host File Transfer** (`/sidelantern/v1/files`): Send photos, videos, and files to your PC, or browse shared folders on your PC and download files directly to Android.
- **Resilient Chunked Queue**: Certificate-pinned HTTPS with disk-backed chunking, pause/resume/cancel, automatic offset recovery, and persistent task history across restarts.

### Redesigned Device Browser
- **Modern Device List & Detail UI**: Collapsible Online / Connecting / Offline groups, responsive 2-column tablet layout, manual LAN/WAN/IPv6/custom-port entry, desktop app auto-detection/picker, and per-host quick-launch management.
- **Localization**: Full English and Simplified Chinese (`zh-CN`) support.

## Building

### Prerequisites
- JDK 17+ (or Android Studio JBR)
- Android SDK (API 37) and Android NDK (`29.0.14206865`)

### Steps
1. Clone the repository with submodules:
   ```bash
   git clone --recursive https://github.com/Xu-Perry/sidelantern-android.git
   cd sidelantern-android
   ```
   If already cloned without `--recursive`:
   ```bash
   git submodule update --init --recursive
   ```
2. Run unit tests:
   ```bash
   ./gradlew testNonRootDebugUnitTest
   ```
3. Build APKs:
   - **Debug** (`com.sidelantern.debug`):
     ```bash
     ./gradlew assembleNonRootDebug
     ```
   - **Release** (`com.sidelantern`):
     ```bash
     ./gradlew assembleNonRootRelease
     ```

## Upstream & License

SideLantern Android is derived from [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) and is licensed under the **GNU General Public License v3.0** (see [LICENSE.txt](LICENSE.txt)).

Upstream Moonlight Android authors:
- [Cameron Gutman](https://github.com/cgutman)
- [Diego Waxemberg](https://github.com/dwaxemberg)
- [Aaron Neyer](https://github.com/Aaronneyer)
- [Andrew Hennessy](https://github.com/yetanothername)
