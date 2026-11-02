package com.vibe.maptooltip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public class CrystalMacro implements ClientModInitializer {
    // ---- delays (in ticks, 20 ticks = 1 second). Raise these if your server lags. ----
    /** Ticks to wait after switching back from obsidian before placing the crystal. */
    static final int OBSIDIAN_DELAY_TICKS = 2;
    /** Ticks to wait after placing the crystal before the break is allowed. */
    static final int BREAK_DELAY_TICKS = 1;
    /** Give up on a step after this many ticks. */
    static final int TIMEOUT_TICKS = 12;

    // Obsidian step runs one stage per tick so each hotbar switch shows its animation.
    enum State { IDLE, OBS_SELECT, OBS_PLACE, OBS_BACK, PLACE, BREAK }

    static State state = State.IDLE;
    static BlockHitResult pendingHit = null;
    static int hotbarObs = -1;
    static int invObs = -1;
    static boolean swapped = false;
    static int crystalSlot = 0;
    static int timer = 0;
    static boolean wasDown = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(CrystalMacro::tick);
    }

    static void reset() {
        state = State.IDLE;
        pendingHit = null;
        hotbarObs = -1;
        invObs = -1;
        swapped = false;
        timer = 0;
    }

    /** Stop mid-way and put your hotbar back how it was. */
    static void abort(Minecraft mc) {
        if (swapped && invObs >= 0) swapIn(mc, invObs, crystalSlot);
        if (state == State.OBS_SELECT || state == State.OBS_PLACE || state == State.OBS_BACK) {
            mc.player.getInventory().setSelectedSlot(crystalSlot);
        }
        reset();
    }

    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            reset();
            wasDown = false;
            return;
        }

        boolean down = mc.options.keyUse.isDown();
        boolean pressed = down && !wasDown;   // one cycle per click, holding does not repeat
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
                // only place if you're still aiming at the same block face
                if (!(mc.hitResult instanceof BlockHitResult cur) || cur.getType() != HitResult.Type.BLOCK
                        || !cur.getBlockPos().equals(pendingHit.getBlockPos())
                        || cur.getDirection() != pendingHit.getDirection()) {
                    abort(mc);
                    return;
                }
                use(mc, cur);                                              // place the obsidian
                state = State.OBS_BACK;
            }
            case OBS_BACK -> {
                if (swapped) {
                    swapIn(mc, invObs, crystalSlot);
                    swapped = false;
                }
                mc.player.getInventory().setSelectedSlot(crystalSlot);     // back to the crystal (animation plays)
                pendingHit = null;
                timer = 0;
                state = State.PLACE;
            }
            case PLACE -> {
                if (++timer > TIMEOUT_TICKS) { reset(); return; }
                if (timer < OBSIDIAN_DELAY_TICKS) return;
                if (!mc.player.getMainHandItem().is(Items.END_CRYSTAL)) { reset(); return; }
                if (placeOnAim(mc)) { timer = 0; state = State.BREAK; }
            }
            case BREAK -> {
                if (++timer > TIMEOUT_TICKS) { reset(); return; }
                if (timer <= BREAK_DELAY_TICKS) return;
                // break: only when your crosshair is on an end crystal
                if (mc.hitResult instanceof EntityHitResult eh && eh.getEntity() instanceof EndCrystal crystal) {
                    mc.gameMode.attack(mc.player, crystal);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    reset();
                }
            }
        }
    }

    /** Check what you're aiming at; place obsidian only if there isn't any. */
    static void start(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos target = hit.getBlockPos();
        BlockState st = mc.level.getBlockState(target);

        // Already aiming at obsidian/bedrock: never place obsidian, just place the crystal.
        if (isBase(st)) {
            if (!canPlaceCrystal(mc, target)) return;
            takeOverClick(mc);
            crystalSlot = mc.player.getInventory().getSelectedSlot();
            use(mc, hit);                                                  // place packet
            timer = 0;
            state = State.BREAK;
            return;
        }

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

        takeOverClick(mc);
        crystalSlot = mc.player.getInventory().getSelectedSlot();
        pendingHit = hit;
        swapped = false;
        state = State.OBS_SELECT;
    }

    /** Place the crystal on the obsidian your crosshair is on. */
    static boolean placeOnAim(Minecraft mc) {
        if (mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK
                && isBase(mc.level.getBlockState(bh.getBlockPos()))
                && canPlaceCrystal(mc, bh.getBlockPos())) {
            use(mc, bh);                                                   // place packet
            return true;
        }
        return false;
    }

    static boolean canPlaceCrystal(Minecraft mc, BlockPos base) {
        return mc.level.getBlockState(base.above()).isAir()
                && mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(base.above())).isEmpty();
    }

    /** The macro handles this click, so the game doesn't also place a crystal on its own. */
    static void takeOverClick(Minecraft mc) {
        while (mc.options.keyUse.consumeClick()) { }
        mc.options.keyUse.setDown(false);
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

        static void use(Minecraft mc, BlockHitResult hit) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
