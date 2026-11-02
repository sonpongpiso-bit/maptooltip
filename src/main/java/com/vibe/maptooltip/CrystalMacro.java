package com.vibe.maptooltip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
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
    /** Wait after placing obsidian before placing the crystal. */
    static final int OBSIDIAN_DELAY_TICKS = 1;
    /** When you aim at existing obsidian, wait this long for your own right-click to place the crystal before the macro does. */
    static final int VANILLA_WAIT_TICKS = 2;
    /** Wait after the crystal appears before breaking it. */
    static final int BREAK_DELAY_TICKS = 1;
    /** Give up if something never shows up. */
    static final int TIMEOUT_TICKS = 20;

    enum State { IDLE, WAIT_OBSIDIAN, WAIT_CRYSTAL }

    static State state = State.IDLE;
    static BlockPos base = null;      // the obsidian block the crystal sits on
    static int crystalSlot = 0;
    static int timer = 0;
    static int seenFor = 0;
    static boolean attempted = false; // macro already tried to place the crystal
    static boolean wasDown = false;   // for "once per right-click hold"

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(CrystalMacro::tick);
    }

    static void reset() {
        state = State.IDLE;
        base = null;
        timer = 0;
        seenFor = 0;
        attempted = false;
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

        switch (state) {
            case IDLE -> {
                if (pressed && MapTooltip.enabled && mc.screen == null
                        && mc.player.getMainHandItem().is(Items.END_CRYSTAL)) {
                    start(mc);
                }
            }
            case WAIT_OBSIDIAN -> {
                if (++timer > TIMEOUT_TICKS) { reset(); return; }
                if (timer >= OBSIDIAN_DELAY_TICKS && mc.level.getBlockState(base).is(Blocks.OBSIDIAN)) {
                    if (!placeCrystal(mc)) { reset(); return; }
                    attempted = true;
                    state = State.WAIT_CRYSTAL;
                    timer = 0;
                    seenFor = 0;
                }
            }
            case WAIT_CRYSTAL -> {
                if (++timer > TIMEOUT_TICKS) { reset(); return; }
                List<EndCrystal> found = mc.level.getEntitiesOfClass(EndCrystal.class, new AABB(base.above()));
                if (!found.isEmpty()) {
                    if (++seenFor > BREAK_DELAY_TICKS) {
                        mc.gameMode.attack(mc.player, found.get(0));
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        reset();
                    }
                } else if (!attempted && timer >= VANILLA_WAIT_TICKS) {
                    if (!placeCrystal(mc)) { reset(); return; }
                    attempted = true;
                }
            }
        }
    }

    /** 1) check what you're aiming at, 2-3) place obsidian only if needed. */
    static void start(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        crystalSlot = mc.player.getInventory().getSelectedSlot();
        BlockPos target = hit.getBlockPos();
        BlockState st = mc.level.getBlockState(target);

        // Already aiming at obsidian/bedrock: never place obsidian, just do the crystal.
        if (isBase(st)) {
            if (!mc.level.getBlockState(target.above()).isAir()) return;
            beginCrystal(target);
            return;
        }

        // Where the new obsidian would go (fire, tall grass etc. get replaced in place).
        BlockPos spot = st.is(BlockTags.REPLACEABLE) ? target : target.relative(hit.getDirection());
        BlockState spotState = mc.level.getBlockState(spot);
        if (!(spotState.isAir() || spotState.is(BlockTags.REPLACEABLE))) return;
        if (!mc.level.getBlockState(spot.above()).isAir()) return;

        // Obsidian already underneath that spot: use it instead of stacking another one.
        if (isBase(mc.level.getBlockState(spot.below()))) {
            beginCrystal(spot.below());
            return;
        }

        int obsidianSlot = findHotbar(mc, Items.OBSIDIAN);
        if (obsidianSlot < 0) return;

        mc.player.getInventory().setSelectedSlot(obsidianSlot);
        use(mc, hit);
        mc.player.getInventory().setSelectedSlot(crystalSlot);   // 4) back to the crystal right away

        base = spot;
        state = State.WAIT_OBSIDIAN;
        timer = 0;
        attempted = false;
    }

    static void beginCrystal(BlockPos obsidian) {
        base = obsidian;
        state = State.WAIT_CRYSTAL;
        timer = 0;
        seenFor = 0;
        attempted = false;
    }

    /** 5) place the crystal on top of the obsidian. */
    static boolean placeCrystal(Minecraft mc) {
        if (!mc.player.getInventory().getItem(crystalSlot).is(Items.END_CRYSTAL)) return false;
        mc.player.getInventory().setSelectedSlot(crystalSlot);
        use(mc, new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false));
        return true;
    }

    static boolean isBase(BlockState st) {
        return st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK);
    }

    static int findHotbar(Minecraft mc, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    static void use(Minecraft mc, BlockHitResult hit) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
