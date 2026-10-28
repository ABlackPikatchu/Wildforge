package com.wildforge.render;

import com.wildforge.core.GameSettings;
import com.wildforge.core.KeyBindings;
import java.util.List;

import com.wildforge.render.HudRenderer.MenuButton;
import com.wildforge.render.HudRenderer.ScreenMode;
import static com.wildforge.render.PixelFontRenderer.*;
import static com.wildforge.render.UiPrimitives.rect;

/** Menu layouts and input geometry. One source of truth for buttons and sliders. */
final class MenuRenderer {
    private final UiSkin uiSkin;

    MenuRenderer(UiSkin uiSkin) {
        this.uiSkin = uiSkin;
    }

    public MenuButton buttonAt(
            float mouseX,
            float mouseY,
            int width,
            int height,
            ScreenMode screenMode) {
        return buttonAt(mouseX, mouseY, width, height, screenMode, 0);
    }

    public MenuButton buttonAt(
            float mouseX,
            float mouseY,
            int width,
            int height,
            ScreenMode screenMode,
            int optionsPage) {

        if (screenMode != ScreenMode.MAIN_MENU
                && screenMode != ScreenMode.SAVE_SELECT
                && screenMode != ScreenMode.OPTIONS
                && screenMode != ScreenMode.DELETE_CONFIRM
                && screenMode != ScreenMode.PAUSED
                && screenMode != ScreenMode.KEYBINDS) {
            return MenuButton.NONE;
        }

        float scale = HudRenderer.effectiveUiScale(width, height);
        mouseX /= scale;
        mouseY /= scale;
        width = HudRenderer.virtualSize(width, scale);
        height = HudRenderer.virtualSize(height, scale);
        return buttonAtVirtual(mouseX, mouseY, width, height, screenMode, optionsPage);
    }

    private MenuButton buttonAtVirtual(float mouseX, float mouseY,
                                       int width, int height,
                                       ScreenMode screenMode, int optionsPage) {
        // Delete buttons are inset inside save rows; test those first.
        for (int index = buttonCount(screenMode, optionsPage) - 1; index >= 0; index--) {
            float[] bounds = buttonBounds(index, width, height, screenMode, optionsPage);
            if (mouseX >= bounds[0]
                    && mouseX < bounds[0] + bounds[2]
                    && mouseY >= bounds[1]
                    && mouseY < bounds[1] + bounds[3]) {
                return buttonForIndex(index, screenMode, optionsPage);
            }
        }

        return MenuButton.NONE;
    }

    /** Convert physical framebuffer coordinates to the virtual FOV slider. */
    public int fovSliderValueAt(float mouseX, float mouseY,
                                int width, int height, int optionsPage) {
        if (optionsPage != 0) return -1;
        float scale = HudRenderer.effectiveUiScale(width, height);
        mouseX /= scale;
        mouseY /= scale;
        width = HudRenderer.virtualSize(width, scale);
        height = HudRenderer.virtualSize(height, scale);
        float[] row = buttonBounds(6, width, height, ScreenMode.OPTIONS, optionsPage);
        float[] track = fovSliderBounds(row, optionsMenuScale(width, height, optionsPage));
        float margin = Math.max(6f * optionsMenuScale(width, height, optionsPage), 4f);
        if (mouseX < track[0] - margin || mouseX > track[0] + track[2] + margin
                || mouseY < row[1] || mouseY > row[1] + row[3]) return -1;
        return sliderValue(mouseX, track, GameSettings.MIN_FOV, GameSettings.MAX_FOV, 1);
    }

    public int fovSliderValueClamped(float mouseX, int width, int height, int optionsPage) {
        if (optionsPage != 0) return -1;
        float scale = HudRenderer.effectiveUiScale(width, height);
        mouseX /= scale;
        width = HudRenderer.virtualSize(width, scale);
        height = HudRenderer.virtualSize(height, scale);
        float[] row = buttonBounds(6, width, height, ScreenMode.OPTIONS, optionsPage);
        float[] track = fovSliderBounds(row, optionsMenuScale(width, height, optionsPage));
        return sliderValue(mouseX, track, GameSettings.MIN_FOV, GameSettings.MAX_FOV, 1);
    }

    /**
     * Return an anchored, physical-pixel slider track for drag gestures. The
     * scale changes as the user drags, so the gesture must retain the original
     * geometry rather than jumping when the menu itself resizes.
     */
    public float[] uiScaleSliderTrack(int width, int height, int optionsPage) {
        if (optionsPage != 0 || width <= 0 || height <= 0) return null;
        float scale = HudRenderer.effectiveUiScale(width, height);
        int virtualWidth = HudRenderer.virtualSize(width, scale);
        int virtualHeight = HudRenderer.virtualSize(height, scale);
        float[] row = buttonBounds(8, virtualWidth, virtualHeight,
                ScreenMode.OPTIONS, optionsPage);
        float[] virtualTrack = fovSliderBounds(row,
                optionsMenuScale(virtualWidth, virtualHeight, optionsPage));
        return new float[] {
                virtualTrack[0] * scale,
                virtualTrack[1] * scale,
                virtualTrack[2] * scale,
                virtualTrack[3] * scale
        };
    }

    private static int sliderValue(float cursorX, float[] track,
                                   int minimum, int maximum, int step) {
        float ratio = Math.max(0f, Math.min(1f,
                (cursorX - track[0]) / track[2]));
        int value = minimum + Math.round(ratio * (maximum - minimum) / step) * step;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private float[] fovSliderBounds(float[] row, float scale) {
        return new float[] {row[0] + 258f * scale, row[1] + 29f * scale,
                row[2] - 355f * scale, 10f * scale};
    }

    /**
     * The artwork contains the Wildforge logo, so the main menu must not draw
     * another title over it. Buttons sit in a low-profile footer instead.
     */
    void drawMenu(
            List<Float> data,
            List<Float> dimData,
            List<Float> skinData,
            int width,
            int height,
            float mouseX,
            float mouseY,
            ScreenMode screenMode,
            String statusMessage,
            String[] visibleSaveLabels,
            boolean[] visibleSaveExists,
            int savePage,
            boolean hasPreviousSavePage,
            boolean hasNextSavePage,
            GameSettings settings,
            String deleteWorldLabel,
            int optionsPage) {

        if (screenMode == ScreenMode.MAIN_MENU) {
            // A shallow footer sits completely inside the window. The title art
            // already contains the logo, so no competing header is drawn.
            float s = mainMenuScale(width, height);
            float[] footer = mainFooterGeometry(width, height);
            uiSkin.menuFooter(skinData, footer[0], footer[1], footer[2], footer[3]);
            menuText(data, "EXPLORE   BUILD   FORGE", width / 2f,
                    footer[1] + 87f * s, 1.5f * s,
                    0.94f, 0.79f, 0.52f, 0.9f);
        } else {
            rect(dimData, 0, 0, width, height,
                    0.018f, 0.028f, 0.035f,
                    screenMode == ScreenMode.SAVE_SELECT ? 0.40f : 0.54f);

            // Shared panel geometry is also used for button hit testing.
            // This allows compact tabs without disconnecting click targets.
            float[] panel = menuPanelGeometry(width, height, screenMode, optionsPage);
            float x = panel[0];
            float y = panel[1];
            float w = panel[2];
            float h = panel[3];
            float s = panel[4];
            uiSkin.menuPanel(skinData, x, y, w, h);

            if (screenMode == ScreenMode.SAVE_SELECT) {
                boolean anySave = false;
                if (visibleSaveExists != null) {
                    for (boolean exists : visibleSaveExists) anySave |= exists;
                }
                menuText(data, "SELECT WORLD", width / 2f,
                        y + 28f * s, 3.35f * s,
                        0.98f, 0.83f, 0.55f, 1f);
                menuText(data, anySave ? "CHOOSE A SAVE OR START FRESH"
                                : "START YOUR FIRST ADVENTURE",
                        width / 2f, y + 68f * s, 1.50f * s,
                        0.76f, 0.76f, 0.72f, 1f);
                uiSkin.divider(skinData, x + 36f * s,
                        y + 93f * s, w - 72f * s);
                menuText(data, "PAGE " + (savePage + 1), width / 2f,
                        y + 436f * s, 1.50f * s,
                        0.83f, 0.79f, 0.71f, 1f);
            } else if (screenMode == ScreenMode.OPTIONS) {
                menuText(data, "OPTIONS", width / 2f, y + 29f * s,
                        3.65f * s, 0.98f, 0.83f, 0.55f, 1f);
                menuText(data, optionsPage == 0 ? "VIDEO AND DISPLAY SETTINGS"
                                : "CONTROLS AND INTERFACE",
                        width / 2f, y + 75f * s, 1.5f * s,
                        0.78f, 0.78f, 0.73f, 1f);
            } else if (screenMode == ScreenMode.KEYBINDS) {
                menuText(data, "KEY BINDINGS", width / 2f, y + 30f * s,
                        3.55f * s, 0.98f, 0.83f, 0.55f, 1f);
                menuText(data, "CLICK A KEY TO REBIND   PAGE " + (optionsPage - 1) + " OF 3",
                        width / 2f, y + 76f * s, 1.45f * s,
                        0.78f, 0.79f, 0.75f, 1f);
            } else if (screenMode == ScreenMode.DELETE_CONFIRM) {
                menuText(data, "DELETE WORLD?", width / 2f,
                        y + 32f * s, 3.7f * s,
                        1f, 0.67f, 0.46f, 1f);
                menuText(data, deleteWorldLabel == null ? "WORLD" : deleteWorldLabel,
                        width / 2f, y + 100f * s, 2.6f * s,
                        0.98f, 0.94f, 0.86f, 1f);
                menuText(data, "THIS CANNOT BE UNDONE", width / 2f,
                        y + 146f * s, 1.65f * s,
                        1f, 0.54f, 0.45f, 1f);
            } else {
                menuText(data, "GAME PAUSED", width / 2f,
                        y + 61f * s, 4.0f * s,
                        0.98f, 0.83f, 0.55f, 1f);
                menuText(data, "YOUR ADVENTURE AWAITS", width / 2f,
                        y + 112f * s, 1.7f * s,
                        0.77f, 0.77f, 0.74f, 1f);
            }
        }

        for (int index = 0; index < buttonCount(screenMode, optionsPage); index++) {
            MenuButton button = buttonForIndex(index, screenMode, optionsPage);
            float[] bounds = buttonBounds(index, width, height, screenMode, optionsPage);
            float x = bounds[0];
            float y = bounds[1];
            float w = bounds[2];
            float h = bounds[3];

            boolean saveEntry = screenMode == ScreenMode.SAVE_SELECT && index < 3;
            boolean deleteEntry = screenMode == ScreenMode.SAVE_SELECT && index >= 7;
            int worldIndex = deleteEntry ? index - 7 : index;
            boolean existingSave = (saveEntry || deleteEntry)
                    && visibleSaveExists != null
                    && worldIndex < visibleSaveExists.length
                    && visibleSaveExists[worldIndex];
            boolean disabled = (screenMode == ScreenMode.KEYBINDS
                    && ((button == MenuButton.BIND_PREVIOUS && optionsPage <= 2)
                        || (button == MenuButton.BIND_NEXT && optionsPage >= 4)
                        || (button.name().startsWith("BIND_")
                            && button.ordinal() >= MenuButton.BIND_1.ordinal()
                            && button.ordinal() <= MenuButton.BIND_6.ordinal()
                            && KeyBindings.Action.at((optionsPage - 2) * 6
                                + button.ordinal() - MenuButton.BIND_1.ordinal()) == null)))
                    || ((saveEntry || deleteEntry) && !existingSave)
                    || (button == MenuButton.PREVIOUS_PAGE && !hasPreviousSavePage)
                    || (button == MenuButton.NEXT_PAGE && !hasNextSavePage)
                    || (screenMode == ScreenMode.OPTIONS && settings != null
                        && button == MenuButton.RESOLUTION && !settings.allowsResolutionChange());
            boolean hovered = !disabled && buttonAtVirtual(mouseX, mouseY,
                    width, height, screenMode, optionsPage) == button;
            boolean emphasized = button == MenuButton.PLAY || button == MenuButton.NEW_GAME
                    || (button == MenuButton.CONTINUE && screenMode == ScreenMode.PAUSED);
            float s = screenMode == ScreenMode.MAIN_MENU
                    ? mainMenuScale(width, height)
                    : menuPanelGeometry(width, height, screenMode, optionsPage)[4];

            boolean danger = button == MenuButton.CONFIRM_DELETE || deleteEntry;
            boolean selectedTab = (button == MenuButton.VIDEO_TAB && optionsPage == 0)
                    || (button == MenuButton.CONTROLS_TAB && optionsPage == 1);

            // Unused save slots are quiet placeholders, not three huge
            // disabled buttons. Creation always goes through NEW GAME.
            if (saveEntry && !existingSave) {
                uiSkin.divider(skinData, x + 10f * s, y + h - 7f * s,
                        w - 20f * s);
                menuText(data, "EMPTY SLOT", x + w / 2f,
                        y + 22f * s, 1.85f * s,
                        0.61f, 0.62f, 0.61f, 0.9f);
                continue;
            }
            if (deleteEntry && !existingSave) continue;

            uiSkin.menuButton(skinData, x, y, w, h,
                    hovered, emphasized, disabled, danger, selectedTab);

            if (screenMode == ScreenMode.KEYBINDS && button.ordinal() >= MenuButton.BIND_1.ordinal()
                    && button.ordinal() <= MenuButton.BIND_6.ordinal()) {
                KeyBindings.Action action = KeyBindings.Action.at((optionsPage - 2) * 6
                        + button.ordinal() - MenuButton.BIND_1.ordinal());
                if (action != null && settings != null) {
                    menuText(data, action.label(), x + 175f * s, y + 15f * s,
                            1.9f * s, 0.95f, 0.93f, 0.86f, 1f);
                    menuText(data, settings.keyBindings().keyLabel(action), x + 475f * s,
                            y + 15f * s, 1.9f * s, 1f, 0.74f, 0.42f, 1f);
                }
                continue;
            }
            if (screenMode == ScreenMode.OPTIONS && settings != null
                    && (button == MenuButton.FOV || button == MenuButton.UI_SCALE)) {
                boolean isFov = button == MenuButton.FOV;
                menuText(data, isFov ? "FIELD OF VIEW" : "UI SCALE", x + 120f * s,
                        y + 17f * s, 2.0f * s,
                        0.96f, 0.92f, 0.85f, 1f);
                float[] track = fovSliderBounds(bounds, s);
                float ratio = isFov
                        ? (settings.fov() - GameSettings.MIN_FOV)
                                / (float) (GameSettings.MAX_FOV - GameSettings.MIN_FOV)
                        : (settings.uiScalePercent() - GameSettings.MIN_UI_SCALE)
                                / (float) (GameSettings.MAX_UI_SCALE - GameSettings.MIN_UI_SCALE);
                ratio = Math.max(0f, Math.min(1f, ratio));
                uiSkin.menuSlider(skinData, track[0], track[1],
                        track[2], track[3], ratio, s);
                menuText(data, isFov ? settings.fov() + " DEG"
                                : settings.uiScalePercent() + " PCT", x + 519f * s,
                        y + 15f * s, 1.9f * s,
                        0.99f, 0.87f, 0.66f, 1f);
                continue;
            }
            if (deleteEntry) {
                menuText(data, "DELETE", x + w / 2f,
                        y + (h - 7f * 1.65f * s) / 2f, 1.65f * s,
                        disabled ? 0.55f : hovered ? 1f : 0.94f,
                        disabled ? 0.55f : 0.55f,
                        disabled ? 0.55f : 0.46f, 1f);
                continue;
            }

            if (saveEntry) {
                String label = existingSave && visibleSaveLabels != null
                        && index < visibleSaveLabels.length
                        && visibleSaveLabels[index] != null
                        ? visibleSaveLabels[index] : "EMPTY SLOT";
                menuText(data, label, x + 204f * s, y + 9f * s,
                        2.45f * s,
                        existingSave ? 0.96f : 0.70f,
                        existingSave ? 0.92f : 0.70f,
                        existingSave ? 0.82f : 0.70f, 1f);
                menuText(data, "LOAD WORLD",
                        x + 204f * s, y + 36f * s, 1.35f * s,
                        existingSave ? 0.74f : 0.60f,
                        existingSave ? 0.76f : 0.61f,
                        existingSave ? 0.70f : 0.59f, 1f);
            } else {
                String label = button == MenuButton.BIND_PREVIOUS ? "PREVIOUS"
                        : button == MenuButton.BIND_NEXT ? "NEXT"
                        : button == MenuButton.RESET_BINDS ? "RESET DEFAULTS"
                        : button == MenuButton.PREVIOUS_PAGE ? "PREVIOUS"
                        : button == MenuButton.NEXT_PAGE ? "NEXT"
                        : screenMode == ScreenMode.OPTIONS ? optionLabel(button, settings)
                        : buttonLabel(button);
                float size = screenMode == ScreenMode.SAVE_SELECT ? 2.35f * s
                        : screenMode == ScreenMode.OPTIONS || screenMode == ScreenMode.KEYBINDS
                                ? 2.15f * s : 2.95f * s;
                menuText(data, label, x + w / 2f,
                        y + (h - 7f * size) / 2f, size,
                        disabled ? 0.54f : hovered ? 1.0f : 0.96f,
                        disabled ? 0.55f : hovered ? 0.87f : 0.93f,
                        disabled ? 0.53f : hovered ? 0.58f : 0.86f, 1f);
            }
        }

        if (statusMessage != null && !statusMessage.isBlank()) {
            menuText(data, statusMessage.toUpperCase(), width / 2f,
                    height * 0.96f, 1.9f, 1f, 0.55f, 0.43f, 1f);
        }
    }

    private String optionLabel(MenuButton button, GameSettings settings) {
        if (settings == null) return buttonLabel(button);
        return switch (button) {
            case VSYNC -> "VSYNC " + (settings.vsync() ? "ON" : "OFF");
            case DISPLAY_MODE -> "DISPLAY MODE  " + settings.displayMode().label();
            case RESOLUTION -> settings.allowsResolutionChange()
                    ? "RESOLUTION " + settings.resolutionLabel()
                    : "RESOLUTION  AUTO";
            case FPS_LIMIT -> "MAX FPS " + settings.fpsLimitLabel();
            case SHOW_FPS -> "SHOW FPS " + (settings.showFps() ? "ON" : "OFF");
            case VIDEO_TAB -> "VIDEO";
            case CONTROLS_TAB -> "CONTROLS";
            case CROSSHAIR -> "CROSSHAIR " + (settings.crosshair() ? "ON" : "OFF");
            case UI_SCALE -> "UI SCALE " + settings.uiScalePercent() + " PCT";
            case KEYBINDS_TAB -> "KEY BINDINGS";
            default -> buttonLabel(button);
        };
    }

    private void menuText(List<Float> data, String value, float centerX,
                          float y, float size, float r, float g, float b,
                          float alpha) {
        drawText(data, value, centerX + size * 0.52f, y + size * 0.6f,
                size, 0.015f, 0.018f, 0.024f, alpha * 0.8f);
        drawText(data, value, centerX, y,
                size, r, g, b, alpha);
    }

    private float mainMenuScale(int width, int height) {
        return Math.min(1f, Math.min((width - 36f) / 964f,
                (height - 26f) / 670f));
    }

    private float[] mainFooterGeometry(int width, int height) {
        float s = mainMenuScale(width, height);
        float w = 964f * s;
        float h = 120f * s;
        return new float[] {(width - w) / 2f,
                height - h - 25f * s, w, h, s};
    }

    /**
     * Natural pixel sizes for each menu; panels are fitted as a unit to the
     * viewport. The exact same bounds are used for drawing and mouse input.
     */
    private float[] menuPanelGeometry(int width, int height,
                                       ScreenMode mode, int page) {
        float naturalWidth;
        float naturalHeight;
        switch (mode) {
            case SAVE_SELECT -> { naturalWidth = 570f; naturalHeight = 534f; }
            case OPTIONS -> {
                naturalWidth = 660f;
                naturalHeight = page == 0 ? 690f : 356f;
            }
            case KEYBINDS -> { naturalWidth = 660f; naturalHeight = 665f; }
            case DELETE_CONFIRM -> { naturalWidth = 570f; naturalHeight = 350f; }
            default -> { naturalWidth = 510f; naturalHeight = 435f; }
        }
        float s = Math.min(1f, Math.min((width - 28f) / naturalWidth,
                (height - 28f) / naturalHeight));
        return new float[] {(width - naturalWidth * s) / 2f,
                (height - naturalHeight * s) / 2f,
                naturalWidth * s, naturalHeight * s, s};
    }

    private float optionsMenuScale(int width, int height, int optionsPage) {
        return menuPanelGeometry(width, height, ScreenMode.OPTIONS, optionsPage)[4];
    }

    void drawStatusScreen(
            List<Float> data,
            List<Float> dimData,
            List<Float> skinData,
            int width,
            int height,
            ScreenMode screenMode,
            String statusMessage) {

        rect(dimData, 0, 0, width, height,
                0.025f, 0.035f, 0.055f, 0.72f);
        uiSkin.menuPanel(skinData, width / 2f - 240f,
                height / 2f - 95f, 480f, 190f);

        String title = screenMode == ScreenMode.LOADING
                ? "LOADING WORLD" : "SAVING WORLD";
        drawText(
                data, title,
                width / 2.0f,
                height / 2.0f - 34.0f,
                4.0f,
                0.96f, 0.84f, 0.44f, 1.0f
        );

        if (statusMessage != null && !statusMessage.isBlank()) {
            drawText(
                    data,
                    statusMessage.toUpperCase(),
                    width / 2.0f,
                    height / 2.0f + 22.0f,
                    2.0f,
                    0.96f, 0.96f, 0.94f, 1.0f
            );
        }
    }

    private int buttonCount(ScreenMode screenMode, int optionsPage) {
        return switch (screenMode) {
            case SAVE_SELECT -> 10;
            case OPTIONS -> optionsPage == 0 ? 10 : 5;
            case KEYBINDS -> 10;
            case DELETE_CONFIRM -> 2;
            default -> 3;
        };
    }

    private float[] buttonBounds(
            int index, int width, int height, ScreenMode screenMode, int optionsPage) {
        if (screenMode == ScreenMode.MAIN_MENU) {
            float[] footer = mainFooterGeometry(width, height);
            float s = footer[4];
            float w = 282f * s;
            float gap = 22f * s;
            float total = 3f * w + 2f * gap;
            return new float[] {
                    (width - total) / 2f + index * (w + gap),
                    footer[1] + 22f * s,
                    w, 52f * s
            };
        }

        float[] panel = menuPanelGeometry(width, height, screenMode, optionsPage);
        float x = panel[0];
        float y = panel[1];
        float s = panel[4];

        if (screenMode == ScreenMode.SAVE_SELECT) {
            if (index >= 7 && index <= 9) {
                int rowIndex = index - 7;
                return new float[] {
                        x + 405f * s, y + (117f + rowIndex * 67f) * s,
                        107f * s, 37f * s
                };
            }
            if (index < 3) {
                return new float[] {
                        x + 46f * s, y + (105f + index * 67f) * s,
                        478f * s, 61f * s
                };
            }
            if (index == 3) {
                return new float[] {x + 46f * s, y + 316f * s,
                        478f * s, 52f * s};
            }
            if (index == 4 || index == 5) {
                return new float[] {x + (index == 4 ? 46f : 291f) * s,
                        y + 382f * s, 233f * s, 45f * s};
            }
            return new float[] {x + 46f * s, y + 459f * s,
                    478f * s, 50f * s};
        }
        if (screenMode == ScreenMode.OPTIONS) {
            if (index < 2) {
                return new float[] {x + (index == 0 ? 45f : 343f) * s,
                        y + 99f * s, 272f * s, 44f * s};
            }
            if (optionsPage == 0) {
                if (index == 9) {
                    return new float[] {x + 46f * s, y + 602f * s,
                            568f * s, 51f * s};
                }
                return new float[] {x + 46f * s,
                        y + (157f + (index - 2) * 58f) * s,
                        568f * s, 49f * s};
            }
            // Controls only has two settings; do not reuse the tall video panel.
            if (index == 4) {
                return new float[] {x + 46f * s, y + 286f * s,
                        568f * s, 51f * s};
            }
            return new float[] {x + 46f * s,
                    y + (158f + (index - 2) * 57f) * s,
                    568f * s, 49f * s};
        }
        if (screenMode == ScreenMode.KEYBINDS) {
            if (index < 6) {
                return new float[] {x + 46f * s,
                        y + (112f + index * 63f) * s,
                        568f * s, 50f * s};
            }
            if (index == 6 || index == 7) {
                return new float[] {x + (index == 6 ? 46f : 336f) * s,
                        y + 494f * s, 278f * s, 44f * s};
            }
            return new float[] {x + (index == 8 ? 46f : 336f) * s,
                    y + 573f * s, 278f * s, 51f * s};
        }
        if (screenMode == ScreenMode.DELETE_CONFIRM) {
            return new float[] {x + 46f * s,
                    y + (190f + index * 71f) * s,
                    478f * s, 54f * s};
        }
        return new float[] {x + 57f * s,
                y + (170f + index * 79f) * s,
                396f * s, 58f * s};
    }

    private MenuButton buttonForIndex(int index, ScreenMode screenMode, int optionsPage) {
        if (screenMode == ScreenMode.MAIN_MENU) {
            return switch (index) {
                case 0 -> MenuButton.PLAY;
                case 1 -> MenuButton.OPTIONS;
                case 2 -> MenuButton.QUIT;
                default -> MenuButton.NONE;
            };
        }

        if (screenMode == ScreenMode.SAVE_SELECT) {
            return switch (index) {
                case 0 -> MenuButton.SAVE_ONE;
                case 1 -> MenuButton.SAVE_TWO;
                case 2 -> MenuButton.SAVE_THREE;
                case 3 -> MenuButton.NEW_GAME;
                case 4 -> MenuButton.PREVIOUS_PAGE;
                case 5 -> MenuButton.NEXT_PAGE;
                case 6 -> MenuButton.BACK;
                case 7 -> MenuButton.DELETE_ONE;
                case 8 -> MenuButton.DELETE_TWO;
                case 9 -> MenuButton.DELETE_THREE;
                default -> MenuButton.NONE;
            };
        }
        if (screenMode == ScreenMode.OPTIONS) {
            if (optionsPage == 0) {
                return switch (index) {
                    case 0 -> MenuButton.VIDEO_TAB;
                    case 1 -> MenuButton.CONTROLS_TAB;
                    case 2 -> MenuButton.DISPLAY_MODE;
                    case 3 -> MenuButton.RESOLUTION;
                    case 4 -> MenuButton.VSYNC;
                    case 5 -> MenuButton.FPS_LIMIT;
                    case 6 -> MenuButton.FOV;
                    case 7 -> MenuButton.SHOW_FPS;
                    case 8 -> MenuButton.UI_SCALE;
                    case 9 -> MenuButton.BACK;
                    default -> MenuButton.NONE;
                };
            }
            return switch (index) {
                case 0 -> MenuButton.VIDEO_TAB;
                case 1 -> MenuButton.CONTROLS_TAB;
                case 2 -> MenuButton.CROSSHAIR;
                case 3 -> MenuButton.KEYBINDS_TAB;
                case 4 -> MenuButton.BACK;
                default -> MenuButton.NONE;
            };
        }
        if (screenMode == ScreenMode.KEYBINDS) {
            return switch (index) {
                case 0 -> MenuButton.BIND_1;
                case 1 -> MenuButton.BIND_2;
                case 2 -> MenuButton.BIND_3;
                case 3 -> MenuButton.BIND_4;
                case 4 -> MenuButton.BIND_5;
                case 5 -> MenuButton.BIND_6;
                case 6 -> MenuButton.BIND_PREVIOUS;
                case 7 -> MenuButton.BIND_NEXT;
                case 8 -> MenuButton.RESET_BINDS;
                case 9 -> MenuButton.BACK;
                default -> MenuButton.NONE;
            };
        }
        if (screenMode == ScreenMode.DELETE_CONFIRM) {
            return switch (index) {
                case 0 -> MenuButton.CONFIRM_DELETE;
                case 1 -> MenuButton.CANCEL_DELETE;
                default -> MenuButton.NONE;
            };
        }

        return switch (index) {
            case 0 -> MenuButton.CONTINUE;
            case 1 -> MenuButton.OPTIONS;
            case 2 -> MenuButton.QUIT;
            default -> MenuButton.NONE;
        };
    }

    private String buttonLabel(MenuButton button) {
        return switch (button) {
            case PLAY -> "PLAY";
            case CONTINUE -> "CONTINUE";
            case OPTIONS -> "OPTIONS";
            case QUIT -> "QUIT";
            case SAVE_ONE -> "WORLD ONE";
            case SAVE_TWO -> "WORLD TWO";
            case SAVE_THREE -> "WORLD THREE";
            case NEW_GAME -> "NEW GAME";
            case PREVIOUS_PAGE -> "PREVIOUS PAGE";
            case NEXT_PAGE -> "NEXT PAGE";
            case DELETE_ONE, DELETE_TWO, DELETE_THREE -> "DELETE";
            case CONFIRM_DELETE -> "DELETE FOREVER";
            case CANCEL_DELETE -> "CANCEL";
            case VIDEO_TAB -> "VIDEO";
            case CONTROLS_TAB -> "CONTROLS";
            case DISPLAY_MODE -> "DISPLAY MODE";
            case RESOLUTION -> "RESOLUTION";
            case VSYNC -> "VSYNC";
            case FPS_LIMIT -> "MAX FPS";
            case SHOW_FPS -> "SHOW FPS";
            case FOV -> "FIELD OF VIEW";
            case CROSSHAIR -> "CROSSHAIR";
            case UI_SCALE -> "UI SCALE";
            case KEYBINDS_TAB -> "KEY BINDINGS";
            case BIND_1, BIND_2, BIND_3, BIND_4, BIND_5, BIND_6 -> "REMAP KEY";
            case BIND_PREVIOUS -> "PREVIOUS";
            case BIND_NEXT -> "NEXT";
            case RESET_BINDS -> "RESET DEFAULTS";
            case BACK -> "BACK";
            case NONE -> "";
        };
    }

}
