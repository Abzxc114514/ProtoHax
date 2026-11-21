package dev.sora.relay.cheat.module.impl.movement

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.cheat.value.Choice
import dev.sora.relay.game.event.EventPacketInbound
import dev.sora.relay.game.event.EventPacketOutbound
import dev.sora.relay.game.event.EventTick
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.Ability
import org.cloudburstmc.protocol.bedrock.data.AbilityLayer
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.PlayerPermission
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermission
import org.cloudburstmc.protocol.bedrock.packet.RequestAbilityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket
import kotlin.math.cos
import kotlin.math.sin

class ModuleAdvancedFly : CheatModule("AdvancedFly", CheatCategory.MOVEMENT) {

	private var modeValue by choiceValue("Mode", arrayOf(BoatFly(), Vanilla(), Damage()), "BoatFly")
	private var speedValue by floatValue("Speed", 1f, 0.1f..5f)

	private open inner class BoatFly : Choice("BoatFly") {

		private val handleTick = handle<EventTick> {
			if (!session.netSessionInitialized) return@handle

			val player = session.player
			val boat = player.rideEntity ?: return@handle
			val yaw = Math.toRadians(player.rotationYaw.toDouble()).toFloat()
			// keep the boat hovering, ascend while the jump button is held
			val motionY = if (player.inputData.contains(PlayerAuthInputData.JUMP_DOWN)) speedValue else 0f

			session.netSession.inboundPacket(SetEntityMotionPacket().apply {
				runtimeEntityId = boat
				motion = Vector3f.from(-sin(yaw) * speedValue, motionY, cos(yaw) * speedValue)
			})
		}
	}

	private inner class Vanilla : Choice("Vanilla") {

		private val handleTick = handle<EventTick> {
			if (session.player.tickExists % 10 == 0L) {
				sendAbilities()
			}
		}

		private val handlePacketInbound = handle<EventPacketInbound> {
			if (packet is UpdateAbilitiesPacket || packet is StartGamePacket) {
				cancel()
				sendAbilities()
			}
		}

		private val handlePacketOutbound = handle<EventPacketOutbound> {
			if (packet is RequestAbilityPacket && packet.ability == Ability.FLYING) {
				cancel()
			}
		}

		private fun sendAbilities() {
			if (!session.netSessionInitialized) return

			session.netSession.inboundPacket(UpdateAbilitiesPacket().apply {
				uniqueEntityId = session.player.uniqueEntityId
				playerPermission = PlayerPermission.OPERATOR
				commandPermission = CommandPermission.OWNER
				abilityLayers.add(AbilityLayer().apply {
					layerType = AbilityLayer.Type.BASE
					abilitiesSet.addAll(Ability.values())
					abilityValues.addAll(arrayOf(
						Ability.BUILD, Ability.MINE, Ability.DOORS_AND_SWITCHES, Ability.OPEN_CONTAINERS,
						Ability.ATTACK_PLAYERS, Ability.ATTACK_MOBS, Ability.OPERATOR_COMMANDS,
						Ability.MAY_FLY, Ability.FLY_SPEED, Ability.WALK_SPEED
					))
					walkSpeed = 0.1f
					flySpeed = speedValue
				})
			})
		}
	}

	/**
	 * best effort implementation of the damage burst: NoFall is turned off for
	 * [NOFALL_OFF_TICKS] ticks, one tick of falling is sent so the server applies fall
	 * damage, then a single movement packet moves the player [Damage.distanceValue] blocks forward.
	 */
	private inner class Damage : Choice("Damage") {

		private var distanceValue by floatValue("Distance", 2f, 0.5f..10f)
		private var stage = 0
		private var ticks = 0
		private var noFallDisabled = false

		override fun onEnable() {
			stage = 0
			ticks = 0
			if (!session.netSessionInitialized) return

			val noFall = moduleManager.getModule(ModuleNoFall::class.java)
			if (noFall.state) {
				noFall.state = false
				noFallDisabled = true
			}
		}

		override fun onDisable() {
			restoreNoFall()
		}

		private val handleTick = handle<EventTick> {
			if (!session.netSessionInitialized) return@handle

			val player = session.player
			when (stage) {
				0 -> {
					if (++ticks >= NOFALL_OFF_TICKS) {
						session.netSession.inboundPacket(SetEntityMotionPacket().apply {
							runtimeEntityId = player.runtimeEntityId
							motion = Vector3f.from(0f, -FALL_MOTION, 0f)
						})
						stage = 1
					}
				}
				1 -> {
					val yaw = Math.toRadians(player.rotationYaw.toDouble()).toFloat()
					player.teleport(
						player.posX - sin(yaw) * distanceValue,
						player.posY,
						player.posZ + cos(yaw) * distanceValue
					)
					restoreNoFall()
					stage = 2
				}
			}
		}

		private fun restoreNoFall() {
			if (!noFallDisabled) return
			noFallDisabled = false
			if (session.netSessionInitialized) {
				moduleManager.getModule(ModuleNoFall::class.java).state = true
			}
		}
	}

	companion object {
		private const val NOFALL_OFF_TICKS = 4
		private const val FALL_MOTION = 0.5f
	}
}
