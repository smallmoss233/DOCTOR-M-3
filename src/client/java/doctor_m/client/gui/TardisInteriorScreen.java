package doctor_m.client.gui;

import doctor_m.network.SetTardisInteriorPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class TardisInteriorScreen extends Screen {

    // 布局常量
    private static final int PANEL_W        = 440;
    private static final int PANEL_H        = 260;
    private static final int HEADER_H       = 26;
    private static final int PAD            = 16;
    private static final int LEFT_W         = 190;
    private static final int PREVIEW_H      = 190;
    private static final int BTN_H          = 20;
    private static final int SMALL_BTN      = 24;

    // 配色
    private static final int C_BG           = 0xF0000000;
    private static final int C_BORDER       = 0xFFB088FF;
    private static final int C_BORDER_GLOW  = 0x50B088FF;
    private static final int C_ACCENT       = 0xFFD0A8FF;
    private static final int C_HEADER_BG    = 0x60000000;
    private static final int C_PANEL_INNER  = 0x25FFFFFF;
    private static final int C_PANEL_BORDER = 0x40FFFFFF;
    private static final int C_DIVIDER      = 0x30FFFFFF;

    private final UUID tardisId;
    private final Identifier originalInteriorId;
    private final List<Identifier> availableInteriors;

    private Identifier previewingId;
    private int currentIndex = 0;

    private int panelX, panelY;
    private int leftColX, rightColX, rightW;
    private int previewY;
    private int cycleBtnY;
    private int infoBoxY, infoBoxH;
    private int bottomBtnY;

    public TardisInteriorScreen(UUID tardisId, Identifier originalInteriorId, List<Identifier> availableInteriors) {
        super(Component.translatable("doctor_m.screen.interior.title"));
        this.tardisId = tardisId;
        this.originalInteriorId = originalInteriorId;
        this.availableInteriors = availableInteriors;
        this.previewingId = originalInteriorId;

        for (int i = 0; i < availableInteriors.size(); i++) {
            if (Objects.equals(availableInteriors.get(i), originalInteriorId)) {
                this.currentIndex = i;
                break;
            }
        }
    }

    @Override
    protected void init() {
        super.init();

        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        leftColX = panelX + PAD;
        rightColX = leftColX + LEFT_W + PAD;
        rightW = panelX + PANEL_W - PAD - rightColX;

        previewY = panelY + HEADER_H + PAD;

        cycleBtnY = panelY + HEADER_H + PAD + 56;
        infoBoxY  = cycleBtnY + 26;
        infoBoxH  = 90;
        bottomBtnY = panelY + PANEL_H - PAD - BTN_H;

        int btnX = rightColX + rightW - SMALL_BTN * 2 - 4;

        // 切换按钮
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycle(-1))
                .pos(btnX, cycleBtnY).size(SMALL_BTN, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycle(+1))
                .pos(btnX + SMALL_BTN + 4, cycleBtnY).size(SMALL_BTN, BTN_H).build());

        // 底部按钮
        int bottomBtnW = (rightW - 12) / 2;
        addRenderableWidget(Button.builder(
                        Component.translatable("doctor_m.screen.interior.apply").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                        b -> applyInterior())
                .pos(rightColX, bottomBtnY).size(bottomBtnW, BTN_H).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"),
                        b -> onClose())
                .pos(rightColX + bottomBtnW + 12, bottomBtnY).size(bottomBtnW, BTN_H).build());
    }

    private void cycle(int dir) {
        if (availableInteriors.isEmpty()) return;
        currentIndex = (currentIndex + dir + availableInteriors.size()) % availableInteriors.size();
        previewingId = availableInteriors.get(currentIndex);
    }

    private void applyInterior() {
        if (previewingId == null) return;
        ClientPlayNetworking.send(new SetTardisInteriorPayload(tardisId, previewingId));
        onClose();
    }

    /** 获取本地化名称，若未定义则回退显示原路径 */
    private Component getLocalizedName(Identifier id) {
        String key = "doctor_m.interior." + id.getNamespace() + "." + id.getPath().replace("/", ".");
        return Component.translatableWithFallback(key, id.getPath());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float a) {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float a) {
        int px = panelX, py = panelY;
        int pr = px + PANEL_W, pb = py + PANEL_H;

        // 阴影
        ctx.fill(px - 3, py - 3, pr + 3, pb + 3, 0x40000000);
        ctx.fill(px - 2, py - 2, pr + 2, pb + 2, 0x50000000);
        ctx.fill(px - 1, py - 1, pr + 1, pb + 1, 0x80000000);

        // 背景与边框
        ctx.fill(px, py, pr, pb, C_BG);
        ctx.fill(px, py, pr, py + 1, C_BORDER);
        ctx.fill(px, pb - 1, pr, pb, C_BORDER);
        ctx.fill(px, py, px + 1, pb, C_BORDER);
        ctx.fill(pr - 1, py, pr, pb, C_BORDER);

        // 内发光
        ctx.fill(px + 1, py + 1, pr - 1, py + 2, C_BORDER_GLOW);
        ctx.fill(px + 1, py + 1, px + 2, pb - 1, C_BORDER_GLOW);
        ctx.fill(pr - 2, py + 1, pr - 1, pb - 1, C_BORDER_GLOW);
        ctx.fill(px + 1, pb - 2, pr - 1, pb - 1, C_BORDER_GLOW);

        // 标题栏
        ctx.fill(px + 1, py + 1, pr - 1, py + HEADER_H, C_HEADER_BG);
        ctx.fill(px + 1, py + HEADER_H, pr - 1, py + HEADER_H + 1, C_ACCENT);

        MutableComponent titleText = Component.literal("✦ ")
                .withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(this.title.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" ✦").withStyle(ChatFormatting.LIGHT_PURPLE));
        ctx.centeredText(this.font, titleText, px + PANEL_W / 2, py + 8, 0xFFFFFFFF);

        if (availableInteriors.isEmpty()) {
            ctx.centeredText(this.font, Component.translatable("doctor_m.screen.interior.empty").withStyle(ChatFormatting.RED),
                    px + PANEL_W / 2, py + PANEL_H / 2, 0xFFFFFFFF);
            super.extractRenderState(ctx, mouseX, mouseY, a);
            return;
        }

        // 左侧预览区
        drawInnerPanel(ctx, leftColX, previewY, LEFT_W, PREVIEW_H);

        if (previewingId != null) {
            Identifier texturePath = Identifier.fromNamespaceAndPath(
                    previewingId.getNamespace(),
                    "textures/gui/interior_preview/" + previewingId.getPath() + ".png"
            );

            var mc = net.minecraft.client.Minecraft.getInstance();

            if (mc.getResourceManager().getResource(texturePath).isPresent()) {
                if (mc.getTextureManager().getTexture(texturePath) != null) {
                    ctx.blit(texturePath, leftColX + 2, previewY + 2, leftColX + LEFT_W - 2, previewY + PREVIEW_H - 2, 0.0f, 1.0f, 0.0f, 1.0f);
                } else {
                    ctx.centeredText(this.font, Component.translatable("doctor_m.screen.interior.texture_unloaded").withStyle(ChatFormatting.YELLOW),
                            leftColX + LEFT_W / 2, previewY + PREVIEW_H / 2 - 4, 0xFFFFFFFF);
                }
            } else {
                ctx.centeredText(this.font, Component.translatable("doctor_m.screen.interior.no_preview").withStyle(ChatFormatting.DARK_GRAY),
                        leftColX + LEFT_W / 2, previewY + PREVIEW_H / 2, 0xFFFFFFFF);
            }
        }

        // 右侧信息区
        Component displayName = previewingId != null ? getLocalizedName(previewingId) : Component.translatable("doctor_m.screen.interior.unknown");
        boolean isOriginal = Objects.equals(previewingId, originalInteriorId);

        MutableComponent nameText = Component.translatable("doctor_m.screen.interior.current")
                .append(displayName.copy().withStyle(ChatFormatting.WHITE));
        if (isOriginal) {
            nameText.append(Component.translatable("doctor_m.screen.interior.applied").withStyle(ChatFormatting.GREEN));
        }
        ctx.text(this.font, nameText, rightColX, panelY + HEADER_H + PAD, 0xFFFFFFFF);

        String idStr = previewingId != null ? previewingId.toString() : "null";
        ctx.text(this.font,
                Component.translatable("doctor_m.screen.interior.id", idStr).withStyle(ChatFormatting.DARK_GRAY),
                rightColX, panelY + HEADER_H + PAD + 16, 0xFFFFFFFF);

        ctx.fill(rightColX, panelY + HEADER_H + PAD + 34, rightColX + rightW, panelY + HEADER_H + PAD + 35, C_DIVIDER);

        // 切换按钮文字
        ctx.text(this.font, Component.translatable("doctor_m.screen.interior.switch").withStyle(ChatFormatting.GRAY),
                rightColX, cycleBtnY + 6, 0xFFFFFFFF);

        // 提示信息面板
        drawInnerPanel(ctx, rightColX, infoBoxY, rightW, infoBoxH);
        int infoY = infoBoxY + 8;
        ctx.text(this.font, Component.translatable("doctor_m.screen.interior.tips").withStyle(ChatFormatting.GOLD), rightColX + 8, infoY, 0xFFFFFFFF);
        infoY += 14;
        ctx.text(this.font, Component.translatable("doctor_m.screen.interior.tip1").withStyle(ChatFormatting.GRAY), rightColX + 8, infoY, 0xFFFFFFFF);
        infoY += 12;
        ctx.text(this.font, Component.translatable("doctor_m.screen.interior.tip2").withStyle(ChatFormatting.GRAY), rightColX + 8, infoY, 0xFFFFFFFF);
        infoY += 12;
        ctx.text(this.font, Component.translatable("doctor_m.screen.interior.tip3").withStyle(ChatFormatting.GRAY), rightColX + 8, infoY, 0xFFFFFFFF);

        ctx.fill(rightColX, bottomBtnY - 10, pr - PAD, bottomBtnY - 9, C_DIVIDER);

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