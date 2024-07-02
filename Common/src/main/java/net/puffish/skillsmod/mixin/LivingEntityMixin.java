package net.puffish.skillsmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.puffish.skillsmod.access.DamageSourceAccess;
import net.puffish.skillsmod.access.WorldChunkAccess;
import net.puffish.skillsmod.api.SkillsAPI;
import net.puffish.skillsmod.experience.source.builtin.KillEntityExperienceSource;
import net.puffish.skillsmod.experience.source.builtin.TakeDamageExperienceSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	@Unique
	private int entityDroppedXp = 0;

	@Inject(method = "damage", at = @At("TAIL"))
	private void injectAtDamage(DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
		if (((LivingEntity) (Object) this) instanceof ServerPlayerEntity serverPlayer) {
			SkillsAPI.updateExperienceSources(
					serverPlayer,
					TakeDamageExperienceSource.class,
					experienceSource -> experienceSource.getValue(serverPlayer, damage, source)
			);
		}
	}

	@Inject(method = "drop", at = @At("TAIL"))
	private void injectAtDrop(DamageSource source, CallbackInfo ci) {
		if (source.getAttacker() instanceof ServerPlayerEntity player) {
			var entity = ((LivingEntity) (Object) this);
			var weapon = ((DamageSourceAccess) source).getWeapon().orElse(ItemStack.EMPTY);

			WorldChunkAccess worldChunk = ((WorldChunkAccess) entity.getWorld().getWorldChunk(entity.getBlockPos()));
			worldChunk.antiFarmingCleanupOutdated();
			SkillsAPI.updateExperienceSources(player, experienceSource -> {
				if (experienceSource instanceof KillEntityExperienceSource entityExperienceSource) {
					if (entityExperienceSource.getAntiFarming()
							.map(worldChunk::antiFarmingAddAndCheck)
							.orElse(true)
					) {
						int xpValue = entityExperienceSource.getValue(player, entity, weapon, source, entityDroppedXp);
						return entityExperienceSource.applyTeamSharedExperience(player, xpValue);
					}
				}
				return 0;
			});
		}
	}

	@ModifyArg(method = "dropXp", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ExperienceOrbEntity;spawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/Vec3d;I)V"), index = 2)
	private int injectAtDropXp(int droppedXp) {
		entityDroppedXp = droppedXp;
		return droppedXp;
	}
}
