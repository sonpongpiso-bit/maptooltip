package com.vibe.maptooltip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class CrystalMacro implements ClientModInitializer {
    // ---- delays (in ticks, 20 ticks = 1 second). Raise these if your server lags. ----
    /** Ticks between each place / break action while right-click is held. */
    static final int CYCLE_DELAY_TICKS = 1;
    /** Ticks to wait after switching back from obsidian before the first crystal. */
    static final int OBSIDIAN_DELAY_TICKS = 2;
    /** Give up (until you release right-click) if the obsidian never shows up. */
    static final int OBSIDIAN_TIMEOUT_TICKS = 20;

    // Obsidian step runs one stage per tick so each hotbar switch shows its animation.
    enum State { IDLE, OBS_SELECT, OBS_PLACE, OBS_BACK, LOOP }

    static State state = State.IDLE;
    static BlockPos base = null;       // the obsidian block the crystals sit on
    static BlockHitResult pendingHit = null;
    static BlockPos pendingSpot = null;
    static int hotbarObs = -1;
    static int invObs = -1;
    static boolean swapped = false;
    static int crystalSlot = 0;
    static int cooldown = 0;
    static int waitObsidian = 0;
    static boolean blocked = false;    // failed once: stay off until right-click is released

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(CrystalMacro::tick);
    }

    static void reset() {
        state = State.IDLE;
        base = null;
        pendingHit = null;
        pendingSpot = null;
        hotbarObs = -1;
        invObs = -1;
        swapped = false;
        cooldown = 0;
        waitObsidian = 0;
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
            blocked = false;
            return;
        }

        boolean down = mc.options.keyUse.isDown();
        if (!down) blocked = false;

        // stop when right-click is released, a menu opens, or the mod is off
        if (state != State.IDLE && (!down || mc.screen != null || !MapTooltip.enabled)) {
            abort(mc);
            return;
        }

        switch (state) {
            case IDLE -> {
                if (down && !blocked && MapTooltip.enabled && mc.screen == null
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
                use(mc, pendingHit);                                       // place the obsidian
                state = State.OBS_BACK;
            }
            case OBS_BACK -> {
                if (swapped) {
                    swapIn(mc, invObs, crystalSlot);
                    swapped = false;
                }
                mc.player.getInventory().setSelectedSlot(crystalSlot);     // back to the crystal (animation plays)
                BlockPos spot = pendingSpot;
                pendingHit = null;
                beginLoop(spot, OBSIDIAN_DELAY_TICKS);
            }
            case LOOP -> {
                if (!mc.player.getMainHandItem().is(Items.END_CRYSTAL)) { reset(); return; }
                loop(mc);
            }
        }
    }

    /** Check what you're aiming at; place obsidian only if there isn't any. */
    static void start(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        crystalSlot = mc.player.getInventory().getSelectedSlot();
        BlockPos target = hit.getBlockPos();
        BlockState st = mc.level.getBlockState(target);

        // Already aiming at obsidian/bedrock: never place obsidian, go straight to the loop.
        if (isBase(st)) {
            beginLoop(target, 0);
            return;
        }

        // Where the new obsidian would go (fire, tall grass etc. get replaced in place).
        BlockPos spot = st.is(BlockTags.REPLACEABLE) ? target : target.relative(hit.getDirection());
        BlockState spotState = mc.level.getBlockState(spot);
        if (!(spotState.isAir() || spotState.is(BlockTags.REPLACEABLE))) return;
        if (!mc.level.getBlockState(spot.above()).isAir()) return;

        // Obsidian already underneath that spot: use it instead of stacking another one.
        if (isBase(mc.level.getBlockState(spot.below()))) {
            beginLoop(spot.below(), 0);
            return;
        }

        hotbarObs = findHotbar(mc, Items.OBSIDIAN);
        invObs = hotbarObs >= 0 ? -1 : findInventory(mc, Items.OBSIDIAN);
        if (hotbarObs < 0 && invObs < 0) { blocked = true; return; }

        pendingHit = hit;
        pendingSpot = spot;
        swapped = false;
        state = State.OBS_SELECT;
    }

    static void beginLoop(BlockPos obsidian, int delay) {
        base = obsidian;
        cooldown = delay;
        waitObsidian = 0;
        state = State.LOOP;
    }

    /** Runs every tick while right-click is held: place a crystal, break it, repeat. */
    static void loop(Minecraft mc) {
        if (--cooldown > 0) return;
        cooldown = CYCLE_DELAY_TICKS;

        // follow your aim if you look at a different obsidian block
        if (mc.hitResult instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK
                && isBase(mc.level.getBlockState(h.getBlockPos()))) {
            base = h.getBlockPos();
        }

        // wait for the obsidian we placed to show up
        if (!isBase(mc.level.getBlockState(base))) {
            if (++waitObsidian > OBSIDIAN_TIMEOUT_TICKS) { blocked = true; reset(); }
            return;
        }
        waitObsidian = 0;

        if (!mc.player.getInventory().getItem(crystalSlot).is(Items.END_CRYSTAL)) {
            blocked = true;
            reset();
            return;
        }

        List<EndCrystal> crystals = mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(base.above()));
        if (!crystals.isEmpty()) {
            mc.gameMode.attack(mc.player, crystals.get(0));
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else if (mc.level.getBlockState(base.above()).isAir()) {
            use(mc, new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false));
        }
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
