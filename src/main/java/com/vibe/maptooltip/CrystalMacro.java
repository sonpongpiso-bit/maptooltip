package com.vibe.maptooltip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class CrystalMacro implements ClientModInitializer {
    // One stage per tick so each hotbar switch shows its animation.
    enum State { IDLE, OBS_SELECT, OBS_PLACE, OBS_BACK }

    static State state = State.IDLE;
    static BlockHitResult pendingHit = null;
    static int hotbarObs = -1;
    static int invObs = -1;
    static boolean swapped = false;
    static int crystalSlot = 0;
    static boolean wasDown = false;   // for "once per right-click press"

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(CrystalMacro::tick);
    }

    static void reset() {
        state = State.IDLE;
        pendingHit = null;
        hotbarObs = -1;
        invObs = -1;
        swapped = false;
    }

    /** Stop mid-way and put your hotbar back how it was. */
    static void abort(Minecraft mc) {
        if (swapped && invObs >= 0) swapIn(mc, invObs, crystalSlot);
        mc.player.getInventory().setSelectedSlot(crystalSlot);
        reset();
    }

    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            reset();
            wasDown = false;
            return;
        }

        boolean down = mc.options.keyUse.isDown();
        boolean pressed = down && !wasDown;   // only the moment the button goes down
        wasDown = down;

        if (state != State.IDLE && (mc.screen != null || !MapTooltip.enabled)) {
            abort(mc);
            return;
        }

        switch (state) {
            case IDLE -> {
                if (pressed && MapTooltip.enabled && mc.screen == null
                        && mc.player.getMainHandItem().is(Items.END_CRYSTAL)) {
                    start(mc);
                }
            }
            case OBS_SELECT -> {
                if (hotbarObs >= 0) {
                    mc.player.getInventory().setSelectedSlot(hotbarObs);   // switch (animation plays)
                } else {
                    swapIn(mc, invObs, crystalSlot);                       // pull obsidian into the held slot
                    swapped = true;
                }
                state = State.OBS_PLACE;
            }
            case OBS_PLACE -> {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, pendingHit);   // place the obsidian
                mc.player.swing(InteractionHand.MAIN_HAND);
                state = State.OBS_BACK;
            }
            case OBS_BACK -> {
                if (swapped) {
                    swapIn(mc, invObs, crystalSlot);
                    swapped = false;
                }
                mc.player.getInventory().setSelectedSlot(crystalSlot);     // back to the crystal (animation plays)
                reset();
            }
        }
    }

    /** Check what you're aiming at; place obsidian only if there isn't any. */
    static void start(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos target = hit.getBlockPos();
        BlockState st = mc.level.getBlockState(target);

        // Already aiming at obsidian/bedrock: never place obsidian.
        if (isBase(st)) return;

        // Where the new obsidian would go (fire, tall grass etc. get replaced in place).
        BlockPos spot = st.is(BlockTags.REPLACEABLE) ? target : target.relative(hit.getDirection());
        BlockState spotState = mc.level.getBlockState(spot);
        if (!(spotState.isAir() || spotState.is(BlockTags.REPLACEABLE))) return;
        if (!mc.level.getBlockState(spot.above()).isAir()) return;

        // Obsidian already underneath that spot: don't stack another one.
        if (isBase(mc.level.getBlockState(spot.below()))) return;

        hotbarObs = findHotbar(mc, Items.OBSIDIAN);
        invObs = hotbarObs >= 0 ? -1 : findInventory(mc, Items.OBSIDIAN);
        if (hotbarObs < 0 && invObs < 0) return;

        crystalSlot = mc.player.getInventory().getSelectedSlot();
        pendingHit = hit;
        swapped = false;
        state = State.OBS_SELECT;
    }

    static boolean isBase(BlockState st) {
        return st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK);
    }

    static int findHotbar(Minecraft mc, Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    static int findInventory(Minecraft mc, Item item) {
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** Normal inventory swap click: inventory slot (9-35) with a hotbar slot (0-8). */
    static void swapIn(Minecraft mc, int invSlot, int hotbarSlot) {
        mc.gameMode.handleContainerInput(
                mc.player.inventoryMenu.containerId, invSlot, hotbarSlot, ContainerInput.SWAP, mc.player);
    }
}
