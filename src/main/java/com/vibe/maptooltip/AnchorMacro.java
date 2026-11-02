package com.vibe.maptooltip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

public class AnchorMacro implements ClientModInitializer {
    /** Ticks between steps. Every hotbar switch gets its own step so the equip animation plays. */
    static final int STEP_TICKS = 1;

    static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    static BlockPos anchorPos = null;
    static int wait = 0;
    static boolean wasDown = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(AnchorMacro::tick);
    }

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            queue.clear();
            wasDown = false;
            return;
        }

        // run the queued steps, one per STEP_TICKS
        if (!queue.isEmpty()) {
            if (!MapTooltip.enabled || mc.screen != null) { queue.clear(); return; }
            if (--wait <= 0) {
                queue.poll().run();
                wait = STEP_TICKS;
            }
            wasDown = mc.options.keyUse.isDown();
            return;
        }

        boolean down = mc.options.keyUse.isDown();
        boolean pressed = down && !wasDown;
        wasDown = down;

        if (!pressed || !MapTooltip.enabled || mc.screen != null) return;
        if (!p.getMainHandItem().is(Items.RESPAWN_ANCHOR)) return;
        if (p.isShiftKeyDown()) return;                       // sneaking would place the glowstone instead of charging
        if (findSlot(mc, Items.GLOWSTONE) < 0) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        BlockState st = mc.level.getBlockState(hit.getBlockPos());
        boolean existing = st.is(Blocks.RESPAWN_ANCHOR);

        BlockPos spot;
        if (existing) {
            spot = hit.getBlockPos();
        } else {
            // fire, tall grass, snow layers etc. get replaced, so the anchor lands in that spot
            spot = st.is(BlockTags.REPLACEABLE) ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
            BlockState spotState = mc.level.getBlockState(spot);
            if (!(spotState.isAir() || spotState.is(BlockTags.REPLACEABLE))) return;   // no room: let vanilla handle it
        }

        // take over this right-click so the game doesn't also place or interact on its own
        while (mc.options.keyUse.consumeClick()) { }
        mc.options.keyUse.setDown(false);

        anchorPos = spot;
        if (!existing) use(mc, hit);                          // place the anchor

        wait = 0;
        queue.clear();
        queue.add(() -> select(mc, Items.GLOWSTONE));         // switch (animation plays)
        queue.add(() -> use(mc, clickOn(anchorPos)));         // charge the anchor
        queue.add(() -> pickDetonator(mc));                   // switch to the mainhand totem (animation plays)
        queue.add(() -> detonate(mc, true));                  // detonate
    }

    static void detonate(Minecraft mc, boolean retry) {
        if (anchorPos == null) return;

        // if the charge didn't land, charge again once before detonating
        BlockState st = mc.level.getBlockState(anchorPos);
        if (retry && st.is(Blocks.RESPAWN_ANCHOR)
                && st.getValue(RespawnAnchorBlock.CHARGE) == 0
                && findSlot(mc, Items.GLOWSTONE) >= 0) {
            queue.addFirst(() -> detonate(mc, false));
            queue.addFirst(() -> pickDetonator(mc));
            queue.addFirst(() -> use(mc, clickOn(anchorPos)));
            queue.addFirst(() -> select(mc, Items.GLOWSTONE));
            return;
        }
        use(mc, clickOn(anchorPos));
    }

    // ---- helpers ------------------------------------------------------------

    static BlockHitResult clickOn(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    static int findSlot(Minecraft mc, Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    static boolean select(Minecraft mc, Item item) {
        int i = findSlot(mc, item);
        if (i < 0) return false;
        mc.player.getInventory().setSelectedSlot(i);
        return true;
    }

    /** Totem if you have one, otherwise an empty slot, otherwise any non-glowstone item. */
    static boolean pickDetonator(Minecraft mc) {
        if (select(mc, Items.TOTEM_OF_UNDYING)) return true;
        LocalPlayer p = mc.player;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).isEmpty()) { p.getInventory().setSelectedSlot(i); return true; }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.is(Items.GLOWSTONE)) { p.getInventory().setSelectedSlot(i); return true; }
        }
        return false;
    }

    static void use(Minecraft mc, BlockHitResult hit) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
