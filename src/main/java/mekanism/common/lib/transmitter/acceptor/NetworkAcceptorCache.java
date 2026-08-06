package mekanism.common.lib.transmitter.acceptor;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import mekanism.common.content.network.transmitter.Transmitter;
import mekanism.common.lib.transmitter.TransmitterNetworkRegistry;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

public class NetworkAcceptorCache<ACCEPTOR> {

    private final Long2ObjectMap<Map<Direction, ACCEPTOR>> cachedAcceptors = new Long2ObjectOpenHashMap<>();
    private final Map<Transmitter<ACCEPTOR, ?, ?>, Set<Direction>> changedAcceptors = new Object2ObjectOpenHashMap<>();
    @Nullable
    private List<ACCEPTOR> flattenedAcceptors;

    public void updateTransmitterOnSide(Transmitter<ACCEPTOR, ?, ?> transmitter, Direction side) {
        flattenedAcceptors = null;
        transmitter.refreshAcceptorConnections(side);
        ACCEPTOR acceptor = transmitter.canConnectToAcceptor(side) ? transmitter.getAcceptor(side) : null;
        long acceptorPos = WorldUtils.relativePos(transmitter.getWorldPositionLong(), side);
        if (acceptor == null) {
            Map<Direction, ACCEPTOR> cached = cachedAcceptors.get(acceptorPos);
            if (cached != null) {
                cached.remove(side.getOpposite());
                if (cached.isEmpty()) {
                    cachedAcceptors.remove(acceptorPos);
                }
            }
        } else {
            cachedAcceptors.computeIfAbsent(acceptorPos, pos -> new EnumMap<>(Direction.class)).put(side.getOpposite(), acceptor);
        }
    }

    public void adoptAcceptors(NetworkAcceptorCache<ACCEPTOR> other) {
        flattenedAcceptors = null;
        for (ObjectIterator<Long2ObjectMap.Entry<Map<Direction, ACCEPTOR>>> iterator = other.getAcceptorFastIterator(); iterator.hasNext(); ) {
            Long2ObjectMap.Entry<Map<Direction, ACCEPTOR>> entry = iterator.next();
            long pos = entry.getLongKey();
            if (cachedAcceptors.containsKey(pos)) {
                cachedAcceptors.get(pos).putAll(entry.getValue());
            } else {
                cachedAcceptors.put(pos, entry.getValue());
            }
        }
        for (Entry<Transmitter<ACCEPTOR, ?, ?>, Set<Direction>> entry : other.changedAcceptors.entrySet()) {
            Transmitter<ACCEPTOR, ?, ?> transmitter = entry.getKey();
            if (changedAcceptors.containsKey(transmitter)) {
                changedAcceptors.get(transmitter).addAll(entry.getValue());
            } else {
                changedAcceptors.put(transmitter, entry.getValue());
            }
        }
    }

    public void acceptorChanged(Transmitter<ACCEPTOR, ?, ?> transmitter, Direction side) {
        changedAcceptors.computeIfAbsent(transmitter, t -> EnumSet.noneOf(Direction.class)).add(side);
        TransmitterNetworkRegistry.registerChangedNetwork(transmitter.getTransmitterNetworkNN());
    }

    public void commit() {
        if (!changedAcceptors.isEmpty()) {
            for (Entry<Transmitter<ACCEPTOR, ?, ?>, Set<Direction>> entry : changedAcceptors.entrySet()) {
                Transmitter<ACCEPTOR, ?, ?> transmitter = entry.getKey();
                if (transmitter.isValid()) {
                    //Update all the changed directions
                    for (Direction side : entry.getValue()) {
                        updateTransmitterOnSide(transmitter, side);
                    }
                }
            }
            changedAcceptors.clear();
        }
    }

    public void deregister() {
        cachedAcceptors.clear();
        changedAcceptors.clear();
        flattenedAcceptors = null;
    }

    /// @apiNote Listeners should not be added to these LazyOptionals here as they may not correspond to an actual handler and may not get invalidated.
    public ObjectIterator<Long2ObjectMap.Entry<Map<Direction, ACCEPTOR>>> getAcceptorFastIterator() {
        return Long2ObjectMaps.fastIterator(cachedAcceptors);
    }

    /// @apiNote Listeners should not be added to these LazyOptionals here as they may not correspond to an actual handler and may not get invalidated.
    public Collection<Map<Direction, ACCEPTOR>> getAcceptorValues() {
        return cachedAcceptors.values();
    }

    /// Returns a cached flattened view of all acceptors, rebuilding it only after the topology changes.
    ///
    /// @apiNote The returned list must be treated as read-only.
    public List<ACCEPTOR> getFlattenedAcceptors() {
        if (flattenedAcceptors == null) {
            if (cachedAcceptors.isEmpty()) {
                return flattenedAcceptors = List.of();
            }
            int acceptorCount = 0;
            for (Map<Direction, ACCEPTOR> acceptors : cachedAcceptors.values()) {
                acceptorCount += acceptors.size();
            }
            List<ACCEPTOR> flattened = new ArrayList<>(acceptorCount);
            for (Map<Direction, ACCEPTOR> acceptors : cachedAcceptors.values()) {
                flattened.addAll(acceptors.values());
            }
            flattenedAcceptors = List.copyOf(flattened);
        }
        return flattenedAcceptors;
    }

    public int getAcceptorCount() {
        //Count multiple connections to the same position as multiple acceptors
        return cachedAcceptors.values().stream().mapToInt(Map::size).sum();
    }

    public boolean hasAcceptor(BlockPos acceptorPos) {
        return cachedAcceptors.containsKey(acceptorPos.asLong());
    }

    @Nullable
    public ACCEPTOR getCachedAcceptor(long acceptorPos, Direction side) {
        return cachedAcceptors.getOrDefault(acceptorPos, Collections.emptyMap()).get(side);
    }

    public Set<Direction> getAcceptorDirections(long pos) {
        //TODO: Do this better?
        return cachedAcceptors.get(pos).keySet();
    }
}