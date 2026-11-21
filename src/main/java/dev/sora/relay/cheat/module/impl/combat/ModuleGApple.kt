package dev.sora.relay.cheat.module.impl.combat

import dev.sora.relay.cheat.module.CheatCategory
import dev.sora.relay.cheat.module.CheatModule
import dev.sora.relay.game.event.EventTick
import dev.sora.relay.game.registry.itemDefinition
import dev.sora.relay.game.utils.constants.Attribute
import dev.sora.relay.game.utils.removeNetInfo
import dev.sora.relay.utils.timing.MillisecondTimer
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket

/**
 * Automatically switches to a golden apple and eats it once health drops below
 * the configured threshold. Eating is sped up by resending the item use
 * transaction every tick instead of waiting for the vanilla eating time.
 *
 * Only runs while the module is enabled, as every [EventTick] handler is gated
 * by [CheatModule.state].
 */
class ModuleGApple : CheatModule("GApple", CheatCategory.COMBAT) {

	private var healthValue by intValue("Health", 16, 1..20)

	private val cooldown = MillisecondTimer()
	private var eating = false
	private var previousSlot = -1
	private var expectedCount = 0
	private var eatTicks = 0

	override fun onDisable() {
		eating = false
		previousSlot = -1
		expectedCount = 0
		eatTicks = 0
	}

	private val handleTick = handle<EventTick> {
		val player = session.player
		val health = player.attributes[Attribute.HEALTH]?.value ?: return@handle
		if (health > healthValue) {
			stopEating()
			return@handle
		}

		if (!cooldown.hasTimePassed(EAT_COOLDOWN)) return@handle

		val inventory = player.inventory
		val slot = if (inventory.hand.isGApple()) {
			inventory.heldItemSlot
		} else {
			inventory.searchForItemInHotbar { it.isGApple() } ?: run {
				stopEating()
				return@handle
			}
		}

		if (!eating) {
			previousSlot = inventory.heldItemSlot
			eating = true
			eatTicks = 0
			expectedCount = inventory.content[slot].count
		} else if (slot != inventory.heldItemSlot) {
			// the player changed the held slot, switch back to the apple
			expectedCount = inventory.content[slot].count
		}

		selectSlot(slot)

		val item = inventory.content[slot]
		if (!item.isGApple() || item.count < expectedCount) {
			// the apple has been consumed (or replaced) by the server
			stopEating()
			return@handle
		}

		player.useItem(InventoryTransactionPacket().apply {
			actionType = 1
			hotbarSlot = slot
			itemInHand = item.removeNetInfo()
			playerPosition = player.vec3Position
			clickPosition = Vector3f.ZERO
		}, 1)

		if (++eatTicks >= MAX_EAT_TICKS) {
			stopEating()
		}
	}

	private fun stopEating() {
		if (!eating) return
		eating = false
		expectedCount = 0
		eatTicks = 0
		cooldown.reset()

		val slot = previousSlot
		previousSlot = -1
		if (slot != -1) selectSlot(slot)
	}

	private fun selectSlot(slot: Int) {
		val inventory = session.player.inventory
		if (slot == inventory.heldItemSlot) return
		session.sendPacket(PlayerHotbarPacket().apply {
			selectedHotbarSlot = slot
			isSelectHotbarSlot = true
			containerId = ContainerId.INVENTORY
		})
	}

	private fun ItemData.isGApple() =
		itemDefinition.identifier == "minecraft:golden_apple" || itemDefinition.identifier == "minecraft:enchanted_golden_apple"

	companion object {
		/**
		 * vanilla eating takes 32 ticks, give fast eat a shorter window before giving up
		 */
		private const val MAX_EAT_TICKS = 20

		/**
		 * delay before eating again after one apple has been consumed
		 */
		private const val EAT_COOLDOWN = 1000L
	}
}
