package doctor_m.client.gui;

import doctor_m.client.gui.pip.GuiBedrockModelRenderState;
import doctor_m.client.tardis.appearance.TardisAppearanceAssets;
import doctor_m.network.SetTardisAppearancePayload;
import doctor_m.tardis.appearance.TardisAppearance;
import doctor_m.tardis.appearance.TardisAppearanceRegistry;
import doctor_m.tardis.appearance.TardisAsset;
import mosslib.core.bedrock.BedrockModelRef;
import mosslib.core.render.BedrockRenderPipeline;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class TardisAppearanceScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("TARDIS");

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
        super(Component.translatable("doctor_m.screen.appearance.title"));
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
            // 注意：这里原版用的是原始 category 字符串，由于现在的 category 带有 :leader 等后缀，需要提取纯分类名来比对
            String rawCategory = original != null ? original.category() : null;
            currentCategory = TardisAppearanceRegistry.extractCategoryName(rawCategory);

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
                        Component.translatable("doctor_m.screen.appearance.apply").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        b -> applyAppearance())
                .pos(rightColX, bottomBtnY).size(bottomBtnW, BTN_H).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("doctor_m.screen.appearance.close"),
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

        List<String> problems = TardisAppearanceAssets.warmUp(previewingId);
        if (!problems.isEmpty()) {
            LOGGER.warn("[TARDIS] appearance {} applied with asset problems: {}",
                    previewingId, String.join("; ", problems));
        }

        ClientPlayNetworking.send(new SetTardisAppearancePayload(tardisId, previewingId));
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
                    Component.translatable("doctor_m.screen.appearance.empty").withStyle(ChatFormatting.RED),
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
                float yaw = (System.currentTimeMillis() % 20000L) / 20000f * 360f;

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

        // 获取已本地化的名称组件，找不到翻译时会自动回退到 JSON 里的 displayName
        Component displayName = preview != null ? preview.getDisplayName() : Component.literal("?");
        boolean isVariant  = preview != null && preview.variant();
        boolean isOriginal = Objects.equals(previewingId, originalAppearanceId);

        MutableComponent nameText = Component.translatable("doctor_m.screen.appearance.current").withStyle(ChatFormatting.GRAY)
                .append(displayName.copy().withStyle(ChatFormatting.WHITE));
        if (isOriginal) {
            nameText.append(Component.literal("  ").append(
                    Component.translatable("doctor_m.screen.appearance.applied").withStyle(ChatFormatting.GREEN)));
        }
        ctx.text(this.font, nameText,
                rightColX, panelY + HEADER_H + PAD, 0xFFFFFFFF);

        Component catLabel = TardisAppearanceRegistry.getCategoryComponent(currentCategory);
        ctx.text(this.font,
                Component.translatable("doctor_m.screen.appearance.category").withStyle(ChatFormatting.GRAY)
                        .append(catLabel.copy().withStyle(ChatFormatting.WHITE)),
                rightColX, panelY + HEADER_H + PAD + 16, 0xFFFFFFFF);

        // 分隔线
        ctx.fill(rightColX, panelY + HEADER_H + PAD + 34,
                rightColX + rightW, panelY + HEADER_H + PAD + 35, C_DIVIDER);

        // ===== 两组切换的标签 =====
        ctx.text(this.font,
                Component.translatable("doctor_m.screen.appearance.group_switch").withStyle(ChatFormatting.GRAY),
                rightColX, groupSwitchY + 6, 0xFFFFFFFF);
        ctx.text(this.font,
                Component.translatable("doctor_m.screen.appearance.group_cycle").withStyle(ChatFormatting.GRAY),
                rightColX, inGroupBtnY + 6, 0xFFFFFFFF);

        // ===== 信息区 =====
        drawInnerPanel(ctx, rightColX, infoBoxY, rightW, infoBoxH);
        int infoY = infoBoxY + 8;
        if (isVariant) {
            ctx.text(this.font,
                    Component.translatable("doctor_m.screen.appearance.variant").withStyle(ChatFormatting.GOLD),
                    rightColX + 8, infoY, 0xFFFFFFFF);
            infoY += 14;
        }
        ctx.text(this.font,
                Component.translatable("doctor_m.screen.appearance.id", previewingId.toString()).withStyle(ChatFormatting.GRAY),
                rightColX + 8, infoY, 0xFFFFFFFF);
        infoY += 14;
        ctx.text(this.font,
                Component.translatable("doctor_m.screen.appearance.info_placeholder").withStyle(ChatFormatting.DARK_GRAY),
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