package doctor_m.client.gui;

import doctor_m.client.gui.pip.GuiBedrockModelRenderState;
import doctor_m.client.tardis.render.BedrockModelRef;
import doctor_m.client.tardis.render.BedrockRenderPipeline;
import doctor_m.network.SetTardisAppearancePayload;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class TardisAppearanceScreen extends Screen {

    // ===== 布局常量 =====
    private static final int PANEL_W        = 440;
    private static final int PANEL_H        = 260;
    private static final int HEADER_H       = 26;
    private static final int PAD            = 16;
    private static final int LEFT_W         = 190;
    private static final int PREVIEW_H      = 190;
    private static final int BTN_H          = 20;
    private static final int SMALL_BTN      = 24;

    // ===== 配色 =====
    private static final int C_BG           = 0xF0000000;
    private static final int C_BORDER       = 0xFFB088FF;
    private static final int C_BORDER_GLOW  = 0x50B088FF;
    private static final int C_ACCENT       = 0xFFD0A8FF;
    private static final int C_HEADER_BG    = 0x60000000;
    private static final int C_PANEL_INNER  = 0x25FFFFFF;
    private static final int C_PANEL_BORDER = 0x40FFFFFF;
    private static final int C_DIVIDER      = 0x30FFFFFF;

    // ===== 输入 =====
    private final UUID tardisId;
    private final Identifier originalAppearanceId;

    // ===== 状态 =====
    private Identifier previewingId;
    private String     currentCategory;
    private int        categoryIndex = 0;
    private List<String> allCategories = List.of();
    private List<TardisAppearance> currentGroupList = List.of();

    // ===== 运行布局 =====
    private int panelX, panelY;
    private int leftColX, rightColX, rightW;
    private int previewY;
    private int groupSwitchY;
    private int inGroupBtnY;
    private int infoBoxY, infoBoxH;
    private int bottomBtnY;

    public TardisAppearanceScreen(Player player,
                                  UUID tardisId,
                                  Identifier originalAppearanceId) {
        super(Component.literal("TARDIS 外观选择"));
        this.tardisId = tardisId;
        this.originalAppearanceId = originalAppearanceId;
        this.previewingId = originalAppearanceId;
    }

    @Override
    protected void init() {
        super.init();

        allCategories = TardisAppearanceRegistry.allCategories();

        if (!allCategories.isEmpty()) {
            TardisAppearance original = TardisAppearanceRegistry.get(originalAppearanceId);
            currentCategory = original != null ? original.category() : null;
            int idx = allCategories.indexOf(currentCategory);
            categoryIndex = idx >= 0 ? idx : 0;
            currentCategory = allCategories.get(categoryIndex);
            refreshGroup();
        }

        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        leftColX = panelX + PAD;
        rightColX = leftColX + LEFT_W + PAD;
        rightW = panelX + PANEL_W - PAD - rightColX;

        previewY = panelY + HEADER_H + PAD;

        // 右侧从上到下的纵向排布
        groupSwitchY = panelY + HEADER_H + PAD + 56;
        inGroupBtnY  = groupSwitchY + 22;
        infoBoxY     = inGroupBtnY + 26;
        infoBoxH     = 60;
        bottomBtnY   = panelY + PANEL_H - PAD - BTN_H;

        // 两组按钮统一右对齐
        int btnX = rightColX + rightW - SMALL_BTN * 2 - 4;

        // ===== 组切换（上） =====
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleCategory(-1))
                .pos(btnX, groupSwitchY).size(SMALL_BTN, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleCategory(+1))
                .pos(btnX + SMALL_BTN + 4, groupSwitchY).size(SMALL_BTN, BTN_H).build());

        // ===== 组内循环（下） =====
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleInGroup(-1))
                .pos(btnX, inGroupBtnY).size(SMALL_BTN, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleInGroup(+1))
                .pos(btnX + SMALL_BTN + 4, inGroupBtnY).size(SMALL_BTN, BTN_H).build());

        // ===== 底部按钮 =====
        int bottomBtnW = (rightW - 12) / 2;
        addRenderableWidget(Button.builder(
                        Component.literal("应用").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        b -> applyAppearance())
                .pos(rightColX, bottomBtnY).size(bottomBtnW, BTN_H).build());
        addRenderableWidget(Button.builder(
                        Component.literal("关闭"),
                        b -> onClose())
                .pos(rightColX + bottomBtnW + 12, bottomBtnY).size(bottomBtnW, BTN_H).build());
    }

    // ============================================================
    //                      逻辑
    // ============================================================

    private void refreshGroup() {
        currentGroupList = TardisAppearanceRegistry.byCategory(currentCategory);
    }

    private void cycleInGroup(int dir) {
        if (currentGroupList.isEmpty()) return;
        int idx = indexOf(currentGroupList, previewingId);
        idx = (idx + dir + currentGroupList.size()) % currentGroupList.size();
        previewingId = currentGroupList.get(idx).id();
    }

    private void cycleCategory(int dir) {
        if (allCategories.isEmpty()) return;
        categoryIndex = (categoryIndex + dir + allCategories.size()) % allCategories.size();
        currentCategory = allCategories.get(categoryIndex);
        refreshGroup();
        if (!currentGroupList.isEmpty()) {
            previewingId = currentGroupList.get(0).id();
        }
    }

    private void applyAppearance() {
        TardisAppearance app = TardisAppearanceRegistry.get(previewingId);
        if (app == null) return;

        Optional<Identifier> extGeo = Optional.empty();
        Optional<Identifier> intGeo = Optional.empty();
        if (app.exterior() instanceof TardisAsset.Bedrock b) extGeo = Optional.ofNullable(b.geometry());
        if (app.interior() instanceof TardisAsset.Bedrock b) intGeo = Optional.ofNullable(b.geometry());

        ClientPlayNetworking.send(new SetTardisAppearancePayload(
                tardisId, previewingId, extGeo, intGeo));
        onClose();
    }

    private static int indexOf(List<TardisAppearance> list, Identifier id) {
        for (int i = 0; i < list.size(); i++) {
            if (Objects.equals(list.get(i).id(), id)) return i;
        }
        return 0;
    }

    // ============================================================
    //                      渲染
    // ============================================================

    /** 禁掉原版背景（模糊/菜单底图）。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float a) {
        // 留空
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float a) {
        int px = panelX, py = panelY;
        int pr = px + PANEL_W, pb = py + PANEL_H;

        // ===== 阴影 =====
        ctx.fill(px - 3, py - 3, pr + 3, pb + 3, 0x40000000);
        ctx.fill(px - 2, py - 2, pr + 2, pb + 2, 0x50000000);
        ctx.fill(px - 1, py - 1, pr + 1, pb + 1, 0x80000000);

        // ===== 背景 + 边框 =====
        ctx.fill(px, py, pr, pb, C_BG);
        ctx.fill(px, py, pr, py + 1, C_BORDER);
        ctx.fill(px, pb - 1, pr, pb, C_BORDER);
        ctx.fill(px, py, px + 1, pb, C_BORDER);
        ctx.fill(pr - 1, py, pr, pb, C_BORDER);

        // ===== 内发光 =====
        ctx.fill(px + 1, py + 1, pr - 1, py + 2, C_BORDER_GLOW);
        ctx.fill(px + 1, py + 1, px + 2, pb - 1, C_BORDER_GLOW);
        ctx.fill(pr - 2, py + 1, pr - 1, pb - 1, C_BORDER_GLOW);
        ctx.fill(px + 1, pb - 2, pr - 1, pb - 1, C_BORDER_GLOW);

        // ===== 标题栏 =====
        ctx.fill(px + 1, py + 1, pr - 1, py + HEADER_H, C_HEADER_BG);
        ctx.fill(px + 1, py + HEADER_H, pr - 1, py + HEADER_H + 1, C_ACCENT);

        MutableComponent titleText = Component.literal("✦ ")
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(this.title.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" ✦").withStyle(ChatFormatting.LIGHT_PURPLE));
        ctx.centeredText(this.font, titleText, px + PANEL_W / 2, py + 8, 0xFFFFFFFF);

        if (allCategories.isEmpty()) {
            ctx.centeredText(this.font,
                    Component.literal("没有可用的外观").withStyle(ChatFormatting.RED),
                    px + PANEL_W / 2, py + PANEL_H / 2, 0xFFFFFFFF);
            super.extractRenderState(ctx, mouseX, mouseY, a);
            return;
        }

        // ===== 左侧预览 =====
        drawInnerPanel(ctx, leftColX, previewY, LEFT_W, PREVIEW_H);

        TardisAppearance previewApp = TardisAppearanceRegistry.get(previewingId);
        if (previewApp != null && previewApp.exterior() instanceof TardisAsset.Bedrock b) {
            BedrockModelRef ref = BedrockRenderPipeline.resolve(b.geometry(), b.texture());
            if (ref != null) {
                // 20 秒转一圈
                float yaw = (System.currentTimeMillis() % 20000L) / 20000f * 360f;

                // 注意：这里 addPicturesInPictureState 的坐标是 GUI 逻辑像素
                ctx.guiRenderState.addPicturesInPictureState(
                        new GuiBedrockModelRenderState(
                                ref.geometry(), ref.sprite(), yaw,
                                leftColX + 4, previewY + 4,
                                leftColX + LEFT_W - 4, previewY + PREVIEW_H - 4,
                                1.0f, null));
            }
        }

        // ===== 右侧：当前外观 =====
        TardisAppearance preview = TardisAppearanceRegistry.get(previewingId);
        String displayName = preview != null ? preview.displayName() : "?";
        boolean isVariant  = preview != null && preview.variant();
        boolean isOriginal = Objects.equals(previewingId, originalAppearanceId);

        MutableComponent nameText = Component.literal("当前外观：").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(displayName).withStyle(ChatFormatting.WHITE));
        if (isOriginal) {
            nameText.append(Component.literal("  ").append(
                    Component.literal("(已应用)").withStyle(ChatFormatting.GREEN)));
        }
        ctx.text(this.font, nameText,
                rightColX, panelY + HEADER_H + PAD, 0xFFFFFFFF);

        String catLabel = currentCategory == null
                ? TardisAppearanceRegistry.UNCATEGORIZED : currentCategory;
        ctx.text(this.font,
                Component.literal("分类：").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(catLabel).withStyle(ChatFormatting.WHITE)),
                rightColX, panelY + HEADER_H + PAD + 16, 0xFFFFFFFF);

        // 分隔线
        ctx.fill(rightColX, panelY + HEADER_H + PAD + 34,
                rightColX + rightW, panelY + HEADER_H + PAD + 35, C_DIVIDER);

        // ===== 两组切换的标签 =====
        ctx.text(this.font,
                Component.literal("组切换").withStyle(ChatFormatting.GRAY),
                rightColX, groupSwitchY + 6, 0xFFFFFFFF);
        ctx.text(this.font,
                Component.literal("组内循环").withStyle(ChatFormatting.GRAY),
                rightColX, inGroupBtnY + 6, 0xFFFFFFFF);

        // ===== 信息区 =====
        drawInnerPanel(ctx, rightColX, infoBoxY, rightW, infoBoxH);
        int infoY = infoBoxY + 8;
        if (isVariant) {
            ctx.text(this.font,
                    Component.literal("★ 变体外观").withStyle(ChatFormatting.GOLD),
                    rightColX + 8, infoY, 0xFFFFFFFF);
            infoY += 14;
        }
        ctx.text(this.font,
                Component.literal("ID: " + previewingId).withStyle(ChatFormatting.GRAY),
                rightColX + 8, infoY, 0xFFFFFFFF);
        infoY += 14;
        ctx.text(this.font,
                Component.literal("(作者 / 描述 / 其它)").withStyle(ChatFormatting.DARK_GRAY),
                rightColX + 8, infoY, 0xFFFFFFFF);

        // ===== 底部分隔线 =====
        ctx.fill(rightColX, bottomBtnY - 10, pr - PAD, bottomBtnY - 9, C_DIVIDER);

        // 渲染按钮（自动）
        super.extractRenderState(ctx, mouseX, mouseY, a);
    }

    private void drawInnerPanel(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, C_PANEL_INNER);
        ctx.fill(x, y, x + w, y + 1, C_PANEL_BORDER);
        ctx.fill(x, y + h - 1, x + w, y + h, C_PANEL_BORDER);
        ctx.fill(x, y, x + 1, y + h, C_PANEL_BORDER);
        ctx.fill(x + w - 1, y, x + w, y + h, C_PANEL_BORDER);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}