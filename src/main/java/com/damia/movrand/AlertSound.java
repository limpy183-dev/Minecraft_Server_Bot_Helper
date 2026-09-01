package com.damia.movrand;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Plays the user's own alert file from a folder on disk — not a bundled resource,
 * so the file can be swapped without rebuilding the mod.
 *
 * <p>.wav / .aiff / .au go through {@code javax.sound.sampled} in-process, which gives
 * exact repeat timing and volume. Anything else (.mp3, .ogg, .m4a…) has no decoder in
 * the JVM, so it is handed to the OS media player.
 */
public final class AlertSound {

	private static final List<String> EXTENSIONS =
			List.of(".wav", ".mp3", ".ogg", ".aiff", ".aif", ".au", ".m4a", ".flac", ".wma");
	private static final List<String> NATIVE_EXTENSIONS = List.of(".wav", ".aiff", ".aif", ".au");

	private static final AtomicReference<Clip> ACTIVE_CLIP = new AtomicReference<>();
	private static volatile Thread worker;
	private static volatile String lastError = "";

	private AlertSound() {
	}

	public static String lastError() {
		return lastError;
	}

	/** Every playable file in the configured folder, sorted by name. */
	public static List<String> listFiles(Config cfg) {
		List<String> out = new ArrayList<>();
		try {
			Path dir = Path.of(cfg.alertFolder);
			if (!Files.isDirectory(dir)) return out;
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(Files::isRegularFile)
						.map(p -> p.getFileName().toString())
						.filter(n -> hasAudioExtension(n))
						.sorted(Comparator.naturalOrder())
						.forEach(out::add);
			}
		} catch (Exception ignored) {
			// an unreadable folder is reported through resolve() instead
		}
		return out;
	}

	/** The file that would actually be played, or null with {@link #lastError()} set. */
	public static Path resolve(Config cfg) {
		try {
			Path dir = Path.of(cfg.alertFolder);
			if (!Files.isDirectory(dir)) {
				lastError = "Folder not found: " + cfg.alertFolder;
				return null;
			}
			if (cfg.alertFile != null && !cfg.alertFile.isBlank()) {
				Path f = dir.resolve(cfg.alertFile);
				if (Files.isRegularFile(f)) return f;
				lastError = "File not found: " + cfg.alertFile;
				return null;
			}
			List<String> files = listFiles(cfg);
			if (files.isEmpty()) {
				lastError = "No audio files in " + cfg.alertFolder;
				return null;
			}
			return dir.resolve(files.getFirst());
		} catch (Exception e) {
			lastError = String.valueOf(e.getMessage());
			return null;
		}
	}

	public static boolean hasAudioExtension(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return EXTENSIONS.stream().anyMatch(n::endsWith);
	}

	public static boolean isNativelyDecodable(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return NATIVE_EXTENSIONS.stream().anyMatch(n::endsWith);
	}

	/** Fire and forget. Any in-flight alert is cancelled first. */
	public static void play(Config cfg, int repeats) {
		Path file = resolve(cfg);
		if (file == null) {
			MovRand.LOG.warn("[movrand] alert sound unavailable: {}", lastError);
			return;
		}
		stop();
		int count = Math.max(1, repeats);
		double volume = Math.max(0.0, Math.min(1.0, cfg.alertVolume));
		long gapMs = (long) Math.max(0, cfg.alertGapSec * 1000);

		Thread t = new Thread(() -> {
			try {
				if (isNativelyDecodable(file)) {
					playViaJavaSound(file, count, volume, gapMs);
				} else {
					playViaOs(file, count, volume, gapMs);
				}
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				lastError = String.valueOf(e.getMessage());
				MovRand.LOG.warn("[movrand] alert playback failed", e);
			}
		}, "movrand-alert");
		t.setDaemon(true);
		worker = t;
		t.start();
	}

	public static void stop() {
		Thread t = worker;
		worker = null;
		if (t != null) t.interrupt();
		Clip c = ACTIVE_CLIP.getAndSet(null);
		if (c != null) {
			try {
				c.stop();
				c.close();
			} catch (Exception ignored) {
				// already released
			}
		}
	}

	// ------------------------------------------------------------- backends

	private static void playViaJavaSound(Path file, int count, double volume, long gapMs) throws Exception {
		for (int i = 0; i < count && !Thread.currentThread().isInterrupted(); i++) {
			try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
				Clip clip = AudioSystem.getClip();
				clip.open(in);
				applyVolume(clip, volume);
				ACTIVE_CLIP.set(clip);
				clip.start();
				long ms = clip.getMicrosecondLength() / 1000L;
				Thread.sleep(Math.max(50, ms));
				clip.stop();
				clip.close();
				ACTIVE_CLIP.compareAndSet(clip, null);
			}
			if (i < count - 1) Thread.sleep(gapMs);
		}
	}

	private static void applyVolume(Clip clip, double volume) {
		try {
			if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
			FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
			float db = volume <= 0.0001 ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
			gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
		} catch (Exception ignored) {
			// unsupported mixer: play at whatever the default level is
		}
	}

	private static void playViaOs(Path file, int count, double volume, long gapMs) throws Exception {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			playWindowsHidden(file, count, volume, gapMs);
			return;
		}
		String[] cmd = os.contains("mac")
				? new String[]{"afplay", "-v", String.valueOf(volume), file.toString()}
				: new String[]{"ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", file.toString()};
		for (int i = 0; i < count && !Thread.currentThread().isInterrupted(); i++) {
			new ProcessBuilder(cmd).inheritIO().start().waitFor();
			if (i < count - 1) Thread.sleep(gapMs);
		}
	}

	/**
	 * The whole repeat loop runs inside one PowerShell process, launched through a
	 * wscript shim so no console window flashes over the game.
	 */
	private static void playWindowsHidden(Path file, int count, double volume, long gapMs) throws Exception {
		Path dir = Files.createTempDirectory("movrand-alert");
		dir.toFile().deleteOnExit();
		Path ps1 = dir.resolve("play.ps1");
		Path vbs = dir.resolve("play.vbs");

		String script = """
				Add-Type -AssemblyName presentationCore
				$player = New-Object System.Windows.Media.MediaPlayer
				$player.Open([uri]'%s')
				Start-Sleep -Milliseconds 400
				$ms = 1500
				if ($player.NaturalDuration.HasTimeSpan) { $ms = [int]$player.NaturalDuration.TimeSpan.TotalMilliseconds }
				$player.Volume = %s
				for ($i = 0; $i -lt %d; $i++) {
				    $player.Position = [TimeSpan]::Zero
				    $player.Play()
				    Start-Sleep -Milliseconds $ms
				    $player.Stop()
				    if ($i -lt %d) { Start-Sleep -Milliseconds %d }
				}
				$player.Close()
				""".formatted(
				file.toString().replace("'", "''"),
				String.format(Locale.ROOT, "%.3f", volume),
				count, count - 1, gapMs);
		Files.writeString(ps1, script);

		// A text block cannot hold VBScript's doubled quotes, so this one is concatenated.
		Files.writeString(vbs,
				"Set sh = CreateObject(\"WScript.Shell\")\r\n"
						+ "sh.Run \"powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"\""
						+ ps1 + "\"\"\", 0, True\r\n");

		Process p = new ProcessBuilder("wscript.exe", "//B", "//Nologo", vbs.toString())
				.redirectErrorStream(true)
				.start();
		p.waitFor();
		deleteQuietly(ps1);
		deleteQuietly(vbs);
		deleteQuietly(dir);
	}

	private static void deleteQuietly(Path p) {
		try {
			Files.deleteIfExists(p);
		} catch (Exception ignored) {
			// temp dir, the JVM exit hook will get it
		}
	}

	/** For the GUI: a short human-readable state of the sound setup. */
	public static String status(Config cfg) {
		Path f = resolve(cfg);
		if (f == null) return "⚠ " + lastError;
		String kind = isNativelyDecodable(f) ? "in-game decoder" : "system player";
		return f.getFileName() + "  (" + kind + ")";
	}

	public static File folderAsFile(Config cfg) {
		return new File(cfg.alertFolder);
	}
}
