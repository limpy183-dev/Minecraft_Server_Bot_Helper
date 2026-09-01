package com.damia.movrand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

/**
 * Which world or server the client is currently on.
 *
 * <p>The log rotates by day, not by world, so one file happily holds a morning on one server
 * and an afternoon on another. Without this the viewer would show them mixed together and the
 * map would plot two unrelated sets of coordinates on top of each other.
 */
public final class WorldId {

	private WorldId() {
	}

	/**
	 * A stable key stored on every entry. Empty when there is no world loaded.
	 *
	 * <p>Four ways to ask, in order of how much they can be trusted. The last two matter
	 * because {@code getCurrentServer()} is briefly null while a connection is being set up,
	 * and an entry logged in that window would otherwise be stamped with a key that belongs
	 * to nothing and splits one session in two.
	 */
	public static String current() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
				return "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
			}

			String ip = addressOf(mc.getCurrentServer());
			if (ip == null && mc.getConnection() != null) {
				ip = addressOf(mc.getConnection().getServerData());
				if (ip == null) {
					// last resort: whoever the socket is actually pointed at
					java.net.SocketAddress remote = mc.getConnection().getConnection().getRemoteAddress();
					if (remote != null) ip = remote.toString().replace("/", "").toLowerCase(Locale.ROOT);
				}
			}
			return ip == null ? "" : "mp:" + ip;
		} catch (Exception | LinkageError e) {
			return "";
		}
	}

	private static String addressOf(ServerData server) {
		if (server == null || server.ip == null || server.ip.isBlank()) return null;
		return server.ip.toLowerCase(Locale.ROOT);
	}

	/** What to put in front of a person. */
	public static String label(String id) {
		if (id == null || id.isBlank()) return "unknown";
		if (id.startsWith("sp:")) return id.substring(3) + "  (single player)";
		if (id.startsWith("mp:")) return id.substring(3);
		return id;
	}

	public static String currentLabel() {
		return label(current());
	}

	/**
	 * @param includeUnknown what to do with an entry written before the world was recorded.
	 *                       There is no way to work out where those came from, so they are
	 *                       neither this world nor another one — showing them everywhere
	 *                       makes "this world only" mean nothing, which is worse than
	 *                       hiding them, so the default is to leave them out.
	 * @return true if an entry belongs to {@code world}.
	 */
	public static boolean matches(String entryWorld, String world, boolean includeUnknown) {
		String entry = entryWorld == null ? "" : entryWorld;
		if (entry.isEmpty()) return includeUnknown;
		return entry.equals(world);
	}
}
