package plus.mygo.whotickstoolong.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import plus.mygo.whotickstoolong.profile.TickContext;
import plus.mygo.whotickstoolong.profile.TickPhase;
import plus.mygo.whotickstoolong.profile.deep.DeepProbe;

/**
 * Publishes which chunk the server thread is working on, for the three kinds of level work
 * that carry a position: scheduled block ticks, scheduled fluid ticks, and the random ticks
 * a chunk receives.
 *
 * <p>Every injection is a plain HEAD or RETURN callback. Nothing is redirected, overwritten
 * or cancelled, so any number of other mods can target the same methods without conflict.
 * None of the call sites nest inside one another, because vanilla runs these phases one
 * after another rather than inside each other.
 *
 * <p>Each site asks {@link DeepProbe#isTarget} before it looks up a registry name, so the
 * only cost paid for objects outside an inspected chunk is a comparison. All of
 * {@code chunkPosition()}, {@code getPos()} and {@code ChunkPos.pack} read cached fields or
 * do plain arithmetic, so the always-on instrumentation allocates nothing.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

	@Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
	private void wttl$enterEntityTick(Entity entity, CallbackInfo ci) {
		long chunkKey = entity.chunkPosition().pack();
		TickContext.enter(chunkKey, TickPhase.ENTITY);

		if (DeepProbe.isTarget(chunkKey)) {
			BlockPos pos = entity.blockPosition();
			DeepProbe.begin(chunkKey, TickPhase.ENTITY, entity.typeHolder().getRegisteredName(),
					entity.getId(), pos.getX(), pos.getY(), pos.getZ());
		}
	}

	@Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
	private void wttl$exitEntityTick(Entity entity, CallbackInfo ci) {
		DeepProbe.end();
		TickContext.exit();
	}

	@Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("HEAD"))
	private void wttl$enterRandomTick(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
		ChunkPos chunkPos = chunk.getPos();
		long chunkKey = chunkPos.pack();
		TickContext.enter(chunkKey, TickPhase.RANDOM_TICK);

		if (DeepProbe.isTarget(chunkKey)) {
			// Random ticks are a batch over the whole chunk with no individual object to
			// name, so the chunk's own origin stands in as the position.
			DeepProbe.begin(chunkKey, TickPhase.RANDOM_TICK, "(whole chunk)", 0,
					chunkPos.getMinBlockX(), 0, chunkPos.getMinBlockZ());
		}
	}

	@Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("RETURN"))
	private void wttl$exitRandomTick(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
		DeepProbe.end();
		TickContext.exit();
	}

	@Inject(method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"))
	private void wttl$enterScheduledBlockTick(BlockPos pos, Block type, CallbackInfo ci) {
		long chunkKey = ChunkPos.pack(pos);
		TickContext.enter(chunkKey, TickPhase.SCHEDULED_BLOCK);

		if (DeepProbe.isTarget(chunkKey)) {
			DeepProbe.begin(chunkKey, TickPhase.SCHEDULED_BLOCK, BuiltInRegistries.BLOCK.getKey(type), pos);
		}
	}

	@Inject(method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("RETURN"))
	private void wttl$exitScheduledBlockTick(BlockPos pos, Block type, CallbackInfo ci) {
		DeepProbe.end();
		TickContext.exit();
	}

	@Inject(method = "tickFluid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;)V", at = @At("HEAD"))
	private void wttl$enterScheduledFluidTick(BlockPos pos, Fluid type, CallbackInfo ci) {
		long chunkKey = ChunkPos.pack(pos);
		TickContext.enter(chunkKey, TickPhase.SCHEDULED_FLUID);

		if (DeepProbe.isTarget(chunkKey)) {
			DeepProbe.begin(chunkKey, TickPhase.SCHEDULED_FLUID, BuiltInRegistries.FLUID.getKey(type), pos);
		}
	}

	@Inject(method = "tickFluid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;)V", at = @At("RETURN"))
	private void wttl$exitScheduledFluidTick(BlockPos pos, Fluid type, CallbackInfo ci) {
		DeepProbe.end();
		TickContext.exit();
	}
}
