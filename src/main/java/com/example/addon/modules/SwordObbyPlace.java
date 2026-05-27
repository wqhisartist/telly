package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class SwordObbyPlace extends Module {

    private final SettingGroup sgGeneral      = this.settings.getDefaultGroup();
    private final SettingGroup sgExclusions   = this.settings.createGroup("Exclusions");
    private final SettingGroup sgTriggerItems = this.settings.createGroup("Trigger Items");

    // ── General ────────────────────────────────────────────────────────────────

    private final Setting<Boolean> onlyGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-ground")
        .description("Only trigger when punching the top face of a block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireSurvival = sgGeneral.add(new BoolSetting.Builder()
        .name("require-survival")
        .description("Only place obsidian when in Survival or Adventure mode.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Swap back to your original hotbar slot after placing obsidian.")
        .defaultValue(true)
        .build()
    );

    // ── Exclusions ─────────────────────────────────────────────────────────────

    private final Setting<Boolean> excludeBedrock = sgExclusions.add(new BoolSetting.Builder()
        .name("exclude-bedrock")
        .description("Do not trigger when punching bedrock.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> excludeObsidian = sgExclusions.add(new BoolSetting.Builder()
        .name("exclude-obsidian")
        .description("Do not trigger when punching obsidian.")
        .defaultValue(false)
        .build()
    );

    // ── Trigger Items ──────────────────────────────────────────────────────────

    private final Setting<Boolean> triggerSword = sgTriggerItems.add(new BoolSetting.Builder()
        .name("sword")
        .description("Trigger when holding any sword.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> triggerCrystal = sgTriggerItems.add(new BoolSetting.Builder()
        .name("end-crystal")
        .description("Trigger when holding an End Crystal.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> triggerObsidian = sgTriggerItems.add(new BoolSetting.Builder()
        .name("obsidian")
        .description("Trigger when holding Obsidian.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> triggerTotem = sgTriggerItems.add(new BoolSetting.Builder()
        .name("totem")
        .description("Trigger when holding a Totem of Undying.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> triggerGlowstone = sgTriggerItems.add(new BoolSetting.Builder()
        .name("glowstone")
        .description("Trigger when holding Glowstone.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> triggerAnchor = sgTriggerItems.add(new BoolSetting.Builder()
        .name("respawn-anchor")
        .description("Trigger when holding a Respawn Anchor.")
        .defaultValue(true)
        .build()
    );

    // ──────────────────────────────────────────────────────────────────────────

    public SwordObbyPlace() {
        super(AddonTemplate.CATEGORY, "obsidian-place",
            "(Ported from ClickCrystals) Instantly places obsidian where you punch the ground when holding a configured trigger item.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onStartBreaking(StartBreakingBlockEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // Survival / Adventure guard
        if (requireSurvival.get()) {
            GameType gm = mc.gameMode.getPlayerMode();
            if (gm != GameType.SURVIVAL && gm != GameType.ADVENTURE) return;
        }

        // Only trigger on the top face if enabled
        if (onlyGround.get() && event.direction != Direction.UP) return;

        // Check held item against enabled triggers
        ItemStack held = mc.player.getMainHandItem();
        if (!isTriggerItem(held)) return;

        BlockPos hitPos = event.blockPos;
        Direction face  = event.direction;

        // Exclusion checks
        var hitBlock = mc.level.getBlockState(hitPos).getBlock();
        if (excludeBedrock.get()  && hitBlock == Blocks.BEDROCK)  return;
        if (excludeObsidian.get() && hitBlock == Blocks.OBSIDIAN) return;

        // ── Determine placement position ───────────────────────────────────────
        BlockPos placePos;
        BlockPos supportPos; // the block face we actually send the placement packet against
        Direction placeAgainst;

        if (face == Direction.UP) {
            // Standard ground punch: place on top of the hit block.
            placePos     = hitPos.above();
            supportPos   = hitPos;
            placeAgainst = Direction.UP;
        } else {
            // Side / bottom punch: the block would be placed in the neighbour slot.
            placePos     = hitPos.relative(face);
            supportPos   = hitPos;
            placeAgainst = face;
        }

        // Target position must be air
        if (!mc.level.getBlockState(placePos).isAir()) return;

        // Find obsidian in hotbar via Meteor's InvUtils
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (!obsidian.found()) return;

        int obsidianSlot = obsidian.slot();
        int originalSlot = mc.player.getInventory().getSelectedSlot();
        boolean needsSwap = obsidianSlot != originalSlot;

        if (needsSwap) {
            InvUtils.swap(obsidianSlot, false);
        }

        // Build the hit result aimed at the correct face of the support block.
        Vec3 hitVec = Vec3.atCenterOf(supportPos).add(
            placeAgainst.getStepX() * 0.5,
            placeAgainst.getStepY() * 0.5,
            placeAgainst.getStepZ() * 0.5
        );
        BlockHitResult hitResult = new BlockHitResult(
            hitVec,
            placeAgainst,
            supportPos,
            false
        );

        // Execute placement interaction
        InteractionResult result = mc.gameMode.useItemOn(
            mc.player,
            InteractionHand.MAIN_HAND,
            hitResult
        );

        if (swapBack.get() && needsSwap) {
            InvUtils.swap(originalSlot, false);
        }

        // Cancel the punch only if placement was accepted
        if (result != InteractionResult.PASS && result != InteractionResult.FAIL) {
            event.cancel();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean isTriggerItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();

        if (triggerSword.get()     && stack.is(ItemTags.SWORDS))          return true;
        if (triggerCrystal.get()   && item == Items.END_CRYSTAL)          return true;
        if (triggerObsidian.get()  && item == Items.OBSIDIAN)             return true;
        if (triggerTotem.get()     && item == Items.TOTEM_OF_UNDYING)     return true;
        if (triggerGlowstone.get() && item == Items.GLOWSTONE)            return true;
        if (triggerAnchor.get()    && item == Items.RESPAWN_ANCHOR)       return true;

        return false;
    }
}