package mekanism.common.network.to_client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import mekanism.common.Mekanism;
import mekanism.common.network.IMekanismPacket;
import mekanism.common.tile.base.TileEntityUpdateable;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PacketUpdateTile(List<TileUpdate> updates) implements IMekanismPacket {

    public static final CustomPacketPayload.Type<PacketUpdateTile> TYPE = new CustomPacketPayload.Type<>(Mekanism.rl("update_tile"));
    private static final int MAX_UPDATES_PER_PACKET = 4_096;
    private static final int MAX_BATCH_DATA_SIZE = 1_000_000;
    public static final StreamCodec<ByteBuf, PacketUpdateTile> STREAM_CODEC = StreamCodec.composite(
          TileUpdate.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_UPDATES_PER_PACKET)), PacketUpdateTile::updates,
          PacketUpdateTile::new
    );

    public PacketUpdateTile {
        if (updates.isEmpty()) {
            throw new IllegalArgumentException("Block entity update batch cannot be empty.");
        }
        updates = List.copyOf(updates);
    }

    public static List<PacketUpdateTile> createBatches(List<TileUpdate> updates) {
        if (updates.isEmpty()) {
            return List.of();
        }
        List<PacketUpdateTile> packets = new ArrayList<>();
        List<TileUpdate> current = new ArrayList<>();
        int currentSize = 5;
        for (TileUpdate update : updates) {
            int updateSize = encodedSize(update);
            if (!current.isEmpty() && (current.size() == MAX_UPDATES_PER_PACKET || currentSize + updateSize > MAX_BATCH_DATA_SIZE)) {
                packets.add(new PacketUpdateTile(current));
                current = new ArrayList<>();
                currentSize = 5;
            }
            if (updateSize + 5 > MAX_BATCH_DATA_SIZE) {
                Mekanism.logger.warn("Block entity update at {} is {} bytes and exceeds the preferred batch payload size.", update.pos, updateSize);
            }
            current.add(update);
            currentSize += updateSize;
        }
        packets.add(new PacketUpdateTile(current));
        return packets;
    }

    private static int encodedSize(TileUpdate update) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            TileUpdate.STREAM_CODEC.encode(buffer, update);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    @Override
    public CustomPacketPayload.Type<PacketUpdateTile> type() {
        return TYPE;
    }

    @Override
    public void handle(IPayloadContext context) {
        Level world = context.player().level();
        for (TileUpdate update : updates) {
            handleUpdate(world, update);
        }
    }

    private static void handleUpdate(Level world, TileUpdate update) {
        BlockPos pos = update.pos;
        //Only handle the update packet if the block is currently loaded (otherwise we would have the warning get logged in cases we don't want it to)
        if (WorldUtils.isBlockLoaded(world, pos)) {
            TileEntityUpdateable tile = WorldUtils.getTileEntity(TileEntityUpdateable.class, world, pos, true);
            if (tile == null) {
                Mekanism.logger.warn("Update tile packet received for position: {} in world: {}, but no valid tile was found.", pos,
                      world.dimension().identifier());
            } else {
                //TODO - 26.1: Is this fine for how to create the problem reporter?
                try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(tile.problemPath(), Mekanism.logger)) {
                    tile.handleUpdateTag(TagValueInput.create(reporter, world.registryAccess(), update.updateTag));
                } catch (RuntimeException e) {
                    Mekanism.logger.error("Failed to apply block entity update at {} in {}.", pos, world.dimension().identifier(), e);
                }
            }
        }
    }

    public record TileUpdate(BlockPos pos, CompoundTag updateTag) {

        public static final StreamCodec<ByteBuf, TileUpdate> STREAM_CODEC = StreamCodec.composite(
              BlockPos.STREAM_CODEC, TileUpdate::pos,
              ByteBufCodecs.TRUSTED_COMPOUND_TAG, TileUpdate::updateTag,
              TileUpdate::new
        );
    }
}