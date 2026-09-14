package com.vnap;

import com.vnap.config.VillagerNewsSettings;
import com.vnap.dialogue.DialogueCatalog;
import com.vnap.item.VillagerNewsItems;
import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. As of the client-only rewrite this mod is declared {@code "environment":
 * "client"} in fabric.mod.json, so this only ever runs inside the same physical client process as
 * {@link com.vnap.client.VillagerNewsAddonPortClient} (including for the integrated server in
 * singleplayer/LAN) - it is kept separate mainly to mirror the registry-construction ordering
 * Fabric expects (items/sounds/dialogue catalog before client rendering/animation wiring reads
 * them), not because it needs to run on a dedicated server.
 */
public class VillagerNewsAddonPort implements ModInitializer {
	public static final String MOD_ID = "villager-news-addon-port";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		VillagerNewsItems.register();
		VillagerNewsSettings.load();
		DialogueCatalog.register();
		LOGGER.info("Villager News models, textures, and contextual dialogue are ready.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
