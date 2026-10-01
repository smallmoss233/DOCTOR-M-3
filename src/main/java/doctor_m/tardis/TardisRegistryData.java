package doctor_m.tardis;

import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.*;

public class TardisRegistryData extends SavedData {

    private static final Identifier DATA_ID =
            Identifier.fromNamespaceAndPath("doctor_m", "tardis_registry");

    private final Map<UUID, TardisData> tardises = new HashMap<>();

    private static final Codec<TardisRegistryData> CODEC =
            TardisData.CODEC.listOf().xmap(
                    list -> {
                        TardisRegistryData d = new TardisRegistryData();
                        for (TardisData t : list) d.tardises.put(t.id(), t);
                        return d;
                    },
                    d -> List.copyOf(d.tardises.values())
            );

    public static final SavedDataType<TardisRegistryData> TYPE =
            new SavedDataType<>(DATA_ID, TardisRegistryData::new, CODEC, null);

    public TardisRegistryData() {}

    public TardisData get(UUID id)     { return tardises.get(id); }
    public Collection<TardisData> all(){ return tardises.values(); }
    public int size()                  { return tardises.size(); }

    public TardisData put(TardisData data) {
        tardises.put(data.id(), data); setDirty(); return data;
    }
    public boolean remove(UUID id) {
        if (tardises.remove(id) != null) { setDirty(); return true; }
        return false;
    }
}