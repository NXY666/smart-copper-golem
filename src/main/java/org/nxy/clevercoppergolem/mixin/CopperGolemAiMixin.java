package org.nxy.clevercoppergolem.mixin;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.animal.golem.CopperGolemAi;
import net.minecraft.world.entity.animal.golem.CopperGolemState;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.nxy.clevercoppergolem.ContainerHelper;
import org.nxy.clevercoppergolem.ModMemoryModuleTypes;
import org.nxy.clevercoppergolem.SmartTransportItemsBetweenContainers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Mixin 用于替换铜傀儡的 AI 行为
 * 使用 SmartTransportItemsBetweenContainers 替代原版的 TransportItemsBetweenContainers
 */
@Mixin(CopperGolemAi.class)
public abstract class CopperGolemAiMixin {

	@Unique
	private static final float SPEED_MULTIPLIER_WHEN_PANICKING = 1.5F;

	@Unique
	private static final float SPEED_MULTIPLIER_WHEN_IDLING = 1.0F;

	@Unique
	private static final int TRANSPORT_ITEM_HORIZONTAL_SEARCH_RADIUS = 32;

	@Unique
	private static final int TRANSPORT_ITEM_VERTICAL_SEARCH_RADIUS = 16;

	@Unique
	private static final int TICK_TO_START_ON_REACHED_INTERACTION = 1;

	@Unique
	private static final int TICK_TO_PLAY_ON_REACHED_SOUND = 9;

	@Unique
	private static final Predicate<BlockState> TRANSPORT_ITEM_SOURCE_BLOCK = blockState -> blockState.is(BlockTags.COPPER_CHESTS);

	@Unique
	private static final Predicate<BlockState> TRANSPORT_ITEM_DESTINATION_BLOCK = blockState ->
		blockState.is(Blocks.CHEST) ||
			blockState.is(Blocks.TRAPPED_CHEST) ||
			blockState.is(Blocks.BARREL) ||
			blockState.is(BlockTags.SHULKER_BOXES);

	/**
	 * @author clever-copper-golem
	 * @reason 替换 brainProvider 以添加自定义记忆模块
	 */
	@Overwrite
	public static Brain.Provider<CopperGolem> brainProvider() {
		ImmutableList<SensorType<? extends Sensor<? super CopperGolem>>> sensorTypes = ImmutableList.of(
			SensorType.NEAREST_LIVING_ENTITIES,
			SensorType.HURT_BY
		);

		// 添加自定义记忆模块类型
		ImmutableList<MemoryModuleType<?>> memoryTypes = ImmutableList.of(
			MemoryModuleType.IS_PANICKING,
			MemoryModuleType.HURT_BY,
			MemoryModuleType.HURT_BY_ENTITY,
			MemoryModuleType.NEAREST_LIVING_ENTITIES,
			MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
			MemoryModuleType.WALK_TARGET,
			MemoryModuleType.LOOK_TARGET,
			MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
			MemoryModuleType.PATH,
			MemoryModuleType.GAZE_COOLDOWN_TICKS,
			MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS,
			MemoryModuleType.VISITED_BLOCK_POSITIONS,
			MemoryModuleType.UNREACHABLE_TRANSPORT_BLOCK_POSITIONS,
			MemoryModuleType.DOORS_TO_CLOSE,
			ModMemoryModuleTypes.INSTANCE.getCOPPER_GOLEM_DEEP_MEMORY() // 自定义记忆模块
		);

		return Brain.provider(memoryTypes, sensorTypes);
	}

	/**
	 * @author clever-copper-golem
	 * @reason 替换 makeBrain 以使用自定义行为
	 */
	@Overwrite
	public static Brain<?> makeBrain(Brain<CopperGolem> brain) {
		initCoreActivity(brain);
		initIdleActivity(brain);
		brain.setCoreActivities(Set.of(Activity.CORE));
		brain.setDefaultActivity(Activity.IDLE);
		brain.useDefaultActivity();
		return brain;
	}

	@Unique
	private static void initCoreActivity(Brain<CopperGolem> brain) {
		brain.addActivity(
			Activity.CORE,
			0,
			ImmutableList.of(
				new AnimalPanic<>(SPEED_MULTIPLIER_WHEN_PANICKING),
				new LookAtTargetSink(45, 90),
				new MoveToTargetSink(),
				InteractWithDoor.create(),
				new CountDownCooldownTicks(MemoryModuleType.GAZE_COOLDOWN_TICKS),
				new CountDownCooldownTicks(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS)
			)
		);
	}

	@Unique
	private static void initIdleActivity(Brain<CopperGolem> brain) {
		brain.addActivity(
			Activity.IDLE,
			ImmutableList.of(
				Pair.of(
					0,
					// 使用自定义的 SmartTransportItemsBetweenContainers
					new SmartTransportItemsBetweenContainers(
						SPEED_MULTIPLIER_WHEN_IDLING,
						TRANSPORT_ITEM_SOURCE_BLOCK,
						TRANSPORT_ITEM_DESTINATION_BLOCK,
						TRANSPORT_ITEM_HORIZONTAL_SEARCH_RADIUS,
						TRANSPORT_ITEM_VERTICAL_SEARCH_RADIUS,
						getTargetReachedInteractions(),
						onTravelling(),
						shouldQueueForTarget()
					)
				),
				Pair.of(1, SetEntityLookTargetSometimes.create(EntityType.PLAYER, 6.0F, UniformInt.of(40, 80))),
				Pair.of(
					2,
					new RunOne<>(
						ImmutableMap.of(
							MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
							MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS, MemoryStatus.VALUE_PRESENT
						),
						ImmutableList.of(
							Pair.of(RandomStroll.stroll(SPEED_MULTIPLIER_WHEN_IDLING, 2, 2), 1),
							Pair.of(new DoNothing(30, 60), 1)
						)
					)
				)
			)
		);
	}

	@Unique
	private static Map<SmartTransportItemsBetweenContainers.ContainerInteractionState, SmartTransportItemsBetweenContainers.OnTargetReachedInteraction> getTargetReachedInteractions() {
		return Map.of(
			SmartTransportItemsBetweenContainers.ContainerInteractionState.PICKUP_ITEM,
			onReachedTargetInteraction(CopperGolemState.GETTING_ITEM, SoundEvents.COPPER_GOLEM_ITEM_GET),
			SmartTransportItemsBetweenContainers.ContainerInteractionState.PICKUP_NO_ITEM,
			onReachedTargetInteraction(CopperGolemState.GETTING_NO_ITEM, SoundEvents.COPPER_GOLEM_ITEM_NO_GET),
			SmartTransportItemsBetweenContainers.ContainerInteractionState.PLACE_ITEM,
			onReachedTargetInteraction(CopperGolemState.DROPPING_ITEM, SoundEvents.COPPER_GOLEM_ITEM_DROP),
			SmartTransportItemsBetweenContainers.ContainerInteractionState.PLACE_NO_ITEM,
			onReachedTargetInteraction(CopperGolemState.DROPPING_NO_ITEM, SoundEvents.COPPER_GOLEM_ITEM_NO_DROP)
		);
	}

	@Unique
	private static SmartTransportItemsBetweenContainers.OnTargetReachedInteraction onReachedTargetInteraction(
		CopperGolemState copperGolemState,
		@Nullable SoundEvent soundEvent
	) {
		return (pathfinderMob, transportItemTarget, ticksSinceReachingTarget) -> {
			if (pathfinderMob instanceof CopperGolem copperGolem) {
				Container container = transportItemTarget.getContainer();
				switch (ticksSinceReachingTarget) {
					// 1 tick: 打开容器
					case TICK_TO_START_ON_REACHED_INTERACTION -> {
						ContainerHelper.startOpen(container, copperGolem);
						copperGolem.setOpenedChestPos(transportItemTarget.getPos());
						copperGolem.setState(copperGolemState);
					}
					// 9 ticks: 播放声音
					case TICK_TO_PLAY_ON_REACHED_SOUND -> {
						if (soundEvent != null) {
							copperGolem.playSound(soundEvent);
						}
					}
					// 60 ticks: 关闭容器
					case SmartTransportItemsBetweenContainers.TARGET_INTERACTION_TIME -> {
						ContainerHelper.stopOpen(container, copperGolem);
						copperGolem.clearOpenedChestPos();
					}
				}
			}
		};
	}

	@Unique
	private static Consumer<PathfinderMob> onTravelling() {
		return pathfinderMob -> {
			if (pathfinderMob instanceof CopperGolem copperGolem) {
				copperGolem.clearOpenedChestPos();
				copperGolem.setState(CopperGolemState.IDLE);
			}
		};
	}

	@Unique
	private static Predicate<SmartTransportItemsBetweenContainers.TransportItemTarget> shouldQueueForTarget() {
		return transportItemTarget -> {
			if (transportItemTarget.getBlockEntity() instanceof ChestBlockEntity chestBlockEntity) {
				return !chestBlockEntity.getEntitiesWithContainerOpen().isEmpty();
			}
			return false;
		};
	}
}
