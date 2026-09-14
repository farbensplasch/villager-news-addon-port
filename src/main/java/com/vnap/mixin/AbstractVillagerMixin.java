package com.vnap.mixin;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Trade completion is now detected client-side by {@code com.vnap.client.ClientDialogueController}
 * (by polling the local player's open {@code MerchantMenu} for a used-offer count change), since
 * {@code notifyTrade} only ever runs on the authoritative side and a vanilla dedicated server has
 * no idea this mod exists. Only the vanilla trade/celebrate sound suppression remains here, since
 * that runs on whichever side renders/plays the sound.
 */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
	@Inject(method = "getNotifyTradeSound", at = @At("HEAD"), cancellable = true)
	private void vnap$removeVanillaTradeSound(CallbackInfoReturnable<SoundEvent> cir) {
		cir.setReturnValue(SoundEvents.EMPTY);
	}

	@Inject(method = "getTradeUpdatedSound", at = @At("HEAD"), cancellable = true)
	private void vnap$removeVanillaTradeUpdatedSound(boolean sold, CallbackInfoReturnable<SoundEvent> cir) {
		cir.setReturnValue(SoundEvents.EMPTY);
	}

	@Inject(method = "playCelebrateSound", at = @At("HEAD"), cancellable = true)
	private void vnap$removeVanillaCelebrateSound(CallbackInfo ci) {
		ci.cancel();
	}
}
