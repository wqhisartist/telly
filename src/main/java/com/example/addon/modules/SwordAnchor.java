package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public class SwordAnchor extends Module {

    private final SettingGroup sgGeneral  = this.settings.getDefaultGroup();
    private final SettingGroup sgTriggers = this.settings.createGroup("Triggers");
    private final SettingGroup sgDelays   = this.settings.createGroup("Delays");

    // ── Triggers ───────────────────────────────────────────────────────────────

    private final Setting<Boolean> triggerSword = sgTriggers.add(new BoolSetting.Builder()
        .name("sword")
        .description("Trigger sequence when holding a sword.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> triggerAnchor = sgTriggers.add(new BoolSetting.Builder()
        .name("anchor")
        .description("Trigger sequence when holding an Anchor.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> triggerGlowstone = sgTriggers.add(new BoolSetting.Builder()
        .name("glowstone")
        .description("Trigger sequence when holding Glowstone.")
        .defaultValue(true)
        .build()
    );

    // ── General ────────────────────────────────────────────────────────────────

    private final Setting<Boolean> requireSurvival = sgGeneral.add(new BoolSetting.Builder()
        .name("require-survival")
        .description("Only activate in Survival or Adventure mode.")
        .defaultValue(true)
        .build()
    );

    // ── Delays ─────────────────────────────────────────────────────────────────

    private final Setting<Integer> delayMinMs = sgDelays.add(new IntSetting.Builder()
        .name("delay-min-ms")
        .description("Minimum random reaction delay in milliseconds before charging.")
        .defaultValue(10)
        .min(0)
        .sliderMax(150)
        .build()
    );

    private final Setting<Integer> delayMaxMs = sgDelays.add(new IntSetting.Builder()
        .name("delay-max-ms")
        .description("Maximum random reaction delay in milliseconds before charging.")
        .defaultValue(50)
        .min(0)
        .sliderMax(150)
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────────────

    private long    lastExecutedMs          = 0;
    private boolean wasUseDown              = false;
    private int     originalSlot            = -1;
    private final Random rng                = new Random();

    private BlockPos queuedAnchorPos        = null;
    private long     chargeExecutionTimestamp = -1;

    private int  slotToRevertTo   = -1;
    private long revertTimestamp  = -1;

    public SwordAnchor() {
        super(AddonTemplate.CATEGORY, "sword-anchor",
            "Places anchors on click (any face), then switches hotbar slots to charge them after a customizable millisecond delay.");
    }

    @Override
    public void onDeactivate() {
        wasUseDown = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && slotToRevertTo != -1) {
            // Use InvUtils.swap — sends packet only if slot actually changed,
            // avoiding Vulcan BadPacketsG redundant-packet flag.
            InvUtils.swap(slotToRevertTo, false);
        }
        resetQueue();
        slotToRevertTo  = -1;
        revertTimestamp = -1;
    }

    private void resetQueue() {
        originalSlot             = -1;
        queuedAnchorPos          = null;
        chargeExecutionTimestamp = -1;
    }

    // ── PART 1: Placement Trigger ──────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.screen != null) return;

        // Deferred revert back to original slot
        if (revertTimestamp > 0) {
            if (System.currentTimeMillis() >= revertTimestamp) {
                if (slotToRevertTo != -1) {
                    // InvUtils.swap only sends ServerboundSetCarriedItemPacket when
                    // the slot actually differs from the current one, preventing the
                    // redundant-packet flag (Vulcan BadPacketsG / Grim BadPacketsA).
                    InvUtils.swap(slotToRevertTo, false);
                }
                slotToRevertTo  = -1;
                revertTimestamp = -1;
            }
            return;
        }

        if (requireSurvival.get()) {
            GameType gm = mc.gameMode.getPlayerMode();
            if (gm != GameType.SURVIVAL && gm != GameType.ADVENTURE) return;
        }

        // Execute scheduled charge
        if (chargeExecutionTimestamp > 0) {
            if (System.currentTimeMillis() >= chargeExecutionTimestamp) {
                executeQueuedCharge(mc);
            }
            return;
        }

        boolean useDown    = mc.options.keyUse.isDown();
        boolean justPressed = useDown && !wasUseDown;
        wasUseDown = useDown;

        if (!justPressed) return;
        if (System.currentTimeMillis() - lastExecutedMs < 250) return;
        if (queuedAnchorPos != null) return;
        if (!isTriggerItem(mc)) return;
        if (!(mc.hitResult instanceof BlockHitResult bhr)) return;
        if (bhr.getType() != HitResult.Type.BLOCK) return;

        BlockPos hitPos = bhr.getBlockPos();
        if (mc.level.getBlockState(hitPos).isAir()) return;

        // If the player is aiming directly at a charged Respawn Anchor, abort.
        // This must be checked before anything else — when you aim at the top
        // face of an anchor, anchorPos = hitPos.above() = air, so any check
        // that only runs in the !isAir() branch will be silently skipped.
        BlockState hitState = mc.level.getBlockState(hitPos);
        if (hitState.is(Blocks.RESPAWN_ANCHOR)
                && hitState.getValue(RespawnAnchorBlock.CHARGE) > 0) return;

        BlockPos anchorPos = hitPos.relative(bhr.getDirection());

        // Also abort if the placement position already has a charged anchor.
        BlockState anchorState = mc.level.getBlockState(anchorPos);
        if (anchorState.is(Blocks.RESPAWN_ANCHOR)
                && anchorState.getValue(RespawnAnchorBlock.CHARGE) > 0) return;

        // Abort if the placement position is not air (something else is there).
        if (!mc.level.getBlockState(anchorPos).isAir()) return;

        FindItemResult anchor = InvUtils.findInHotbar(Items.RESPAWN_ANCHOR);
        if (!anchor.found()) return;

        // Save the current slot before any swap
        originalSlot = mc.player.getInventory().getSelectedSlot();

        // InvUtils.swap goes through the correct slot-tracking path — it only
        // sends ServerboundSetCarriedItemPacket when the new slot differs from
        // the current one, which prevents Grim BadPacketsA (multiple slot packets
        // in one tick) and Vulcan BadPacketsG (redundant held-item packet).
        InvUtils.swap(anchor.slot(), false);

        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, bhr);
        mc.player.swing(InteractionHand.MAIN_HAND);

        lastExecutedMs = System.currentTimeMillis();

        // Suppress vanilla right-click processing so it doesn't also fire
        mc.options.keyUse.setDown(false);
        while (mc.options.keyUse.consumeClick()) {}
    }

    // ── PART 2: Block Update — schedule the charge ─────────────────────────────

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (originalSlot == -1) return;

        BlockState newState = event.newState;
        if (newState.is(Blocks.RESPAWN_ANCHOR)) {
            int charges = newState.getValue(RespawnAnchorBlock.CHARGE);
            if (charges == 0 && queuedAnchorPos == null) {
                queuedAnchorPos          = event.pos;
                chargeExecutionTimestamp = System.currentTimeMillis() + getRandomDelayMs();
            }
        }
    }

    // ── PART 3: Execute the charge ─────────────────────────────────────────────

    private void executeQueuedCharge(Minecraft mc) {
        if (queuedAnchorPos == null) { resetQueue(); return; }

        FindItemResult glowstone = InvUtils.findInHotbar(Items.GLOWSTONE);
        if (glowstone.found()) {
            InvUtils.swap(glowstone.slot(), false);

            // Use mc.hitResult directly if the player is still looking at the
            // anchor — this gives Grim the exact cursor/face/pos from the
            // player's real raycast, which is what PositionPlace verifies.
            // A manually constructed BlockHitResult with a hardcoded center
            // position fails PositionPlace because Grim ray-traces from the
            // player's actual eye+rotation and the hit point won't match.
            BlockHitResult chargeHit;
            if (mc.hitResult instanceof BlockHitResult bhr
                    && bhr.getBlockPos().equals(queuedAnchorPos)) {
                // Player is still looking at the anchor — use the real hit result.
                chargeHit = bhr;
            } else {
                // Player looked away — construct a hit result from their current
                // eye position toward the anchor face so the cursor is realistic.
                Vec3 eyes       = mc.player.getEyePosition();
                Vec3 anchorCenter = Vec3.atCenterOf(queuedAnchorPos);
                Vec3 diff       = anchorCenter.subtract(eyes).normalize();
                // Find the dominant axis of the direction vector to pick a face.
                // This replaces Direction.getNearest(double,double,double) which
                // no longer exists in 26.1 — the new signature takes a Vec3i.
                double ax = Math.abs(diff.x);
                double ay = Math.abs(diff.y);
                double az = Math.abs(diff.z);
                Direction face;
                if (ax >= ay && ax >= az) {
                    face = diff.x > 0 ? Direction.EAST : Direction.WEST;
                } else if (ay >= az) {
                    face = diff.y > 0 ? Direction.UP : Direction.DOWN;
                } else {
                    face = diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
                }
                Vec3 hitVec = anchorCenter.add(
                    face.getStepX() * 0.5,
                    face.getStepY() * 0.5,
                    face.getStepZ() * 0.5
                );
                chargeHit = new BlockHitResult(hitVec, face, queuedAnchorPos, false);
            }
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, chargeHit);
            mc.player.swing(InteractionHand.MAIN_HAND);

            // Defer revert by 50ms so the swap fully registers server-side
            // before we send another ServerboundSetCarriedItemPacket.
            slotToRevertTo  = originalSlot;
            revertTimestamp = System.currentTimeMillis() + 50;
        } else {
            if (originalSlot != -1) InvUtils.swap(originalSlot, false);
        }

        resetQueue();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private int getRandomDelayMs() {
        int lo = Math.min(delayMinMs.get(), delayMaxMs.get());
        int hi = Math.max(delayMinMs.get(), delayMaxMs.get());
        return lo == hi ? lo : lo + rng.nextInt(hi - lo + 1);
    }

    private boolean isTriggerItem(Minecraft mc) {
        var stack = mc.player.getMainHandItem();
        if (stack.isEmpty()) return false;
        var item = stack.getItem();
        if (triggerSword.get()     && stack.is(ItemTags.SWORDS))   return true;
        if (triggerAnchor.get()    && item == Items.RESPAWN_ANCHOR) return true;
        if (triggerGlowstone.get() && item == Items.GLOWSTONE)      return true;
        return false;
    }
}