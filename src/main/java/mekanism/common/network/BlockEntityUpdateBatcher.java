package mekanism.common.network;

import mekanism.common.tile.base.TileEntityUpdateable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/// Collects block entity update requests so that each target is serialized once and updates for the same tracking chunk share a payload.
public interface BlockEntityUpdateBatcher {

    /// Records an update request for the target block entity. Repeated requests in the same tick retain the strongest requested update mode.
    ///
    /// @param level       level containing the target and tracking block entities
    /// @param trackingPos position whose chunk determines the recipients
    /// @param target      block entity whose state is synchronized
    /// @param mode        whether the final state requires a full snapshot or a reduced update
    void enqueue(ServerLevel level, BlockPos trackingPos, TileEntityUpdateable target, UpdateMode mode);

    /// Serializes and sends all requests collected during the current server tick.
    void flush();

    /// Discards pending requests belonging to an unloading level.
    ///
    /// @param level level being unloaded
    void clear(ServerLevel level);

    /// Discards all pending requests when the server stops.
    void clear();

    /// Describes the amount of state that the target must write when the batch is flushed.
    enum UpdateMode {
        /// Writes only runtime state that changed since the previous update.
        REDUCED,
        /// Writes the complete state required after initial tracking or a structural change.
        FULL;

        /// Combines two requests without allowing a reduced request to downgrade a full snapshot.
        ///
        /// @param other mode of the later request
        /// @return the strongest of the two modes
        public UpdateMode merge(UpdateMode other) {
            return this == FULL || other == FULL ? FULL : REDUCED;
        }
    }
}
