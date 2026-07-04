package io.liparakis.chunkis.mixin.client.screen;

import java.lang.reflect.Method;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws live MCA-to-CIS migration progress on the resource-reload splash overlay.
 */
@Mixin(SplashOverlay.class)
public abstract class SplashOverlayMixin {

    /**
     * Fully qualified tracker class name resolved reflectively so this client mixin
     * can read startup migration status without introducing a direct linkage issue.
     */
    @Unique
    private static final String TRACKER_CLASS = "io.liparakis.chunkis.migration.MigrationProgressTracker";

    /**
     * Active client instance that owns the current splash overlay render pass.
     */
    @Shadow
    @Final
    private MinecraftClient client;

    /**
     * Reads the latest startup migration status via reflection.
     *
     * <p>This mixin stays defensive here because the progress tracker lives outside
     * the immediate client mixin package and startup UI should fail soft if that
     * helper is absent or renamed.</p>
     *
     * @return current migration status text, or {@code null} when unavailable
     */
    @Unique
    private static String chunkis$getMigrationStatus() {
        try {
            final Class<?> trackerClass = Class.forName(TRACKER_CLASS);
            final Method getStatus = trackerClass.getMethod("getStatus");
            return (String) getStatus.invoke(null);
        } catch (final ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * Paints the current MCA-to-CIS migration status over the vanilla splash overlay
     * when a startup migration is in progress.
     *
     * @param context draw context for the current frame
     * @param mouseX  current mouse X position
     * @param mouseY  current mouse Y position
     * @param delta   frame delta passed by vanilla
     * @param ci      mixin callback context
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void chunkis$renderMigrationStatus(
            final DrawContext context,
            final int mouseX,
            final int mouseY,
            final float delta,
            final CallbackInfo ci
    ) {
        final String status = chunkis$getMigrationStatus();
        if (status == null || client == null) {
            return;
        }

        final TextRenderer textRenderer = client.textRenderer;
        if (textRenderer == null) {
            return;
        }

        final int width = context.getScaledWindowWidth();
        final int height = context.getScaledWindowHeight();
        final int textWidth = textRenderer.getWidth(status);
        final int textX = (width - textWidth) / 2;
        final int textY = (height / 2) - 8;
        final int padding = 6;

        context.fill(
                textX - padding,
                textY - padding,
                textX + textWidth + padding,
                textY + 9 + padding,
                0xA0000000
        );
        context.drawTextWithShadow(
                textRenderer,
                Text.literal(status),
                textX,
                textY,
                0xFFFFFF
        );
    }
}
