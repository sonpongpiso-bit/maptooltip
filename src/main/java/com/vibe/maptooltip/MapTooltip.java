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

public class MapTooltip implements ClientModInitializer {
    /** Run the full combo off the anchor key. */
    static final boolean AUTO_CHAIN = true;
    /** 0 = everything in one tick, 1 = place+charge+shield on tick 0, detonate on tick 1. */
    static final int DETONATE_DELAY_TICKS = 1;
    /** Whether the mod starts switched on. */
    static final boolean START_ENABLED = true;

    static KeyMapping ANCHOR, GLOWSTONE, TOTEM, TOGGLE;

    static boolean enabled = START_ENABLED;
    static BlockPos anchorPos = null;
    static int detonateIn = -1;

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

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        MultiPlayerGameMode gm = mc.gameMode;
        if (p == null || gm == null) return;

        while (TOGGLE.consumeClick()) {
            enabled = !enabled;
            detonateIn = -1;
            mc.gui.setOverlayMessage(Component.literal("maptooltip: " + (enabled ? "on" : "off")), false);
        }

        if (mc.screen != null) return;

        // Always drain the keys so presses while off don't pile up.
        boolean a = false, g = false, t = false;
        while (ANCHOR.consumeClick()) a = true;
        while (GLOWSTONE.consumeClick()) g = true;
        while (TOTEM.consumeClick()) t = true;
        if (!enabled) return;

        if (detonateIn >= 0 && --detonateIn < 0) detonate(mc);

        if ((a || g || t) && p.isShiftKeyDown()) {
            mc.gui.setOverlayMessage(Component.literal("maptooltip: release sneak"), false);
            return;
        }

        if (a && placeAnchor(mc) && AUTO_CHAIN) {
            chargeAndShield(mc);
            if (DETONATE_DELAY_TICKS <= 0) detonate(mc);
            else detonateIn = DETONATE_DELAY_TICKS;
        }
        if (g) chargeAndShield(mc);
        if (t) detonate(mc);
    }

    // ---- stages -------------------------------------------------------------

    static boolean placeAnchor(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return false;
        if (!select(mc, Items.RESPAWN_ANCHOR)) return false;

        // fire, tall grass, snow layers etc. get replaced, so the anchor lands in that spot
        boolean replaces = mc.level.getBlockState(hit.getBlockPos()).is(BlockTags.REPLACEABLE);

        use(mc, hit);
        anchorPos = replaces ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        return true;
    }

    static void chargeAndShield(Minecraft mc) {
        if (anchorPos == null || !select(mc, Items.GLOWSTONE)) return;
        LocalPlayer p = mc.player;

        // charge the anchor once
        use(mc, clickOn(anchorPos));

        // shield on your side of the anchor, placed on top of the floor block there
        Direction towardYou = p.getDirection().getOpposite();
        BlockPos floor = anchorPos.below().relative(towardYou);
        use(mc, new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
    }

    static void detonate(Minecraft mc) {
        if (anchorPos == null) return;

        // if the charge didn't land, charge again before detonating
        BlockState st = mc.level.getBlockState(anchorPos);
        if (st.is(Blocks.RESPAWN_ANCHOR)
                && st.getValue(RespawnAnchorBlock.CHARGE) == 0
                && select(mc, Items.GLOWSTONE)) {
            use(mc, clickOn(anchorPos));
        }
        // totem if you have one, otherwise an empty slot, otherwise any non-glowstone item
        if (!pickDetonator(mc)) return;
        use(mc, clickOn(anchorPos));
    }

    // ---- helpers ------------------------------------------------------------

    static BlockHitResult clickOn(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
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
        LocalPlayer p = mc.player;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(item)) {
                p.getInventory().setSelectedSlot(i);
                return true;
            }
        }
        return false;
    }

    static void use(Minecraft mc, BlockHitResult hit) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }
}
