package doctor_m.tardis.interior;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

public final class TardisInteriorRegistry {
    private TardisInteriorRegistry() {}

    /**
     * 扫描当前加载的 structure 模板，找出所有合法的内饰 ID。
     */
    public static List<Identifier> getAvailableInteriors(MinecraftServer server) {
        List<Identifier> list = new ArrayList<>();

        // 获取所有已加载的 NBT 模板
        server.getStructureTemplateManager().listTemplates().forEach(id -> {
            // 筛选出放在 interior/ 文件夹下的
            if (id.getPath().startsWith("interior/")) {
                // 把前缀去掉，还原成 UI 和 TardisData 认识的 ID
                // 例如：doctor_m:interior/classic_interior -> doctor_m:classic_interior
                String cleanPath = id.getPath().substring("interior/".length());
                list.add(Identifier.fromNamespaceAndPath(id.getNamespace(), cleanPath));
            }
        });

        return list;
    }
}