# <p align="center"><img src="app/src/main/ic_launcher-playstore.png" width="120" height="120" style="border-radius: 50%; box-shadow: 0 4px 8px rgba(0,0,0,0.2); border: 4px solid #6200EE;" /></p>

# <p align="center">Aivo</p>
<p align="center"><strong>Aivo</strong> is a premium, conversational AI Shopping Assistant built with Jetpack Compose, designed to interact seamlessly with Shopify stores via Android App Functions.</p>

---

## 🌟 Key Features

### 🛜 Real-Time Connectivity Monitoring
*   **Automatic Detection**: Continuously monitors network state using `ConnectivityManager`.
*   **Interactive Warning Banners**: Shows a sliding offline warning banner at the top of the chat area and disables voice/text inputs when connection is lost.
*   **Back-Online Notifications**: Pops down an emerald-colored success banner with slide/fade animations when connectivity is restored, auto-dismissing after 3 seconds.

### 🛍️ Unified Product Options Picker
*   **Interactive Variant Selector**: Automatically parses product parameters (`size`, `color`, `quantity`) from assistant responses and bundles them into an interactive Card.
*   **Custom Chip Controls**: Allows selecting variants directly inside the chat UI before confirming.
*   **One-Tap Confirm**: Submits all variant options to the assistant in a single structured message (e.g. `Quantity: 2, Size: M, Color: Black`).

### 🖼️ Coil 3 Network Image Loading
*   **Premium Product Media**: Integrated network image rendering directly inside chat messages.
*   **Coil OkHttp Engine**: Uses `coil-network-okhttp` for high-performance HTTP/HTTPS image fetching.

### 💵 USD Currency Display
*   **Default Currency**: Supports pricing defaults in **USD ($)** instead of local currencies, standardizing display formatting across search results and details views.

---

## 🛠️ Architecture & Tech Stack
*   **Core Logic**: Kotlin, Android Architecture Components.
*   **UI Framework**: Jetpack Compose, Material 3 with rich transitions and custom state-driven animations.
*   **Networking & Loading**: Coil 3, OkHttp.
*   **Integrations**: Android App Functions SDK.

---

## 🚀 Getting Started

### Prerequisites
*   Android SDK 34+
*   Gradle 8.5+

### Build & Run
```bash
# Compile and build debug apk
./gradlew assembleDebug
```
