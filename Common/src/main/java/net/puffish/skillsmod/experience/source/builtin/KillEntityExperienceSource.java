package net.puffish.skillsmod.experience.source.builtin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.puffish.skillsmod.SkillsMod;
import net.puffish.skillsmod.api.SkillsAPI;
import net.puffish.skillsmod.api.calculation.Calculation;
import net.puffish.skillsmod.api.calculation.operation.OperationFactory;
import net.puffish.skillsmod.api.calculation.prototype.BuiltinPrototypes;
import net.puffish.skillsmod.api.calculation.prototype.Prototype;
import net.puffish.skillsmod.api.config.ConfigContext;
import net.puffish.skillsmod.api.experience.source.ExperienceSource;
import net.puffish.skillsmod.api.experience.source.ExperienceSourceConfigContext;
import net.puffish.skillsmod.api.experience.source.ExperienceSourceDisposeContext;
import net.puffish.skillsmod.api.json.JsonElement;
import net.puffish.skillsmod.api.json.JsonObject;
import net.puffish.skillsmod.api.util.Problem;
import net.puffish.skillsmod.api.util.Result;
import net.puffish.skillsmod.calculation.LegacyCalculation;
import net.puffish.skillsmod.calculation.operation.LegacyOperationRegistry;
import net.puffish.skillsmod.calculation.operation.builtin.AttributeOperation;
import net.puffish.skillsmod.calculation.operation.builtin.DamageTypeCondition;
import net.puffish.skillsmod.calculation.operation.builtin.EffectOperation;
import net.puffish.skillsmod.calculation.operation.builtin.EntityTypeCondition;
import net.puffish.skillsmod.calculation.operation.builtin.ItemStackCondition;
import net.puffish.skillsmod.calculation.operation.builtin.legacy.LegacyDamageTypeTagCondition;
import net.puffish.skillsmod.calculation.operation.builtin.legacy.LegacyEntityTypeTagCondition;
import net.puffish.skillsmod.calculation.operation.builtin.legacy.LegacyItemTagCondition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class KillEntityExperienceSource implements ExperienceSource {
	private static final Identifier ID = SkillsMod.createIdentifier("kill_entity");
	private static final Prototype<Data> PROTOTYPE = Prototype.create(ID);

	static {
		PROTOTYPE.registerOperation(
				SkillsMod.createIdentifier("player"),
				BuiltinPrototypes.PLAYER,
				OperationFactory.create(Data::player)
		);
		PROTOTYPE.registerOperation(
				SkillsMod.createIdentifier("is_on_team"),
				BuiltinPrototypes.BOOLEAN,
				OperationFactory.create(data -> data.player.getScoreboardTeam() != null)
		);
		PROTOTYPE.registerOperation(
				SkillsMod.createIdentifier("weapon_item_stack"),
				BuiltinPrototypes.ITEM_STACK,
				OperationFactory.create(Data::weapon)
		);
		PROTOTYPE.registerOperation(
				SkillsMod.createIdentifier("killed_living_entity"),
				BuiltinPrototypes.LIVING_ENTITY,
				OperationFactory.create(Data::entity)
		);
		PROTOTYPE.registerOperation(
				SkillsMod.createIdentifier("damage_source"),
				BuiltinPrototypes.DAMAGE_SOURCE,
				OperationFactory.create(Data::damageSource)
		);
		PROTOTYPE.registerOperation(
				SkillsMod.createIdentifier("dropped_experience"),
				BuiltinPrototypes.NUMBER,
				OperationFactory.create(Data::entityDroppedXp)
		);

		// Backwards compatibility.
		var legacy = new LegacyOperationRegistry<>(PROTOTYPE);
		legacy.registerBooleanFunction(
				"entity",
				EntityTypeCondition::parse,
				data -> data.entity().getType()
		);
		legacy.registerBooleanFunction(
				"entity_tag",
				LegacyEntityTypeTagCondition::parse,
				data -> data.entity().getType()
		);
		legacy.registerBooleanFunction(
				"weapon",
				ItemStackCondition::parse,
				Data::weapon
		);
		legacy.registerBooleanFunction(
				"weapon_nbt",
				ItemStackCondition::parse,
				Data::weapon
		);
		legacy.registerBooleanFunction(
				"weapon_tag",
				LegacyItemTagCondition::parse,
				Data::weapon
		);
		legacy.registerBooleanFunction(
				"damage_type",
				DamageTypeCondition::parse,
				data -> data.damageSource().getType()
		);
		legacy.registerBooleanFunction(
				"damage_type_tag",
				LegacyDamageTypeTagCondition::parse,
				data -> data.damageSource().getType()
		);
		legacy.registerNumberFunction(
				"player_effect",
				effect -> (double) (effect.getAmplifier() + 1),
				EffectOperation::parse,
				Data::player
		);
		legacy.registerNumberFunction(
				"player_attribute",
				EntityAttributeInstance::getValue,
				AttributeOperation::parse,
				Data::player
		);
		legacy.registerNumberFunction(
				"entity_dropped_experience",
				Data::entityDroppedXp
		);
		legacy.registerNumberFunction(
				"entity_max_health",
				data -> (double) data.entity().getMaxHealth()
		);
	}

	private final Calculation<Data> calculation;
	private final Boolean teamSharedExperience;
	private final AntiFarming antiFarming;

	private static final double MAX_TEAMMATE_SHARE_DISTANCE = 100.0;

	private KillEntityExperienceSource(Calculation<Data> calculation, Boolean teamSharedExperience, AntiFarming antiFarming) {
		this.calculation = calculation;
		this.teamSharedExperience = teamSharedExperience;
		this.antiFarming = antiFarming;
	}

	public static void register() {
		SkillsAPI.registerExperienceSource(
				ID,
				KillEntityExperienceSource::parse
		);
	}

	private static Result<KillEntityExperienceSource, Problem> parse(ExperienceSourceConfigContext context) {
		return context.getData()
				.andThen(JsonElement::getAsObject)
				.andThen(rootObject -> parse(rootObject, context));
	}
	private static Result<KillEntityExperienceSource, Problem> parse(JsonObject rootObject, ConfigContext context) {
		var problems = new ArrayList<Problem>();

		var optCalculation = LegacyCalculation.parse(rootObject, PROTOTYPE, context)
				.ifFailure(problems::add)
				.getSuccess();

		var optTeamSharedExperience = rootObject.getBoolean("team_shared_experience")
				.getSuccess() // ignore failure because this property is optional
				.orElse(false);

		var optAntiFarming = rootObject.get("anti_farming")
				.getSuccess() // ignore failure because this property is optional
				.flatMap(element -> AntiFarming.parse(element)
						.ifFailure(problems::add)
						.getSuccess()
						.flatMap(Function.identity())
				);

		if (problems.isEmpty()) {
			return Result.success(new KillEntityExperienceSource(
					optCalculation.orElseThrow(),
					optTeamSharedExperience,
					optAntiFarming.orElse(null)
			));
		} else {
			return Result.failure(Problem.combine(problems));
		}
	}

	public record AntiFarming(int limitPerChunk, int resetAfterSeconds) {
		public static Result<Optional<AntiFarming>, Problem> parse(JsonElement rootElement) {
			return rootElement.getAsObject()
					.andThen(AntiFarming::parse);
		}

		public static Result<Optional<AntiFarming>, Problem> parse(JsonObject rootObject) {
			var problems = new ArrayList<Problem>();

			// Deprecated
			var enabled = rootObject.getBoolean("enabled")
					.getSuccess()
					.orElse(true);

			var optLimitPerChunk = rootObject.getInt("limit_per_chunk")
					.ifFailure(problems::add)
					.getSuccess();

			var optResetAfterSeconds = rootObject.getInt("reset_after_seconds")
					.ifFailure(problems::add)
					.getSuccess();

			if (problems.isEmpty()) {
				if (enabled) {
					return Result.success(Optional.of(new AntiFarming(
							optLimitPerChunk.orElseThrow(),
							optResetAfterSeconds.orElseThrow()
					)));
				} else {
					return Result.success(Optional.empty());
				}
			} else {
				return Result.failure(Problem.combine(problems));
			}
		}
	}

	private record Data(ServerPlayerEntity player, LivingEntity entity, ItemStack weapon, DamageSource damageSource, double entityDroppedXp) { }

	public int getValue(ServerPlayerEntity player, LivingEntity entity, ItemStack weapon, DamageSource damageSource, double entityDroppedXp) {
		return (int) Math.round(calculation.evaluate(
				new Data(player, entity, weapon, damageSource, entityDroppedXp)
		));
	}

	public Boolean isTeamSharedExperience() {
		return teamSharedExperience;
	}

	public Optional<AntiFarming> getAntiFarming() {
		return Optional.ofNullable(antiFarming);
	}

	public int applyTeamSharedExperience(ServerPlayerEntity player, int xpValue) {
		var playerTeam = player.getScoreboardTeam();
		if (!teamSharedExperience || playerTeam == null) {
			return xpValue;
		}

		List<ServerPlayerEntity> teammatesInRange = player.getServerWorld()
				.getPlayers(p -> p.isTeamPlayer(playerTeam))
				.stream().filter(p -> p != player && isTeammateInShareRange(player, p))
				.collect(Collectors.toCollection(ArrayList::new));

		int sharedXpValue = xpValue / teammatesInRange.size();
		teammatesInRange.forEach(p -> SkillsAPI.updateExperienceSources(p, KillEntityExperienceSource.class, es -> sharedXpValue));

		return sharedXpValue;
	}

	private static boolean isTeammateInShareRange(ServerPlayerEntity player, ServerPlayerEntity teammate) {
		return player.getPos().distanceTo(teammate.getPos()) <= MAX_TEAMMATE_SHARE_DISTANCE;
	}

	@Override
	public void dispose(ExperienceSourceDisposeContext context) {
		// Nothing to do.
	}
}
