package plus.mygo.whotickstoolong.mixin;

import net.minecraft.core.BlockPos;
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
 * <p>All three of {@code chunkPosition()}, {@code getPos()} and {@code ChunkPos.pack} read
 * cached fields or do plain arithmetic, so the instrumentation allocates nothing.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

	@Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
	private void wttl$enterEntityTick(Entity entity, CallbackInfo ci) {
		TickContext.enter(entity.chunkPosition().pack(), TickPhase.ENTITY);
	}

	@Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
	private void wttl$exitEntityTick(Entity entity, CallbackInfo ci) {
		TickContext.exit();
	}

	@Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("HEAD"))
	private void wttl$enterRandomTick(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
		TickContext.enter(chunk.getPos().pack(), TickPhase.RANDOM_TICK);
	}

	@Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("RETURN"))
	private void wttl$exitRandomTick(LevelChunk chunk, int tickSpeed, CallbackInfo ci) {
		TickContext.exit();
	}

	@Inject(method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"))
	private void wttl$enterScheduledBlockTick(BlockPos pos, Block type, CallbackInfo ci) {
		TickContext.enter(ChunkPos.pack(pos), TickPhase.SCHEDULED_BLOCK);
	}

	@Inject(method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("RETURN"))
	private void wttl$exitScheduledBlockTick(BlockPos pos, Block type, CallbackInfo ci) {
		TickContext.exit();
	}

	@Inject(method = "tickFluid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;)V", at = @At("HEAD"))
	private void wttl$enterScheduledFluidTick(BlockPos pos, Fluid type, CallbackInfo ci) {
		TickContext.enter(ChunkPos.pack(pos), TickPhase.SCHEDULED_FLUID);
	}

	@Inject(method = "tickFluid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;)V", at = @At("RETURN"))
	private void wttl$exitScheduledFluidTick(BlockPos pos, Fluid type, CallbackInfo ci) {
		TickContext.exit();
	}
}
