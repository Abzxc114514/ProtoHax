package dev.sora.relay.cheat.module.impl.combat

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.cheat.value.NamedChoice
import dev.sora.relay.game.entity.EntityPlayer
import dev.sora.relay.game.event.EventTick
import dev.sora.relay.game.utils.Rotation
import dev.sora.relay.game.utils.getRotationDifference
import dev.sora.relay.game.utils.toRotation
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Serverside aim assist. It only changes the rotation sent to the server
 * ([dev.sora.relay.game.entity.EntityLocalPlayer.silentRotation]), the camera
 * on the client keeps following the player's own input.
 */
class ModuleAimAssist : CheatModule("AimAssist", CheatCategory.COMBAT) {

	private var rangeValue by floatValue("Range", 4.5f, 2f..8f)

	private var softLockValue by boolValue("SoftLock", true)
	/**
	 * maximum angle between the crosshair and the target before soft lock takes over
	 */
	private var triggerRangeValue by floatValue("TriggerRange", 20f, 1f..90f).visible { softLockValue }

	/**
	 * body part the aim assist aims at
	 */
	private var aimPositionValue by listValue("AimPosition", AimPosition.values(), AimPosition.HEAD)

	private var randomValue by boolValue("Random", false)
	/**
	 * radius in blocks of the random offset around the aim position
	 */
	private var randomDistanceValue by floatValue("RandomDistance", 0.3f, 0f..1f).visible { randomValue }

	private val handleTick = handle<EventTick> {
		val player = session.player
		val targets = moduleManager.getModule(ModuleTargets::class.java)
		val rangeSq = rangeValue * rangeValue
		val crosshair = Rotation(player.rotationYaw, player.rotationPitch)
		val target = session.level.entityMap.values
			.filterIsInstance<EntityPlayer>()
			.filter { it.distanceSq(player) <= rangeSq && with(targets) { it.isTarget() } }
			.filter { !softLockValue || getRotationDifference(crosshair, toRotation(player.vec3Position, anchorFor(it))) <= triggerRangeValue }
			.minByOrNull { it.distanceSq(player) } ?: return@handle

		val anchor = anchorFor(target)

		val aimPoint = if (randomValue) randomOffset(anchor) else anchor
		player.silentRotation = toRotation(player.vec3Position, aimPoint)
	}

	private fun anchorFor(target: EntityPlayer) = partPosition(target, aimPositionValue)

	private fun partPosition(target: EntityPlayer, part: AimPosition): Vector3f {
		return when (part) {
			AimPosition.HEAD -> target.vec3Position
			AimPosition.BODY -> target.vec3PositionFeet.add(0f, 1f, 0f)
			AimPosition.FEET -> target.vec3PositionFeet
		}
	}

	/**
	 * picks a point inside a sphere of [randomDistanceValue] blocks around the anchor,
	 * the direction and the radius are driven by smoothed value noise so the aim point
	 * wanders instead of jumping to a new random spot every tick
	 */
	private fun randomOffset(anchor: Vector3f): Vector3f {
		val time = System.currentTimeMillis() / 200.0
		val x = noise(SEED_X, time)
		val y = noise(SEED_Y, time)
		val z = noise(SEED_Z, time)
		val length = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-4)
		val magnitude = randomDistanceValue * ((noise(SEED_RADIUS, time * 0.5) + 1.0) / 2.0)

		return anchor.add(
			(x / length * magnitude).toFloat(),
			(y / length * magnitude).toFloat(),
			(z / length * magnitude).toFloat()
		)
	}

	private fun noise(seed: Int, x: Double): Double {
		val i = floor(x).toInt()
		val f = x - i
		val u = f * f * (3.0 - 2.0 * f)
		return lerp(hash(seed, i), hash(seed, i + 1), u)
	}

	private fun hash(seed: Int, x: Int): Double {
		var h = seed * 0x27d4eb2d + x * 0x165667b1
		h = (h xor (h ushr 15)) * -2048144777
		h = h xor (h ushr 13)
		return (h ushr 1).toDouble() / Int.MAX_VALUE * 2.0 - 1.0
	}

	private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

	private enum class AimPosition(override val choiceName: String) : NamedChoice {
		HEAD("Head"),
		BODY("Body"),
		FEET("Feet")
	}

	companion object {
		private const val SEED_X = 0x1a2b3c4d
		private const val SEED_Y = 0x5e6f7a8b
		private const val SEED_Z = 0x13579bdf
		private const val SEED_RADIUS = 0x3f4a5b6c
	}
}
