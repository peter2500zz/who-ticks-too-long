package plus.mygo.whotickstoolong.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import plus.mygo.whotickstoolong.profile.TickContext;
import plus.mygo.whotickstoolong.profile.TickPhase;
import plus.mygo.whotickstoolong.profile.deep.DeepProbe;

/**
 * Publishes the chunk behind each block entity tick.
 *
 * <p>Targets the bound ticker rather than the rebindable wrapper that {@code Level} actually
 * iterates: the wrapper only delegates, so instrumenting both would count every block entity
 * twice.
 *
 * <p>The class is private, so it has to be named by string rather than by class literal.
 * {@code getPos()} is the same call vanilla already makes for every ticker on this path, so
 * it is known to be cheap and allocation-free; {@code getType()} is only called for chunks
 * under deep inspection.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {

	@Shadow
	public abstract BlockPos getPos();

	@Shadow
	public abstract String getType();

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void wttl$enterBlockEntityTick(CallbackInfo ci) {
		BlockPos pos = this.getPos();
		long chunkKey = ChunkPos.pack(pos);
		TickContext.enter(chunkKey, TickPhase.BLOCK_ENTITY);

		if (DeepProbe.isTarget(chunkKey)) {
			DeepProbe.begin(chunkKey, TickPhase.BLOCK_ENTITY, this.getType(), pos);
		}
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void wttl$exitBlockEntityTick(CallbackInfo ci) {
		DeepProbe.end();
		TickContext.exit();
	}
}
