package doctor_m.tardis;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

public enum TardisState implements StringRepresentable {
    LANDED("landed"),
    TAKEOFF("takeoff"),
    FLYING("flying"),
    LANDING("landing");

    public static final Codec<TardisState> CODEC =
            StringRepresentable.fromEnum(TardisState::values);

    private final String name;
    TardisState(String name) { this.name = name; }

    @Override public String getSerializedName() { return name; }

    /** 门是否可以打开 / 玩家是否可以穿门。 */
    public boolean canOpenDoors() { return this == LANDED; }
}