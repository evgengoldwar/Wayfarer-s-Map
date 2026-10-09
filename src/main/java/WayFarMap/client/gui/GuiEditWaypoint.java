package WayFarMap.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import WayFarMap.client.Lang;
import WayFarMap.client.Teleport;
import WayFarMap.client.gui.ui.FlatButton;
import WayFarMap.client.gui.ui.FlatTextField;
import WayFarMap.client.gui.ui.Icons;
import WayFarMap.client.gui.ui.ScaledScreen;
import WayFarMap.client.gui.ui.Theme;
import WayFarMap.client.gui.ui.WindowHeader;
import WayFarMap.client.waypoint.Symbols;
import WayFarMap.client.waypoint.TeamWaypoints;
import WayFarMap.client.waypoint.Waypoint;
import WayFarMap.client.waypoint.WaypointGroup;
import WayFarMap.client.waypoint.WaypointManager;
import WayFarMap.client.waypoint.WaypointRenderer;
import WayFarMap.client.waypoint.WaypointShare;

/**
 * Creates or edits a waypoint: name, coordinates, group, icon, outline color and whether the team sees it. Only for
 * the player's own waypoints: a teammate's one is saved as an own one first.
 */
public class GuiEditWaypoint extends ScaledScreen {

    private static final int DEFAULT_OUTLINE = 0xFF5555;
    /** Color sample next to the outline switch; a click opens the color picker. */
    private static final int SWATCH_X = 144, SWATCH_Y = 155, SWATCH_W = 76, SWATCH_H = 18;
    private static final int BUTTON_ROW = 229;
    /** How far above the name the panel and its header start. */
    private static final int HEADER_ABOVE = WindowHeader.HEIGHT - 8;

    private static final int ID_GROUP = 1, ID_NEW_GROUP = 2, ID_ICON = 3, ID_OUTLINE = 4, ID_SAVE = 5, ID_DELETE = 6,
        ID_CANCEL = 7, ID_TELEPORT = 8, ID_BEAM = 9, ID_SHARE = 10, ID_TEAM = 11;
    /** Square left of the icon button showing the marker as it will look. */
    private static final int PREVIEW = 18;

    private final GuiScreen parent;
    /** Waypoint being edited, or null when creating a new one. */
    private final Waypoint target;
    /** Working copy; written to {@link #target} on save. */
    private final Waypoint edited;
    /** Last outline color, remembered while the outline is switched off. */
    private int outlineColor;
    /** The id it is shared under, kept while sharing is switched off and on again. */
    private String shareId;
    /** The team switch was set by hand: picking a group no longer sets it. */
    private boolean shareTouched;

    private GuiTextField nameField, xField, yField, zField, newGroupField;
    private final List<GuiTextField> fields = new ArrayList<>();
    private FlatButton deleteButton;
    private boolean confirmDelete;
    private int left, top;

    /** Editor for a new waypoint at the given position. */
    public static GuiEditWaypoint create(GuiScreen parent, int x, int y, int z, int dimension) {
        return new GuiEditWaypoint(parent, null, new Waypoint("", x, y, z, dimension));
    }

    public static GuiEditWaypoint edit(GuiScreen parent, Waypoint waypoint) {
        return new GuiEditWaypoint(parent, waypoint, waypoint.copy());
    }

    private GuiEditWaypoint(GuiScreen parent, Waypoint target, Waypoint edited) {
        this.parent = parent;
        this.target = target;
        this.edited = edited;
        this.outlineColor = edited.outlineColor != null ? edited.outlineColor : DEFAULT_OUTLINE;
        this.shareId = edited.shareId;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = width / 2 - 110;
        // Room above for the header, which starts over the name.
        top = Math.max(4 + HEADER_ABOVE, height / 2 - 142);
        fields.clear();
        buttonList.clear();

        nameField = field(left, top + 24, 220, edited.name, 48);
        xField = field(left, top + 58, 70, String.valueOf(edited.x), 9);
        yField = field(left + 75, top + 58, 70, String.valueOf(edited.y), 4);
        zField = field(left + 150, top + 58, 70, String.valueOf(edited.z), 9);
        newGroupField = field(left, top + 107, 170, "", 32);
        ((FlatTextField) newGroupField).setHint(Lang.format("wayfarmap.gui.new_group_hint"));
        nameField.setFocused(true);

        buttonList.add(new FlatButton(ID_GROUP, left, top + 83, 220, 18, ""));
        buttonList.add(new FlatButton(ID_NEW_GROUP, left + 174, top + 107, 46, 18, "+"));
        buttonList.add(new FlatButton(ID_ICON, left + PREVIEW + 4, top + 131, 220 - PREVIEW - 4, 18, ""));
        buttonList.add(new FlatButton(ID_OUTLINE, left, top + 155, 140, 18, ""));
        // Beacon beam switch, and sharing the waypoint in the chat.
        buttonList.add(new FlatButton(ID_BEAM, left, top + 179, 140, 18, ""));
        buttonList.add(new FlatButton(ID_SHARE, left + 144, top + 179, 76, 18, Lang.format("wayfarmap.share.button")));
        // Shared with the team: teammates see it, and what is changed here.
        buttonList.add(new FlatButton(ID_TEAM, left, top + 203, 220, 18, ""));
        // Bottom row: Save [Teleport Delete] Cancel; teleport and delete only exist for saved waypoints. Each button
        // gets its text width plus an equal share of the remaining space.
        FlatButton saveButton = new FlatButton(ID_SAVE, 0, top + BUTTON_ROW, 0, 18, Lang.format("wayfarmap.gui.save"));
        saveButton.active = true;
        deleteButton = new FlatButton(ID_DELETE, 0, top + BUTTON_ROW, 0, 18, Lang.format("wayfarmap.gui.confirm"));
        deleteButton.danger = true;
        FlatButton cancelButton = new FlatButton(ID_CANCEL, 0, top + BUTTON_ROW, 0, 18, Lang.format("gui.cancel"));
        List<FlatButton> row = new ArrayList<>();
        row.add(saveButton);
        if (target != null) {
            FlatButton teleport = new FlatButton(
                ID_TELEPORT,
                0,
                top + BUTTON_ROW,
                0,
                18,
                Lang.format("wayfarmap.gui.teleport"));
            teleport.enabled = Teleport.isAllowed() && mc.theWorld != null
                && target.dimension == mc.theWorld.provider.dimensionId;
            row.add(teleport);
            row.add(deleteButton);
        }
        row.add(cancelButton);
        int natural = 0;
        for (FlatButton button : row) {
            natural += fontRendererObj.getStringWidth(button.displayString) + 8;
        }
        int extra = Math.max(0, 220 - natural - (row.size() - 1) * 4) / row.size();
        int x = left;
        for (FlatButton button : row) {
            button.xPosition = x;
            button.setWidth(fontRendererObj.getStringWidth(button.displayString) + 8 + extra);
            x += button.getWidth() + 4;
            buttonList.add(button);
        }
        // Rounding leftovers go to the last button so the row ends flush with the fields above.
        cancelButton.setWidth(left + 220 - cancelButton.xPosition);
        buttonList.add(WindowHeader.closeButton(ID_CANCEL, left + 230, top - HEADER_ABOVE));
        updateButtons();
    }

    private GuiTextField field(int x, int y, int width, String text, int maxLength) {
        FlatTextField field = new FlatTextField(fontRendererObj, x, y, width, 18);
        field.setMaxStringLength(maxLength);
        field.setText(text);
        fields.add(field);
        return field;
    }

    private void updateButtons() {
        for (Object o : buttonList) {
            GuiButton button = (GuiButton) o;
            switch (button.id) {
                case ID_GROUP:
                    button.displayString = Lang.format("wayfarmap.gui.group") + ": "
                        + (edited.group == null ? Lang.format("wayfarmap.gui.no_group") : edited.group);
                    break;
                case ID_ICON:
                    ItemStack icon = edited.getIcon();
                    String symbol = edited.getSymbol();
                    String iconName = symbol != null ? Symbols.title(symbol)
                        : icon == null ? Lang.format("wayfarmap.gui.none") : safeName(icon);
                    button.displayString = Lang.format("wayfarmap.gui.icon") + ": " + iconName;
                    break;
                case ID_OUTLINE:
                    button.displayString = Lang.format("wayfarmap.gui.outline") + ": "
                        + Lang.format(edited.outlineColor != null ? "options.on" : "options.off");
                    break;
                case ID_BEAM:
                    button.displayString = Lang.format("wayfarmap.gui.beam") + ": "
                        + Lang.format(edited.beam ? "options.on" : "options.off");
                    ((FlatButton) button).active = edited.beam;
                    break;
                case ID_TEAM:
                    button.displayString = Lang.format("wayfarmap.gui.team_share") + ": "
                        + Lang.format(edited.shareId != null ? "options.on" : "options.off");
                    ((FlatButton) button).active = edited.shareId != null;
                    // Without a team (or the mod on the server) it can only be switched off.
                    button.enabled = !edited.death && (TeamWaypoints.INSTANCE.isAvailable() || edited.shareId != null);
                    break;
                case ID_DELETE:
                    button.displayString = Lang
                        .format(confirmDelete ? "wayfarmap.gui.confirm" : "wayfarmap.gui.delete");
                    break;
                default:
                    break;
            }
        }
    }

    private static String safeName(ItemStack stack) {
        try {
            return stack.getDisplayName();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Copies the text fields into the working copy. Returns false if a coordinate is not a number. */
    private boolean readFields() {
        edited.name = nameField.getText()
            .trim();
        try {
            edited.x = Integer.parseInt(
                xField.getText()
                    .trim());
            edited.y = Integer.parseInt(
                yField.getText()
                    .trim());
            edited.z = Integer.parseInt(
                zField.getText()
                    .trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A new waypoint is shared when put into a group that shares its new ones. */
    private void groupPicked() {
        if (target != null || shareTouched || !TeamWaypoints.INSTANCE.isAvailable()) {
            return;
        }
        WaypointGroup group = WaypointManager.INSTANCE.getGroup(edited.group);
        setShared(group != null && group.shareNew);
    }

    private void setShared(boolean shared) {
        if (shared && shareId == null) {
            shareId = UUID.randomUUID()
                .toString();
        }
        edited.shareId = shared ? shareId : null;
    }

    private List<String> groupOptions() {
        List<String> options = new ArrayList<>();
        options.add(null);
        for (WaypointGroup group : WaypointManager.INSTANCE.getGroups()) {
            options.add(group.name);
        }
        return options;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id != ID_DELETE) {
            confirmDelete = false;
        }
        switch (button.id) {
            case ID_GROUP: {
                List<String> options = groupOptions();
                int index = options.indexOf(edited.group);
                edited.group = options.get((index + 1) % options.size());
                groupPicked();
                break;
            }
            case ID_NEW_GROUP: {
                String name = newGroupField.getText()
                    .trim();
                if (!name.isEmpty()) {
                    edited.group = WaypointManager.INSTANCE.createGroup(name).name;
                    newGroupField.setText("");
                    groupPicked();
                }
                break;
            }
            case ID_ICON:
                readFields();
                mc.displayGuiScreen(new GuiItemPicker(this, stack -> { edited.setIcon(stack); }, edited::setSymbol));
                return;
            case ID_OUTLINE:
                edited.outlineColor = edited.outlineColor == null ? (Integer) outlineColor : null;
                break;
            case ID_BEAM:
                edited.beam = !edited.beam;
                break;
            case ID_TEAM:
                shareTouched = true;
                setShared(edited.shareId == null);
                break;
            case ID_SHARE:
                // Shares what the editor shows now, saved or not.
                if (!readFields()) {
                    return;
                }
                WaypointShare.share(edited);
                mc.displayGuiScreen(null);
                return;
            case ID_SAVE:
                save();
                return;
            case ID_DELETE:
                if (!confirmDelete) {
                    confirmDelete = true;
                } else {
                    WaypointManager.INSTANCE.removeWaypoint(target);
                    mc.displayGuiScreen(parent);
                    return;
                }
                break;
            case ID_TELEPORT:
                mc.displayGuiScreen(null);
                Teleport.teleport(target.x, target.y, target.z);
                return;
            case ID_CANCEL:
                mc.displayGuiScreen(parent);
                return;
            default:
                break;
        }
        updateButtons();
    }

    private void save() {
        if (!readFields()) {
            return;
        }
        if (target == null) {
            WaypointManager.INSTANCE.addWaypoint(edited);
        } else {
            target.copyFrom(edited);
            WaypointManager.INSTANCE.waypointChanged();
        }
        mc.displayGuiScreen(parent);
    }

    private void setOutline(int color) {
        outlineColor = color & 0xFFFFFF;
        edited.outlineColor = outlineColor;
        updateButtons();
    }

    private boolean onSwatch(int mouseX, int mouseY) {
        return Theme.inside(
            mouseX,
            mouseY,
            left + SWATCH_X,
            top + SWATCH_Y,
            left + SWATCH_X + SWATCH_W,
            top + SWATCH_Y + SWATCH_H);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            if (newGroupField.isFocused()) {
                actionPerformed((GuiButton) buttonList.get(1));
            } else {
                save();
            }
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            // Move focus to the next text field.
            int focused = -1;
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i)
                    .isFocused()) {
                    focused = i;
                }
                fields.get(i)
                    .setFocused(false);
            }
            fields.get((focused + 1) % fields.size())
                .setFocused(true);
            return;
        }
        for (GuiTextField field : fields) {
            if (field.isFocused()) {
                field.textboxKeyTyped(typedChar, keyCode);
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        for (GuiTextField field : fields) {
            field.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0 && onSwatch(mouseX, mouseY)) {
            readFields();
            mc.displayGuiScreen(new GuiColorPicker(this, outlineColor, this::setOutline));
        }
    }

    @Override
    public void updateScreen() {
        for (GuiTextField field : fields) {
            field.updateCursorCounter();
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left - 10, top - HEADER_ABOVE, left + 230, top + BUTTON_ROW + 27);
        WindowHeader.draw(
            fontRendererObj,
            left - 10,
            top - HEADER_ABOVE,
            left + 230,
            left + 230 - WindowHeader.CLOSE_ROOM,
            Icons.WAYPOINTS,
            Lang.format(target == null ? "wayfarmap.gui.new_waypoint" : "wayfarmap.gui.edit_waypoint"),
            Lang.format("wayfarmap.gui.edit_waypoint_hint"),
            Theme.TEXT_MUTED,
            null);

        Theme.text(fontRendererObj, Lang.format("wayfarmap.gui.name"), left, top + 14, Theme.TEXT_MUTED);
        Theme.text(fontRendererObj, "X", left, top + 48, Theme.TEXT_MUTED);
        Theme.text(fontRendererObj, "Y", left + 75, top + 48, Theme.TEXT_MUTED);
        Theme.text(fontRendererObj, "Z", left + 150, top + 48, Theme.TEXT_MUTED);
        for (GuiTextField field : fields) {
            field.drawTextBox();
        }

        // Color sample: the chosen outline color with its hex code; dimmed while the outline is off.
        int sx = left + SWATCH_X, sy = top + SWATCH_Y;
        boolean on = edited.outlineColor != null;
        drawRect(sx, sy, sx + SWATCH_W, sy + SWATCH_H, 0xFF000000 | outlineColor);
        if (!on) {
            drawRect(sx, sy, sx + SWATCH_W, sy + SWATCH_H, 0xB0101418);
        }
        Theme.outline(sx, sy, sx + SWATCH_W, sy + SWATCH_H, onSwatch(mouseX, mouseY) ? Theme.ACCENT : Theme.BORDER);
        int r = (outlineColor >> 16) & 0xFF, g = (outlineColor >> 8) & 0xFF, b = outlineColor & 0xFF;
        boolean light = on && r * 299 + g * 587 + b * 114 > 150_000;
        Theme.centered(
            fontRendererObj,
            String.format("#%06X", outlineColor),
            sx + SWATCH_W / 2,
            sy + 5,
            !on ? Theme.TEXT_MUTED : light ? 0xFF000000 : 0xFFFFFFFF);

        super.drawScaled(mouseX, mouseY, partialTicks);

        // Preview of the marker next to the icon button.
        Theme.fill(left, top + 131, left + PREVIEW, top + 131 + PREVIEW, 0xFF0F1216);
        Theme.outline(left, top + 131, left + PREVIEW, top + 131 + PREVIEW, Theme.BORDER);
        WaypointRenderer.drawMapMarker(edited, left + PREVIEW / 2.0, top + 131 + PREVIEW / 2.0, 12f, false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
