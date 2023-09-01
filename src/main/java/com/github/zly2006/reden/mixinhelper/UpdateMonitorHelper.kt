package com.github.zly2006.reden.mixinhelper

import com.github.zly2006.reden.access.PlayerData
import com.github.zly2006.reden.access.PlayerData.Companion.data
import com.github.zly2006.reden.carpet.RedenCarpetSettings
import com.github.zly2006.reden.utils.debugLogger
import com.github.zly2006.reden.utils.server
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.block.BlockState
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos

object UpdateMonitorHelper {
    private var recordId = 20060210L
    val undoRecordsMap: MutableMap<Long, PlayerData.UndoRecord> = HashMap()
    internal val undoRecords = mutableListOf<PlayerData.UndoRecord?>()
    @JvmStatic fun pushRecord(id: Long) = undoRecords.add(undoRecordsMap[id])
    @JvmStatic fun popRecord() = undoRecords.removeLast()
    data class Changed(
        val record: PlayerData.UndoRecord,
        val pos: BlockPos
    )
    var lastTickChanged: MutableSet<Changed> = hashSetOf(); private set
    var thisTickChanged: MutableSet<Changed> = hashSetOf(); private set

    val recording: PlayerData.UndoRecord? get() = undoRecords.lastOrNull()
    enum class LifeTime {
        PERMANENT,
        TICK,
        CHAIN,
        ONCE
    }

    @JvmStatic
    fun monitorSetBlock(world: ServerWorld, pos: BlockPos, blockState: BlockState) {
        debugLogger("id ${recording?.id ?: 0}: set$pos, ${world.getBlockState(pos)} -> $blockState")
        recording?.data?.computeIfAbsent(pos.asLong()) {
            recording!!.fromWorld(world, pos)
        }
        recording?.lastChangedTick = server.ticks
    }

    /**
     * 此函数有危险副作用
     *
     * 使用此函数将**立刻**产生缓存的副作用
     *
     * 此缓存可能在没有确认的情况下不经检查直接调用
     */
    private fun addRecord(): PlayerData.UndoRecord {
        if (undoRecords.size != 0) {
            throw IllegalStateException("Cannot add record when there is already one.")
        }
        val undoRecord = PlayerData.UndoRecord(
            id = recordId,
            lastChangedTick = server.ticks,
        )
        undoRecords.add(undoRecord)
        undoRecordsMap[recordId] = undoRecord
        recordId++
        return undoRecord
    }

    internal fun removeRecord(id: Long) = undoRecordsMap.remove(id)
    @JvmStatic
    fun playerStartRecord(player: ServerPlayerEntity) {
        val playerView = player.data()
        if (!playerView.canRecord) return
        if (!playerView.isRecording) {
            playerView.isRecording = true
            playerView.undo.add(addRecord())
        }
    }

    fun playerStopRecording(player: ServerPlayerEntity) {
        val playerView = player.data()
        if (playerView.isRecording) {
            playerView.isRecording = false
            undoRecords.removeLast()
            debugLogger("Stop recording, undo depth=" + undoRecords.size)
            playerView.redo
                .onEach { removeRecord(it.id) }
                .clear()
            var sum = playerView.undo.map(PlayerData.UndoRecord::getMemorySize).sum()
            debugLogger("Undo size: $sum")
            if (RedenCarpetSettings.allowedUndoSizeInBytes >= 0) {
                while (sum > RedenCarpetSettings.allowedUndoSizeInBytes) {
                    removeRecord(playerView.undo.first().id)
                    playerView.undo.removeFirst()
                    debugLogger("Undo size: $sum, removing.")
                    sum = playerView.undo.map(PlayerData.UndoRecord::getMemorySize).sum()
                }
            }
        }
    }

    private fun playerQuit(player: ServerPlayerEntity) =
        player.data().undo.forEach { removeRecord(it.id) }

    @JvmStatic
    fun tryAddRelatedEntity(entity: Entity) {
        if (entity.noClip) return
        if (entity is ServerPlayerEntity) return
        // not used
    }

    @JvmStatic
    fun entitySpawned(entity: Entity) {
        if (entity is ServerPlayerEntity) return
        recording?.entities?.put(entity.uuid, null)
    }

    init {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> playerQuit(handler.player) }
        ServerTickEvents.START_SERVER_TICK.register {
            lastTickChanged = thisTickChanged
            thisTickChanged = hashSetOf()
        }
    }
}