# Claude Run

A small endless-runner Android game starring Claude's orange spark. Jump over bugs and
error terminals, duck under `429` rate-limit clouds, and collect tokens for bonus points.
The world speeds up as you go, and day turns to night every 700 points.

**Dark mode:** the game follows your phone's system theme. With dark mode on, it plays
in the dark palette the whole time; switch the setting while playing and the colors fade
over without interrupting your run.

| Title | Running |
|---|---|
| ![title](docs/1_title.png) | ![running](docs/2_running.png) |
| ![jump](docs/3_jump.png) | ![game over](docs/4_game_over.png) |
| ![dark title](docs/5_dark_title.png) | ![dark running](docs/6_dark_running.png) |

## Controls

| Action | Touch | Keyboard |
|---|---|---|
| Jump (hold for a higher jump) | Tap / hold the right side of the screen | Space, ↑, W |
| Duck / fast-fall | Hold the left side, or swipe down | ↓, S |

## Get the APK

- **From GitHub:** every push runs the *Build APK* workflow. Open the run in the
  **Actions** tab and download the `claude-run-apk` artifact, unzip it, and install
  `app-debug.apk` on your phone (allow "install unknown apps").
- **Build locally:** with the Android SDK installed (or Android Studio), run

  ```sh
  ./gradlew assembleDebug
  # APK: app/build/outputs/apk/debug/app-debug.apk
  adb install app/build/outputs/apk/debug/app-debug.apk
  ```

Or just open the folder in Android Studio and press Run.

## Project layout

- `app/src/main/java/com/claudegame/run/GameView.java` — the whole game: physics,
  spawning, collisions and Canvas rendering (no image assets).
- `app/src/main/java/com/claudegame/run/MainActivity.java` — fullscreen landscape host.
- `app/src/test/.../GameViewRenderTest.java` — headless Robolectric test that plays the
  game and renders frames to `app/build/renders/` (`./gradlew testDebugUnitTest`).

Requires Android 8.0 (API 26) or newer.
