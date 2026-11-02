package com.vibe.maptooltip;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

public class HoverTotem {
    /** Turn the hover-totem feature on or off. */
    static final boolean HOVER_TOTEM = true;
    /** Hotbar slot for the mainhand totem (0 = slot 1, 8 = slot 9). */
    static final int TOTEM_HOTBAR_SLOT = 8;

    // Menu slot numbers in the inventory screen: 9-35 main inventory, 36-44 hotbar.
    static final int MENU_FIRST = 9;
    static final int MENU_LAST = 44;
    static final int MENU_HOTBAR_START = 36;

    static int cooldown = 0;

    static void init() {
        if (!HOVER_TOTEM) return;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof InventoryScreen inv) {
                ScreenEvents.afterTick(screen).register(s -> tick(client, inv));
            }
        });
    }

    static void swap(Minecraft mc, Slot slot, int button) {
        mc.gameMode.handleInventoryMouseClick(
                mc.player.containerMenu.containerId, slot.index, button, ClickType.SWAP, mc.player);
        cooldown = 2;
    }

    static void tick(Minecraft mc, AbstractContainerScreen<?> screen) {
        if (!MapTooltip.enabled || mc.player == null || mc.gameMode == null) return;
        if (cooldown > 0) { cooldown--; return; }

        Slot slot = screen.getSlotUnderMouse();
        if (slot == null || !slot.getItem().is(Items.TOTEM_OF_UNDYING)) return;
        // only inventory + hotbar slots (not armor, crafting or the offhand slot itself)
        if (slot.index < MENU_FIRST || slot.index > MENU_LAST) return;

        boolean offhandHasTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        boolean mainSlotHasTotem = mc.player.getInventory().getItem(TOTEM_HOTBAR_SLOT).is(Items.TOTEM_OF_UNDYING);
        int mainMenuIndex = MENU_HOTBAR_START + TOTEM_HOTBAR_SLOT;

        if (!offhandHasTotem) {
            swap(mc, slot, 40);                       // 40 = offhand
        } else if (!mainSlotHasTotem && slot.index != mainMenuIndex) {
            swap(mc, slot, TOTEM_HOTBAR_SLOT);        // 0-8 = hotbar slot
        }
    }
}
