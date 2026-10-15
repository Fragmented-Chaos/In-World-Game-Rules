package com.fragmentedchaos.inworldgamerules.client;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.LockIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundLockDifficultyPacket;
import net.minecraft.world.Difficulty;

/**
 * 「世界选项」界面：难度 + 难度锁 + 编辑游戏规则。
 * 文案走 {@link Translations}（Fabric/Quilt 上模组语言文件不进资源系统，需自己解析）。
 */
public class WorldOptionsScreen extends Screen {

    private static final int CELL_WIDTH = 150;
    private static final int LOCK_WIDTH = 20;
    private static final int ROW_Y = 70;

    private final Screen lastScreen;
    @Nullable
    private CycleButton<Difficulty> difficultyButton;
    @Nullable
    private LockIconButton lockButton;

    public WorldOptionsScreen(Screen lastScreen) {
        super(Translations.get("inworldgamerules.world_options.title", "World Options"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        Minecraft minecraft = this.minecraft;
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        // 用 getSingleplayerServer() 判定"是不是房主"：世界一旦"对局域网开放"，
        // hasSingleplayerServer() 会变成 false，那样房主自己反而改不了难度。
        boolean host = minecraft.getSingleplayerServer() != null;
        boolean locked = minecraft.level.getLevelData().isDifficultyLocked();
        boolean hardcore = minecraft.level.getLevelData().isHardcore();

        int leftX = this.width / 2 - 155;
        int rightX = this.width / 2 + 5;

        CycleButton<Difficulty> difficulty = OptionsScreen.createDifficultyButton(leftX, ROW_Y, "options.difficulty", minecraft);
        if (host && !hardcore) {
            difficulty.setWidth(CELL_WIDTH - LOCK_WIDTH);
            this.difficultyButton = this.addRenderableWidget(difficulty);
            this.lockButton = this.addRenderableWidget(new LockIconButton(leftX + CELL_WIDTH - LOCK_WIDTH, ROW_Y,
                    button -> minecraft.setScreen(new ConfirmScreen(this::inwrLockCallback,
                            Component.translatable("difficulty.lock.title"),
                            Component.translatable("difficulty.lock.question",
                                    minecraft.level.getLevelData().getDifficulty().getDisplayName())))));
            this.lockButton.setLocked(locked);
            this.lockButton.active = !locked;
            this.difficultyButton.active = !locked;
        } else {
            // 硬核或非主机：只读难度
            difficulty.setWidth(CELL_WIDTH);
            difficulty.active = false;
            this.difficultyButton = this.addRenderableWidget(difficulty);
        }

        boolean canEditRules = WorldOptionsHelper.canEditGameRules(minecraft);
        Component label = Translations.get("inworldgamerules.world_options.gamerules.button", "Edit Game Rules...");
        Button gameRulesButton = Button.builder(label, button -> WorldOptionsHelper.openGameRules(minecraft, this))
                .bounds(rightX, ROW_Y, CELL_WIDTH, 20).build();
        gameRulesButton.active = canEditRules;
        if (!canEditRules) {
            gameRulesButton.setTooltip(Tooltip.create(Translations.get(
                    "inworldgamerules.world_options.gamerules.disabled",
                    "Updating game rules requires operator permissions.")));
        }
        this.addRenderableWidget(gameRulesButton);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    /** 锁定确认回调：顺序同原版 {@code OptionsScreen#lockCallback}。 */
    private void inwrLockCallback(boolean confirmed) {
        this.minecraft.setScreen(this);
        if (confirmed && this.minecraft.level != null) {
            this.minecraft.getConnection().send(new ServerboundLockDifficultyPacket(true));
            if (this.lockButton != null) {
                this.lockButton.setLocked(true);
                this.lockButton.active = false;
            }
            if (this.difficultyButton != null) {
                this.difficultyButton.active = false;
            }
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 33, 16777215);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.lastScreen);
    }
}
