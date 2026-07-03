package io.liparakis.chunkis.mixin.client.screen;

import io.liparakis.chunkis.migration.MigrationProgressTracker;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders MCA-to-CIS migration progress on the singleplayer world loading screen.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin extends Screen {

    /**
     * Protected constructor required by the Screen base class.
     *
     * @param title screen title passed through to the supertype
     */
    protected LevelLoadingScreenMixin(final Text title) {
        super(title);
    }

    /**
     * Draws the current migration status below the default level loading UI.
     *
     * @param context draw context for the current frame
     * @param mouseX  current mouse X position
     * @param mouseY  current mouse Y position
     * @param delta   frame interpolation delta
     * @param ci      mixin callback
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void chunkis$renderMigrationProgress(
            final DrawContext context,
            final int mouseX,
            final int mouseY,
            final float delta,
            final CallbackInfo ci
    ) {
        final String status = MigrationProgressTracker.getStatus();
        if (status == null || this.textRenderer == null) {
            return;
        }

        final int textY = Math.min(this.height - 28, (this.height / 2) + 70);
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal(status),
                this.width / 2,
                textY,
                0xFFFFFF
        );
    }
}
