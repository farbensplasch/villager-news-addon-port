package com.vnap.client;

import com.vnap.VillagerNewsAddonPort;
import com.vnap.item.VillagerNewsItems;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;
import traben.entity_model_features.EMFAnimationApi;

import java.io.IOException;
import java.util.function.Supplier;

public final class VillagerNewsAddonPortClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(VillagerNewsAddonPort.id("villager_news"));
	private static KeyMapping toggleNoseKey;
	private static KeyMapping cycleSignKey;
	private static KeyMapping openHandbookKey;

	@Override
	public void onInitializeClient() {
		VillagerCosmetics.load();
		try {
			DialogueAnimationState.load();
			registerFloat("vnap_speaking", DialogueAnimationState::speaking, "Whether the Villager News character is speaking");
			registerFloat("vnap_mouth_open", DialogueAnimationState::mouthOpen, "Current Villager News mouth opening");
			registerFloat("vnap_mouth_width", DialogueAnimationState::mouthWidth, "Current Villager News mouth width");
			registerFloat("vnap_mouth_closed", DialogueAnimationState::mouthClosed, "Current Villager News closed-mouth layer");
			registerFloat("vnap_has_nose", DialogueAnimationState::hasNose, "Villager News nose visibility");
			registerFloat("vnap_cosmetic_mayor_hat", () -> DialogueAnimationState.cosmetic(1), "Villager News mayor hat visibility");
			registerFloat("vnap_cosmetic_helmet", () -> DialogueAnimationState.cosmetic(2), "Villager News helmet visibility");
			registerFloat("vnap_cosmetic_microphone", () -> DialogueAnimationState.cosmetic(3), "Villager News microphone visibility");
			registerFloat("vnap_cosmetic_moustache", () -> DialogueAnimationState.cosmetic(4), "Villager News moustache visibility");
			for (String variable : DialogueAnimationState.animationVariables()) {
				registerFloat(variable, () -> DialogueAnimationState.transform(variable), "Synchronized Villager News dialogue transform");
			}
		} catch (IOException | RuntimeException exception) {
			throw new IllegalStateException("Could not load Villager News animations", exception);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not register Villager News EMF animation variables", exception);
		}

		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
			if (entityType == EntityTypes.VILLAGER && entityRenderer instanceof VillagerRenderer villagerRenderer) {
				helper.register(new VillagerNewsSignLayer(villagerRenderer));
			}
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!level.isClientSide()) return InteractionResult.PASS;
			if (player.getItemInHand(hand).getItem() != VillagerNewsItems.HANDBOOK) return InteractionResult.PASS;
			Minecraft.getInstance().setScreenAndShow(new HandbookScreen());
			return InteractionResult.SUCCESS;
		});

		toggleNoseKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.villager-news-addon-port.toggle_nose", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY));
		cycleSignKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.villager-news-addon-port.cycle_sign", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
		openHandbookKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.villager-news-addon-port.open_handbook", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));

		DialogueSubtitleState.register();
		ClientDialogueController.register();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			DialogueSoundState.tick(client);
			DialogueAnimationState.tick(client);
			DialogueSubtitleState.tick(client);
			handleKeybinds(client);
		});
		VillagerNewsAddonPort.LOGGER.info("Registered synchronized EMF facial and dialogue animations");
	}

	private static void handleKeybinds(Minecraft client) {
		Villager target = targetedVillager(client);
		while (toggleNoseKey.consumeClick()) {
			if (target != null) VillagerCosmetics.toggleNose(target.getUUID());
		}
		while (cycleSignKey.consumeClick()) {
			if (target == null) continue;
			boolean shift = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
				|| GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
			if (shift) VillagerCosmetics.cycleSignType(target.getUUID(), false);
			else VillagerCosmetics.cycleSignMessage(target.getUUID(), false);
		}
		while (openHandbookKey.consumeClick()) {
			client.setScreenAndShow(new HandbookScreen());
		}
	}

	private static Villager targetedVillager(Minecraft client) {
		HitResult hit = client.hitResult;
		if (hit instanceof EntityHitResult entityHitResult && entityHitResult.getEntity() instanceof Villager villager) {
			return villager;
		}
		return null;
	}

	private static void registerFloat(String name, Supplier<Float> supplier, String description) throws Exception {
		EMFAnimationApi.registerSingletonAnimationVariable(VillagerNewsAddonPort.MOD_ID, name, description, supplier);
	}
}
