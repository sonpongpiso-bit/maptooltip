package com.vibe.maptooltip;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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

public class MapTooltip implements ClientModInitializer {
    /** Run the full combo off the anchor key. */
    static final boolean AUTO_CHAIN = true;
    /** Ticks between steps. Every hotbar switch gets its own step so the equip animation plays. */
    static final int STEP_TICKS = 1;
    /** Whether the mod starts switched on. */
    static final boolean START_ENABLED = true;

    static KeyMapping ANCHOR, GLOWSTONE, TOTEM, TOGGLE;

    static boolean enabled = START_ENABLED;
    static BlockPos anchorPos = null;

    static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    static int wait = 0;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category cat = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("maptooltip", "main"));
        ANCHOR    = reg("key.maptooltip.safe_anchor", 96, cat);                    // ` / ~ key
        GLOWSTONE = reg("key.maptooltip.glowstone", InputConstants.KEY_R, cat);
        TOTEM     = reg("key.maptooltip.totem",     InputConstants.KEY_TAB, cat);
        TOGGLE    = reg("key.maptooltip.toggle",    InputConstants.KEY_PAGEDOWN, cat);

        ClientTickEvents.END_CLIENT_TICK.register(MapTooltip::tick);
        HoverTotem.init();
    }

    static KeyMapping reg(String name, int key, KeyMapping.Category cat) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping(name, InputConstants.Type.KEYSYM, key, cat));
    }

    static void q(Runnable r) { queue.add(r); }

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (p == null || gm == null) { queue.clear(); return; }

        while (TOGGLE.consumeClick()) {
            enabled = !enabled;
            queue.clear();
            mc.gui.setOverlayMessage(Component.literal("maptooltip: " + (enabled ? "on" : "off")), false);
        }

        if (mc.screen != null) { queue.clear(); return; }

        // Always drain the keys so presses while off don't pile up.
        boolean a = false, g = false, t = false;
        while (ANCHOR.consumeClick()) a = true;
        while (GLOWSTONE.consumeClick()) g = true;
        while (TOTEM.consumeClick()) t = true;
        if (!enabled) { queue.clear(); return; }

        // run one queued step per STEP_TICKS
        if (!queue.isEmpty()) {
            if (--wait <= 0) {
                queue.poll().run();
                wait = STEP_TICKS;
            }
            return;   // ignore new presses until the combo is finished
        }

        if ((a || g || t) && p.isShiftKeyDown()) {
            mc.gui.setOverlayMessage(Component.literal("maptooltip: release sneak"), false);
            return;
        }

        if (a) startCombo(mc);
        else if (g) { queueCharge(mc); wait = 0; }
        else if (t) { queueDetonate(mc); wait = 0; }
    }

    // ---- combo --------------------------------------------------------------

    static void startCombo(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (findSlot(mc, Items.RESPAWN_ANCHOR) < 0) return;

        // fire, tall grass, snow layers etc. get replaced, so the anchor lands in that spot
        boolean replaces = mc.level.getBlockState(hit.getBlockPos()).is(BlockTags.REPLACEABLE);
        anchorPos = replaces ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());

        queue.clear();
        wait = 0;
        q(() -> select(mc, Items.RESPAWN_ANCHOR));   // switch (animation plays)
        q(() -> use(mc, hit));                       // place the anchor
        if (AUTO_CHAIN) {
            queueCharge(mc);
            queueDetonate(mc);
        }
    }

    static void queueCharge(Minecraft mc) {
        if (anchorPos == null) return;
        q(() -> select(mc, Items.GLOWSTONE));        // switch (animation plays)
        q(() -> use(mc, clickOn(anchorPos)));        // charge the anchor once
        q(() -> {                                    // shield on your side of the anchor
            Direction towardYou = mc.player.getDirection().getOpposite();
            BlockPos floor = anchorPos.below().relative(towardYou);
            use(mc, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
        });
    }

    static void queueDetonate(Minecraft mc) {
        if (anchorPos == null) return;
        q(() -> pickDetonator(mc));                  // switch to totem / empty slot (animation plays)
        q(() -> detonateStep(mc, true));
    }

    static void detonateStep(Minecraft mc, boolean retry) {
        if (anchorPos == null) return;

        // if the charge didn't land, charge again once before detonating
        BlockState st = mc.level.getBlockState(anchorPos);
        if (retry && st.is(Blocks.RESPAWN_ANCHOR)
                && st.getValue(RespawnAnchorBlock.CHARGE) == 0
                && findSlot(mc, Items.GLOWSTONE) >= 0) {
            queue.addFirst(() -> detonateStep(mc, false));
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

    static boolean select(Minecraft mc, Item item) {
        int i = findSlot(mc, item);
        if (i < 0) return false;
        mc.player.getInventory().setSelectedSlot(i);
        return true;
    }

    static void use(Minecraft mc, BlockHitResult hit) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
