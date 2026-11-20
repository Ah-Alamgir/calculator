# Scientific Calculator (`fx-82MS` Style) with Smart Camera Focus & OpenAI GPT-4o Solver

An Android application styled after the Classic White / Dark Grey **240-Function 2-Line Scientific Calculator (`fx-82MS`)**, proportioned to fit a **Samsung Buddy / Vega** smartphone mounted inside a physical calculator shell.

---

## Key Features

1. **Exact `fx-82MS` 2-Line LCD + Full Keypad Layout**
   - **Classic White / Dark Grey** casing and button colors matching the physical calculator.
   - **2-Line Greenish-Grey LCD Display** (`#9EAD86`) with top status indicators (`S`, `A`, `M`, `D/R/G`, and Camera/AI status), an upper scrollable expression/answer matrix line, and a lower 12-digit result readout.
   - **All Keypad Buttons Functional**:
     - **Top Control Row**: `SHIFT`, `ALPHA`, `MODE/CLR` (cycles `DEG`/`RAD`/`GRAD`), `ON` (resets state).
     - **Scientific Function Rows (22 keys)**: `x⁻¹` (`x!`), `nCr` (`nPr`), `Pol(` (`Rec(`), `x³` (`³√(`), `a b/c`, `√`, `x²`, `^`, `log` (`10^x`), `ln` (`e^x`), `(−)`, `°’”`, `hyp`, `sin` (`sin⁻¹`), `cos` (`cos⁻¹`), `tan` (`tan⁻¹`), `RCL`, `ENG`, `(`, `)`, `,`, `M+` (`M-`).
     - **Main Number & Operator Keypad (20 keys)**: `0`–`9`, `.`, `+`, `−`, `×`, `÷`, `EXP` (`π`), `Ans`, `DEL`, `AC`, `=`.

2. **Big Center Button (`REPLAY`) -> Auto-Focus Camera Capture -> OpenAI `gpt-4o` Answer**
   - Clicking the **center of the Big Circular Button** (`REPLAY`) launches the rear camera via **CameraX** (`SmartFocusCameraManager`).
   - A subtle, low-opacity preview appears inside the LCD screen while the camera focuses on the paper.
   - **Automatic Paper Focus Detection**: Monitors hardware autofocus lock (`CONTROL_AF_STATE_FOCUSED_LOCKED` / `PASSIVE_FOCUSED`) combined with real-time **2D Laplacian edge sharpness** and **frame-to-frame motion stability** on the center crop so the photo is snapped automatically the moment the camera is sharply focused and steady on the paper.
   - Sends the captured photo to **OpenAI `gpt-4o`** (`OpenAiSolverService`) with a strict prompt instructing the model to return **only the final answer(s) with zero text explanation**, and displays the result directly inside the calculator LCD display area.

3. **Upward Button (`▲`) Scrolls Display Text Upward**
   - Pressing the **Upward button (`▲`)** on the top of the big circular directional pad scrolls the text in the LCD display area **upward** (and cycles multi-line AI answers upward line-by-line).
   - Pressing **Downward (`▼`)** scrolls the display text back downward.

---

## Setting Your Secret OpenAI API Key (`.env`)

Your API key is stored in the root [`.env`](file:///home/antigravity/Ah-Alamgir/calculator/.env) file, which is listed in [`.gitignore`](file:///home/antigravity/Ah-Alamgir/calculator/.gitignore) so it will **never** be uploaded to GitHub:

1. **Edit `.env` in the project root**:
   ```env
   OPENAI_API_KEY=sk-your-real-openai-api-key
   ```
   - During build, [`app/build.gradle.kts`](file:///home/antigravity/Ah-Alamgir/calculator/app/build.gradle.kts) reads `.env` and injects `BuildConfig.OPENAI_API_KEY`.
   - [`EnvConfig.kt`](file:///home/antigravity/Ah-Alamgir/calculator/app/src/main/java/com/calculator/scientific/EnvConfig.kt) connects `BuildConfig.OPENAI_API_KEY` directly to [`MainActivity.kt`](file:///home/antigravity/Ah-Alamgir/calculator/app/src/main/java/com/calculator/scientific/MainActivity.kt) and [`OpenAiSolverService.kt`](file:///home/antigravity/Ah-Alamgir/calculator/app/src/main/java/com/calculator/scientific/OpenAiSolverService.kt).

2. **Optional In-App Override**:
   - You can also **long-press** the `ON` or `MODE/CLR` button inside the app at any time to set or update the key on the phone.

---

## Building & Running in Android Studio

1. Open `/home/antigravity/Ah-Alamgir/calculator` in **Android Studio**.
2. Put your real OpenAI API key in [`.env`](file:///home/antigravity/Ah-Alamgir/calculator/.env):
   ```env
   OPENAI_API_KEY=sk-your-real-openai-api-key
   ```
3. Connect your **Samsung Buddy / Vega** smartphone via USB (with **USB Debugging** enabled in Developer Options) and click **Run (`Shift+F10`)**.

---

## Turning Your Phone Into a Dedicated Single-App Calculator OS

You do **not** need to flash a risky custom ROM to make your Samsung Buddy / Vega run **only** this calculator app. The app is built with **Android Home Launcher + Single-App Kiosk OS Mode** ([`AndroidManifest.xml`](file:///home/antigravity/Ah-Alamgir/calculator/app/src/main/AndroidManifest.xml) & [`KioskDeviceAdminReceiver.kt`](file:///home/antigravity/Ah-Alamgir/calculator/app/src/main/java/com/calculator/scientific/KioskDeviceAdminReceiver.kt)):

### Option 1: Set as the Phone's Default Home OS Launcher (No PC Commands Needed)
1. In your phone's **Settings > Lock screen > Screen lock type**, select **None** (so turning on the screen skips the lock screen).
2. In **Settings > Apps > Choose default apps > Home app**, select **`fx-82MS Calculator`**.
3. Now, whenever the phone powers on or you press the Home/Back buttons, it boots directly into and stays inside the Calculator app!
4. You can also **long-press `ON`** inside the calculator and tap **"Lock Only This App"** to pin the app on screen.

### Option 2: Full Hardware Kiosk OS Lock via ADB (Device Owner Mode)
If you want the OS to completely block the notification bar, status bar, and all other apps at the OS level:
1. Connect the phone to your PC with USB Debugging enabled and run:
   ```bash
   adb shell dpm set-device-owner com.calculator.scientific/.KioskDeviceAdminReceiver
   ```
2. On launch, [`MainActivity.enableKioskModeIfDeviceOwner`](file:///home/antigravity/Ah-Alamgir/calculator/app/src/main/java/com/calculator/scientific/MainActivity.kt#L92-L102) automatically locks the phone into dedicated Kiosk Task Mode (`startLockTask()`).
3. Whenever you need to change Wi-Fi settings, **long-press `ON` or `MODE/CLR`** on the calculator and tap **"Wi-Fi / Exit Kiosk"**.
