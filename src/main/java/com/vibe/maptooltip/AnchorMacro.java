package com.vibe.maptooltip;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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

public class AnchorMacro implements ClientModInitializer {
    /** Give up after this many ticks (20 ticks = 1 second). */
    static final int TIMEOUT_TICKS = 60;
    /** Ticks between charge attempts if the charge hasn't shown up yet. */
    static final int RETRY_TICKS = 3;

    // One stage per tick so each hotbar switch shows its animation.
    enum State { IDLE, SWITCH_GLOW, CHARGE, SWITCH_TOTEM, DETONATE }

    static State state = State.IDLE;
    static BlockPos anchorPos = null;
    static int timer = 0;
    static int sinceTry = 0;
    static boolean wasDown = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.START_CLIENT_TICK.register(AnchorMacro::tick);
    }

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            state = State.IDLE;
            wasDown = false;
            return;
        }

        boolean down = mc.options.keyUse.isDown();
        boolean pressed = down && !wasDown;
        wasDown = down;

        if (state != State.IDLE) {
            if (!MapTooltip.enabled || mc.screen != null) { state = State.IDLE; return; }
            run(mc);
            return;
        }

        if (!pressed || !MapTooltip.enabled || mc.screen != null) return;
        if (!p.getMainHandItem().is(Items.RESPAWN_ANCHOR)) return;
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
        timer = 0;
        sinceTry = 0;
        state = State.SWITCH_GLOW;
    }

    static void run(Minecraft mc) {
        if (++timer > TIMEOUT_TICKS) { state = State.IDLE; return; }

        // sneaking makes the game place glowstone as a block instead of charging, so wait for release
        if (mc.player.isShiftKeyDown()) {
            mc.gui.setOverlayMessage(Component.literal("anchor: release sneak"), false);
            return;
        }

        BlockState st = mc.level.getBlockState(anchorPos);

        switch (state) {
            case SWITCH_GLOW -> {
                if (!select(mc, Items.GLOWSTONE)) { state = State.IDLE; return; }   // switch (animation plays)
                state = State.CHARGE;
                sinceTry = RETRY_TICKS;
            }
            case CHARGE -> {
                if (!st.is(Blocks.RESPAWN_ANCHOR)) return;                          // wait for the anchor to appear
                if (st.getValue(RespawnAnchorBlock.CHARGE) > 0) {                   // charged: on to the totem
                    state = State.SWITCH_TOTEM;
                    return;
                }
                if (++sinceTry >= RETRY_TICKS) {                                    // charge, and re-try until it shows
                    sinceTry = 0;
                    select(mc, Items.GLOWSTONE);
                    use(mc, clickOn(anchorPos));
                }
            }
            case SWITCH_TOTEM -> {
                pickDetonator(mc);                                                  // switch to the mainhand totem (animation plays)
                state = State.DETONATE;
            }
            case DETONATE -> {
                if (st.is(Blocks.RESPAWN_ANCHOR)) use(mc, clickOn(anchorPos));
                state = State.IDLE;
            }
            default -> state = State.IDLE;
        }
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
