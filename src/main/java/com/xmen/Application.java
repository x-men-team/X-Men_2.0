package com.xmen;

import com.xmen.user_interface.XMenInterface;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/** Main Application. */
@SpringBootApplication(scanBasePackages = "com.xmen")
public class Application implements CommandLineRunner {

  /** Spring context handle — kept so the shutdown hook can close it. */
  private static volatile ConfigurableApplicationContext SPRING_CONTEXT;

  /**
   * Main method.
   *
   * @param args Command line arguments
   */
  public static void main(String[] args) {
    System.out.println("java.awt.headless=" + System.getProperty("java.awt.headless"));
    applyLinuxJavafxTuning();
    SPRING_CONTEXT = SpringApplication.run(Application.class, args);

    if (!isTestRuntime()) {
      // JVM-wide safety net: if anything (an OS signal, a sibling exit() call,
      // an Actuator /shutdown, etc.) starts tearing the JVM down, make sure the
      // JavaFX runtime also exits so we don't leak windows.
      Runtime.getRuntime()
              .addShutdownHook(
                      new Thread(
                              () -> {
                                try {
                                  XMenInterface.shutdownUi();
                                } catch (Throwable ignored) {
                                }

                                try {
                                  if (SPRING_CONTEXT != null && SPRING_CONTEXT.isActive()) {
                                    SPRING_CONTEXT.close();
                                  }
                                } catch (Throwable ignored) {
                                }
                              },
                              "xmen-force-shutdown"));
    }
  }

  /**
   * Run method that is executed after the application context is loaded.
   *
   * @param args Command line arguments
   */
  @Override
  public void run(String... args) {
    System.out.println("Is Headless: " + java.awt.GraphicsEnvironment.isHeadless());

    if (!isUiEnabled()) {
      System.out.println("JavaFX UI disabled by xmen.ui.enabled/XMEN_UI_ENABLED.");
      return;
    }

    if (!java.awt.GraphicsEnvironment.isHeadless()) {
      // Blocks until the JavaFX app exits (last stage closed or Platform.exit()).
      XMenInterface.launch(XMenInterface.class);

      // Belt-and-braces: when launch() returns, force a JVM exit so the
      // embedded web server doesn't keep the process alive forever.
      if (!isTestRuntime()) System.exit(0);
    } else {
      System.err.println("Cannot run GUI in a headless environment");
    }
  }

  /**
   * Spring context is closing for some reason other than the user clicking
   * the X (e.g. SIGTERM, /actuator/shutdown, devtools reload). Pull the
   * JavaFX runtime down with it so no window is left orphaned.
   */
  @EventListener
  public void onContextClosed(ContextClosedEvent event) {
    try {
      XMenInterface.shutdownUi();
    } catch (Throwable ignored) {
      // already gone
    }
  }

  private static boolean isTestRuntime() {
    return System.getProperty("surefire.test.class.path") != null
        || System.getProperty("org.gradle.test.worker") != null
        || System.getProperty("java.class.path", "").contains("surefire");
  }

  private static boolean isUiEnabled() {
    String value =
        System.getProperty(
            "xmen.ui.enabled", System.getenv().getOrDefault("XMEN_UI_ENABLED", "true"));
    return Boolean.parseBoolean(value);
  }

  /**
   * Apply JavaFX Prism tuning that makes the background-video rotator and splash MediaPlayer
   * play smoothly on Linux. These properties have to be set before the JavaFX runtime
   * initialises (i.e. before {@code Application.launch}), so main() is the only correct place.
   *
   * <ul>
   *   <li>{@code prism.order=es2,sw} — prefer the OpenGL ES 2 pipeline and fall back to the
   *       software pipeline. The default order on Linux can pick a problematic GL pipeline that
   *       holds GStreamer frame uploads for several milliseconds at a time, which the user sees
   *       as visible stutter during the background-video crossfade.
   *   <li>{@code prism.vsync=false} — disable the Prism v-sync clamp. With v-sync enabled, the
   *       render thread waits for the compositor's next swap interval, and on Wayland +
   *       Mutter that wait can synchronise badly with GStreamer's frame cadence and produce a
   *       periodic hitch.
   *   <li>{@code prism.lcdtext=false} — text-heavy scenes (the mutation panel) repaint less
   *       text overhead per frame, freeing CPU for media decode.
   *   <li>{@code quantum.multithreaded=true} — explicitly opt into the multi-threaded render
   *       pipeline so the FX thread is not the only one decoding + uploading frames.
   * </ul>
   *
   * <p>Each property is only set if the caller didn't already pin a value via -D, so power users
   * can still override at the command line. No-op on Windows and macOS.
   */
  private static void applyLinuxJavafxTuning() {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    if (!(os.contains("linux") || os.contains("nix"))) return;
    setIfAbsent("prism.order", "es2,sw");
    setIfAbsent("prism.vsync", "false");
    setIfAbsent("prism.lcdtext", "false");
    setIfAbsent("quantum.multithreaded", "true");
  }

  private static void setIfAbsent(String key, String value) {
    if (System.getProperty(key) == null) {
      System.setProperty(key, value);
    }
  }
}
