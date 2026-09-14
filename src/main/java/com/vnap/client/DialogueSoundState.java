package com.vnap.client;

import com.vnap.dialogue.DialogueCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class DialogueSoundState {
	private static final Map<UUID, ActiveSound> ACTIVE = new HashMap<>();
	private static final Map<UUID, PendingSound> PENDING = new HashMap<>();
	private static final long PENDING_TIMEOUT_NANOS = 5_000_000_000L;

	private DialogueSoundState() {
	}

	public static void start(UUID entityId, String groupId, int variantIndex, int durationTicks) {
		Minecraft minecraft = Minecraft.getInstance();
		stop(minecraft, entityId);
		PENDING.remove(entityId);
		if (groupId.isEmpty() || minecraft.level == null) return;
		PendingSound request = new PendingSound(entityId, groupId, variantIndex, durationTicks, System.nanoTime() + PENDING_TIMEOUT_NANOS);
		if (!tryStart(minecraft, request)) {
			PENDING.put(entityId, request);
		}
	}

	private static boolean tryStart(Minecraft minecraft, PendingSound request) {
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(request.groupId());
		if (group == null) return true;
		DialogueCatalog.DialogueVariant variant = group.variants().stream()
			.filter(candidate -> candidate.index() == request.variantIndex()).findFirst().orElse(null);
		Entity entity = minecraft.level.getEntity(request.entityId());
		if (variant == null) return true;
		if (entity == null) return false;
		boolean followsEntity = entity.isAlive() && !entity.isSilent()
			&& !request.groupId().equals("hivgme") && !request.groupId().equals("ecslqo");
		SoundInstance sound = followsEntity
			? new EntityBoundSoundInstance(variant.sound(), SoundSource.NEUTRAL, 1.0F, 1.0F, entity, entity.getRandom().nextLong())
			: new SimpleSoundInstance(variant.sound(), SoundSource.NEUTRAL, 1.0F, 1.0F, RandomSource.create(), entity.getX(), entity.getY(), entity.getZ());
		minecraft.getSoundManager().play(sound);
		ACTIVE.put(request.entityId(), new ActiveSound(sound, followsEntity, System.nanoTime() + request.durationTicks() * 50_000_000L));
		return true;
	}

	public static void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null) {
			clear(minecraft);
			return;
		}
		long now = System.nanoTime();
		Iterator<Map.Entry<UUID, PendingSound>> pendingIterator = PENDING.entrySet().iterator();
		while (pendingIterator.hasNext()) {
			PendingSound pending = pendingIterator.next().getValue();
			if (now >= pending.expiresAtNanos() || tryStart(minecraft, pending)) pendingIterator.remove();
		}
		Iterator<Map.Entry<UUID, ActiveSound>> iterator = ACTIVE.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ActiveSound> entry = iterator.next();
			Entity entity = minecraft.level.getEntity(entry.getKey());
			if (now < entry.getValue().endNanos()
				&& (!entry.getValue().followsEntity() || entity != null && entity.isAlive())) continue;
			minecraft.getSoundManager().stop(entry.getValue().instance());
			iterator.remove();
		}
	}

	public static void clear(Minecraft minecraft) {
		PENDING.clear();
		for (UUID id : ACTIVE.keySet().toArray(UUID[]::new)) stop(minecraft, id);
	}

	private static void stop(Minecraft minecraft, UUID id) {
		ActiveSound active = ACTIVE.remove(id);
		if (active != null) minecraft.getSoundManager().stop(active.instance());
	}

	private record ActiveSound(SoundInstance instance, boolean followsEntity, long endNanos) {
	}

	private record PendingSound(UUID entityId, String groupId, int variantIndex, int durationTicks, long expiresAtNanos) {
	}
}
