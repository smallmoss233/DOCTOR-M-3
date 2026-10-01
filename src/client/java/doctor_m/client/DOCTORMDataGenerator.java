package doctor_m.client;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public class DOCTORMDataGenerator implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        FabricDataGenerator.Pack pack = generator.createPack();

        // 之后添加各个 Provider：
        // pack.addProvider(DoctorMModelProvider::new);
        // pack.addProvider(DoctorMBlockLootProvider::new);
        // pack.addProvider(DoctorMLangProvider::new);
    }
}