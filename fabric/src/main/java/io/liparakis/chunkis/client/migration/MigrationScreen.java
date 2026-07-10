package io.liparakis.chunkis.client.migration;

import io.liparakis.chunkis.integration.migration.MigrationProgressTracker;
import java.util.Locale;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Full-screen MCA-to-CIS conversion status display shown while startup waits for
 * background migration workers.
 */
public final class MigrationScreen extends Screen {

    /**
     * Panel and progress colors used by the migration overlay.
     */
    private static final int PANEL_BACKGROUND = 0xD0101216;
    private static final int PANEL_BORDER = 0xFF4C5260;
    private static final int TRACK_BACKGROUND = 0xFF24272C;
    private static final int BLUE = 0xFF2E6BE6;
    private static final int GREEN = 0xFF44DD60;
    private static final int WHITE = 0xFFF4F6FB;
    private static final int MUTED = 0xFFC3C8D2;
    private static final int SCROLL_TRACK = 0xFF20242A;
    private static final int SCROLL_THUMB = 0xFF8792A6;

    /**
     * Current first visible worker row.
     */
    private int scrollIndex;
    /**
     * Whether the scrollbar thumb is currently being dragged.
     */
    private boolean draggingScrollbar;
    /**
     * Cached table bounds used by mouse interaction.
     */
    private int tableX;
    private int tableY;
    private int tableWidth;
    private int tableHeight;
    /**
     * Cached row geometry used to size and scroll the worker table.
     */
    private int rowHeight;
    private int visibleRows;
    /**
     * Largest valid scroll index for the current snapshot.
     */
    private int maxScroll;

    /**
     * Creates the migration progress screen.
     */
    public MigrationScreen() {
        super(Text.literal("Converting MCA to CIS"));
    }

    /**
     * Renders the responsive summary panel, worker table, and scrollbar.
     */
    @Override
    public void render(final DrawContext context, final int mouseX, final int mouseY, final float delta) {
        // Startup can already have consumed the single blur pass allowed per frame.
        // A translucent overlay keeps this screen reliable during that transition.
        context.fill(0, 0, width, height, 0xD0000000);

        final MigrationProgressTracker.Snapshot snapshot = MigrationProgressTracker.snapshot();
        final int panelWidth = Math.clamp(width - 32, 48, 920);
        final int panelX = (width - panelWidth) / 2;
        final int titleY = Math.max(30, height / 10);
        final String world = snapshot.worldId() == null ? "world" : snapshot.worldId();
        context.drawCenteredTextWithShadow(textRenderer,
                "Converting " + world + " from MCA to CIS",
                width / 2,
                titleY,
                WHITE);

        final int progressY = titleY + 34;
        drawPanel(context, panelX, progressY, panelWidth, 42);
        final double overall = regionProgress(snapshot);
        final String summary = String.format(Locale.ROOT,
                "%.1f%%   Regions: %d / %d   Elapsed: %s   ETA: %s",
                overall * 100.0,
                snapshot.completedRegions(),
                snapshot.totalRegions(),
                formatDuration(elapsedSeconds(snapshot.startedAtNanos())),
                formatDuration(etaSeconds(snapshot)));
        context.drawCenteredTextWithShadow(textRenderer, summary, width / 2, progressY + 8, WHITE);
        drawProgressBar(context, panelX + 12, progressY + 20, panelWidth - 24, 16, overall);

        if (snapshot.workers()
                .isEmpty()) {
            return;
        }

        final int tableY = progressY + 56;
        final boolean compact = panelWidth < 720;
        final boolean dense = compact && snapshot.workers()
                .size() > 2;
        final int availableRowsHeight = Math.max(1, height - tableY - 28);
        final int minimumRowHeight = dense ? 12 : compact ? 28 : 24;
        final int desiredRowHeight = dense ? 18 : compact ? 48 : 36;
        final int workerCount = snapshot.workers()
                .size();
        final int maxRowHeight = workerCount <= 2
                ? Math.max(minimumRowHeight, availableRowsHeight / workerCount)
                : Math.max(minimumRowHeight, availableRowsHeight);
        final int rowHeight = Math.clamp(desiredRowHeight, minimumRowHeight, maxRowHeight);
        final int rows = Math.clamp(availableRowsHeight / rowHeight, 1, workerCount);
        final int maxScroll = Math.max(0, workerCount - rows);
        final int firstRow = Math.clamp(scrollIndex, 0, maxScroll);
        final int tableHeight = 28 + rows * rowHeight;
        this.tableX = panelX;
        this.tableY = tableY;
        this.tableWidth = panelWidth;
        this.tableHeight = tableHeight;
        this.rowHeight = rowHeight;
        this.visibleRows = rows;
        this.maxScroll = maxScroll;
        this.scrollIndex = firstRow;
        drawPanel(context, panelX, tableY, panelWidth, tableHeight);
        drawHeaders(context, panelX, tableY, panelWidth, compact, dense);
        for (int row = 0; row < rows && firstRow + row < snapshot.workers()
                .size(); row++) {
            drawWorkerRow(context,
                    snapshot.workers()
                            .get(firstRow + row),
                    panelX,
                    tableY + 28 + row * rowHeight,
                    panelWidth,
                    compact,
                    dense,
                    rowHeight);
        }
        drawScrollbar(context,
                panelX,
                tableY + 28,
                rows * rowHeight,
                snapshot.workers()
                        .size());
    }

    /**
     * Keeps startup migration screens open until migration resumes or fails.
     */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /**
     * Scrolls the worker table when the pointer is inside it.
     */
    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
            final double horizontalAmount, final double verticalAmount) {
        if (maxScroll == 0 || !isInsideTable(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        scrollIndex = Math.clamp(scrollIndex - (int) Math.signum(verticalAmount), 0, maxScroll);
        return true;
    }

    /**
     * Starts scrollbar dragging or jumps to the clicked track position.
     */
    @Override
    public boolean mouseClicked(final Click click, final boolean doubleClick) {
        if (click.button() == 0 && maxScroll > 0 && isInsideScrollbar(click.x(), click.y())) {
            final int trackY = scrollTrackY();
            final int trackHeight = scrollTrackHeight();
            final int thumbHeight = Math.max(12, trackHeight * visibleRows / (visibleRows + maxScroll));
            final int thumbY = trackY + (trackHeight - thumbHeight) * scrollIndex / maxScroll;
            if (click.y() >= thumbY && click.y() <= thumbY + thumbHeight) {
                draggingScrollbar = true;
                return true;
            }
            scrollIndex = Math.clamp((int) ((click.y() - trackY) * (long) maxScroll / trackHeight), 0, maxScroll);
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    /**
     * Updates the scroll index while dragging the scrollbar thumb.
     */
    @Override
    public boolean mouseDragged(final Click click, final double deltaX, final double deltaY) {
        if (!draggingScrollbar) {
            return super.mouseDragged(click, deltaX, deltaY);
        }
        final int trackHeight = scrollTrackHeight();
        final int thumbHeight = Math.max(12, trackHeight * visibleRows / (visibleRows + maxScroll));
        final int travel = Math.max(1, trackHeight - thumbHeight);
        final int trackY = scrollTrackY();
        scrollIndex = Math.clamp((int) ((click.y() - trackY - (double) thumbHeight / 2L) * maxScroll / travel),
                0,
                maxScroll);
        return true;
    }

    /**
     * Stops scrollbar dragging when the mouse button is released.
     */
    @Override
    public boolean mouseReleased(final Click click) {
        draggingScrollbar = false;
        return super.mouseReleased(click);
    }

    /**
     * Returns whether a pointer position is inside the worker table body.
     */
    private boolean isInsideTable(final double mouseX, final double mouseY) {
        return mouseX >= tableX && mouseX <= tableX + tableWidth
                && mouseY >= tableY + 28 && mouseY <= tableY + tableHeight;
    }

    /**
     * Returns whether a pointer position is inside the scrollbar hit area.
     */
    private boolean isInsideScrollbar(final double mouseX, final double mouseY) {
        return mouseX >= tableX + tableWidth - 24 && mouseX <= tableX + tableWidth
                && mouseY >= scrollTrackY() && mouseY <= scrollTrackY() + scrollTrackHeight();
    }

    /**
     * Returns the vertical start of the scrollbar track.
     */
    private int scrollTrackY() {
        return tableY + 28 + 4;
    }

    /**
     * Returns the scrollbar track height excluding panel margins.
     */
    private int scrollTrackHeight() {
        return Math.max(1, visibleRows * rowHeight - 8);
    }

    /**
     * Draws the track and thumb for the current worker-table viewport.
     */
    private void drawScrollbar(final DrawContext context, final int x, final int y,
            final int height, final int totalRows) {
        final int barX = x + tableWidth - 12;
        final int trackY = y + 4;
        final int trackHeight = Math.max(1, height - 8);
        final int thumbHeight = Math.max(12, trackHeight * visibleRows / Math.max(1, totalRows));
        final int thumbY = trackY + (trackHeight - thumbHeight) * scrollIndex / Math.max(1, maxScroll);
        context.fill(barX, trackY, barX + 8, trackY + trackHeight, SCROLL_TRACK);
        context.fill(barX, thumbY, barX + 8, thumbY + thumbHeight, SCROLL_THUMB);
    }

    /**
     * Draws headers for dense, compact, or full-width table layouts.
     */
    private void drawHeaders(final DrawContext context, final int x, final int y, final int panelWidth,
            final boolean compact, final boolean dense) {
        if (dense) {
            context.drawTextWithShadow(textRenderer, "THREAD", x + 8, y + 8, MUTED);
            context.drawTextWithShadow(textRenderer, "REGION", x + 90, y + 8, MUTED);
            context.drawTextWithShadow(textRenderer, "PROGRESS", x + 190, y + 8, MUTED);
            context.drawTextWithShadow(textRenderer, "STATUS", x + panelWidth - 110, y + 8, MUTED);
            context.drawTextWithShadow(textRenderer, "ETA", x + panelWidth - 48, y + 8, MUTED);
            return;
        }
        if (compact) {
            context.drawTextWithShadow(textRenderer, "THREAD", x + 16, y + 6, MUTED);
            context.drawTextWithShadow(textRenderer, "REGION", x + 100, y + 6, MUTED);
            context.drawTextWithShadow(textRenderer, "STATUS", x + 280, y + 6, MUTED);
            context.drawTextWithShadow(textRenderer, "PROGRESS", x + 100, y + 18, MUTED);
            context.drawTextWithShadow(textRenderer, "TIME / ETA", x + 300, y + 18, MUTED);
            return;
        }
        context.drawTextWithShadow(textRenderer, "THREAD", x + 16, y + 10, MUTED);
        context.drawTextWithShadow(textRenderer, "REGION", x + 150, y + 10, MUTED);
        context.drawTextWithShadow(textRenderer, "PROGRESS", x + 290, y + 10, MUTED);
        context.drawTextWithShadow(textRenderer, "STATUS", x + panelWidth - 270, y + 10, MUTED);
        context.drawTextWithShadow(textRenderer, "TIME / ETA", x + panelWidth - 130, y + 10, MUTED);
    }

    /**
     * Dispatches one worker row to the layout appropriate for the screen width.
     */
    private void drawWorkerRow(final DrawContext context,
            final MigrationProgressTracker.WorkerProgress worker,
            final int x,
            final int y,
            final int panelWidth,
            final boolean compact,
            final boolean dense,
            final int rowHeight) {
        if (dense) {
            drawDenseWorkerRow(context, worker, x, y, panelWidth, rowHeight);
            return;
        }
        if (compact) {
            drawCompactWorkerRow(context, worker, x, y, panelWidth, rowHeight);
            return;
        }
        context.fill(x + 1, y, x + panelWidth - 1, y + rowHeight - 2, 0xB0080A0E);
        context.drawTextWithShadow(textRenderer,
                "●",
                x + 16,
                y + 12,
                worker.state() == MigrationProgressTracker.WorkerState.QUEUED ? MUTED : GREEN);
        context.drawTextWithShadow(textRenderer, "Thread " + worker.worker(), x + 34, y + 12, WHITE);
        context.drawTextWithShadow(textRenderer, worker.regionFileName(), x + 150, y + 12, WHITE);

        final int statusX = x + panelWidth - 270;
        final int progressX = x + 290;
        final int progressWidth = Math.clamp(statusX - progressX - 44, 40, 190);
        drawProgressBar(context,
                progressX,
                y + 9,
                progressWidth,
                16,
                workerProgress(worker));
        context.drawTextWithShadow(textRenderer,
                String.format(Locale.ROOT, "%3.0f%%", workerProgress(worker) * 100.0),
                progressX + progressWidth + 12,
                y + 12,
                WHITE);
        context.drawTextWithShadow(textRenderer, status(worker), statusX, y + 12, statusColor(worker));
        context.drawTextWithShadow(textRenderer, formatDuration(elapsedSeconds(worker.startedAtNanos())) + " / "
                + formatEta(worker), x + panelWidth - 130, y + 12, MUTED);
    }

    /**
     * Draws a two-line worker row for compact screens.
     */
    private void drawCompactWorkerRow(final DrawContext context,
            final MigrationProgressTracker.WorkerProgress worker,
            final int x,
            final int y,
            final int panelWidth,
            final int rowHeight) {
        context.fill(x + 1, y, x + panelWidth - 1, y + rowHeight - 2, 0xB0080A0E);
        context.drawTextWithShadow(textRenderer, "●", x + 16, y + 5,
                worker.state() == MigrationProgressTracker.WorkerState.QUEUED ? MUTED : GREEN);
        context.drawTextWithShadow(textRenderer, "Thread " + worker.worker(), x + 34, y + 5, WHITE);
        context.drawTextWithShadow(textRenderer, worker.regionFileName(), x + 100, y + 5, WHITE);
        context.drawTextWithShadow(textRenderer, status(worker), x + 280, y + 5, statusColor(worker));

        final int progressX = x + 100;
        final int progressWidth = Math.clamp(panelWidth - 258, 40, 150);
        final int barY = y + rowHeight - 14;
        drawProgressBar(context, progressX, barY, progressWidth, 10, workerProgress(worker));
        context.drawTextWithShadow(textRenderer,
                String.format(Locale.ROOT, "%3.0f%%", workerProgress(worker) * 100.0),
                progressX + progressWidth + 8, barY, WHITE);
        context.drawTextWithShadow(textRenderer,
                formatShortDuration(elapsedSeconds(worker.startedAtNanos())) + " / "
                        + formatShortEta(worker),
                x + 300, barY, MUTED);
    }

    /**
     * Draws a single-line worker row when many workers are visible.
     */
    private void drawDenseWorkerRow(final DrawContext context,
            final MigrationProgressTracker.WorkerProgress worker,
            final int x,
            final int y,
            final int panelWidth,
            final int rowHeight) {
        context.fill(x + 1, y, x + panelWidth - 1, y + rowHeight - 1, 0xB0080A0E);
        context.drawTextWithShadow(textRenderer, "●", x + 8, y + 2,
                worker.state() == MigrationProgressTracker.WorkerState.QUEUED ? MUTED : GREEN);
        context.drawTextWithShadow(textRenderer, "T" + worker.worker(), x + 24, y + 2, WHITE);
        context.drawTextWithShadow(textRenderer, worker.regionFileName(), x + 90, y + 2, WHITE);

        final int progressX = x + 190;
        final int statusX = x + panelWidth - 110;
        final int progressWidth = Math.clamp(statusX - progressX - 42, 35, 100);
        drawProgressBar(context, progressX, y + 2, progressWidth, 8, workerProgress(worker));
        context.drawTextWithShadow(textRenderer,
                String.format(Locale.ROOT, "%3.0f%%", workerProgress(worker) * 100.0),
                progressX + progressWidth + 5, y + 2, WHITE);
        context.drawTextWithShadow(textRenderer, shortStatus(worker), statusX, y + 2, statusColor(worker));
        context.drawTextWithShadow(textRenderer, formatShortEta(worker), x + panelWidth - 48, y + 2, MUTED);
    }

    /**
     * Returns abbreviated status text for dense rows.
     */
    private static String shortStatus(final MigrationProgressTracker.WorkerProgress worker) {
        if (worker.state() == MigrationProgressTracker.WorkerState.CONVERTING
                && worker.totalChunks() > 0
                && worker.processedChunks() >= worker.totalChunks()) {
            return "Fin.";
        }
        return switch (worker.state()) {
            case QUEUED -> "Queue";
            case CONVERTING -> "Conv.";
            case COMPLETED -> worker.failedChunks() == 0 ? "Done" : "Fail";
        };
    }

    /**
     * Returns full status text for normal and compact rows.
     */
    private static String status(final MigrationProgressTracker.WorkerProgress worker) {
        if (worker.state() == MigrationProgressTracker.WorkerState.CONVERTING
                && worker.totalChunks() > 0
                && worker.processedChunks() >= worker.totalChunks()) {
            return "Finalizing";
        }
        return switch (worker.state()) {
            case QUEUED -> "Queued";
            case CONVERTING -> "Converting";
            case COMPLETED -> worker.failedChunks() == 0 ? "Completed" : "Failed";
        };
    }

    /**
     * Returns the status color associated with the worker lifecycle state.
     */
    private static int statusColor(final MigrationProgressTracker.WorkerProgress worker) {
        if (worker.state() == MigrationProgressTracker.WorkerState.CONVERTING
                && worker.totalChunks() > 0
                && worker.processedChunks() >= worker.totalChunks()) {
            return 0xFFFFC43D;
        }
        return switch (worker.state()) {
            case QUEUED -> MUTED;
            case CONVERTING, COMPLETED -> worker.failedChunks() == 0 ? GREEN : 0xFFFF6B4A;
        };
    }

    /**
     * Calculates the clamped progress fraction for one worker.
     */
    private static double workerProgress(final MigrationProgressTracker.WorkerProgress worker) {
        return worker.state() == MigrationProgressTracker.WorkerState.COMPLETED
                ? 1.0
                : ratio(worker.processedChunks(), worker.totalChunks());
    }

    /**
     * Draws a filled panel with the overlay border style.
     */
    private static void drawPanel(final DrawContext context,
            final int x,
            final int y,
            final int panelWidth,
            final int panelHeight) {
        context.fill(x, y, x + panelWidth, y + panelHeight, PANEL_BACKGROUND);
        context.drawStrokedRectangle(x, y, panelWidth, panelHeight, PANEL_BORDER);
    }

    /**
     * Draws a clamped progress bar with a filled and track portion.
     */
    @SuppressWarnings("SameParameterValue")
    private static void drawProgressBar(final DrawContext context,
            final int x,
            final int y,
            final int progressWidth,
            final int progressHeight,
            final double progress) {
        context.fill(x, y, x + progressWidth, y + progressHeight, TRACK_BACKGROUND);
        final int filledWidth = (int) Math.round(progressWidth * Math.clamp(progress, 0.0, 1.0));
        if (filledWidth > 0) {
            context.fill(x, y, x + filledWidth, y + progressHeight, BLUE);
        }
        context.drawStrokedRectangle(x, y, progressWidth, progressHeight, PANEL_BORDER);
    }

    /**
     * Calculates overall region completion as a clamped fraction.
     */
    private static double regionProgress(final MigrationProgressTracker.Snapshot snapshot) {
        return ratio(snapshot.completedRegions(), snapshot.totalRegions());
    }

    /**
     * Divides progress values safely and clamps the result to [0, 1].
     */
    private static double ratio(final long current, final long total) {
        return total <= 0 ? 0.0 : Math.clamp((double) current / total, 0.0, 1.0);
    }

    /**
     * Converts an elapsed monotonic-nanosecond timestamp to whole seconds.
     */
    private static long elapsedSeconds(final long startedAtNanos) {
        return startedAtNanos == 0L ? 0L : (System.nanoTime() - startedAtNanos) / 1_000_000_000L;
    }

    /**
     * Combines byte-weighted, queued-work, and active-worker ETA estimates.
     */
    static long etaSeconds(final MigrationProgressTracker.Snapshot snapshot) {
        final long slowestWorkerEta = snapshot.workers()
                .stream()
                .mapToLong(MigrationScreen::workerEtaSeconds)
                .max()
                .orElse(0L);
        final long elapsed = Math.max(1L, elapsedSeconds(snapshot.startedAtNanos()));
        final long remainingRegions = Math.max(0L, snapshot.totalRegions() - snapshot.completedRegions());
        final long regionEta = snapshot.completedRegions() == 0 || remainingRegions == 0
                ? 0L
                : ceilDivide(elapsed * remainingRegions, snapshot.completedRegions());
        final long byteEta = byteEtaSeconds(snapshot);
        final long queuedWorkEta = queuedWorkEta(snapshot, elapsed, slowestWorkerEta);
        if (snapshot.processedChunks() == 0 || snapshot.totalChunks() <= snapshot.processedChunks()) {
            return Math.max(byteEta, Math.max(queuedWorkEta, Math.max(regionEta, slowestWorkerEta)));
        }
        final long remainingChunks = snapshot.totalChunks() - snapshot.processedChunks();
        final long aggregateEta = ceilDivide(elapsed * remainingChunks, snapshot.processedChunks());
        return Math.max(byteEta,
                Math.max(queuedWorkEta, Math.max(regionEta, Math.max(aggregateEta, slowestWorkerEta))));
    }

    /**
     * Estimates remaining time from the first completed worker batch's byte rate.
     */
    private static long byteEtaSeconds(final MigrationProgressTracker.Snapshot snapshot) {
        final long totalBytes = Math.max(0L, snapshot.totalBytes());
        final long processedBytes = Math.clamp(snapshot.processedBytes(), 0L, totalBytes);
        final long sampleBytes = Math.clamp(snapshot.sampleBytes(), 0L, totalBytes);
        if (sampleBytes == 0L || totalBytes <= processedBytes) {
            return 0L;
        }
        final long sampleElapsed = Math.max(1L, snapshot.sampleElapsedNanos() / 1_000_000_000L);
        return ceilDivide(sampleElapsed * (totalBytes - processedBytes), sampleBytes);
    }

    /**
     * Estimates work that has not reached a worker yet. The active-worker ETA
     * alone cannot account for the executor queue, so queued work is added to
     * the active tail using the measured global throughput.
     */
    private static long queuedWorkEta(final MigrationProgressTracker.Snapshot snapshot,
            final long elapsed,
            final long activeTailEta) {
        final long totalChunks = Math.max(0L, snapshot.totalChunks());
        final long processedChunks = Math.clamp(snapshot.processedChunks(), 0L, totalChunks);
        final long activeRemainingChunks = Math.clamp(snapshot.workers()
                .stream()
                .filter(worker -> worker.state() == MigrationProgressTracker.WorkerState.CONVERTING)
                .mapToLong(worker -> Math.max(0L, (long) worker.totalChunks() - worker.processedChunks()))
                .sum(), 0L, totalChunks - processedChunks);
        final long queuedChunks = Math.clamp(totalChunks - processedChunks - activeRemainingChunks,
                0L,
                totalChunks - processedChunks);
        final int totalRegions = Math.max(0, snapshot.totalRegions());
        final int completedRegions = Math.clamp(snapshot.completedRegions(), 0, totalRegions);
        final long activeRegions = snapshot.workers()
                .stream()
                .filter(worker -> worker.state() == MigrationProgressTracker.WorkerState.CONVERTING)
                .count();
        final long queuedRegions = Math.clamp(totalRegions - completedRegions - activeRegions,
                0L,
                (long) totalRegions - completedRegions);
        if (queuedChunks == 0L && queuedRegions == 0L) {
            return activeTailEta;
        }

        long queuedEta = 0L;
        if (queuedChunks > 0L && processedChunks > 0L) {
            final long chunksPerSecond = Math.max(1L, processedChunks / elapsed);
            queuedEta = ceilDivide(queuedChunks, chunksPerSecond);
        }
        if (queuedRegions > 0L && completedRegions > 0) {
            queuedEta = Math.max(queuedEta,
                    ceilDivide(elapsed * queuedRegions, completedRegions));
        }
        return activeTailEta + queuedEta;
    }

    /**
     * Estimates remaining time for the worker's current region.
     */
    private static long workerEtaSeconds(final MigrationProgressTracker.WorkerProgress worker) {
        if (worker.processedChunks() == 0 || worker.totalChunks() <= worker.processedChunks()) {
            return 0L;
        }
        final long elapsed = Math.max(1L, elapsedSeconds(worker.startedAtNanos()));
        return ceilDivide(elapsed * (worker.totalChunks() - worker.processedChunks()), worker.processedChunks());
    }

    /**
     * Performs positive integer ceiling division for ETA calculations.
     */
    private static long ceilDivide(final long numerator, final long denominator) {
        return (numerator + denominator - 1L) / denominator;
    }

    /**
     * Formats seconds as a full HH:MM:SS duration.
     */
    private static String formatDuration(final long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    /**
     * Formats seconds as a compact MM:SS duration.
     */
    private static String formatShortDuration(final long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d", (seconds / 60) % 60, seconds % 60);
    }

    /**
     * Formats a worker ETA for dense rows.
     */
    private static String formatShortEta(final MigrationProgressTracker.WorkerProgress worker) {
        return worker.state() != MigrationProgressTracker.WorkerState.CONVERTING
                || worker.totalChunks() <= 0
                || worker.processedChunks() >= worker.totalChunks()
                ? "--:--"
                : formatShortDuration(workerEtaSeconds(worker));
    }

    /**
     * Formats a worker ETA for full-width rows.
     */
    private static String formatEta(final MigrationProgressTracker.WorkerProgress worker) {
        return worker.state() != MigrationProgressTracker.WorkerState.CONVERTING
                || worker.totalChunks() <= 0
                || worker.processedChunks() >= worker.totalChunks()
                ? "--:--:--"
                : formatDuration(workerEtaSeconds(worker));
    }
}
