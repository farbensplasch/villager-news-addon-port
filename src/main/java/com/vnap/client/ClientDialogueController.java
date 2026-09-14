package com.vnap.client;

import com.vnap.config.VillagerNewsSettings;
import com.vnap.dialogue.DialogueCatalog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Client-tick-driven rewrite of the old server-side {@code ContextualDialogueController}. This is
 * the piece that makes the mod work standing entirely on the player's own client, against a
 * vanilla/unmodded dedicated server, in singleplayer, and in LAN games alike - it drives itself
 * off client-visible world/entity state and Fabric API's client-firing events instead of
 * server-only ones, and triggers dialogue playback with direct, in-process calls into
 * {@link DialogueSoundState}, {@link DialogueAnimationState} and {@link DialogueSubtitleState}
 * instead of building a network packet.
 *
 * <p>A few pieces of the original design cannot be preserved on a client-only install and are
 * either dropped or approximated - see the per-feature notes below and the module's plan
 * document. In short: natural/scoreboard-tracked special-villager spawning, the {@code
 * /dialoguetest} debug command, the villager sleep-delay mixin, and the "Unreachable" villager's
 * active flee/teleport AI (which requires server authority over the entity's movement) are gone;
 * reputation-gated dialogue and the raid "busy" line use local, client-observable proxies instead
 * of the real (unsynced) server data.</p>
 */
public final class ClientDialogueController {
	private static final double OBSERVER_RANGE = 16.0;
	private static final double NEARBY_SUBJECT_RANGE = 8.0;
	private static final long SHORT_COOLDOWN = 20L * 45L;
	private static final long LONG_COOLDOWN = 20L * 150L;
	private static final Map<String, Long> COOLDOWNS = new HashMap<>();
	private static final Map<UUID, Long> BUSY_UNTIL = new HashMap<>();
	private static final Map<UUID, ActiveSound> ACTIVE_SOUNDS = new HashMap<>();
	private static final Map<UUID, Boolean> LAST_SLEEPING = new HashMap<>();
	private static final Map<UUID, Boolean> LAST_TRADER_INVISIBLE = new HashMap<>();
	private static final Map<UUID, VillagerSnapshot> VILLAGER_STATES = new HashMap<>();
	private static final Map<UUID, Map<String, Integer>> VILLAGER_INVENTORIES = new HashMap<>();
	private static final Map<UUID, SpeechTarget> SPEECH_TARGETS = new HashMap<>();
	private static final Map<String, List<Integer>> SHARED_RECENT_VARIANTS = new HashMap<>();
	private static final Map<UUID, Long> NO_WORKSTATION_SINCE = new HashMap<>();
	private static final Map<UUID, Long> LAST_DANGER = new HashMap<>();
	private static final Map<UUID, Long> NO_BELL_SINCE = new HashMap<>();
	private static final Map<UUID, Float> HEALTH_SNAPSHOT = new HashMap<>();
	private static final Map<UUID, Integer> LOCAL_REPUTATION = new HashMap<>();
	// Stores the synced game-time each pair was first observed standing together, rather than a
	// per-client incrementing counter - so "have they been together long enough" is measured from
	// an absolute shared clock and lines up across independently-ticking clients.
	private static final Map<String, Long> PAIR_TICKS = new HashMap<>();
	private static final Set<UUID> ACTIVE_PLAYER_ENCOUNTER = new HashSet<>();
	private static final List<PendingSpeech> PENDING_SPEECH = new ArrayList<>();
	// Keyed per-player rather than a single shared instance: processPlayer() now runs once per
	// observable player each tick, and each player needs their own independent movement/stare/ground
	// tracking state rather than one player's movement clobbering another's.
	private static final Map<UUID, PlayerObservation> OBSERVATIONS = new HashMap<>();
	private static final Set<String> MOBILE_DIALOGUES = Set.of(
		"huhcbd", "gesjov", "gacgtq", "hmadgp", "nkcoqb", "qhpyaw", "uveohs", "caykki",
		"swomdw", "rtikom", "hfmwvf", "mytmrk", "ikrwzy", "fcbygh", "etkxko", "elryje",
		"rogpvp", "igebly", "vnaodx", "yzqpvi", "nsxmkr", "cifbit", "wyvzhk", "rueszy",
		"yjctyw", "qqyjjg", "hpnsfu", "vevdkl", "ahcvzd", "ecslqo", "ssbhiv", "ltdnvy",
		"fzoqwd", "behifz", "wrbvvp", "asuufu", "eyiraw", "ncyeaw", "uzdxum", "lpuocy",
		"slbqfwbayahw"
	);
	private static final Set<String> BABY_DIALOGUES = Set.of(
		"abfwiv", "aezdiy", "ahcvzd", "cmrqhw", "durjjd", "ecslqo", "fzyrfm", "ggitzq",
		"gotjxf", "gzsztp", "hbalps", "hcdvqm", "jfuftm", "lgjtnf", "mqnapy", "msemoe",
		"nxalcz", "qrdzmt", "rfnirh", "saxuwk", "svdjdk", "vbclem", "vhwksn", "wkwcrf",
		"wsxfok", "wtuguc", "zeykfp", "cxeziv", "riezum", "rlkdqd"
	);
	private static final Set<String> COSMETIC_RECIPIENT_DIALOGUES = Set.of(
		"wurmgu", "inirxg", "ozxzla", "cxeziv", "riezum", "rlkdqd"
	);
	private static final Set<String> DAMAGE_LOCK_DIALOGUES = Set.of(
		"elryje", "onindz", "rogpvp", "etkxko", "igebly", "vnaodx"
	);
	private static final Set<String> RAIDER_TYPES = Set.of(
		"pillager", "vindicator", "evoker", "vex", "ravager", "witch", "illusioner"
	);
	private static final List<List<String>> WANDERING_CONVERSATIONS = List.of(
		List.of("gmrypkswxeva", "gmrypkbayahw", "gmrypkmudlec"),
		List.of("gmrypkoallbt", "gmrypkfobzlt", "gmrypkcljvls"),
		List.of("gmrypkhiqnpi", "gmrypkvkuidc", "gmrypkhnvsiu", "gmrypkvswnrg"),
		List.of("gmrypkmwtiaf", "gmrypkgougka")
	);
	private static final List<String> CAMPFIRE_CONVERSATION = List.of(
		"wrswgiswxeva", "wrswgibayahw", "wrswgimudlec", "wrswgitvewwu", "wrswgisrlwzw", "wrswgicsmkgk"
	);
	private static final List<String> GOSSIP_CONVERSATION = List.of(
		"wrjbddswxeva", "wrjbddbayahw", "wrjbddmudlec", "wrjbddtvewwu", "wrjbddsrlwzw"
	);
	private static final List<List<String>> ONE_MISSING_NOSE_CONVERSATIONS = List.of(
		List.of("bygaxwswxeva", "bygaxwbayahw"),
		List.of("bygaxwoallbt", "bygaxwfobzlt", "bygaxwcljvls"),
		List.of("bygaxwhiqnpi"),
		List.of("bygaxwmwtiaf")
	);
	private static final List<List<String>> TWO_MISSING_NOSES_CONVERSATIONS = List.of(
		List.of("loicswswxeva", "loicswbayahw"),
		List.of("loicswrotbcq"),
		List.of("loicswhiqnpi", "loicswvkuidc", "loicswhnvsiu")
	);
	private static final Map<String, String> NEARBY_ENTITY_DIALOGUES = Map.ofEntries(
		Map.entry("allay", "rnlher"), Map.entry("armor_stand", "ckniqq"), Map.entry("bat", "ozmthf"),
		Map.entry("bee", "rbkjsr"), Map.entry("cave_spider", "gtmfpl"),
		Map.entry("bogged", "nsosix"), Map.entry("camel", "turlrl"), Map.entry("cat", "ynxhfb"),
		Map.entry("chicken", "hggexx"), Map.entry("cow", "lvzfcv"), Map.entry("creaking", "nwlcij"),
		Map.entry("creeper", "odwhzm"), Map.entry("dolphin", "aqtshb"), Map.entry("drowned", "atwycp"),
		Map.entry("enderman", "yeqxvm"), Map.entry("frog", "pguaqp"), Map.entry("horse", "yazvzs"),
		Map.entry("husk", "gcoysc"), Map.entry("llama", "ysbfqu"), Map.entry("trader_llama", "ysbfqu"),
		Map.entry("panda", "swewsr"), Map.entry("parrot", "vapupl"), Map.entry("phantom", "nwzvkb"),
		Map.entry("pig", "jqdeef"), Map.entry("rabbit", "spfefr"), Map.entry("sheep", "vxycol"),
		Map.entry("skeleton", "lqzdqk"), Map.entry("slime", "rzvitn"), Map.entry("sniffer", "tqishj"),
		Map.entry("spider", "gtmfpl"), Map.entry("stray", "bxbibd"), Map.entry("turtle", "neoxpu"),
		Map.entry("warden", "jicosq"), Map.entry("witch", "lwcrnt"), Map.entry("wither", "satsrf"),
		Map.entry("wolf", "vvntcf"), Map.entry("zombie", "dortcb"), Map.entry("zombie_villager", "xtooxu"),
		Map.entry("zombified_piglin", "wboncy"), Map.entry("copper_golem", "ktdshy"),
		Map.entry("snow_golem", "kxjegd"), Map.entry("iron_golem", "cuchwi"),
		Map.entry("ender_dragon", "xxjkmo"), Map.entry("happy_ghast", "lxvofx"),
		Map.entry("polar_bear", "toolzx"), Map.entry("sulfur_cube", "dmcjmd"),
		Map.entry("cod", "trkugw"), Map.entry("salmon", "trkugw"), Map.entry("pufferfish", "trkugw"),
		Map.entry("tropical_fish", "trkugw")
	);
	private static final Map<String, String> BABY_ENTITY_DIALOGUES = Map.ofEntries(
		Map.entry("bee", "qqtnlm"), Map.entry("cat", "knjdbi"), Map.entry("chicken", "pjcwec"),
		Map.entry("cow", "hzahog"), Map.entry("drowned", "vakwgb"), Map.entry("horse", "ualabt"),
		Map.entry("husk", "hwltxk"), Map.entry("panda", "gggzar"), Map.entry("pig", "htibul"),
		Map.entry("sheep", "eccdga"), Map.entry("wolf", "hyzwpr"), Map.entry("zombie", "zvwapr"),
		Map.entry("zombified_piglin", "qltnkz"), Map.entry("zombie_villager", "nstwos")
	);
	private static long ticks;
	private static long lastLevelTime = Long.MIN_VALUE;
	private static Difficulty lastDifficulty;
	private static TradeSession activeTrade;

	private ClientDialogueController() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ClientDialogueController::tick);
		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> onEntityLoad(entity));
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> onEntityUnload(entity));

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!level.isClientSide() || !isLocalPlayer(player)) return;
			PlayerObservation observation = observationFor(player);
			String title = selectBreakContext(state, observation);
			if (title.equals("Harvest Crops") && nearbyVillagers(level, Vec3.atCenterOf(pos), OBSERVER_RANGE).stream()
					.anyMatch(villager -> profession(villager).equals("farmer"))) title = "Harvest Crops Near a Farmer";
			playObserved(level, player, Vec3.atCenterOf(pos), title, SHORT_COOLDOWN);
		});

		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (level.isClientSide() && isLocalPlayer(player)) onUseBlock(level, player, hand, hitResult.getBlockPos(), hitResult.getLocation());
			return InteractionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() && isLocalPlayer(player)) {
				String title = selectUseItemContext(player.getItemInHand(hand));
				if (title != null) playObserved(level, player, player.position(), title, SHORT_COOLDOWN);
			}
			return InteractionResult.PASS;
		});

		UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (level.isClientSide() && isLocalPlayer(player)) return onUseEntity(player, entity, hand);
			return InteractionResult.PASS;
		});

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (level.isClientSide() && isLocalPlayer(player)) onAttackEntity(player, entity);
			return InteractionResult.PASS;
		});

		EntitySleepEvents.STOP_SLEEPING.register((entity, sleepingPos) -> {
			if (entity.level().isClientSide() && entity instanceof Villager villager) {
				interrupt(villager);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearState());
	}

	private static boolean isLocalPlayer(Player player) {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.player != null && minecraft.player == player;
	}

	/**
	 * All non-spectator players currently visible on this client, sorted by UUID. Two independently
	 * running clients on the same server see the same world state but {@link ClientLevel#players()}
	 * makes no ordering guarantee, so anything that needs a deterministic scan/iteration order across
	 * clients (nearest-of-all-observers picks, tie-breaks, etc.) should iterate this instead of
	 * {@code level.players()} directly.
	 */
	private static List<AbstractClientPlayer> observablePlayers(ClientLevel level) {
		return level.players().stream()
			.filter(candidate -> !candidate.isSpectator())
			.sorted(Comparator.comparing(Player::getUUID))
			.toList();
	}

	private static PlayerObservation observationFor(Player player) {
		return OBSERVATIONS.computeIfAbsent(player.getUUID(), ignored -> new PlayerObservation());
	}

	private static void onUseBlock(Level level, Player player, InteractionHand hand, BlockPos pos, Vec3 hitLocation) {
		ItemStack held = player.getItemInHand(hand);
		BlockState clicked = level.getBlockState(pos);
		String clickedPath = BuiltInRegistries.BLOCK.getKey(clicked.getBlock()).getPath();
		String title = selectHeldBlockContext(held, clicked);
		if (title == null && held.getItem() instanceof BlockItem blockItem) {
			title = selectPlaceContext(blockItem.getBlock(), level, pos);
		} else if (title == null) {
			title = selectUseBlockContext(clicked);
		}
		if (clickedPath.endsWith("_door") && clicked.hasProperty(BlockStateProperties.OPEN)
				&& clicked.getValue(BlockStateProperties.OPEN)
				&& !nearbyVillagers(level, hitLocation, 3.0).isEmpty()) title = "Close a Door in a Villager's Face";
		String heldPath = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
		if ((heldPath.equals("pumpkin") || heldPath.equals("carved_pumpkin"))
				&& nearBlock(level, pos, "iron_block", 3)) title = "Build an Iron Golem Frame";
		boolean played = (clickedPath.equals("chest") || clickedPath.equals("trapped_chest"))
			&& playHomeChestReaction(level, player, pos);
		if (!played) played = title != null && playObserved(level, player, hitLocation, title, SHORT_COOLDOWN);
		if (!played && clickedPath.equals("bell")) {
			nearbyVillagers(level, hitLocation, OBSERVER_RANGE).stream()
				.filter(villager -> villager.isBaby() && !villager.isSleeping())
				.min(Comparator.comparingDouble(villager -> villager.distanceToSqr(hitLocation)))
				.ifPresent(baby -> playId(baby, "nxalcz", "baby_bell:" + baby.getUUID(), SHORT_COOLDOWN, player));
		}
	}

	private static void onEntityLoad(Entity entity) {
		normalizeSpecialEntity(entity);
		String entityPath = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		if (entityPath.equals("firework_rocket")) {
			playFireworkReactions(entity.level(), entity);
			return;
		}
		if (entityPath.equals("lightning_bolt")) {
			playLightningReaction(entity.level(), entity);
			return;
		}
		if (!(entity instanceof Villager villager)) return;
		VILLAGER_STATES.put(villager.getUUID(), snapshot(villager, false));
		VILLAGER_INVENTORIES.put(villager.getUUID(), inventoryCounts(villager));
	}

	private static void onEntityUnload(Entity entity) {
		UUID id = entity.getUUID();
		String encodedId = id.toString();
		BUSY_UNTIL.remove(id);
		ACTIVE_SOUNDS.remove(id);
		SPEECH_TARGETS.remove(id);
		LAST_SLEEPING.remove(id);
		LAST_TRADER_INVISIBLE.remove(id);
		VILLAGER_STATES.remove(id);
		VILLAGER_INVENTORIES.remove(id);
		NO_WORKSTATION_SINCE.remove(id);
		LAST_DANGER.remove(id);
		NO_BELL_SINCE.remove(id);
		HEALTH_SNAPSHOT.remove(id);
		PENDING_SPEECH.removeIf(pending -> pending.speakerId.equals(id) || id.equals(pending.targetId));
		PAIR_TICKS.keySet().removeIf(pair -> pair.contains(encodedId));
	}

	private static void tick(Minecraft minecraft) {
		if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) {
			if (minecraft.level == null) clearState();
			return;
		}
		// Use the server-synced world time instead of a local per-client counter: every client
		// connected to the same world sees the same value here, so periodic checks (ticks % N) and
		// cooldown/busy deadlines line up across clients instead of drifting by however long each
		// client happened to be running before this tick.
		ticks = minecraft.level.getGameTime();
		if (!VillagerNewsSettings.dialogueEnabled()) {
			stopActiveDialogue(minecraft);
			ACTIVE_PLAYER_ENCOUNTER.clear();
			return;
		}
		ACTIVE_SOUNDS.entrySet().removeIf(entry -> entry.getValue().endTick <= ticks);
		maintainSpeechTargets(minecraft.level);
		processPendingSpeech();
		processTradeSession(minecraft);
		processDamageAndDeath(minecraft.level);
		if (ticks % 10L != 0L) return;

		ClientLevel level = minecraft.level;
		processTimeChange(level);
		processDifficultyChange(level);
		List<AbstractClientPlayer> observable = observablePlayers(level);
		if (minecraft.player.isSpectator()) ACTIVE_PLAYER_ENCOUNTER.remove(minecraft.player.getUUID());
		for (Player observed : observable) {
			processPlayer(level, observed);
			processWanderingTrader(level, observed);
			processWooly(level, observed);
		}
		if (ticks % 100L == 0L) for (Player observed : observable) processConversations(level, observed);

		if (ticks % 1200L == 0L) {
			COOLDOWNS.entrySet().removeIf(entry -> entry.getValue() + 20L * 600L < ticks);
			BUSY_UNTIL.entrySet().removeIf(entry -> entry.getValue() < ticks);
			LOCAL_REPUTATION.keySet().retainAll(VILLAGER_STATES.keySet());
		}
	}

	private static void clearState() {
		COOLDOWNS.clear();
		BUSY_UNTIL.clear();
		ACTIVE_SOUNDS.clear();
		LAST_SLEEPING.clear();
		LAST_TRADER_INVISIBLE.clear();
		VILLAGER_STATES.clear();
		VILLAGER_INVENTORIES.clear();
		SPEECH_TARGETS.clear();
		SHARED_RECENT_VARIANTS.clear();
		NO_WORKSTATION_SINCE.clear();
		LAST_DANGER.clear();
		NO_BELL_SINCE.clear();
		HEALTH_SNAPSHOT.clear();
		LOCAL_REPUTATION.clear();
		PAIR_TICKS.clear();
		ACTIVE_PLAYER_ENCOUNTER.clear();
		PENDING_SPEECH.clear();
		OBSERVATIONS.clear();
		lastLevelTime = Long.MIN_VALUE;
		lastDifficulty = null;
		activeTrade = null;
		ticks = 0L;
	}

	private static void stopActiveDialogue(Minecraft minecraft) {
		if (minecraft.level == null) return;
		for (UUID id : List.copyOf(ACTIVE_SOUNDS.keySet())) {
			Entity entity = minecraft.level.getEntity(id);
			if (entity instanceof LivingEntity speaker) interrupt(speaker);
			else {
				ACTIVE_SOUNDS.remove(id);
				BUSY_UNTIL.remove(id);
				SPEECH_TARGETS.remove(id);
			}
		}
	}

	// ---------------------------------------------------------------- trading

	private static void processTradeSession(Minecraft minecraft) {
		Player player = minecraft.player;
		if (activeTrade == null) return;
		boolean menuOpen = player.containerMenu instanceof MerchantMenu;
		if (menuOpen) {
			MerchantMenu menu = (MerchantMenu) player.containerMenu;
			if (!activeTrade.opened) {
				activeTrade.opened = true;
				activeTrade.lastUsesTotal = usesTotal(menu);
				Entity trader = minecraft.level.getEntity(activeTrade.traderId);
				if (trader instanceof Villager villager) {
					String id = tradeOpeningId(villager, player, menu.getOffers());
					playId(villager, id, "trade_open:" + villager.getUUID() + ":" + id, SHORT_COOLDOWN, player);
				} else if (trader instanceof WanderingTrader wanderingTrader) {
					playId(wanderingTrader, "yubpbb", "trade_open:" + wanderingTrader.getUUID(), SHORT_COOLDOWN, player);
				}
			} else {
				int uses = usesTotal(menu);
				if (uses > activeTrade.lastUsesTotal) {
					activeTrade.lastUsesTotal = uses;
					activeTrade.completed = true;
					onTradeCompleted(minecraft.level.getEntity(activeTrade.traderId), player);
				}
			}
			return;
		}
		if (!activeTrade.opened && ticks - activeTrade.createdTick <= 20L) return;
		Entity trader = minecraft.level.getEntity(activeTrade.traderId);
		if (!activeTrade.opened) {
			if (trader instanceof Villager villager) {
				String id = unavailableTradeId(villager, player, null);
				if (id != null) playId(villager, id, "trade_unavailable:" + villager.getUUID() + ":" + id, SHORT_COOLDOWN, player);
			}
		} else if (trader instanceof Villager villager) {
			CastProfile profile = cast(villager);
			String id = switch (profile) {
				case MAYOR -> activeTrade.completed ? "shrrya" : "bgzmea";
				case TESTIFICATE_MAN -> activeTrade.completed ? "xcjort" : "rdugrl";
				case NUMBER_5 -> activeTrade.completed ? "msofrj" : "lilimm";
				case NUMBER_9 -> activeTrade.completed ? "czvvwy" : "lilimm";
				default -> activeTrade.completed ? "czvvwy" : ticks % 2L == 0L ? "lilimm" : "laztau";
			};
			boolean played = playId(villager, id, "trade_close:" + villager.getUUID(), 10L, player);
			if (!played) {
				String fallback = profile == CastProfile.TESTIFICATE_MAN ? "ctzfzj"
					: profile == CastProfile.NUMBER_5 ? "nfdery" : profile == CastProfile.NUMBER_9 ? "hvjfnk" : null;
				if (fallback != null) playId(villager, fallback, "trade_close_fallback:" + villager.getUUID(), 10L, player);
			}
		} else if (trader instanceof WanderingTrader wanderingTrader) {
			playId(wanderingTrader, activeTrade.completed ? "uzdvsi" : "erbcfn",
				"trade_close:" + wanderingTrader.getUUID(), 10L, player);
		}
		activeTrade = null;
	}

	private static int usesTotal(MerchantMenu menu) {
		int total = 0;
		for (MerchantOffer offer : menu.getOffers()) total += offer.getUses();
		return total;
	}

	private static void onTradeCompleted(Entity trader, Player player) {
		if (trader == null) return;
		bumpReputation(trader.getUUID(), 3);
		String id = null;
		if (trader instanceof Villager villager && cast(villager) == CastProfile.VILLAGER) id = "xmkwxd";
		else if (trader instanceof WanderingTrader) id = "bvrbhy";
		if (id != null && trader instanceof LivingEntity livingTrader) {
			long due = Math.max(ticks + 1L, BUSY_UNTIL.getOrDefault(trader.getUUID(), ticks) + 1L);
			PENDING_SPEECH.add(new PendingSpeech(livingTrader.getUUID(), id, player.getUUID(), due, false));
		}
	}

	private static String tradeOpeningId(Villager villager, Player player, net.minecraft.world.item.trading.MerchantOffers offers) {
		CastProfile profile = cast(villager);
		if (profile != CastProfile.VILLAGER) return profile.trade;
		String unavailable = unavailableTradeId(villager, player, offers);
		if (unavailable != null) return unavailable;
		int reputation = localReputation(villager);
		if (reputation < -225) return "xduuwm";
		if (reputation < -75) return "qmdvft";
		if (reputation >= 75) return "vlrsrn";
		if (reputation >= 25) return "kuhvdv";
		return profile.trade;
	}

	/**
	 * {@code offers} is only available when a {@link MerchantMenu} is actually open for this
	 * villager (trades are lazily generated server-side; {@code AbstractVillager.getOffers()}
	 * throws on the client outside of that case) - pass {@code null} when checking a villager
	 * whose trade screen was never opened, which simply skips the empty-offers check.
	 */
	private static String unavailableTradeId(Villager villager, Player player, net.minecraft.world.item.trading.MerchantOffers offers) {
		if (cast(villager) != CastProfile.VILLAGER) return null;
		String profession = profession(villager);
		if (profession.equals("nitwit")) return "nukxsf";
		if (profession.equals("none")) return "nlbhku";
		if (nearbyRaid(villager.level(), villager.blockPosition())) return "klabhl";
		if (offers != null && offers.isEmpty()) return "zalmof";
		if (localReputation(villager) <= -150) return "lhdgsy";
		return null;
	}

	// ---------------------------------------------------------------- local reputation proxy

	private static int localReputation(Villager villager) {
		return LOCAL_REPUTATION.getOrDefault(villager.getUUID(), 0);
	}

	private static void bumpReputation(UUID villagerId, int delta) {
		LOCAL_REPUTATION.merge(villagerId, delta, (oldValue, addend) -> Math.max(-300, Math.min(300, oldValue + addend)));
	}

	private static boolean isNegativeReputation(Villager villager, Player player) {
		return localReputation(villager) < -75;
	}

	private static String reputationApproach(Villager villager, Player player) {
		if (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) return "gnetsk";
		int reputation = localReputation(villager);
		if (reputation < -225) {
			return BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath().endsWith("_sword")
				? "stuirs" : "zstdjn";
		}
		if (reputation < -75) return "tfzlsw";
		if (reputation >= 75) return "kcbenk";
		if (reputation >= 25) return "omgcte";
		return "xfpjxq";
	}

	/** Real raid detection needs server-only {@code ServerLevel#isRaided}; approximate with a nearby-raider headcount. */
	private static boolean nearbyRaid(Level level, BlockPos pos) {
		long raiders = level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(Vec3.atCenterOf(pos), 48, 24, 48), Entity::isAlive)
			.stream().filter(entity -> RAIDER_TYPES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath())).count();
		return raiders >= 3;
	}

	// ---------------------------------------------------------------- time / difficulty

	private static void processTimeChange(ClientLevel level) {
		long now = level.getOverworldClockTime();
		long before = lastLevelTime;
		lastLevelTime = now;
		if (before == Long.MIN_VALUE || Math.abs(now - before) <= 40L) return;
		List<AbstractClientPlayer> observers = observablePlayers(level);
		Villager speaker = nearestVillagerToAnyObserver(level, observers, 32.0, villager -> !villager.isSleeping());
		if (speaker == null) return;
		boolean wasDay = Math.floorMod(before, 24000L) < 12000L;
		boolean isDay = Math.floorMod(now, 24000L) < 12000L;
		String id = wasDay == isDay ? (speaker.isBaby() ? "durjjd" : "uqwdqn")
			: isDay ? (speaker.isBaby() ? "wkwcrf" : "mgmzeh")
			: (speaker.isBaby() ? "msemoe" : "ohdwnz");
		Player target = nearestOf(observers, speaker);
		playSharedId(speaker, id, "time_skip", SHORT_COOLDOWN, target);
	}

	private static void processDifficultyChange(ClientLevel level) {
		Difficulty difficulty = level.getDifficulty();
		Difficulty previous = lastDifficulty;
		lastDifficulty = difficulty;
		if (previous == null || previous == difficulty) return;
		List<AbstractClientPlayer> observers = observablePlayers(level);
		Villager speaker = nearestVillagerToAnyObserver(level, observers, 32.0,
			villager -> !villager.isBaby() && !villager.isSleeping());
		if (speaker == null) return;
		String id = difficulty == Difficulty.HARD ? "arzojk" : difficulty == Difficulty.PEACEFUL ? "xuyypm" : "ibcrvx";
		Player target = nearestOf(observers, speaker);
		playSharedId(speaker, id, "difficulty:" + difficulty.name(), SHORT_COOLDOWN, target);
	}

	/**
	 * Nearest eligible villager to any of {@code observers} (not just the local player), so every
	 * client - each with its own local player at a slightly different position - converges on the
	 * same villager. Ties (including float-imprecise equal distances) are broken by villager UUID for
	 * determinism across independently-running clients.
	 */
	private static Villager nearestVillagerToAnyObserver(ClientLevel level, List<? extends Player> observers, double range,
			java.util.function.Predicate<Villager> filter) {
		Villager best = null;
		double bestDistanceSqr = Double.MAX_VALUE;
		for (Player observer : observers) {
			for (Villager candidate : nearbyVillagers(level, observer.position(), range)) {
				if (!filter.test(candidate)) continue;
				double distanceSqr = candidate.distanceToSqr(observer);
				if (best == null || distanceSqr < bestDistanceSqr
						|| (distanceSqr == bestDistanceSqr && candidate.getUUID().compareTo(best.getUUID()) < 0)) {
					best = candidate;
					bestDistanceSqr = distanceSqr;
				}
			}
		}
		return best;
	}

	/** The observer nearest to {@code subject}, for use as a deterministic {@code target} entity. */
	private static Player nearestOf(List<? extends Player> observers, Entity subject) {
		return observers.stream().min(Comparator.comparingDouble((Player candidate) -> candidate.distanceToSqr(subject))
			.thenComparing(Player::getUUID)).orElse(null);
	}

	// ---------------------------------------------------------------- player observation

	private static void processPlayer(ClientLevel level, Player player) {
		PlayerObservation observation = observationFor(player);
		Minecraft minecraft = Minecraft.getInstance();
		// There's no synced GameType for a remote player's exact game mode on the client, so this
		// observation is left local-player-only; guarded here so it's simply skipped for others.
		String gameMode = player != minecraft.player || minecraft.gameMode == null ? "" : minecraft.gameMode.getPlayerMode().getName();
		boolean changedGameMode = player == minecraft.player && observation.lastGameMode != null && !observation.lastGameMode.equals(gameMode);
		if (player == minecraft.player) observation.lastGameMode = gameMode;
		Vec3 movement = player.position().subtract(observation.lastPosition);
		if (movement.horizontalDistanceSqr() < 0.0004 && Math.abs(movement.y) < 0.01) observation.stillTicks += 10;
		else observation.stillTicks = 0;
		observation.lastPosition = player.position();
		BlockPos ground = player.blockPosition().below();
		String groundBlock = BuiltInRegistries.BLOCK.getKey(level.getBlockState(ground).getBlock()).getPath();
		if (ground.equals(observation.lastGroundPos) && "farmland".equals(observation.lastGroundBlock) && groundBlock.equals("dirt")) {
			playObserved(level, player, player.position(), "Trample Crops", SHORT_COOLDOWN);
		}
		observation.lastGroundPos = ground;
		observation.lastGroundBlock = groundBlock;

		List<Villager> nearby = nearbyVillagers(level, player.position(), OBSERVER_RANGE).stream()
			.filter(villager -> !villager.isSleeping()).toList();
		Villager adult = nearby.stream()
			.filter(villager -> !villager.isBaby())
			.filter(villager -> cast(villager) != CastProfile.UNREACHABLE)
			.filter(villager -> villager.hasLineOfSight(player))
			.min(Comparator.comparingDouble(villager -> villager.distanceToSqr(player)))
			.orElse(null);
		if (adult == null) {
			Villager baby = nearby.stream().filter(Villager::isBaby)
				.filter(villager -> cast(villager) != CastProfile.UNREACHABLE).filter(villager -> villager.hasLineOfSight(player))
				.min(Comparator.comparingDouble(villager -> villager.distanceToSqr(player))).orElse(null);
			if (baby != null) {
				boolean firstNotice = ACTIVE_PLAYER_ENCOUNTER.add(player.getUUID());
				if (ticks % 40L == 0L && playNearbyEntityContext(level, baby)) return;
				String id = player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE) ? "fzyrfm"
					: isNegativeReputation(baby, player) ? "jfuftm" : "wtuguc";
				if (firstNotice) playId(baby, id, "player_greeting:" + player.getUUID(), LONG_COOLDOWN, player);
			} else ACTIVE_PLAYER_ENCOUNTER.remove(player.getUUID());
			return;
		}

		boolean firstNotice = ACTIVE_PLAYER_ENCOUNTER.add(player.getUUID());
		String pair = player.getUUID() + ":" + adult.getUUID();
		if (playCosmeticObservation(player, adult)) return;
		if (player.getBoundingBox().inflate(0.15).intersects(adult.getBoundingBox()) && movement.horizontalDistanceSqr() > 0.002) {
			String id = adult.getVehicle() != null && BuiltInRegistries.ENTITY_TYPE.getKey(adult.getVehicle().getType()).getPath().contains("boat")
				? "zvbnea" : "ajexrq";
			if (playId(adult, id, "nudge:" + adult.getUUID(), SHORT_COOLDOWN, player)) return;
		}
		if (changedGameMode && playSharedId(adult, gameMode.equals("Creative") ? "ohtblt" : "fhhqxg",
				"gamemode:" + gameMode, SHORT_COOLDOWN, player)) return;
		CastProfile adultProfile = cast(adult);
		if (firstNotice && adultProfile != CastProfile.VILLAGER && player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)
				&& playSharedId(adult, "gnetsk", "player_greeting:" + player.getUUID(), LONG_COOLDOWN, player)) return;
		String approach = adultProfile == CastProfile.VILLAGER ? reputationApproach(adult, player) : adultProfile.approach;
		if (firstNotice && playId(adult, approach, "player_greeting:" + player.getUUID(), LONG_COOLDOWN, player)) {
			return;
		}

		Vec3 toVillager = adult.getEyePosition().subtract(player.getEyePosition()).normalize();
		double lookDot = player.getLookAngle().dot(toVillager);
		if (lookDot > 0.985) observation.stareTicks += 10;
		else observation.stareTicks = 0;

		if (observation.stareTicks >= 60 && playSharedTitle(adult, "Stare at a Villager", "stare:" + pair, LONG_COOLDOWN, player)) {
			observation.stareTicks = 0;
			return;
		}
		if (ticks % 400L == 0L && observation.stillTicks >= 2400
				&& playSharedTitle(adult, "Stand Completely Still", "still:" + player.getUUID(), LONG_COOLDOWN, player)) {
			observation.stillTicks = 0;
			return;
		}

		String playerContext = playerContext(player);
		boolean changedPlayerContext = playerContext != null && !playerContext.equals(observation.lastPlayerContext);
		observation.lastPlayerContext = playerContext;
		if (changedPlayerContext && playSharedTitle(adult, playerContext,
				"player_context:" + playerContext, LONG_COOLDOWN, player)) return;
		long nearbyPlayers = level.players().stream().filter(other -> other.distanceToSqr(adult) <= 64.0).count();
		if (nearbyPlayers >= 2 && playSharedId(adult, "cstyvg", "player_crowd:" + adult.getUUID(), LONG_COOLDOWN, player)) return;

		String environment = environmentContext(level, adult);
		if (environment != null && playTitle(adult, environment, "environment:" + adult.getUUID() + ":" + environment, LONG_COOLDOWN)) return;

		if (ticks % 40L == 0L && playNearbyEntityContext(level, adult)) return;

		if (ticks % 200L == 0L) {
			String time = timeContext(level, adult);
			if (playTitle(adult, time, "time:" + adult.getUUID() + ":" + time, LONG_COOLDOWN)) return;
		}

		if (ticks % 100L == 0L && adult.getDeltaMovement().horizontalDistanceSqr() > 0.0004) {
			CastProfile profile = cast(adult);
			if (profile != CastProfile.VILLAGER) playId(adult, profile.idle, "idle:" + adult.getUUID(), LONG_COOLDOWN);
			else {
				String ambient = ambientDialogue(adult);
				playId(adult, ambient, "idle:" + adult.getUUID() + ":" + ambient, LONG_COOLDOWN);
			}
		}
	}

	private static String environmentContext(Level level, Villager villager) {
		Entity vehicle = villager.getVehicle();
		if (vehicle != null) {
			String vehiclePath = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getPath();
			if (vehiclePath.contains("minecart")) {
				return vehicle.getDeltaMovement().horizontalDistanceSqr() > 0.001 ? "Ride in a Moving Minecart" : "Sit in a Minecart";
			}
			if (vehiclePath.contains("boat")) {
				if (ticks / LONG_COOLDOWN % 3L == 0L) return "Sit in a Boat";
				return vehicle.isInWater() ? "Boat on Water" : "Boat on Land";
			}
		}
		if (level.dimension() == Level.NETHER) return "Wander in the Nether";
		if (level.dimension() == Level.END) return "Wander in the End";
		if (level.dimension() != Level.OVERWORLD) return "Wander in Another Dimension";
		if (level.isRainingAt(villager.blockPosition())) return "Caught in the Rain";
		if (villager.isInWater()) return "Stand in Shallow Water";
		String biome = level.getBiome(villager.blockPosition()).unwrapKey().map(key -> key.identifier().getPath()).orElse("");
		if (biome.contains("desert") || biome.contains("badlands") || biome.contains("savanna")) return "Wander Somewhere Hot";
		if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice") || biome.contains("cold")) return "Wander Somewhere Cold";
		BlockState below = level.getBlockState(villager.blockPosition().below());
		if (below.is(Blocks.ICE) || below.is(Blocks.PACKED_ICE) || below.is(Blocks.BLUE_ICE)) return "Stand on Ice";
		if (below.is(Blocks.SNOW_BLOCK) || below.is(Blocks.POWDER_SNOW)) return "Stand on Snow";
		if (below.is(Blocks.MAGMA_BLOCK)) return "Stand on Magma";
		for (int x = -3; x <= 3; x++) for (int y = -2; y <= 2; y++) for (int z = -3; z <= 3; z++) {
			String block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(villager.blockPosition().offset(x, y, z)).getBlock()).getPath();
			if (block.contains("campfire")) return "See a Campfire";
			if (block.equals("fire") || block.equals("soul_fire")) return "Stand Near Fire";
			if (block.equals("bookshelf") && villager.getDeltaMovement().horizontalDistanceSqr() < 0.0004) return "Inspect Bookshelves";
		}
		if (villager.getY() < level.getSeaLevel() - 30) return "Wander Deep Underground";
		if (villager.getY() > level.getSeaLevel() + 75) return "Wander High Above the Ground";
		return null;
	}

	private static String timeContext(Level level, Villager villager) {
		float sunAngle = level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, villager.blockPosition());
		if (sunAngle < 0.125F || sunAngle >= 0.875F) return "Morning";
		if (sunAngle < 0.45F) return "Afternoon";
		if (sunAngle < 0.625F) return "Evening";
		return "Night";
	}

	private static String ambientDialogue(Villager villager) {
		String profession = profession(villager);
		if (profession.equals("nitwit")) return "uookqp";
		if (profession.equals("none")) return "gbxzxv";
		LocalDate date = LocalDate.now();
		List<String> choices = new ArrayList<>();
		if (date.getMonthValue() == 10) choices.add("mltyge");
		if (date.getMonthValue() == 12) choices.add("tkkegl");
		if (date.getMonthValue() == 1 && date.getDayOfMonth() == 1) choices.add("uyqiwv");
		if (date.getMonthValue() == 2 && date.getDayOfMonth() == 14) choices.add("fabiyx");
		if (date.getMonthValue() == 4 && date.getDayOfMonth() == 1) choices.add("obitls");
		if (date.getMonthValue() == 5 && date.getDayOfMonth() == 17) choices.add("iriuqa");
		if (date.getMonthValue() == 10 && date.getDayOfMonth() == 31) choices.add("adhxce");
		if (date.getMonthValue() == 12 && date.getDayOfMonth() == 24) choices.add("zoqxvy");
		if (date.getMonthValue() == 12 && date.getDayOfMonth() == 25) choices.add("rclyrl");
		if (date.getMonthValue() == 12 && date.getDayOfMonth() == 31) choices.add("xljknt");
		if (date.getDayOfMonth() == 13 && date.getDayOfWeek() == DayOfWeek.FRIDAY) choices.add("qfcwvz");
		if (LocalDateTime.now().getMinute() == 0) choices.add("jqgkhy");
		if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
			choices.add("bkyidl");
			choices.add("ckngck");
		}
		choices.add(switch (date.getDayOfWeek()) {
			case MONDAY -> ticks / LONG_COOLDOWN % 2L == 0L ? "gkvlqc" : "jpucos";
			case TUESDAY -> ticks / LONG_COOLDOWN % 2L == 0L ? "dkpihl" : "lgeeem";
			case WEDNESDAY -> ticks / LONG_COOLDOWN % 2L == 0L ? "gwakiz" : "qiqiez";
			case THURSDAY -> ticks / LONG_COOLDOWN % 2L == 0L ? "zglkgp" : "caiyte";
			case FRIDAY -> ticks / LONG_COOLDOWN % 2L == 0L ? "ypyumu" : "cxtvsx";
			case SATURDAY -> ticks / LONG_COOLDOWN % 2L == 0L ? "ildosa" : "lfhnxz";
			case SUNDAY -> ticks / LONG_COOLDOWN % 2L == 0L ? "uzvatl" : "zckxrc";
		});
		if (choices.size() == 1) choices.add("lvigit");
		return choices.get(Math.floorMod((int) (ticks / LONG_COOLDOWN), choices.size()));
	}

	private static String playerContext(Player player) {
		if (player.isFallFlying()) return "Glide with Elytra";
		// No synced GameType for a remote player, so we can't confirm CREATIVE specifically here -
		// but the flying ability flag itself is synced per-entity for every player, and only
		// creative/spectator players can have it set, so checking it alone (minus spectators, who
		// are excluded from observation elsewhere but guarded here too for safety) generalizes this
		// correctly without needing the exact game mode.
		if (!player.isSpectator() && player.getAbilities().flying) return "Fly in Creative Mode";
		if (player.isShiftKeyDown() && player.getDeltaMovement().horizontalDistanceSqr() > 0.002) return "Crouch-Walk";
		if (player.getHealth() <= player.getMaxHealth() * 0.3F) return "Low Health";
		if (player.hasEffect(MobEffects.INVISIBILITY)) return "Invisibility";
		if (player.hasEffect(MobEffects.DARKNESS)) return "Darkness";
		if (player.hasEffect(MobEffects.NIGHT_VISION)) return "Night Vision";
		if (player.hasEffect(MobEffects.WATER_BREATHING)) return "Water Breathing";
		if (player.hasEffect(MobEffects.SPEED)) return "Swiftness";
		if (player.hasEffect(MobEffects.SLOWNESS)) return "Slowness";
		if (player.hasEffect(MobEffects.STRENGTH)) return "Strength";
		if (player.hasEffect(MobEffects.WEAKNESS)) return "Weakness";
		if (player.hasEffect(MobEffects.HUNGER)) return "Hunger";
		if (player.hasEffect(MobEffects.NAUSEA)) return "Nausea";
		if (player.hasEffect(MobEffects.BAD_OMEN) || player.hasEffect(MobEffects.RAID_OMEN)) return "Bad Omen";
		if (player.hasEffect(MobEffects.OOZING)) return "Oozing";
		if (player.getActiveEffects().size() >= 2) return "Multiple Status Effects";
		if (BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(player.blockPosition().below()).getBlock())
				.getPath().endsWith("_bed")) return "Stand on a Villager's Bed";

		ItemStack held = player.getMainHandItem();
		if (held.isDamageableItem() && held.getDamageValue() >= held.getMaxDamage() * 0.85F) return "Hold a Nearly Broken Item";
		int armor = 0;
		Set<String> armorMaterials = new HashSet<>();
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			armor++;
			String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			armorMaterials.add(path.substring(0, path.indexOf('_') > 0 ? path.indexOf('_') : path.length()));
		}
		if (armor == 4 && armorMaterials.size() == 1 && armorMaterials.contains("iron")) return "Wear Full Iron Armor";
		if (armor == 4 && armorMaterials.size() > 1) return "Wear Mixed Armor";
		if (armor == 4 && (armorMaterials.contains("diamond") || armorMaterials.contains("netherite"))) return "Wear High-Level Armor";
		if (armor > 0) return "Wear Armor";
		return null;
	}

	private static boolean playCosmeticObservation(Player player, Villager villager) {
		ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
		ItemStack held = player.getMainHandItem();
		CastProfile profile = cast(villager);
		String id = null;
		if (head.getItem() == com.vnap.item.VillagerNewsItems.VILLAGER_NOSE) id = "kejscw";
		else if (head.getItem() == com.vnap.item.VillagerNewsItems.MAYOR_HAT && profile == CastProfile.MAYOR) id = "cmkesu";
		else if (head.getItem() == com.vnap.item.VillagerNewsItems.TESTIFICATE_MAN_HELMET && profile == CastProfile.TESTIFICATE_MAN) id = "rooiup";
		else if (head.getItem() == com.vnap.item.VillagerNewsItems.MOUSTACHE && profile == CastProfile.NUMBER_5) id = "mjyhgw";
		else if (held.getItem() == com.vnap.item.VillagerNewsItems.MICROPHONE && profile == CastProfile.NUMBER_9) id = "adhvqz";
		if (id == null) return false;
		String key = "player_cosmetic:" + villager.getUUID() + ":" + id;
		return id.equals("kejscw") ? playSharedId(villager, id, key, LONG_COOLDOWN, player)
			: playId(villager, id, key, LONG_COOLDOWN, player);
	}

	// ---------------------------------------------------------------- wandering trader / wooly

	private static void processWanderingTrader(Level level, Player player) {
		AABB area = AABB.ofSize(player.position(), OBSERVER_RANGE * 2.0, OBSERVER_RANGE, OBSERVER_RANGE * 2.0);
		WanderingTrader trader = level.getEntitiesOfClass(WanderingTrader.class, area, Entity::isAlive).stream()
			.filter(candidate -> candidate.hasLineOfSight(player))
			.min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(player)))
			.orElse(null);
		if (trader == null) return;
		String pair = player.getUUID() + ":" + trader.getUUID();
		if (playId(trader, "hxlyuc", "approach:" + pair, LONG_COOLDOWN, player)) return;
		boolean invisible = trader.hasEffect(MobEffects.INVISIBILITY);
		boolean wasInvisible = LAST_TRADER_INVISIBLE.put(trader.getUUID(), invisible) == Boolean.TRUE;
		if (invisible) {
			long llamas = level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(trader.position(), 24, 12, 24), Entity::isAlive)
				.stream().filter(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().equals("trader_llama")).count();
			String id = wasInvisible ? "dbzjqi" : llamas >= 2 ? "myajyt" : llamas == 1 ? "jkeahu" : "vggdrt";
			if (playId(trader, id, "invisible:" + trader.getUUID() + ":" + id, LONG_COOLDOWN, player)) return;
		}
		if (level.isRainingAt(trader.blockPosition())
				&& playId(trader, "kxoqky", "rain:" + trader.getUUID(), LONG_COOLDOWN)) return;
		if (ticks % 100L == 0L && trader.getDeltaMovement().horizontalDistanceSqr() > 0.0004) {
			playId(trader, "stqafd", "idle:" + trader.getUUID(), LONG_COOLDOWN);
		}
	}

	private static void processWooly(Level level, Player player) {
		AABB area = AABB.ofSize(player.position(), OBSERVER_RANGE * 2.0, OBSERVER_RANGE, OBSERVER_RANGE * 2.0);
		Sheep wooly = level.getEntitiesOfClass(Sheep.class, area, sheep -> sheep.isAlive() && isWooly(sheep)).stream()
			.filter(candidate -> candidate.hasLineOfSight(player))
			.min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(player)))
			.orElse(null);
		if (wooly == null) return;
		String pair = player.getUUID() + ":" + wooly.getUUID();
		if (playId(wooly, "uvtocs", "approach:" + pair, LONG_COOLDOWN, player)) return;
		if (ticks % 100L == 0L && wooly.getDeltaMovement().horizontalDistanceSqr() > 0.0004) {
			playId(wooly, "vmohcm", "idle:" + wooly.getUUID(), LONG_COOLDOWN);
		}
	}

	// ---------------------------------------------------------------- villager state / conversations

	private static void processConversations(Level level, Player player) {
		for (Villager villager : nearbyVillagers(level, player.position(), 24)) {
			if (cast(villager) != CastProfile.UNREACHABLE) processVillagerState(villager);
		}
		Set<String> checkedPairs = new HashSet<>();
		List<Villager> villagers = nearbyVillagers(level, player.position(), 24).stream()
			.filter(villager -> !villager.isBaby() && !villager.isSleeping())
			.filter(villager -> cast(villager) != CastProfile.UNREACHABLE).toList();
		for (Villager subject : villagers) {
			int cosmetic = VillagerCosmetics.cosmeticFor(cast(subject));
			if (cosmetic == 0) continue;
			Villager witness = villagers.stream().filter(other -> other != subject && other.hasLineOfSight(subject))
				.min(Comparator.comparingDouble(other -> other.distanceToSqr(subject))).orElse(null);
			if (witness != null) {
				String id = cast(witness) == CastProfile.TESTIFICATE_MAN && cosmetic == 2 ? "pbbywc" : "anrhns";
				if (playSharedId(witness, id, "cosmetic_witness:" + witness.getUUID() + ":" + subject.getUUID() + ":" + id,
					LONG_COOLDOWN, subject)) return;
			}
		}
		if (villagers.size() >= 8 && playSharedId(villagers.getFirst(), "kzemrz", "villager_crowd:" + player.getUUID(), LONG_COOLDOWN, player)) return;
		Villager gatheringSpeaker = villagers.stream().filter(villager -> cast(villager) == CastProfile.VILLAGER).findFirst().orElse(null);
		Villager gatheringTarget = gatheringSpeaker == null ? null : nearestConversationPartner(gatheringSpeaker, villagers);
		if (gatheringTarget != null && villagers.size() >= 3 && playId(gatheringSpeaker, "ebfifz",
				"gathering:" + player.getUUID(), LONG_COOLDOWN, gatheringTarget)) return;
		for (int firstIndex = 0; firstIndex < villagers.size(); firstIndex++) {
			Villager first = villagers.get(firstIndex);
			Villager nearestPartner = nearestConversationPartner(first, villagers);
			if (nearestPartner == null) continue;
			for (int secondIndex = firstIndex + 1; secondIndex < villagers.size(); secondIndex++) {
				Villager second = villagers.get(secondIndex);
				String pair = orderedPair(first.getUUID(), second.getUUID());
				if (second != nearestPartner || !checkedPairs.add(pair) || isBusy(first) || isBusy(second)) continue;
				long firstSeenTogether = PAIR_TICKS.computeIfAbsent(pair, key -> ticks);
				long togetherTicks = ticks - firstSeenTogether;
				if (togetherTicks < 200) continue;
				boolean firstHasNose = VillagerCosmetics.hasNose(first.getUUID());
				boolean secondHasNose = VillagerCosmetics.hasNose(second.getUUID());
				if (!firstHasNose || !secondHasNose) {
					boolean bothMissing = !firstHasNose && !secondHasNose;
					Villager conversationSpeaker = !bothMissing && !firstHasNose ? second : first;
					Villager conversationSubject = conversationSpeaker == first ? second : first;
					List<List<String>> choices = bothMissing
						? TWO_MISSING_NOSES_CONVERSATIONS : ONE_MISSING_NOSE_CONVERSATIONS;
					List<String> sequence = choices.get(Math.floorMod(pair.hashCode() + (int) (ticks / LONG_COOLDOWN), choices.size()));
					if (playId(conversationSpeaker, sequence.getFirst(), "nose_conversation:" + pair + ":" + sequence.getFirst(),
							LONG_COOLDOWN, conversationSubject)) {
						holdListener(conversationSubject, conversationSpeaker, DialogueCatalog.byId(sequence.getFirst()).durationTicks());
						queueConversation(conversationSpeaker, conversationSubject, sequence);
						PAIR_TICKS.put(pair, ticks);
					}
					return;
				}
				CastProfile firstCast = cast(first);
				CastProfile secondCast = cast(second);
				String meetId = meetDialogue(firstCast == CastProfile.VILLAGER ? secondCast : firstCast);
				if (meetId != null && (firstCast == CastProfile.VILLAGER || secondCast == CastProfile.VILLAGER)) {
					Villager speaker = firstCast == CastProfile.VILLAGER ? first : second;
					Villager subject = speaker == first ? second : first;
					if (playId(speaker, meetId, "meet:" + pair, LONG_COOLDOWN, subject)) {
						holdListener(subject, speaker, 80L);
						PAIR_TICKS.put(pair, ticks);
					}
					return;
				}
				boolean atCampfire = nearBlock(level, first.blockPosition(), "campfire", 4)
					&& nearBlock(level, second.blockPosition(), "campfire", 4);
				boolean negativeGossip = isNegativeReputation(first, player) || isNegativeReputation(second, player);
				String conversation = atCampfire ? CAMPFIRE_CONVERSATION.getFirst()
					: first.getVehicle() != null && first.getVehicle() == second.getVehicle()
					? "zqfvby"
					: negativeGossip && first.getDeltaMovement().horizontalDistanceSqr()
						+ second.getDeltaMovement().horizontalDistanceSqr() < 0.0004
						? (Math.floorMod(pair.hashCode() + (int) (ticks / LONG_COOLDOWN), 2) == 0 ? "wrjbdd" : GOSSIP_CONVERSATION.getFirst())
						: wanderingConversation(pair).getFirst();
				if (playId(first, conversation, "conversation:" + pair + ":" + conversation, LONG_COOLDOWN, second)) {
					holdListener(second, first, DialogueCatalog.byId(conversation).durationTicks());
					if (conversation.startsWith("gmrypk")) queueConversation(first, second, wanderingConversation(pair));
					else if (atCampfire) queueConversation(first, second, CAMPFIRE_CONVERSATION);
					else if (conversation.equals(GOSSIP_CONVERSATION.getFirst())) queueConversation(first, second, GOSSIP_CONVERSATION);
					PAIR_TICKS.put(pair, ticks);
					return;
				}
			}
		}

		Villager adult = villagers.stream().findFirst().orElse(null);
		List<Villager> babies = nearbyVillagers(level, player.position(), 24).stream()
			.filter(villager -> villager.isBaby() && !villager.isSleeping()).toList();
		Villager baby = babies.stream().findFirst().orElse(null);
		if (babies.size() >= 2 && babies.get(0).distanceToSqr(babies.get(1)) <= 64.0
				&& babies.get(0).getDeltaMovement().horizontalDistanceSqr() + babies.get(1).getDeltaMovement().horizontalDistanceSqr() > 0.01) {
			playId(babies.get(0), "rfnirh", "baby_chase:" + orderedPair(babies.get(0).getUUID(), babies.get(1).getUUID()), LONG_COOLDOWN, babies.get(1));
		}
		if (adult != null && baby != null && adult.distanceToSqr(baby) <= 64.0) {
			playSharedId(adult, "pbmrxx", "see_baby:" + adult.getUUID() + ":" + baby.getUUID(), LONG_COOLDOWN, baby);
		}
	}

	private static List<String> wanderingConversation(String pair) {
		return WANDERING_CONVERSATIONS.get(Math.floorMod(pair.hashCode() + (int) (ticks / LONG_COOLDOWN), WANDERING_CONVERSATIONS.size()));
	}

	private static void queueConversation(Villager first, Villager second, List<String> sequence) {
		long due = ticks + DialogueCatalog.byId(sequence.getFirst()).durationTicks() + 2L;
		for (int index = 1; index < sequence.size(); index++) {
			Villager speaker = index % 2 == 1 ? second : first;
			Villager target = speaker == first ? second : first;
			String id = sequence.get(index);
			PENDING_SPEECH.add(new PendingSpeech(speaker.getUUID(), id, target.getUUID(), due, false));
			due += DialogueCatalog.byId(id).durationTicks() + 2L;
		}
	}

	private static Villager nearestConversationPartner(Villager villager, List<Villager> candidates) {
		return candidates.stream()
			.filter(candidate -> candidate != villager && !isBusy(candidate))
			.filter(candidate -> villager.distanceToSqr(candidate) <= 6.25 && villager.hasLineOfSight(candidate))
			.min(Comparator.comparingDouble(villager::distanceToSqr))
			.orElse(null);
	}

	private static boolean nearBlock(Level level, BlockPos origin, String pathPart, int range) {
		for (int x = -range; x <= range; x++) for (int y = -2; y <= 2; y++) for (int z = -range; z <= range; z++) {
			String path = BuiltInRegistries.BLOCK.getKey(level.getBlockState(origin.offset(x, y, z)).getBlock()).getPath();
			if (path.contains(pathPart)) return true;
		}
		return false;
	}

	private static boolean playHomeChestReaction(Level level, Player player, BlockPos chestPos) {
		boolean bedNearby = nearBlock(level, chestPos, "bed", 6);
		return nearbyVillagers(level, Vec3.atCenterOf(chestPos), OBSERVER_RANGE).stream()
			.filter(villager -> !villager.isBaby() && !villager.isSleeping() && cast(villager) == CastProfile.VILLAGER)
			.filter(villager -> bedNearby || homeMatches(villager, chestPos, 12))
			.filter(villager -> villager.hasLineOfSight(player))
			.min(Comparator.comparingDouble(villager -> villager.distanceToSqr(player)))
			.map(villager -> playId(villager, "qfhrlh", "home_chest:" + villager.getUUID(), SHORT_COOLDOWN, player))
			.orElse(false);
	}

	private static boolean homeMatches(Villager villager, BlockPos pos, int range) {
		return villager.getBrain().getMemory(MemoryModuleType.HOME)
			.map(home -> home.isCloseEnough(villager.level().dimension(), pos, range)).orElse(false);
	}

	private static void playFireworkReactions(Level level, Entity firework) {
		List<Villager> witnesses = nearbyVillagers(level, firework.position(), OBSERVER_RANGE).stream()
			.filter(villager -> !villager.isSleeping())
			.filter(villager -> villager.hasLineOfSight(firework)).toList();
		witnesses.stream().filter(villager -> !villager.isBaby())
			.min(Comparator.comparingDouble(villager -> villager.distanceToSqr(firework)))
			.ifPresent(villager -> playSharedId(villager, "dfdkli", "firework_spawn:" + villager.getUUID(), SHORT_COOLDOWN, firework));
		witnesses.stream().filter(Villager::isBaby)
			.min(Comparator.comparingDouble(villager -> villager.distanceToSqr(firework)))
			.ifPresent(villager -> playId(villager, "zeykfp", "firework_seen:" + villager.getUUID(), SHORT_COOLDOWN, firework));
	}

	private static void playLightningReaction(Level level, Entity lightning) {
		nearbyVillagers(level, lightning.position(), 128.0).stream()
			.filter(villager -> !villager.isBaby() && !villager.isSleeping() && villager.hasLineOfSight(lightning))
			.min(Comparator.comparingDouble(villager -> villager.distanceToSqr(lightning)))
			.ifPresent(villager -> playSharedId(villager, "ikrwzy", "lightning:" + villager.getUUID(), SHORT_COOLDOWN, lightning));
	}

	private static void processVillagerState(Villager villager) {
		VillagerSnapshot previous = VILLAGER_STATES.get(villager.getUUID());
		BlockPos workstation = findWorkstation(villager);
		VillagerSnapshot current = snapshot(villager, workstation != null);
		Map<String, Integer> oldInventory = VILLAGER_INVENTORIES.get(villager.getUUID());
		boolean sleeping = villager.isSleeping();
		boolean wasSleeping = LAST_SLEEPING.getOrDefault(villager.getUUID(), sleeping);
		if (sleeping) {
			if (wasSleeping) playTitle(villager, "Sleeping", "sleeping:" + villager.getUUID(), LONG_COOLDOWN);
			LAST_SLEEPING.put(villager.getUUID(), true);
			VILLAGER_INVENTORIES.put(villager.getUUID(), inventoryCounts(villager));
			VILLAGER_STATES.put(villager.getUUID(), current);
			return;
		}
		if (wasSleeping) playTitle(villager, "Wake Up Naturally", "wake:" + villager.getUUID(), LONG_COOLDOWN);
		LAST_SLEEPING.put(villager.getUUID(), false);
		ItemStack pickedUp = findNewItem(villager, oldInventory);
		if (pickedUp != null) {
			String path = BuiltInRegistries.ITEM.getKey(pickedUp.getItem()).getPath();
			boolean armor = path.endsWith("_helmet") || path.endsWith("_chestplate") || path.endsWith("_leggings") || path.endsWith("_boots");
			String id = armor ? (pickedUp.isEnchanted() ? "habfnx" : "zjwpzi") : "dxmmiu";
			boolean food = path.equals("beetroot") || path.equals("bread") || path.equals("carrot") || path.equals("potato");
			Villager donor = nearbyVillagers(villager.level(), villager.position(), 4.0).stream()
				.filter(other -> other != villager).min(Comparator.comparingDouble(other -> other.distanceToSqr(villager))).orElse(null);
			Player nearbyPlayer = villager.level().getNearestPlayer(villager, 6.0);
			if (villager.isBaby() && donor != null && food
					&& playId(donor, "locuih", "share_food:" + donor.getUUID(), SHORT_COOLDOWN, villager)) {
				long due = ticks + DialogueCatalog.byId("locuih").durationTicks() + 2L;
				PENDING_SPEECH.add(new PendingSpeech(villager.getUUID(), "saxuwk", donor.getUUID(), due, false));
			} else if (donor != null && !armor) {
				playId(villager, "ujyxfg", "pickup_villager:" + villager.getUUID() + ":" + path, SHORT_COOLDOWN, donor);
			} else if (nearbyPlayer != null) {
				playId(villager, armor ? id : "vkhrme", "pickup_player:" + villager.getUUID() + ":" + path, SHORT_COOLDOWN, nearbyPlayer);
			} else playId(villager, id, "pickup:" + villager.getUUID() + ":" + path, SHORT_COOLDOWN);
		}
		VILLAGER_INVENTORIES.put(villager.getUUID(), inventoryCounts(villager));
		if (previous != null) {
			if (previous.baby && !current.baby) {
				playId(villager, "smvnbj", "grow:" + villager.getUUID(), 1L);
			} else if ((previous.profession.equals("none") || previous.profession.equals("nitwit"))
					&& !current.profession.equals("none") && !current.profession.equals("nitwit")) {
				playId(villager, "zndzjx", "job:" + villager.getUUID(), 1L);
			} else if (current.level > previous.level) {
				playId(villager, current.level >= 5 ? "pnvkfy" : "fltegg", "level:" + villager.getUUID() + ":" + current.level, 1L);
			} else if (!current.name.isEmpty() && !current.name.equals(previous.name)) {
				String lower = current.name.toLowerCase(Locale.ROOT);
				String id = current.baby ? (lower.equals("dragon") ? "cmrqhw" : "gzsztp")
					: lower.equals("dinnerbone") ? "qmpcxi" : lower.equals("jeb") || lower.equals("jeb_") ? "armupg" : "spfsrr";
				playId(villager, id, "name:" + villager.getUUID() + ":" + current.name, 1L);
			} else if (current.poisoned && !previous.poisoned) {
				playId(villager, "onindz", "effect:poison:" + villager.getUUID(), SHORT_COOLDOWN);
			} else if (current.slowed && !previous.slowed) {
				playId(villager, "xemyaj", "effect:slowness:" + villager.getUUID(), SHORT_COOLDOWN);
			} else if (current.weakened && !previous.weakened) {
				playId(villager, "yebifs", "effect:weakness:" + villager.getUUID(), SHORT_COOLDOWN);
			} else if (previous.suffocating && !current.suffocating) {
				interrupt(villager);
				playId(villager, "fxbysi", "freed:" + villager.getUUID(), SHORT_COOLDOWN);
			}
			if (!previous.working && current.working) {
				playId(villager, "qawras", "work_start:" + villager.getUUID(), LONG_COOLDOWN, workstation == null ? null : Vec3.atCenterOf(workstation));
			} else if (current.working) {
				String work = ticks / LONG_COOLDOWN % 3L == 0L ? "sdhkke" : professionWorkDialogue(current.profession);
				if (work != null) playId(villager, work, "work:" + villager.getUUID() + ":" + work, LONG_COOLDOWN,
					workstation == null ? null : Vec3.atCenterOf(workstation));
			}
		}
		if (!current.profession.equals("none") && !current.profession.equals("nitwit") && workstation == null) {
			long since = NO_WORKSTATION_SINCE.computeIfAbsent(villager.getUUID(), ignored -> ticks);
			if (ticks - since >= 600L) {
				playId(villager, "ywzhwz", "missing_workstation:" + villager.getUUID(), LONG_COOLDOWN);
				NO_WORKSTATION_SINCE.put(villager.getUUID(), ticks);
			}
		} else NO_WORKSTATION_SINCE.remove(villager.getUUID());
		if (!villager.isBaby() && !villager.getBrain().hasMemoryValue(MemoryModuleType.MEETING_POINT)) {
			long since = NO_BELL_SINCE.computeIfAbsent(villager.getUUID(), ignored -> ticks);
			if (ticks - since >= 1200L) {
				playId(villager, "trphsn", "missing_bell:" + villager.getUUID(), LONG_COOLDOWN);
				NO_BELL_SINCE.put(villager.getUUID(), ticks);
			}
		} else NO_BELL_SINCE.remove(villager.getUUID());
		if (profession(villager).equals("farmer") && nearCrops(villager.level(), villager.blockPosition(), 4)) {
			playId(villager, "aobqjt", "farming:" + villager.getUUID(), LONG_COOLDOWN);
		}
		if (!villager.isSleeping() && villager.getDeltaMovement().horizontalDistanceSqr() > 0.0004) {
			if (!VillagerCosmetics.hasNose(villager.getUUID())) {
				playId(villager, "dcvgnm", "no_nose_wander:" + villager.getUUID(), LONG_COOLDOWN);
			}
			float sunAngle = villager.level().environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, villager.blockPosition());
			if (sunAngle >= 0.5F && sunAngle < 0.85F) {
				String id = villager.level().dimension() == Level.END ? "iubjul" : villager.level().dimension() == Level.NETHER ? "bvtmmz"
					: villager.level().dimension() != Level.OVERWORLD ? "uhbigm"
					: villager.getBrain().hasMemoryValue(MemoryModuleType.HOME) ? "wkfbuv" : "uqguqj";
				playId(villager, id, "return_home:" + villager.getUUID() + ":" + id, LONG_COOLDOWN);
			}
		}
		long danger = LAST_DANGER.getOrDefault(villager.getUUID(), Long.MIN_VALUE / 2);
		if (ticks - danger >= 100L && ticks - danger <= 200L) {
			playId(villager, villager.isBaby() ? "wsxfok" : "wbbxpo", "calm:" + villager.getUUID(), LONG_COOLDOWN);
		}
		if (villager.isBaby() && villager.getDeltaMovement().horizontalDistanceSqr() > 0.02) {
			boolean weekend = LocalDate.now().getDayOfWeek() == DayOfWeek.SATURDAY || LocalDate.now().getDayOfWeek() == DayOfWeek.SUNDAY;
			playId(villager, weekend ? "vbclem" : "vhwksn", "baby_sprint:" + villager.getUUID(), LONG_COOLDOWN);
		}
		VILLAGER_STATES.put(villager.getUUID(), current);
	}

	private static Map<String, Integer> inventoryCounts(Villager villager) {
		Map<String, Integer> counts = new HashMap<>();
		for (ItemStack stack : villager.getInventory().getItems()) {
			if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), stack.getCount(), Integer::sum);
		}
		return counts;
	}

	private static ItemStack findNewItem(Villager villager, Map<String, Integer> previous) {
		if (previous == null) return null;
		Map<String, Integer> current = inventoryCounts(villager);
		for (ItemStack stack : villager.getInventory().getItems()) {
			if (stack.isEmpty()) continue;
			String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			if (current.getOrDefault(path, 0) > previous.getOrDefault(path, 0)) return stack;
		}
		return null;
	}

	private static VillagerSnapshot snapshot(Villager villager, boolean working) {
		String name = villager.hasCustomName() && villager.getCustomName() != null ? villager.getCustomName().getString() : "";
		return new VillagerSnapshot(villager.isBaby(), profession(villager), villager.getVillagerData().level(), working, name,
			villager.hasEffect(MobEffects.POISON), villager.hasEffect(MobEffects.SLOWNESS), villager.hasEffect(MobEffects.WEAKNESS),
			villager.isInWall());
	}

	private static String profession(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey()
			.map(key -> key.identifier().getPath()).orElse("none");
	}

	private static BlockPos findWorkstation(Villager villager) {
		String block = switch (profession(villager)) {
			case "armorer" -> "blast_furnace";
			case "butcher" -> "smoker";
			case "cartographer" -> "cartography_table";
			case "cleric" -> "brewing_stand";
			case "farmer" -> "composter";
			case "fisherman" -> "barrel";
			case "fletcher" -> "fletching_table";
			case "leatherworker" -> "cauldron";
			case "librarian" -> "lectern";
			case "mason" -> "stonecutter";
			case "shepherd" -> "loom";
			case "toolsmith" -> "smithing_table";
			case "weaponsmith" -> "grindstone";
			default -> null;
		};
		if (block == null) return null;
		BlockPos origin = villager.blockPosition();
		for (int x = -3; x <= 3; x++) for (int y = -2; y <= 2; y++) for (int z = -3; z <= 3; z++) {
			BlockPos pos = origin.offset(x, y, z);
			if (BuiltInRegistries.BLOCK.getKey(villager.level().getBlockState(pos).getBlock()).getPath().equals(block)) return pos;
		}
		return null;
	}

	private static boolean nearCrops(Level level, BlockPos origin, int range) {
		for (int x = -range; x <= range; x++) for (int y = -2; y <= 2; y++) for (int z = -range; z <= range; z++) {
			String path = BuiltInRegistries.BLOCK.getKey(level.getBlockState(origin.offset(x, y, z)).getBlock()).getPath();
			if (path.equals("wheat") || path.equals("carrots") || path.equals("potatoes") || path.equals("beetroots")
					|| path.equals("torchflower_crop") || path.equals("pitcher_crop")) return true;
		}
		return false;
	}

	private static String professionWorkDialogue(String profession) {
		return switch (profession) {
			case "armorer" -> "djpksc";
			case "butcher" -> "ueczyh";
			case "cartographer" -> "wuoloh";
			case "cleric" -> "hkowex";
			case "farmer" -> "umdvtb";
			case "fisherman" -> "tgggoh";
			case "fletcher" -> "fzjope";
			case "leatherworker" -> "ljewqf";
			case "librarian" -> "fezzjw";
			case "mason" -> "yldlzt";
			case "shepherd" -> "opxfuo";
			case "toolsmith" -> "ivktls";
			case "weaponsmith" -> "ccpvqj";
			default -> null;
		};
	}

	private static boolean playNearbyEntityContext(Level level, Villager speaker) {
		AABB area = speaker.getBoundingBox().inflate(NEARBY_SUBJECT_RANGE, 6.0, NEARBY_SUBJECT_RANGE);
		List<Entity> visibleEntities = level.getEntities(speaker, area, Entity::isAlive).stream()
			.filter(entity -> speaker.distanceToSqr(entity) <= NEARBY_SUBJECT_RANGE * NEARBY_SUBJECT_RANGE)
			.filter(speaker::hasLineOfSight)
			.sorted(Comparator.comparingDouble(speaker::distanceToSqr))
			.toList();
		for (Entity entity : visibleEntities) {
			String path = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
			String id = switch (path) {
				case "falling_block" -> "bodvsv";
				case "experience_orb" -> "cvltyw";
				case "tnt" -> "pmaqgq";
				case "firework_rocket" -> speaker.isBaby() ? "zeykfp" : null;
				default -> null;
			};
			if (id != null && playSharedId(speaker, id, "nearby_misc:" + speaker.getUUID() + ":" + entity.getUUID() + ":" + id, LONG_COOLDOWN, entity)) {
				return true;
			}
		}
		List<ItemEntity> droppedItems = level.getEntitiesOfClass(ItemEntity.class,
			AABB.ofSize(speaker.position(), 10, 10, 10), Entity::isAlive).stream()
			.filter(speaker::hasLineOfSight)
			.sorted(Comparator.comparingDouble(speaker::distanceToSqr))
			.toList();
		if (droppedItems.size() >= 5 && playSharedId(speaker, "zywcju", "item_pile:" + speaker.getUUID(), LONG_COOLDOWN,
				droppedItems.getFirst())) return true;
		return visibleEntities.stream()
			.filter(LivingEntity.class::isInstance)
			.map(LivingEntity.class::cast)
			.filter(entity -> entity != speaker && !(entity instanceof Player) && !(entity instanceof Villager))
			.anyMatch(entity -> {
				String path = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
				Entity target = entity;
				String dialogue = speaker.isBaby() && path.equals("iron_golem") ? "mqnapy" : null;
				if (dialogue == null && entity instanceof Sheep sheep && sheep.isSheared()) dialogue = "afxbav";
				if (dialogue == null && entity instanceof net.minecraft.world.entity.TamableAnimal tame && tame.isTame()) {
					dialogue = entity.isBaby() ? "sxikgq" : "aqxgxh";
				}
				if (dialogue == null && (entity.isPassenger() || !entity.getPassengers().isEmpty())) dialogue = "dxeaal";
				if (dialogue == null && entity.isBaby()) dialogue = BABY_ENTITY_DIALOGUES.get(path);
				if (dialogue == null) dialogue = NEARBY_ENTITY_DIALOGUES.get(path);
				if (dialogue == null && !(entity instanceof WanderingTrader) && !(entity instanceof Sheep sheep && isWooly(sheep))) {
					dialogue = "gjtuqd";
				}
				return dialogue != null && speaker.hasLineOfSight(target)
					&& playSharedId(speaker, dialogue, "nearby:" + speaker.getUUID() + ":" + entity.getUUID() + ":" + dialogue, LONG_COOLDOWN, target);
			});
	}

	// ---------------------------------------------------------------- damage / death (per-tick polling)

	private static void processDamageAndDeath(ClientLevel level) {
		List<AbstractClientPlayer> observers = observablePlayers(level);
		if (observers.isEmpty()) return;
		AABB area = null;
		for (Player observer : observers) {
			AABB observerArea = AABB.ofSize(observer.position(), OBSERVER_RANGE * 3.0, OBSERVER_RANGE * 2.0, OBSERVER_RANGE * 3.0);
			area = area == null ? observerArea : area.minmax(observerArea);
		}
		List<LivingEntity> tracked = level.getEntitiesOfClass(LivingEntity.class, area, entity ->
			entity instanceof Villager || entity instanceof WanderingTrader || entity instanceof Sheep sheep && isWooly(sheep)
				|| entity instanceof Player);
		Set<UUID> present = new HashSet<>();
		for (LivingEntity entity : tracked) {
			present.add(entity.getUUID());
			Float previousHealth = HEALTH_SNAPSHOT.get(entity.getUUID());
			float health = entity.getHealth();
			HEALTH_SNAPSHOT.put(entity.getUUID(), health);
			if (previousHealth == null) continue;
			if (health <= 0.0F && previousHealth > 0.0F) {
				onDeath(entity);
			} else if (health < previousHealth) {
				onDamage(entity, level);
			}
		}
		HEALTH_SNAPSHOT.keySet().retainAll(present);
	}

	private static void onDamage(LivingEntity entity, Level level) {
		playIronGolemAttackWitness(entity, level);
		playHurtWitness(entity, level);
		if (hasDamageLock(entity)) return;
		if (entity instanceof Sheep sheep && isWooly(sheep)) {
			Entity attacker = nearestAttacker(sheep, level);
			String id = attacker instanceof Player ? "ncyeaw" : "eyiraw";
			playDamageDialogue(sheep, id, "hurt:" + sheep.getUUID(), attacker);
			return;
		}
		if (entity instanceof WanderingTrader trader) {
			Entity attacker = nearestAttacker(trader, level);
			String id = attacker instanceof Player ? "vevdkl" : "wyvzhk";
			playDamageDialogue(trader, id, "hurt:" + trader.getUUID(), attacker);
			return;
		}
		if (!(entity instanceof Villager villager)) return;
		LAST_DANGER.put(villager.getUUID(), ticks);
		Entity attacker = nearestAttacker(villager, level);
		if (villager.isBaby()) {
			String id = attacker instanceof Player ? "ahcvzd" : "ecslqo";
			playDamageDialogue(villager, id, "hurt:" + villager.getUUID(), attacker);
			return;
		}
		CastProfile profile = cast(villager);
		if (profile != CastProfile.VILLAGER) {
			String id = attacker instanceof Player ? profile.attack : profile.hurt;
			playDamageDialogue(villager, id, "hurt:" + villager.getUUID(), attacker);
			return;
		}
		if (attacker instanceof Player player && homeMatches(villager, villager.blockPosition(), 4)) {
			if (isPlaying(villager, "lpuocy") || isPlaying(villager, "slbqfwbayahw")) return;
			interrupt(villager);
			playHomeAttackReaction(villager, player);
			return;
		}
		bumpReputation(villager.getUUID(), attacker instanceof Player ? -15 : 0);
		String dialogue = damageDialogue(villager, level, attacker);
		playDamageDialogue(villager, dialogue, "hurt:" + villager.getUUID() + ":" + dialogue, attacker);
	}

	/** No synced {@code DamageSource} on the client; approximate the attacker from nearby state. */
	private static Entity nearestAttacker(LivingEntity victim, Level level) {
		if (level instanceof ClientLevel clientLevel) {
			Player nearestCandidate = observablePlayers(clientLevel).stream()
				.filter(candidate -> candidate != victim && candidate.distanceToSqr(victim) <= 16.0 && candidate.hasLineOfSight(victim))
				.min(Comparator.comparingDouble((Player candidate) -> candidate.distanceToSqr(victim)).thenComparing(Player::getUUID))
				.orElse(null);
			if (nearestCandidate != null) return nearestCandidate;
		}
		return level.getEntitiesOfClass(LivingEntity.class, victim.getBoundingBox().inflate(3.0),
				candidate -> candidate != victim && candidate instanceof net.minecraft.world.entity.monster.Monster)
			.stream().min(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(victim))).map(Entity.class::cast).orElse(null);
	}

	private static boolean playDamageDialogue(LivingEntity speaker, String id, String cooldownKey, Entity target) {
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(id);
		long cooldown = target == null ? SHORT_COOLDOWN : group == null ? 20L : group.durationTicks() + 20L;
		if (isPlaying(speaker, id) || !ready(cooldownKey, cooldown)) return false;
		interrupt(speaker);
		boolean sharedAdult = speaker instanceof WanderingTrader || speaker instanceof Villager villager
			&& cast(villager) == CastProfile.UNREACHABLE;
		return sharedAdult ? playSharedId(speaker, id, cooldownKey, cooldown, target)
			: playId(speaker, id, cooldownKey, cooldown, target);
	}

	private static void playHurtWitness(LivingEntity entity, Level level) {
		nearbyVillagers(level, entity.position(), OBSERVER_RANGE).stream()
			.filter(witness -> witness != entity && !witness.isBaby() && !witness.isSleeping()
				&& !isBusy(witness) && witness.hasLineOfSight(entity))
			.min(Comparator.comparingDouble(witness -> witness.distanceToSqr(entity)))
			.ifPresent(witness -> playSharedId(witness, "pkvhpv", "witness_hurt:" + witness.getUUID(), SHORT_COOLDOWN, entity));
	}

	private static void playIronGolemAttackWitness(LivingEntity entity, Level level) {
		String hurtType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
		Player player = null;
		if (hurtType.equals("iron_golem")) {
			Entity attacker = nearestAttacker(entity, level);
			if (attacker instanceof Player candidate) player = candidate;
		} else if (entity instanceof Player candidate) {
			boolean nearGolem = level.getEntitiesOfClass(LivingEntity.class, entity.getBoundingBox().inflate(3.0),
				living -> BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).getPath().equals("iron_golem")).size() > 0;
			if (nearGolem) player = candidate;
		}
		if (player == null || player.isCreative()) return;
		Player subject = player;
		nearbyVillagers(level, entity.position(), OBSERVER_RANGE).stream()
			.filter(witness -> !witness.isBaby() && !witness.isSleeping() && !isBusy(witness)
				&& witness.hasLineOfSight(subject))
			.min(Comparator.comparingDouble(witness -> witness.distanceToSqr(entity)))
			.ifPresent(witness -> playSharedId(witness, "qffeco", "golem_attack:" + witness.getUUID(),
				SHORT_COOLDOWN, subject));
	}

	private static String damageDialogue(LivingEntity victim, Level level, Entity attacker) {
		if (victim.isOnFire()) return "etkxko";
		if (victim.isInLava()) return "elryje";
		if (victim.getTicksFrozen() > 0) return "igebly";
		if (attacker instanceof Player player) return weaponAttackDialogue(player.getMainHandItem());
		if (attacker != null) {
			String attackerType = BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).getPath();
			if (attackerType.equals("evoker")) return "nkcoqb";
			if (attackerType.equals("pillager")) return "qhpyaw";
			if (attackerType.equals("ravager")) return "uveohs";
			if (attackerType.equals("vex")) return "caykki";
			if (attackerType.equals("vindicator")) return "swomdw";
			if (attackerType.equals("witch")) return "rtikom";
			if (attackerType.equals("zoglin")) return "hfmwvf";
			if (attackerType.contains("zombie") || attackerType.equals("drowned") || attackerType.equals("husk")) return "mytmrk";
		}
		if (victim.fallDistance > 3.0F) return "cifbit";
		return "wyvzhk";
	}

	private static void onDeath(LivingEntity entity) {
		interrupt(entity);
		Level level = entity.level();
		if (entity instanceof Villager villager) {
			BUSY_UNTIL.remove(villager.getUUID());
			if (villager.isBaby()) playId(villager, "ecslqo", "death:" + villager.getUUID(), 1L);
			else playSharedId(villager, "hivgme", "death:" + villager.getUUID(), 1L, null);
		}
		if (entity instanceof Villager || entity instanceof WanderingTrader
				|| entity instanceof Sheep sheep && isWooly(sheep)) {
			nearbyVillagers(level, entity.position(), OBSERVER_RANGE).stream()
				.filter(witness -> witness != entity && !witness.isBaby() && !witness.isSleeping())
				.min(Comparator.comparingDouble(witness -> witness.distanceToSqr(entity)))
				.ifPresent(witness -> playSharedId(witness, "pmqrpb", "witness_death:" + witness.getUUID(), SHORT_COOLDOWN, entity));
		} else if (entity instanceof Player player) {
			String id = level.getLevelData().isHardcore() ? "elcjbb" : "hzjycq";
			nearbyVillagers(level, player.position(), OBSERVER_RANGE).stream()
				.filter(witness -> !witness.isBaby() && !witness.isSleeping() && witness.hasLineOfSight(player))
				.min(Comparator.comparingDouble(witness -> witness.distanceToSqr(player)))
				.ifPresent(witness -> playSharedId(witness, id, "player_death:" + witness.getUUID(), SHORT_COOLDOWN, player));
		}
	}

	// ---------------------------------------------------------------- interaction

	private static InteractionResult onUseEntity(Player player, Entity entity, InteractionHand hand) {
		if (entity instanceof Villager villager) {
			ItemStack heldStack = player.getItemInHand(hand);
			String heldItem = BuiltInRegistries.ITEM.getKey(heldStack.getItem()).getPath();
			String gift = foodGiftDialogue(villager.isBaby(), heldItem);
			if (gift != null) {
				playId(villager, gift, "food_gift:" + villager.getUUID() + ":" + heldItem, SHORT_COOLDOWN, player);
				bumpReputation(villager.getUUID(), 2);
				return InteractionResult.PASS;
			}
			if (villager.isSleeping()) {
				playId(villager, "viwaal", "wake_interact:" + villager.getUUID(), SHORT_COOLDOWN, player);
				return InteractionResult.PASS;
			}
			if (!villager.isBaby() && hand == InteractionHand.MAIN_HAND) {
				activeTrade = new TradeSession(villager.getUUID(), ticks);
			}
			if (villager.isBaby()) playId(villager, "aezdiy", "baby_trade:" + villager.getUUID(), SHORT_COOLDOWN, player);
		} else if (entity instanceof WanderingTrader trader) {
			if (hand == InteractionHand.MAIN_HAND) activeTrade = new TradeSession(trader.getUUID(), ticks);
		} else if (entity instanceof Sheep sheep && isWooly(sheep)) {
			String held = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath();
			playId(sheep, held.equals("shears") ? "jqaekk" : "fskcce", "interact:" + sheep.getUUID(), SHORT_COOLDOWN, player);
		} else if (BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath().equals("lead")) {
			playObserved(player.level(), player, entity.position(), "Use a Lead", SHORT_COOLDOWN);
		}
		if (entity instanceof Sheep && BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).getPath().equals("shears")) {
			playObserved(player.level(), player, entity.position(), "Shear a Sheep", SHORT_COOLDOWN);
		}
		return InteractionResult.PASS;
	}

	private static String foodGiftDialogue(boolean baby, String item) {
		if (baby) return switch (item) {
			case "beetroot" -> "qrdzmt";
			case "bread" -> "hbalps";
			case "carrot" -> "hcdvqm";
			case "potato" -> "gotjxf";
			default -> null;
		};
		return switch (item) {
			case "beetroot" -> "rlfjux";
			case "bread" -> "bbjsik";
			case "carrot" -> "nqktml";
			case "potato" -> "ytydjc";
			case "wheat" -> "ebyrtk";
			default -> null;
		};
	}

	private static void onAttackEntity(Player player, Entity entity) {
		if (entity instanceof Villager villager) {
			bumpReputation(villager.getUUID(), -25);
			if (villager.isSleeping()) return;
		}
	}

	private static void playHomeAttackReaction(Villager villager, Player player) {
		Level level = villager.level();
		Villager witness = nearbyVillagers(level, villager.position(), 10.0).stream()
			.filter(other -> other != villager && !other.isBaby() && !other.isSleeping() && other.hasLineOfSight(villager))
			.min(Comparator.comparingDouble(other -> other.distanceToSqr(villager))).orElse(null);
		if (witness != null && playSharedId(witness, "slbqfwswxeva", "home_witness:" + witness.getUUID(), 20L, villager)) {
			long due = ticks + DialogueCatalog.byId("slbqfwswxeva").durationTicks() + 2L;
			PENDING_SPEECH.add(new PendingSpeech(villager.getUUID(), "slbqfwbayahw", witness.getUUID(), due, false));
		} else playId(villager, "lpuocy", "attacked_home:" + villager.getUUID(), 20L, player);
	}

	private static String weaponAttackDialogue(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (path.endsWith("_sword")) return "rueszy";
		if (path.endsWith("_axe")) return "yjctyw";
		if (path.endsWith("_hoe")) return "qqyjjg";
		if (path.endsWith("_shovel")) return "hpnsfu";
		return "vevdkl";
	}

	// ---------------------------------------------------------------- block/item context detection

	private static String selectBreakContext(BlockState state, PlayerObservation observation) {
		if (ticks - observation.lastBreakTick <= 30L) observation.breakStreak++;
		else observation.breakStreak = 1;
		observation.lastBreakTick = ticks;
		if (observation.breakStreak >= 4) return "Break Multiple Blocks";
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (path.equals("wheat") || path.equals("carrots") || path.equals("potatoes") || path.equals("beetroots")
			|| path.equals("torchflower_crop") || path.equals("pitcher_crop")) return "Harvest Crops";
		if (path.contains("flower") || path.contains("candle") || path.contains("coral") || path.contains("banner")
			|| path.contains("decorated_pot")) return "Break a Decorative Block";
		if (path.endsWith("_door")) return "Break a Door";
		if (path.endsWith("_bed")) return "Break a Bed";
		if (path.equals("bell")) return "Break a Bell";
		if (isWorkstation(path)) return "Break a Workstation";
		if (path.contains("log") || path.contains("wood") || path.contains("stem") || path.contains("hyphae")) return "Break Wood";
		if (path.contains("stone") || path.contains("deepslate") || path.contains("cobblestone")) return "Break Stone";
		return "Break a Block";
	}

	private static String selectPlaceContext(Block block, Level level, BlockPos position) {
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (level.dimension() == Level.END) return "Place a Block from the End";
		if (level.dimension() == Level.NETHER) return "Place a Block from the Nether";
		String biome = level.getBiome(position).unwrapKey().map(key -> key.identifier().getPath()).orElse("");
		if (biome.contains("ocean")) return "Place a Block from the Ocean";
		if (path.equals("daylight_detector")) return "Place a Daylight Detector";
		if (path.equals("detector_rail")) return "Place a Detector Rail";
		if (path.equals("lightning_rod")) return "Place a Lightning Rod";
		if (path.equals("melon")) return "Place a Melon";
		if (path.equals("observer")) return "Place an Observer";
		if (path.endsWith("pressure_plate")) return "Place a Pressure Plate";
		if (path.equals("pumpkin")) return "Place a Pumpkin";
		if (path.equals("redstone_lamp")) return "Place a Redstone Lamp";
		if (path.equals("repeater")) return "Place a Redstone Repeater";
		if (path.equals("redstone_torch") || path.equals("redstone_wall_torch")) return "Place a Redstone Torch";
		if (path.contains("sculk_sensor")) return "Place a Sculk Sensor";
		if (path.equals("tripwire_hook")) return "Place a Tripwire Hook";
		if (path.equals("jack_o_lantern")) return "Place a Jack o'Lantern";
		if (path.equals("end_stone")) return "Place End Stone";
		if (path.contains("purpur")) return "Place Purpur";
		if (path.contains("copper")) return "Place a Copper Block";
		if (path.contains("brick")) return "Place Bricks";
		if (path.equals("powder_snow")) return "Place Powder Snow";
		if (path.equals("light")) return "Place a Light Block";
		if (path.equals("barrier") || path.contains("command_block") || path.equals("structure_block") || path.equals("jigsaw")) {
			return "Place a Creative-Only Block";
		}
		if (path.endsWith("sand") || path.endsWith("gravel") || path.equals("anvil")) return "Place a Gravity-Affected Block";
		if (path.equals("iron_block") || path.equals("gold_block") || path.equals("diamond_block")
			|| path.equals("emerald_block") || path.equals("netherite_block")) return "Place a Valuable Block";
		if (path.contains("redstone") || path.equals("lever") || path.endsWith("button") || path.endsWith("rail")) {
			return "Place a Redstone Component";
		}
		if (path.endsWith("_bed")) return "Place a Bed";
		if (path.equals("chest")) return "Place a Chest";
		if (path.equals("trapped_chest")) return "Place a Trapped Chest";
		if (path.equals("crafting_table")) return "Place a Crafting Table";
		if (path.equals("furnace")) return "Place a Furnace";
		if (path.equals("bookshelf")) return "Place a Bookshelf";
		if (path.equals("jukebox")) return "Place a Jukebox";
		if (path.equals("armor_stand")) return "Place an Armor Stand";
		if (path.equals("beacon")) return "Place a Beacon";
		if (isWorkstation(path)) return "Place a Workstation";
		if (path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_planks")) return "Place Wood";
		if (path.contains("dirt")) return "Place Dirt";
		if (path.contains("leaves") || path.contains("sapling") || path.contains("flower")) return "Place Leaves or Plants";
		if (path.contains("wool")) return "Place Wool";
		if (path.contains("glass")) return "Place Glass";
		if (path.contains("concrete_powder")) return "Place Concrete Powder";
		if (path.contains("concrete")) return "Place Concrete";
		if (path.contains("glazed_terracotta")) return "Place Glazed Terracotta";
		if (path.contains("terracotta")) return "Place Terracotta";
		if (path.equals("iron_block")) return "Place an Iron Block";
		if (path.equals("gold_block")) return "Place a Gold Block";
		if (path.equals("diamond_block")) return "Place a Diamond Block";
		if (path.equals("emerald_block")) return "Place an Emerald Block";
		if (path.equals("lapis_block")) return "Place a Lapis Block";
		if (path.contains("ice")) return "Place Ice";
		if (path.contains("snow")) return "Place Snow";
		if (path.endsWith("_button")) return "Place a Button";
		if (path.equals("lever")) return "Place a Lever";
		return "Place a Block";
	}

	private static String selectUseBlockContext(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (path.endsWith("_button")) return "Press a Button";
		if (path.equals("bell")) return "Hear a Bell Ring";
		if (path.equals("lever")) return "Flip a Lever";
		if (path.endsWith("_door")) return "Use a Door";
		if (path.endsWith("_fence_gate")) {
			return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)
				? "Close a Fence Gate" : "Open a Fence Gate";
		}
		if (path.equals("crafter")) return "Use a Crafter";
		if (path.equals("dispenser")) return "Use a Dispenser";
		if (path.equals("dropper")) return "Use a Dropper";
		if (path.equals("jukebox")) return "Use a Jukebox";
		if (path.equals("loom")) return "Use a Loom";
		if (path.contains("shulker_box")) return "Use a Shulker Box";
		if (path.equals("stonecutter")) return "Use a Stonecutter";
		if (path.equals("beacon")) return "Use a Beacon";
		if (path.contains("campfire")) return "Use a Campfire";
		if (path.equals("cartography_table")) return "Use a Cartography Table";
		if (path.equals("cauldron") || path.endsWith("_cauldron")) return "Use a Cauldron";
		if (path.equals("chiseled_bookshelf")) return "Use a Chiseled Bookshelf";
		if (path.equals("composter")) return "Use a Composter";
		if (path.equals("ender_chest")) return "Use an Ender Chest";
		if (path.contains("shelf")) return "Use Shelves";
		if (path.contains("chest")) return "Open a Chest";
		if (path.equals("crafting_table")) return "Use a Crafting Table";
		if (path.equals("furnace") || path.equals("blast_furnace") || path.equals("smoker")) return "Use a Furnace";
		if (path.equals("anvil") || path.endsWith("_anvil")) return "Use an Anvil";
		if (path.equals("enchanting_table")) return "Use an Enchanting Table";
		if (path.equals("brewing_stand")) return "Use a Brewing Stand";
		if (path.equals("grindstone")) return "Use a Grindstone";
		if (path.equals("smithing_table")) return "Use a Smithing Table";
		return null;
	}

	private static String selectHeldBlockContext(ItemStack stack, BlockState clicked) {
		String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		String block = BuiltInRegistries.BLOCK.getKey(clicked.getBlock()).getPath();
		if (item.equals("redstone")) return "Place Redstone Dust";
		if ((item.equals("flint_and_steel") || item.equals("fire_charge")) && block.equals("tnt")) return "Light TNT";
		if (block.equals("tnt")) return "See TNT";
		if ((item.equals("flint_and_steel") || item.equals("fire_charge")) && block.contains("campfire")) return "Light a Campfire";
		if ((item.equals("flint_and_steel") || item.equals("fire_charge")) && block.contains("candle")) return "Light a Candle";
		if (block.contains("campfire") && (item.contains("beef") || item.contains("porkchop") || item.contains("chicken")
			|| item.contains("mutton") || item.contains("rabbit") || item.equals("potato"))) return "Cook Food on a Campfire";
		if ((item.equals("water_bucket") || item.endsWith("_shovel")) && block.contains("campfire")) return "Extinguish a Campfire";
		if (item.equals("water_bucket") && block.contains("candle")) return "Extinguish a Candle";
		if (item.equals("shears") && block.equals("pumpkin")) return "Carve a Pumpkin";
		return null;
	}

	private static String selectUseItemContext(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (path.equals("firework_rocket")) return "Set Off a Firework";
		if (path.equals("ender_pearl")) return "Teleport with an Ender Pearl";
		if (path.equals("snowball")) return "Snowball";
		if (path.contains("apple") || path.contains("bread") || path.contains("carrot") || path.contains("potato")
			|| path.contains("beef") || path.contains("porkchop") || path.contains("chicken") || path.contains("mutton")
			|| path.contains("rabbit") || path.contains("stew") || path.contains("berries") || path.contains("melon")) {
			return "Eat Food";
		}
		return null;
	}

	private static boolean isWorkstation(String path) {
		return path.equals("composter") || path.equals("barrel") || path.equals("blast_furnace")
			|| path.equals("smoker") || path.equals("cartography_table") || path.equals("brewing_stand")
			|| path.equals("fletching_table") || path.equals("cauldron") || path.equals("lectern")
			|| path.equals("stonecutter") || path.equals("loom") || path.equals("smithing_table")
			|| path.equals("grindstone");
	}

	private static boolean playObserved(Level level, Player player, Vec3 eventPosition, String title, long cooldown) {
		Villager speaker = nearbyVillagers(level, eventPosition, OBSERVER_RANGE).stream()
			.filter(villager -> !villager.isBaby() && !villager.isSleeping())
			.filter(villager -> cast(villager) != CastProfile.UNREACHABLE)
			.filter(villager -> villager.hasLineOfSight(player))
			.min(Comparator.comparingDouble(villager -> villager.distanceToSqr(eventPosition)))
			.orElse(null);
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byTitle(title, "villager");
		return speaker != null && group != null && play(speaker, group,
			"observed:" + speaker.getUUID() + ":" + title, cooldown, player, null, true);
	}

	private static List<Villager> nearbyVillagers(Level level, Vec3 position, double range) {
		AABB area = AABB.ofSize(position, range * 2.0, range, range * 2.0);
		List<Villager> found = level.getEntitiesOfClass(Villager.class, area, Entity::isAlive);
		// getEntitiesOfClass returns entities in the level's own internal storage order, which is
		// not guaranteed to match between two independently-running clients (it depends on chunk/
		// entity-load timing, which can differ slightly over the network) - sorting by UUID makes
		// every selection downstream that walks this list (e.g. picking who starts a conversation
		// with whom) deterministic and identical across clients observing the same villagers.
		found.sort(Comparator.comparing(Entity::getUUID));
		return found;
	}

	private static boolean playTitle(LivingEntity speaker, String title, String cooldownKey, long cooldown) {
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byTitle(title, speakerType(speaker));
		return group != null && play(speaker, group, cooldownKey, cooldown, null, null);
	}

	private static boolean playSharedTitle(LivingEntity speaker, String title, String cooldownKey, long cooldown, Entity target) {
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byTitle(title, "villager");
		return group != null && play(speaker, group, cooldownKey, cooldown, target, null, true);
	}

	private static boolean playId(LivingEntity speaker, String id, String cooldownKey, long cooldown) {
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(id);
		return group != null && play(speaker, group, cooldownKey, cooldown, null, null);
	}

	private static boolean playId(LivingEntity speaker, String id, String cooldownKey, long cooldown, Entity target) {
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(id);
		return group != null && play(speaker, group, cooldownKey, cooldown, target, null);
	}

	private static boolean playSharedId(LivingEntity speaker, String id, String cooldownKey, long cooldown, Entity target) {
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(id);
		return group != null && play(speaker, group, cooldownKey, cooldown, target, null, true);
	}

	private static boolean playId(LivingEntity speaker, String id, String cooldownKey, long cooldown, Vec3 target) {
		DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(id);
		return group != null && play(speaker, group, cooldownKey, cooldown, null, target);
	}

	private static boolean play(LivingEntity speaker, DialogueCatalog.DialogueGroup group, String cooldownKey, long cooldown,
			Entity target, Vec3 targetPosition) {
		return play(speaker, group, cooldownKey, cooldown, target, targetPosition, false);
	}

	/**
	 * Derives a variant-selection seed from data every nearby client observes identically - the
	 * speaker's identity, the dialogue group, and the server-synced world time bucketed to a
	 * half-second window - so that independent clients reacting to the same moment converge on
	 * the same line instead of each picking their own random variant (there is no server to
	 * broadcast a single authoritative choice anymore).
	 */
	private static long variantSeed(LivingEntity speaker, DialogueCatalog.DialogueGroup group) {
		// A wide (5s) bucket rather than a narrow one: two clients observing "the same" moment can
		// still be a tick or two apart from ordinary network jitter, and a narrow bucket means that
		// jitter can push them across a bucket boundary - which silently reshuffles the seed for
		// every simultaneous trigger at once (seen live: ~30 villagers dying together landed in two
		// different buckets one tick apart, and every single one picked a different variant).
		long timeBucket = speaker.level().getGameTime() / 100L;
		return Objects.hash(speaker.getUUID(), group.id(), timeBucket);
	}

	private static boolean play(LivingEntity speaker, DialogueCatalog.DialogueGroup group, String cooldownKey, long cooldown,
			Entity target, Vec3 targetPosition, boolean sharedAdult) {
		boolean sharedVillagerVoice = speaker instanceof Villager villager && !villager.isBaby()
			|| speaker instanceof WanderingTrader;
		boolean validSpeaker = matchesSpeaker(speaker, group) || sharedAdult && sharedVillagerVoice
			&& group.speaker().equals("villager");
		if (!speaker.level().isClientSide() || !validSpeaker
				|| !VillagerNewsSettings.dialogueEnabled()
				|| speaker instanceof Villager villager && villager.isSleeping() && !group.id().equals("asqzby")
				|| isBusy(speaker)
				|| !ready(cooldownKey, VillagerNewsSettings.scaleCooldown(cooldown))) return false;
		List<Integer> recentVariants = SHARED_RECENT_VARIANTS.getOrDefault(group.id(), List.of());
		long seed = variantSeed(speaker, group);
		DialogueCatalog.DialogueVariant variant = group.chooseVariant(VillagerNewsSettings.rareVoicelines(), Set.copyOf(recentVariants), seed);
		if (variant == null) return false;
		DialogueSoundState.start(speaker.getUUID(), group.id(), variant.index(), (int) variant.durationTicks());
		DialogueAnimationState.start(speaker.getUUID(), group.id(), variant.index(), (int) variant.durationTicks());
		DialogueSubtitleState.start(speaker.getUUID(), group.id(), variant.index(), (int) variant.durationTicks());
		ACTIVE_SOUNDS.put(speaker.getUUID(), new ActiveSound(group.id(), ticks + variant.durationTicks()));
		COOLDOWNS.put(cooldownKey, ticks);
		int maximumWeight = group.variants().stream().mapToInt(DialogueCatalog.DialogueVariant::weight).max().orElse(1);
		int eligibleVariants = VillagerNewsSettings.rareVoicelines() == 0
			? (int) group.variants().stream().filter(candidate -> candidate.weight() >= maximumWeight * 0.8).count()
			: group.variants().size();
		int historySize = Math.min(8, eligibleVariants - 1);
		if (historySize > 0) {
			List<Integer> updatedHistory = new ArrayList<>(recentVariants);
			updatedHistory.remove(Integer.valueOf(variant.index()));
			updatedHistory.add(variant.index());
			while (updatedHistory.size() > historySize) updatedHistory.removeFirst();
			SHARED_RECENT_VARIANTS.put(group.id(), updatedHistory);
		}
		markBusy(speaker, variant.durationTicks() + 10L);
		if (speaker instanceof Mob mob) {
			Vec3 position = target != null ? target.getEyePosition() : targetPosition;
			boolean lockMovement = !MOBILE_DIALOGUES.contains(group.id());
			SPEECH_TARGETS.put(speaker.getUUID(), new SpeechTarget(target == null ? null : target.getUUID(), position,
				ticks + variant.durationTicks(), lockMovement));
			if (lockMovement) holdMob(mob, position);
			else faceMob(mob, position);
		}
		return true;
	}

	private static boolean matchesSpeaker(LivingEntity speaker, DialogueCatalog.DialogueGroup group) {
		if (speaker instanceof Villager villager) {
			if (villager.isBaby()) return BABY_DIALOGUES.contains(group.id());
			if (BABY_DIALOGUES.contains(group.id())) return false;
			if (COSMETIC_RECIPIENT_DIALOGUES.contains(group.id())) return true;
		}
		return group.speaker().equals(speakerType(speaker));
	}

	private static boolean ready(String key, long cooldown) {
		return ticks - COOLDOWNS.getOrDefault(key, Long.MIN_VALUE / 2) >= cooldown;
	}

	private static boolean isBusy(LivingEntity entity) {
		ActiveSound sound = ACTIVE_SOUNDS.get(entity.getUUID());
		return BUSY_UNTIL.getOrDefault(entity.getUUID(), 0L) > ticks || sound != null && sound.endTick > ticks;
	}

	private static boolean isPlaying(LivingEntity entity, String groupId) {
		ActiveSound sound = ACTIVE_SOUNDS.get(entity.getUUID());
		return sound != null && sound.endTick > ticks && sound.groupId.equals(groupId);
	}

	private static boolean hasDamageLock(LivingEntity entity) {
		ActiveSound sound = ACTIVE_SOUNDS.get(entity.getUUID());
		return sound != null && sound.endTick > ticks && DAMAGE_LOCK_DIALOGUES.contains(sound.groupId);
	}

	private static void markBusy(LivingEntity entity, long duration) {
		BUSY_UNTIL.put(entity.getUUID(), ticks + duration);
	}

	private static void holdListener(LivingEntity listener, Entity speaker, long duration) {
		markBusy(listener, duration);
		if (listener instanceof Mob mob) {
			Vec3 position = speaker.getEyePosition();
			SPEECH_TARGETS.put(listener.getUUID(), new SpeechTarget(speaker.getUUID(), position, ticks + duration, true));
			holdMob(mob, position);
		}
	}

	private static void holdMob(Mob mob, Vec3 position) {
		mob.getNavigation().stop();
		mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
		mob.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
		if (mob.onGround() && !mob.isPassenger()) {
			Vec3 movement = mob.getDeltaMovement();
			mob.setDeltaMovement(0.0, movement.y, 0.0);
		}
		faceMob(mob, position);
	}

	private static void faceMob(Mob mob, Vec3 position) {
		if (position == null) return;
		mob.getLookControl().setLookAt(position.x, position.y, position.z, 15.0F, 10.0F);
		double x = position.x - mob.getX();
		double y = position.y - mob.getEyeY();
		double z = position.z - mob.getZ();
		double horizontal = Math.sqrt(x * x + z * z);
		if (horizontal > 0.01) {
			float targetYaw = (float) (net.minecraft.util.Mth.atan2(z, x) * 180.0 / Math.PI) - 90.0F;
			float headOffset = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(targetYaw - mob.yBodyRot), -65.0F, 65.0F);
			float bodyYaw = easedRotation(mob.yBodyRot, targetYaw - headOffset, 12.0F, 0.35F);
			float headYaw = easedRotation(mob.getYHeadRot(), targetYaw, 15.0F, 0.4F);
			mob.setYRot(bodyYaw);
			mob.setYBodyRot(bodyYaw);
			mob.setYHeadRot(headYaw);
		}
		float targetPitch = (float) (-(net.minecraft.util.Mth.atan2(y, horizontal) * 180.0 / Math.PI));
		mob.setXRot(easedRotation(mob.getXRot(), net.minecraft.util.Mth.clamp(targetPitch, -90.0F, 90.0F), 10.0F, 0.3F));
	}

	private static float easedRotation(float current, float target, float maximumStep, float proportion) {
		float distance = Math.abs(net.minecraft.util.Mth.wrapDegrees(target - current));
		return net.minecraft.util.Mth.approachDegrees(current, target, net.minecraft.util.Mth.clamp(distance * proportion, 0.2F, maximumStep));
	}

	private static void interrupt(LivingEntity speaker) {
		ACTIVE_SOUNDS.remove(speaker.getUUID());
		DialogueSoundState.start(speaker.getUUID(), "", 0, 0);
		DialogueAnimationState.start(speaker.getUUID(), "", 0, 0);
		DialogueSubtitleState.start(speaker.getUUID(), "", 0, 0);
		BUSY_UNTIL.remove(speaker.getUUID());
		SPEECH_TARGETS.remove(speaker.getUUID());
	}

	private static void maintainSpeechTargets(Level level) {
		SPEECH_TARGETS.entrySet().removeIf(entry -> {
			SpeechTarget speech = entry.getValue();
			if (speech.untilTick <= ticks) return true;
			Entity speaker = level.getEntity(entry.getKey());
			if (!(speaker instanceof Mob mob) || !mob.isAlive()) return true;
			Entity target = speech.targetId == null ? null : level.getEntity(speech.targetId);
			Vec3 position = target != null && target.isAlive() ? target.getEyePosition() : speech.position;
			if (speech.lockMovement) holdMob(mob, position);
			else faceMob(mob, position);
			return false;
		});
	}

	private static void processPendingSpeech() {
		Level level = Minecraft.getInstance().level;
		if (level == null) return;
		PENDING_SPEECH.removeIf(pending -> {
			if (pending.dueTick > ticks) return false;
			Entity speaker = level.getEntity(pending.speakerId);
			Entity target = pending.targetId == null ? null : level.getEntity(pending.targetId);
			if (speaker instanceof LivingEntity living && living.isAlive()) {
				DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(pending.dialogueId);
				boolean played = group != null && (pending.sharedAdult
					? playSharedId(living, pending.dialogueId, "queued:" + living.getUUID() + ":" + pending.dialogueId, 1L, target)
					: playId(living, pending.dialogueId, "queued:" + living.getUUID() + ":" + pending.dialogueId, 1L, target));
				if (played && target instanceof LivingEntity listener) {
					holdListener(listener, living, group.durationTicks());
				}
			}
			return true;
		});
	}

	private static boolean isWooly(Sheep sheep) {
		String name = sheep.getName().getString().toLowerCase(Locale.ROOT);
		return name.equals("wooly") || name.equals("wooly the sheep");
	}

	private static void normalizeSpecialEntity(Entity entity) {
		Component customName = entity.getCustomName();
		if (customName == null) return;
		String name = customName.getString();
		String prefix = "{\"text\":\"";
		if (!name.startsWith(prefix) || !name.endsWith("\"}")) return;
		String decoded = name.substring(prefix.length(), name.length() - 2);
		if (Set.of("Mayor Villager", "The Mayor", "Testificate Man", "Villager #5", "Villager #9",
				"Villager Unreachable", "Can't Catch Me!", "Wooly The Sheep").contains(decoded)) {
			entity.setCustomName(Component.literal(decoded));
		}
	}

	private static String orderedPair(UUID first, UUID second) {
		return first.compareTo(second) < 0 ? first + ":" + second : second + ":" + first;
	}

	private static String meetDialogue(CastProfile profile) {
		return switch (profile) {
			case MAYOR -> "lyatyf";
			case NUMBER_5 -> "kmvqxe";
			case NUMBER_9 -> "sifqsj";
			case TESTIFICATE_MAN -> "zvamyb";
			default -> null;
		};
	}

	public static CastProfile cast(Villager villager) {
		String name = villager.getName().getString().toLowerCase(Locale.ROOT);
		if (name.equals("mayor") || name.equals("the mayor") || name.equals("mayor villager")) return CastProfile.MAYOR;
		if (name.equals("testificate man")) return CastProfile.TESTIFICATE_MAN;
		if (name.equals("villager #5") || name.equals("villager number 5")) return CastProfile.NUMBER_5;
		if (name.equals("villager #9") || name.equals("villager number 9")) return CastProfile.NUMBER_9;
		if (name.equals("villager unreachable") || name.equals("can't catch me!")) return CastProfile.UNREACHABLE;
		return CastProfile.VILLAGER;
	}

	private static String speakerType(LivingEntity speaker) {
		if (speaker instanceof Villager villager) {
			return switch (cast(villager)) {
				case VILLAGER -> "villager";
				case MAYOR -> "mayor";
				case TESTIFICATE_MAN -> "testificate_man";
				case NUMBER_5 -> "number_5";
				case NUMBER_9 -> "number_9";
				case UNREACHABLE -> "unreachable";
			};
		}
		if (speaker instanceof WanderingTrader) return "wandering_trader";
		if (speaker instanceof Sheep sheep && isWooly(sheep)) return "wooly";
		return "";
	}

	public enum CastProfile {
		VILLAGER("xfpjxq", "lvigit", "clbjww", "wyvzhk", "vevdkl"),
		MAYOR("dpwhhs", "xxehbq", "njyapy", "ssbhiv", "ltdnvy"),
		TESTIFICATE_MAN("nmwmrz", "luoibc", "mpbnsm", "fzoqwd", "fzoqwd"),
		NUMBER_5("xccwah", "legnsy", "sclaoa", "behifz", "behifz"),
		NUMBER_9("kzogzi", "ezgbfw", "snnkrl", "wrbvvp", "asuufu"),
		UNREACHABLE("eltxge", "eltxge", "eltxge", "wyvzhk", "vevdkl");

		private final String approach;
		private final String idle;
		private final String trade;
		private final String hurt;
		private final String attack;

		CastProfile(String approach, String idle, String trade, String hurt, String attack) {
			this.approach = approach;
			this.idle = idle;
			this.trade = trade;
			this.hurt = hurt;
			this.attack = attack;
		}
	}

	private static final class PlayerObservation {
		private Vec3 lastPosition = Vec3.ZERO;
		private int stillTicks;
		private int stareTicks;
		private int breakStreak;
		private long lastBreakTick = Long.MIN_VALUE / 2;
		private String lastGameMode;
		private String lastPlayerContext;
		private BlockPos lastGroundPos = BlockPos.ZERO;
		private String lastGroundBlock = "";
	}

	private record VillagerSnapshot(boolean baby, String profession, int level, boolean working, String name,
			boolean poisoned, boolean slowed, boolean weakened, boolean suffocating) {
	}

	private record SpeechTarget(UUID targetId, Vec3 position, long untilTick, boolean lockMovement) {
	}

	private record PendingSpeech(UUID speakerId, String dialogueId, UUID targetId, long dueTick, boolean sharedAdult) {
	}

	private record ActiveSound(String groupId, long endTick) {
	}

	private static final class TradeSession {
		private final UUID traderId;
		private final long createdTick;
		private boolean opened;
		private boolean completed;
		private int lastUsesTotal;

		private TradeSession(UUID traderId, long createdTick) {
			this.traderId = traderId;
			this.createdTick = createdTick;
		}
	}
}
