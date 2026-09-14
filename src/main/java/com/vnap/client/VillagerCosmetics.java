package com.vnap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vnap.VillagerNewsAddonPort;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic, item-free villager cosmetics/nose/sign board state.
 *
 * <p>Everything here is a pure function of the villager's UUID (and, for cosmetics, its
 * {@link ClientDialogueController.CastProfile}) so that it never needs to be synced from a
 * server - a vanilla dedicated server obviously has no idea about any of this. A small
 * per-UUID override map (populated via the nose-toggle / sign-cycle keybinds) lets the player
 * locally opt a specific villager in or out of the deterministic default; those overrides are
 * persisted to a small JSON file in the config directory so they survive a restart.</p>
 */
public final class VillagerCosmetics {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("villager-news-addon-port-cosmetics.json");
	private static final Map<UUID, Boolean> NOSE_OVERRIDES = new HashMap<>();
	private static final Map<UUID, Integer> SIGN_TYPE_OVERRIDES = new HashMap<>();
	private static final Map<UUID, Integer> SIGN_MESSAGE_OVERRIDES = new HashMap<>();
	private static final int SIGN_TYPE_COUNT = 12;
	private static final int SIGN_MESSAGE_COUNT = 87;

	private VillagerCosmetics() {
	}

	public static synchronized void load() {
		if (!Files.exists(PATH)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
			readOverrides(root, "noseOverrides", NOSE_OVERRIDES, element -> element.getAsBoolean());
			readOverrides(root, "signTypeOverrides", SIGN_TYPE_OVERRIDES, element -> element.getAsInt());
			readOverrides(root, "signMessageOverrides", SIGN_MESSAGE_OVERRIDES, element -> element.getAsInt());
		} catch (IOException | RuntimeException exception) {
			VillagerNewsAddonPort.LOGGER.warn("Could not load Villager News cosmetic overrides", exception);
		}
	}

	private interface ValueReader<T> {
		T read(com.google.gson.JsonElement element);
	}

	private static <T> void readOverrides(JsonObject root, String key, Map<UUID, T> target, ValueReader<T> reader) {
		if (!root.has(key)) return;
		for (Map.Entry<String, com.google.gson.JsonElement> entry : root.getAsJsonObject(key).entrySet()) {
			try {
				target.put(UUID.fromString(entry.getKey()), reader.read(entry.getValue()));
			} catch (IllegalArgumentException ignored) {
				// skip malformed entry
			}
		}
	}

	private static synchronized void save() {
		JsonObject root = new JsonObject();
		JsonObject nose = new JsonObject();
		NOSE_OVERRIDES.forEach((id, value) -> nose.addProperty(id.toString(), value));
		root.add("noseOverrides", nose);
		JsonObject signType = new JsonObject();
		SIGN_TYPE_OVERRIDES.forEach((id, value) -> signType.addProperty(id.toString(), value));
		root.add("signTypeOverrides", signType);
		JsonObject signMessage = new JsonObject();
		SIGN_MESSAGE_OVERRIDES.forEach((id, value) -> signMessage.addProperty(id.toString(), value));
		root.add("signMessageOverrides", signMessage);
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			VillagerNewsAddonPort.LOGGER.warn("Could not save Villager News cosmetic overrides", exception);
		}
	}

	private static long hash(UUID id, long salt) {
		long value = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17) ^ salt;
		value ^= value >>> 33;
		value *= 0xFF51AFD7ED558CCDL;
		value ^= value >>> 33;
		value *= 0xC4CEB9FE1A85EC53L;
		value ^= value >>> 33;
		return value;
	}

	private static int positiveMod(long value, int modulus) {
		return (int) Math.floorMod(value, (long) modulus);
	}

	public static boolean hasNose(UUID villagerId) {
		Boolean override = NOSE_OVERRIDES.get(villagerId);
		return override != null ? override : true;
	}

	public static void toggleNose(UUID villagerId) {
		NOSE_OVERRIDES.put(villagerId, !hasNose(villagerId));
		save();
	}

	/**
	 * Cosmetic id following the villager's cast - 1 mayor hat, 2 testificate man helmet,
	 * 3 microphone, 4 moustache, 0 none. No longer purchasable/given; it simply always renders
	 * on the matching special villager.
	 */
	public static int cosmeticFor(ClientDialogueController.CastProfile cast) {
		return switch (cast) {
			case MAYOR -> 1;
			case TESTIFICATE_MAN -> 2;
			case NUMBER_9 -> 3;
			case NUMBER_5 -> 4;
			default -> 0;
		};
	}

	private static boolean defaultHasSign(UUID villagerId) {
		return positiveMod(hash(villagerId, 0x519E17L), 100) < 15;
	}

	public static int signType(UUID villagerId) {
		Integer override = SIGN_TYPE_OVERRIDES.get(villagerId);
		if (override != null) return override;
		return defaultHasSign(villagerId) ? positiveMod(hash(villagerId, 1L), SIGN_TYPE_COUNT) : -1;
	}

	public static int signMessage(UUID villagerId) {
		Integer override = SIGN_MESSAGE_OVERRIDES.get(villagerId);
		if (override != null) return override;
		return defaultHasSign(villagerId) ? positiveMod(hash(villagerId, 2L), SIGN_MESSAGE_COUNT) : -1;
	}

	/** Cycles the sign wood type; shift-clicking removes the sign entirely once cycled past the start. */
	public static void cycleSignType(UUID villagerId, boolean reverse) {
		int current = signType(villagerId);
		int next = current < 0 ? 0 : reverse ? current - 1 : current + 1;
		if (next < 0 || next >= SIGN_TYPE_COUNT) {
			SIGN_TYPE_OVERRIDES.put(villagerId, -1);
			SIGN_MESSAGE_OVERRIDES.put(villagerId, -1);
		} else {
			SIGN_TYPE_OVERRIDES.put(villagerId, next);
			if (signMessage(villagerId) < 0) SIGN_MESSAGE_OVERRIDES.put(villagerId, 0);
		}
		save();
	}

	public static void cycleSignMessage(UUID villagerId, boolean reverse) {
		if (signType(villagerId) < 0) return;
		int current = Math.max(0, signMessage(villagerId));
		int next = Math.floorMod(current + (reverse ? -1 : 1), SIGN_MESSAGE_COUNT);
		SIGN_MESSAGE_OVERRIDES.put(villagerId, next);
		save();
	}
}
