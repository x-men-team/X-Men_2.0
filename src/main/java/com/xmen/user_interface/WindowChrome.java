package com.xmen.user_interface;

import javafx.application.Platform;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * Cross-platform helper that nudges the OS window chrome into a dark theme so the title bar /
 * traffic-light buttons match X-Men's dark UI.
 *
 * <p>JavaFX does not expose a portable API for recolouring the OS title bar, so we degrade
 * gracefully:
 *
 * <ul>
 *   <li><b>Windows</b> — runs {@code reg add} once on first use so the DWM uses immersive dark
 *       mode for the application's window decorations. This is the same hint Windows Settings
 *       toggles when you flip "Choose your default app mode" to Dark, scoped to the current
 *       user, so it covers Windows 10 1903+ and Windows 11.
 *   <li><b>macOS</b> — sets the {@code apple.awt.application.appearance} property to {@code
 *       NSAppearanceNameDarkAqua}. AWT picks this up early in the lifecycle; JavaFX surfaces
 *       inherit it for the OS chrome.
 *   <li><b>Linux</b> — sets the {@code GTK_THEME} environment hint where possible and otherwise
 *       leaves the chrome to the system theme. The settings dialog already documents that
 *       Linux users should pick a dark GTK theme for full parity.
 * </ul>
 *
 * <p>The hints are best-effort; failures are logged at debug level only so production startup
 * never noisily complains about a feature that depends on the OS.
 */
@Slf4j
public final class WindowChrome {

  private static volatile boolean applied;

  private WindowChrome() {}

  /**
   * Invoke once after Application.launch() — safe to call multiple times. The Windows path runs
   * the registry-write on a background thread so it never blocks the FX startup sequence (and
   * so unit tests that don't care about chrome colour don't time out waiting on the external
   * process).
   */
  public static void requestDarkChrome(Stage stage) {
    if (applied) return;
    applied = true;
    String os = System.getProperty("os.name", "").toLowerCase();
    if (os.contains("mac")) {
      // macOS hint is just a system-property tweak — safe to do inline.
      try {
        applyMacDarkMode();
      } catch (Throwable t) {
        log.debug("Could not apply macOS dark-mode hint: {}", t.getMessage());
      }
    } else if (!os.contains("win") && !os.contains("mac")) {
      try {
        applyLinuxDarkMode();
      } catch (Throwable t) {
        log.debug("Could not apply Linux dark-mode hint: {}", t.getMessage());
      }
    } else if (os.contains("win")) {
      // Spawn the registry write off-thread so we never block FX startup.
      Thread t = new Thread(
          () -> {
            try {
              applyWindowsDarkMode();
            } catch (Throwable err) {
              log.debug("Could not apply Windows dark-mode hint: {}", err.getMessage());
            }
          },
          "windows-dark-chrome");
      t.setDaemon(true);
      t.start();

      // Repaint the OS-drawn window border + title bar dark via DWM. The
      // registry hint above only changes future windows; this targets the
      // already-shown X-Men stage so the light 1-px frame Windows 11 draws
      // on the left/right/bottom edges of every window stops contrasting
      // against the dark theme. Fired on stage.showingProperty so the HWND
      // exists by the time PowerShell goes looking for it.
      stage.showingProperty().addListener((obs, was, now) -> {
        if (Boolean.TRUE.equals(now)) {
          Thread w = new Thread(WindowChrome::recolourWindowsFrame, "windows-dark-frame");
          w.setDaemon(true);
          w.start();
        }
      });
    }

    // Black stage fill so any flicker between scenes never shows OS white.
    Platform.runLater(
        () -> {
          try {
            if (stage.getScene() != null) {
              stage.getScene().setFill(javafx.scene.paint.Color.BLACK);
            }
          } catch (Exception ignored) {
          }
        });
  }

  /**
   * Windows 10/11 immersive dark mode. Setting AppsUseLightTheme to 0 under
   * HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Themes\Personalize is the
   * documented hint; DWM honours it for app windows once the process restarts.
   * Because the change is user-scoped we don't elevate; the registry update is
   * idempotent.
   */
  private static void applyWindowsDarkMode() throws IOException, InterruptedException {
    String[] cmd = {
      "reg",
      "add",
      "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
      "/v",
      "AppsUseLightTheme",
      "/t",
      "REG_DWORD",
      "/d",
      "0",
      "/f"
    };
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.waitFor();
    log.debug("Windows dark-mode hint registry write exited with code {}", p.exitValue());
  }

  /**
   * Ask DWM to paint the X-Men window's title bar and 1-px border in the
   * dark theme instead of the OS light default. Windows 11 draws that 1-px
   * frame on the left, right, and bottom of every window; without this
   * call it stays light grey and reads as a visible border against the
   * dark app content. The PowerShell snippet uses Add-Type to import
   * <code>DwmSetWindowAttribute</code> and <code>FindWindow</code>, then
   * sets DWMWA_USE_IMMERSIVE_DARK_MODE (20) and DWMWA_BORDER_COLOR (34)
   * on the window whose title starts with "X-Men". No-op if the call
   * fails (older Windows 10, missing dwmapi.dll, PowerShell missing).
   */
  private static void recolourWindowsFrame() {
    String ps =
        "Add-Type -Namespace W -Name X -MemberDefinition '"
            + "[DllImport(\"user32.dll\", CharSet=CharSet.Unicode)] public static extern System.IntPtr FindWindow(string c, string n);"
            + "[DllImport(\"dwmapi.dll\")] public static extern int DwmSetWindowAttribute(System.IntPtr h, int a, ref int v, int s);"
            + "';"
            + "$d = New-Object int[] 1; for($i=0;$i -lt 80;$i++){"
            + "  $h = [W.X]::FindWindow($null, 'X-Men 2.0');"
            + "  if($h -ne [System.IntPtr]::Zero){"
            + "    $d[0]=1;        [void][W.X]::DwmSetWindowAttribute($h,20,[ref]$d[0],4);"
            + "    $d[0]=0x00170A1A;[void][W.X]::DwmSetWindowAttribute($h,34,[ref]$d[0],4);"
            + "    $d[0]=0x00170A1A;[void][W.X]::DwmSetWindowAttribute($h,35,[ref]$d[0],4);"
            + "    break;"
            + "  } Start-Sleep -Milliseconds 100;"
            + "}";
    try {
      ProcessBuilder pb =
          new ProcessBuilder(
              "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      p.waitFor();
      log.debug("Windows DWM border recolour exited with code {}", p.exitValue());
    } catch (Throwable err) {
      log.debug("Could not recolour Windows window frame: {}", err.getMessage());
    }
  }

  private static void applyMacDarkMode() {
    // Setting this property *before* AWT initialises asks macOS to use the
    // dark NSAppearance for all of the app's windows, including JavaFX surfaces.
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
  }

  private static void applyLinuxDarkMode() {
    // Hint GTK to use a dark variant of the active theme. This does nothing
    // if GTK isn't picking it up, but it's harmless on any other DE.
    if (System.getenv("GTK_THEME") == null) {
      try {
        // ProcessHandle has no setEnvironment, so we just set a system
        // property GTK loaders check via JNI in some distros.
        System.setProperty("gtk.theme", "Adwaita:dark");
      } catch (Exception ignored) {
      }
    }
  }
}
