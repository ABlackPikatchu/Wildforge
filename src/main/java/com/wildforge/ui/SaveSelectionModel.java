package com.wildforge.ui;

import com.wildforge.core.SaveManager;
import java.util.List;
import java.util.Objects;

/** Pagination and display labels for world selection; no rendering code. */
public final class SaveSelectionModel {
    public static final int ENTRIES_PER_PAGE = 3;

    private final SaveManager saveManager;
    private List<SaveManager.SaveSlotSummary> slots = List.of();
    private int page;
    private final String[] visibleLabels = new String[ENTRIES_PER_PAGE];
    private final boolean[] visibleExists = new boolean[ENTRIES_PER_PAGE];

    public SaveSelectionModel(SaveManager saveManager) {
        this.saveManager = Objects.requireNonNull(saveManager, "saveManager");
        rebuildVisibleEntries();
    }

    public void refresh() {
        slots = saveManager.listSlots();
        page = Math.min(page, Math.max(0, (slots.size() - 1) / ENTRIES_PER_PAGE));
        rebuildVisibleEntries();
    }

    public void goToFirstPage() { page = 0; rebuildVisibleEntries(); }
    public int page() { return page; }
    public int totalWorlds() { return slots.size(); }
    public boolean hasPreviousPage() { return page > 0; }
    public boolean hasNextPage() { return (page + 1) * ENTRIES_PER_PAGE < slots.size(); }
    public void previousPage() {
        if (hasPreviousPage()) { page--; rebuildVisibleEntries(); }
    }
    public void nextPage() {
        if (hasNextPage()) { page++; rebuildVisibleEntries(); }
    }

    public SaveManager.SaveSlotSummary visibleAt(int index) {
        if (index < 0 || index >= ENTRIES_PER_PAGE) return null;
        int absoluteIndex = page * ENTRIES_PER_PAGE + index;
        return absoluteIndex >= slots.size() ? null : slots.get(absoluteIndex);
    }

    /** Reuse arrays rather than allocating copies once per rendered frame.
     *  The HUD reads them only; do not mutate these returned arrays. */
    public String[] visibleLabels() { return visibleLabels; }
    public boolean[] visibleExists() { return visibleExists; }

    private void rebuildVisibleEntries() {
        for (int i = 0; i < ENTRIES_PER_PAGE; i++) {
            SaveManager.SaveSlotSummary slot = visibleAt(i);
            visibleLabels[i] = slot == null ? "EMPTY" : slot.displayName();
            visibleExists[i] = slot != null && slot.exists();
        }
    }
}
