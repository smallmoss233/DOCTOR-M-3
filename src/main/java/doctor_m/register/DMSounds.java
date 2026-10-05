package doctor_m.register;

import mosslib.api.AutoRegister;
import net.minecraft.sounds.SoundEvent;

public final class DMSounds {

    private DMSounds() {}

    /** 起飞（消失）音效。 */
    public static final SoundEvent DEMAT = AutoRegister.sound("doctor_m", "tardis.demat");

    /** 降落（出现）音效。 */
    public static final SoundEvent MAT = AutoRegister.sound("doctor_m", "tardis.mat");

    /** 飞行循环音效。 */
    public static final SoundEvent FLY = AutoRegister.sound("doctor_m", "tardis.fly");

    public static void register() {
        AutoRegister.sounds(DMSounds.class);
    }
}