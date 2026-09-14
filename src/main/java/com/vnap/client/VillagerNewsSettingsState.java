package com.vnap.client;

import com.vnap.config.VillagerNewsSettings;

/**
 * Thin client-side cache in front of {@link VillagerNewsSettings}. Now that this is a
 * client-only mod, there is no server to authorize edits or push updates from - every install
 * always behaves the way the old "localSettings" path did, editing the on-disk JSON config
 * directly.
 */
public final class VillagerNewsSettingsState {
	private VillagerNewsSettingsState() {
	}

	public static void prepareConfigScreen() {
		// Nothing to prepare anymore; getters/setters read/write VillagerNewsSettings directly.
	}

	public static int chattiness() {
		return VillagerNewsSettings.chattiness();
	}

	public static int rareVoicelines() {
		return VillagerNewsSettings.rareVoicelines();
	}

	public static boolean spawnSpecialVillagers() {
		return VillagerNewsSettings.spawnSpecialVillagers();
	}

	public static boolean canEdit() {
		return true;
	}

	public static void setChattiness(int value) {
		VillagerNewsSettings.update(Math.floorMod(value, 4), rareVoicelines(), spawnSpecialVillagers());
	}

	public static void setRareVoicelines(int value) {
		VillagerNewsSettings.update(chattiness(), Math.floorMod(value, 3), spawnSpecialVillagers());
	}

	public static void setSpawnSpecialVillagers(boolean value) {
		VillagerNewsSettings.update(chattiness(), rareVoicelines(), value);
	}

	public static void reset() {
		// No-op: settings are always local now.
	}
}
