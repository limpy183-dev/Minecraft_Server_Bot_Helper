package com.damia.movrand;

import com.damia.movrand.gui.ConfigScreen;
import com.damia.movrand.gui.Ui;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MovRand implements ClientModInitializer {

	public static final String MOD_ID = "movrand";
	public static final String MC_VERSION = "26.2";
	public static final Logger LOG = LoggerFactory.getLogger("movrand");
	public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("1.0.0");

	private static Config config;
	private static MovementController controller;
	private static KeyMapping openKey;
	private static KeyMapping toggleKey;

	public static Config config() {
		return config;
	}

	public static MovementController controller() {
		return controller;
	}

	public static void replaceConfig(Config fresh) {
		config = fresh;
		controller = new MovementController(fresh);
	}

	/**
	 * Only safe once GLFW is up. Resolving a key's display name calls
	 * {@code glfwGetKeyName}, and doing that during mod init — before
	 * {@code RenderSystem.initBackendSystem} runs {@code glfwInit} — queues a
	 * GLFW_NOT_INITIALIZED error that the game then rethrows as a startup crash.
	 * Call these from screens and commands, never from an entrypoint.
	 */
	public static String openKeyName() {
		return keyName(openKey);
	}

	public static String toggleKeyName() {
		return keyName(toggleKey);
	}

	private static String keyName(KeyMapping key) {
		if (key == null) return "?";
		try {
			return key.getTranslatedKeyMessage().getString();
		} catch (Exception e) {
			return "?";
		}
	}

	@Override
	public void onInitializeClient() {
		config = Config.load();
		controller = new MovementController(config);

		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.movrand.open", GLFW.GLFW_KEY_APOSTROPHE, KeyMapping.Category.MOVEMENT));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.movrand.toggle", GLFW.GLFW_KEY_SEMICOLON, KeyMapping.Category.MOVEMENT));

		// Runs before the player's own tick, so the key states we write are the ones vanilla reads.
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick()) {
				if (mc.gui.screen() == null) openMenu(mc);
			}
			while (toggleKey.consumeClick()) {
				controller.toggle(mc);
			}
			controller.tick(mc);
		});

		// CHAT is what another player typed, and is always worth reading. GAME is everything
		// else the client prints - including this mod's own output, which is why it is filtered.
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) ->
				onChat(message.getString(), isSelf(sender)));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (printing || !config.chatWatchSystemMessages) return;
			onChat(message.getString(), isAdvancement(message));
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), new StatusHud());

		LOG.info("[movrand] loaded — open the menu with the key bound to key.movrand.open");
	}

	/**
	 * The menu's classes are loaded the first time the menu is opened, and that is the one
	 * moment a broken jar can still take the whole game down: copying a new build over the
	 * old one while the game is running leaves the loader reading stale offsets into a file
	 * that has moved underneath it, and the next class it wants throws a ZipException from
	 * inside this tick handler. Losing the menu until a restart is a fair price. Losing the
	 * world you were standing in is not.
	 */
	private static void openMenu(Minecraft mc) {
		try {
			mc.setScreenAndShow(new ConfigScreen());
		} catch (Throwable t) {
			LOG.error("[movrand] the menu could not be opened", t);
			print(mc, "\u00a7cThe menu failed to open - see the log. If the mod jar was replaced "
					+ "while the game was running, restart the game.");
		}
	}

	/**
	 * Deliberately not gated on movement being on: a message with your name in it is worth
	 * an alert precisely when the bot has already stopped and you are away from the keyboard.
	 */
	private void onChat(String raw, boolean selfOrAdvancement) {
		if (!config.stopOnChatKeyword) return;
		if (raw.startsWith(CHAT_PREFIX)) return; // our own output, do not trigger on it
		String text = raw.toLowerCase(Locale.ROOT);

		List<String> needles = new ArrayList<>(config.chatKeywords);
		if (config.chatKeywordMatchOwnName
				&& !(selfOrAdvancement && config.chatIgnoreSelfAndAdvancements)) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) needles.add(mc.player.getName().getString());
			if (mc.getUser() != null) needles.add(mc.getUser().getName());
		}
		for (String needle : needles) {
			if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
				MovRand.LOG.info("[movrand] chat keyword \"{}\" matched", needle);
				controller.onChatMessage(needle);
				return;
			}
		}
	}

	/** A message this account sent. The keywords still apply to it; only the name match drops. */
	private static boolean isSelf(GameProfile sender) {
		User user = Minecraft.getInstance().getUser();
		return sender != null && user != null && sender.id().equals(user.getProfileId());
	}

	/** "Name has made the advancement [Thing]", and its goal and challenge variants. */
	private static boolean isAdvancement(Component message) {
		return message.getContents() instanceof TranslatableContents t
				&& t.getKey().startsWith("chat.type.advancement");
	}

	/**
	 * Everything this mod prints goes through here.
	 *
	 * <p>A client-side message is a system message, and the chat watcher reads system messages
	 * — so "Stopped: PlayerName is 0 blocks away" used to set off the keyword that produced it,
	 * over and over. The flag closes that loop at the source rather than by pattern-matching
	 * the output, which is also why a player typing the prefix cannot suppress a real alert.
	 */
	public static final String CHAT_PREFIX = "§8[§bMovRand§8] §r";
	private static boolean printing;

	public static void print(Minecraft mc, String message) {
		if (mc.player == null) return;
		printing = true;
		try {
			mc.player.sendSystemMessage(Component.literal(CHAT_PREFIX + message));
		} finally {
			printing = false;
		}
	}

	/** The little status card in the corner. */
	private static final class StatusHud implements HudElement {

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
			if (!config.hudEnabled) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.gui.screen() instanceof ConfigScreen) return;

			Font font = mc.font;
			int accent = config.accentColor;
			boolean on = config.movementEnabled;

			List<String> lines = new ArrayList<>();
			lines.add(controller.describeState());
			if (on) {
				lines.add("Up " + Ui.seconds(controller.runtimeSeconds()));
				if (config.hudShowNextEvent) lines.add("Next " + Ui.seconds(controller.nextEventSeconds()));
			}
			if (config.hudShowGoto && config.areaEnabled) {
				lines.add("Area %d/%d chunks".formatted(controller.area.visitedCount(), controller.area.totalChunks()));
			} else if (config.hudShowGoto && config.gotoEnabled) {
				lines.add(String.format(Locale.ROOT, "→ %.0f, %.0f", config.gotoX, config.gotoZ));
			}
			if (config.destroyerEnabled) {
				lines.add("Mined " + controller.destroyer.mined
						+ " · " + controller.destroyer.remaining() + " left");
				// what the job thinks it is doing, for when that and what it does disagree
				lines.add(controller.destroyer.diagnose(mc, mc.player));
			}
			if (config.hudShowContainers && config.containerScanEnabled) {
				lines.add("Storage " + controller.lastScan.grouped() + "/" + config.containerThreshold);
			}
			if (config.hudShowJournal && config.journalEnabled && controller.journal.size() > 0) {
				lines.add("Logged " + controller.journal.size());
			}
			if (config.hudShowFood && config.autoEatEnabled && mc.player != null) {
				lines.add("Food " + mc.player.getFoodData().getFoodLevel() + "/20");
			}

			int pad = 6;
			int w = pad * 2 + 14;
			for (String s : lines) w = Math.max(w, pad * 2 + 12 + font.width(s));
			int h = pad * 2 + lines.size() * 11;

			float scale = (float) config.hudScale;
			int screenW = (int) (g.guiWidth() / scale);
			int screenH = (int) (g.guiHeight() / scale);
			int x = switch (config.hudCorner) {
				case TOP_LEFT, BOTTOM_LEFT -> config.hudOffsetX;
				case TOP_RIGHT, BOTTOM_RIGHT -> screenW - w - config.hudOffsetX;
			};
			int y = switch (config.hudCorner) {
				case TOP_LEFT, TOP_RIGHT -> config.hudOffsetY;
				case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - h - config.hudOffsetY;
			};

			g.pose().pushMatrix();
			g.pose().scale(scale, scale);
			Ui.card(g, x, y, w, h, 5, 0xD00E0E14, on ? Ui.shade(accent, 0.6) : Ui.BORDER);
			Ui.roundRect(g, x + pad, y + pad + 2, 5, 5, 2, on ? accent : Ui.TEXT_FAINT);
			int ty = y + pad;
			for (int i = 0; i < lines.size(); i++) {
				Ui.text(g, font, lines.get(i), x + pad + 12, ty, i == 0 ? Ui.TEXT : Ui.TEXT_MUTED);
				ty += 11;
			}
			g.pose().popMatrix();
		}
	}
}
