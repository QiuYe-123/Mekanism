package mekanism.common.network;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mekanism.common.Mekanism;
import mekanism.common.network.to_client.PacketUpdateTile;
import mekanism.common.network.to_client.PacketUpdateTile.TileUpdate;
import mekanism.common.tile.base.TileEntityUpdateable;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/// Default server-thread implementation of block entity update batching.
final class BlockEntityUpdateBatcherImpl implements BlockEntityUpdateBatcher {

    private final Map<ServerLevel, PendingLevel> pending = new IdentityHashMap<>();

    @Override
    public void enqueue(ServerLevel level, BlockPos trackingPos, TileEntityUpdateable target, UpdateMode mode) {
        long trackingChunk = ChunkPos.containing(trackingPos).pack();
        PendingLevel pendingLevel = pending.computeIfAbsent(level, ignored -> new PendingLevel());
        PendingUpdate update = pendingLevel.updates.computeIfAbsent(target, ignored -> new PendingUpdate(target, mode));
        update.mode = update.mode.merge(mode);
        pendingLevel.chunks.computeIfAbsent(trackingChunk, ignored -> new PendingChunk(trackingPos)).add(update);
    }

    @Override
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<ServerLevel, PendingLevel> updates = new IdentityHashMap<>(pending);
        pending.clear();
        for (Map.Entry<ServerLevel, PendingLevel> levelEntry : updates.entrySet()) {
            ServerLevel level = levelEntry.getKey();
            PendingLevel pendingLevel = levelEntry.getValue();
            try {
                flush(level, pendingLevel);
            } catch (RuntimeException e) {
                Mekanism.logger.error("Unexpected failure while flushing block entity updates in {}.", level.dimension().identifier(), e);
            } finally {
                complete(level, pendingLevel);
            }
        }
    }

    private void flush(ServerLevel level, PendingLevel pendingLevel) {
        List<PendingChunk> trackedChunks = new ArrayList<>();
        Set<PendingUpdate> requestedUpdates = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PendingChunk chunk : pendingLevel.chunks.values()) {
            try {
                if (PacketUtils.hasPlayersTracking(level, chunk.trackingPos)) {
                    trackedChunks.add(chunk);
                    requestedUpdates.addAll(chunk.updates.values());
                }
            } catch (RuntimeException e) {
                Mekanism.logger.error("Failed to resolve players tracking block entity updates near {} in {}.", chunk.trackingPos,
                      level.dimension().identifier(), e);
            }
        }
        Map<PendingUpdate, TileUpdate> collectedUpdates = new IdentityHashMap<>();
        for (PendingUpdate update : requestedUpdates) {
            TileEntityUpdateable target = update.target;
            try {
                if (!target.isRemoved() && target.getLevel() == level && WorldUtils.getTileEntity(level, target.getBlockPos()) == target) {
                    CompoundTag updateTag = target.collectUpdateTag(update.mode);
                    collectedUpdates.put(update, new TileUpdate(target.getBlockPos(), updateTag));
                }
            } catch (RuntimeException e) {
                Mekanism.logger.error("Failed to collect block entity update data for {} in {}.", target.getBlockPos(), level.dimension().identifier(), e);
            }
        }
        for (PendingChunk chunk : trackedChunks) {
            try {
                List<TileUpdate> chunkUpdates = new ArrayList<>(chunk.updates.size());
                for (PendingUpdate update : chunk.updates.values()) {
                    TileUpdate collectedUpdate = collectedUpdates.get(update);
                    if (collectedUpdate != null) {
                        chunkUpdates.add(collectedUpdate);
                    }
                }
                for (PacketUpdateTile packet : PacketUpdateTile.createBatches(chunkUpdates)) {
                    PacketUtils.sendToAllTracking(packet, level, chunk.trackingPos);
                }
            } catch (RuntimeException e) {
                Mekanism.logger.error("Failed to send block entity updates tracked near {} in {}.", chunk.trackingPos,
                      level.dimension().identifier(), e);
            }
        }
    }

    private void complete(ServerLevel level, PendingLevel completedLevel) {
        PendingLevel queuedLevel = pending.get(level);
        for (TileEntityUpdateable target : completedLevel.updates.keySet()) {
            if (queuedLevel == null || !queuedLevel.updates.containsKey(target)) {
                complete(target);
            }
        }
    }

    @Override
    public void clear(ServerLevel level) {
        PendingLevel removed = pending.remove(level);
        if (removed != null) {
            removed.complete();
        }
    }

    @Override
    public void clear() {
        pending.values().forEach(PendingLevel::complete);
        pending.clear();
    }

    private static void complete(TileEntityUpdateable target) {
        try {
            target.updatePacketHandled();
        } catch (RuntimeException e) {
            Mekanism.logger.error("Failed to finish block entity update handling for {}.", target.getBlockPos(), e);
        }
    }

    private static final class PendingLevel {

        private final Long2ObjectMap<PendingChunk> chunks = new Long2ObjectOpenHashMap<>();
        private final Map<TileEntityUpdateable, PendingUpdate> updates = new IdentityHashMap<>();

        private void complete() {
            updates.keySet().forEach(BlockEntityUpdateBatcherImpl::complete);
        }
    }

    private static final class PendingChunk {

        private final BlockPos trackingPos;
        private final Map<Long, PendingUpdate> updates = new LinkedHashMap<>();

        private PendingChunk(BlockPos trackingPos) {
            this.trackingPos = trackingPos;
        }

        private void add(PendingUpdate update) {
            updates.put(update.target.getBlockPos().asLong(), update);
        }
    }

    private static final class PendingUpdate {

        private final TileEntityUpdateable target;
        private UpdateMode mode;

        private PendingUpdate(TileEntityUpdateable target, UpdateMode mode) {
            this.target = target;
            this.mode = mode;
        }
    }
}
