package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public class AimAssist extends Module {

    // ── Enums ──────────────────────────────────────────────────────────────────

    public enum WeaponMode {
        MACE_ONLY("Mace Only"),
        WEAPONS_ONLY("Weapons Only"),
        MACE_AND_WEAPONS("Mace and Weapons"),
        ALL("All Items");

        private final String name;

        WeaponMode(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    public enum PosMode { Normal, Lerped }
    public enum AimMode { Head, Chest, Legs }
    public enum LerpMode { Normal, Smoothstep, EaseOut }

    private record Rotation(double yaw, double pitch) {}

    // ── Settings ───────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> stickyAim = sgGeneral.add(new BoolSetting.Builder()
        .name("sticky-aim").description("Aims at the last attacked player").defaultValue(false).build());

    private final Setting<WeaponMode> weaponMode = sgGeneral.add(new EnumSetting.Builder<WeaponMode>()
        .name("filter").description("Which items trigger aim assist").defaultValue(WeaponMode.WEAPONS_ONLY).build());

    private final Setting<Boolean> onLeftClick = sgGeneral.add(new BoolSetting.Builder()
        .name("on-left-click").description("Only gets triggered if holding down left click").defaultValue(false).build());

    private final Setting<AimMode> aimAt = sgGeneral.add(new EnumSetting.Builder<AimMode>()
        .name("aim-at").defaultValue(AimMode.Head).build());

    private final Setting<Boolean> stopAtTargetVertical = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-at-target-vert").description("Stops vertically assisting if already aiming at the entity").defaultValue(true).build());

    private final Setting<Boolean> stopAtTargetHorizontal = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-at-target-horiz").description("Stops horizontally assisting if already aiming at the entity").defaultValue(false).build());

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius").defaultValue(5).min(0.1).sliderMax(6).build());

    private final Setting<Boolean> seeOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("see-only").defaultValue(true).build());

    private final Setting<Boolean> lookAtNearest = sgGeneral.add(new BoolSetting.Builder()
        .name("look-at-nearest").defaultValue(false).build());

    private final Setting<Integer> fov = sgGeneral.add(new IntSetting.Builder()
        .name("fov").defaultValue(100).min(5).sliderMax(360).build());

    private final Setting<Double> pitchSpeedMin = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed-min").defaultValue(2).min(0).sliderMax(10).build());
    private final Setting<Double> pitchSpeedMax = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed-max").defaultValue(4).min(0).sliderMax(10).build());

    private final Setting<Double> yawSpeedMin = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed-min").defaultValue(2).min(0).sliderMax(10).build());
    private final Setting<Double> yawSpeedMax = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed-max").defaultValue(4).min(0).sliderMax(10).build());

    private final Setting<Integer> speedChange = sgGeneral.add(new IntSetting.Builder()
        .name("speed-delay").description("Time in milliseconds to wait after resetting random speed").defaultValue(250).min(0).sliderMax(1000).build());

    private final Setting<Integer> randomization = sgGeneral.add(new IntSetting.Builder()
        .name("chance").defaultValue(50).min(0).sliderMax(100).build());

    private final Setting<Boolean> yawAssist = sgGeneral.add(new BoolSetting.Builder()
        .name("horizontal").defaultValue(true).build());

    private final Setting<Boolean> pitchAssist = sgGeneral.add(new BoolSetting.Builder()
        .name("vertical").defaultValue(true).build());

    private final Setting<Integer> waitFor = sgGeneral.add(new IntSetting.Builder()
        .name("wait-on-move").description("After moving mouse, aim assist pauses for this long").defaultValue(0).min(0).sliderMax(1000).build());

    private final Setting<LerpMode> lerpMode = sgGeneral.add(new EnumSetting.Builder<LerpMode>()
        .name("lerp").description("Linear interpolation to use to rotate").defaultValue(LerpMode.Normal).build());

    private final Setting<PosMode> posMode = sgGeneral.add(new EnumSetting.Builder<PosMode>()
        .name("pos-mode").description("Precision of the target position").defaultValue(PosMode.Normal).build());

    // ── State Variables ────────────────────────────────────────────────────────

    private final Random random = new Random();
    private long lastMoveTime = 0;
    private long lastSpeedReset = 0;
    private boolean canMove = true;
    private float currentPitchSpeed, currentYawSpeed;
    private double lastMouseX, lastMouseY;

    public AimAssist() {
        super(AddonTemplate.CATEGORY, "aim-assist", "Automatically aims at players for you.");
    }

    @Override
    public void onActivate() {
        canMove = true;
        currentPitchSpeed = getRandomPitchSpeed();
        currentYawSpeed = getRandomYawSpeed();
        lastSpeedReset = System.currentTimeMillis();
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler != null) {
            lastMouseX = mc.mouseHandler.xpos();
            lastMouseY = mc.mouseHandler.ypos();
        }
    }

    // ── Core Logic ─────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;

        // Mouse Move Listener Simulation
        if (mc.mouseHandler.xpos() != lastMouseX || mc.mouseHandler.ypos() != lastMouseY) {
            canMove = false;
            lastMoveTime = System.currentTimeMillis();
            lastMouseX = mc.mouseHandler.xpos();
            lastMouseY = mc.mouseHandler.ypos();
        }

        if (System.currentTimeMillis() - lastMoveTime >= waitFor.get() && !canMove) {
            canMove = true;
        }

        // Tag-based weapon checking
        var heldStack = mc.player.getMainHandItem();
        switch (weaponMode.get()) {
            case MACE_ONLY -> { if (!heldStack.is(Items.MACE)) return; }
            case WEAPONS_ONLY -> { if (!(heldStack.is(ItemTags.SWORDS) || heldStack.is(ItemTags.AXES))) return; }
            case MACE_AND_WEAPONS -> { if (!(heldStack.is(ItemTags.SWORDS) || heldStack.is(ItemTags.AXES) || heldStack.is(Items.MACE))) return; }
            case ALL -> {}
        }

        // Checking native Minecraft Attack Keybind instead of raw GLFW window pointers
        if (onLeftClick.get() && !mc.options.keyAttack.isDown()) {
            return;
        }

        Player target = findNearestPlayer(mc.player, radius.get(), seeOnly.get());
        
        // Sticky Aim override
        if (stickyAim.get() && mc.player.getLastHurtMob() instanceof Player p && p.distanceTo(mc.player) < radius.get()) {
            target = p;
        }

        if (target == null) return;

        if (System.currentTimeMillis() - lastSpeedReset >= speedChange.get()) {
            currentPitchSpeed = getRandomPitchSpeed();
            currentYawSpeed = getRandomYawSpeed();
            lastSpeedReset = System.currentTimeMillis();
        }

        Vec3 targetPos = posMode.get() == PosMode.Normal ? target.position() : target.getPosition(1.0F);

        if (aimAt.get() == AimMode.Chest) targetPos = targetPos.add(0, -0.5, 0);
        else if (aimAt.get() == AimMode.Legs) targetPos = targetPos.add(0, -1.2, 0);

        if (lookAtNearest.get()) {
            double offsetX = mc.player.getX() - target.getX() > 0 ? 0.29 : -0.29;
            double offsetZ = mc.player.getZ() - target.getZ() > 0 ? 0.29 : -0.29;
            targetPos = targetPos.add(offsetX, 0, offsetZ);
        }

        Rotation rotation = getDirection(mc.player, targetPos);
        double angleToRotation = getAngleToRotation(mc.player, rotation);
        
        if (angleToRotation > (double) fov.get() / 2) return;

        float yawStrength = currentYawSpeed / 50f;
        float pitchStrength = currentPitchSpeed / 50f;

        float playerYaw = mc.player.getYRot();
        float playerPitch = mc.player.getXRot();

        if (lerpMode.get() == LerpMode.Smoothstep) {
            playerYaw = (float) smoothStepLerp(yawStrength, mc.player.getYRot(), rotation.yaw());
            playerPitch = (float) smoothStepLerp(pitchStrength, mc.player.getXRot(), rotation.pitch());
        } else if (lerpMode.get() == LerpMode.Normal) {
            playerYaw = lerp(yawStrength, mc.player.getYRot(), (float) rotation.yaw());
            playerPitch = lerp(pitchStrength, mc.player.getXRot(), (float) rotation.pitch());
        } else if (lerpMode.get() == LerpMode.EaseOut) {
            playerYaw = (float) easeOutBackDegrees(mc.player.getYRot(), rotation.yaw(), yawStrength);
            playerPitch = (float) easeOutBackDegrees(mc.player.getXRot(), rotation.pitch(), pitchStrength);
        }

        if (random.nextInt(100) + 1 <= randomization.get()) {
            if (canMove) {
                if (yawAssist.get()) {
                    if (stopAtTargetHorizontal.get() && getHitResult(mc.player, radius.get(), true) instanceof EntityHitResult ehr && ehr.getEntity() == target) {
                        // Do nothing
                    } else {
                        mc.player.setYRot(playerYaw);
                    }
                }

                if (pitchAssist.get()) {
                    if (stopAtTargetVertical.get() && getHitResult(mc.player, radius.get(), true) instanceof EntityHitResult ehr && ehr.getEntity() == target) {
                        // Do nothing
                    } else {
                        mc.player.setXRot(playerPitch);
                    }
                }
            }
        }
    }

    // ── Baked Utilities ────────────────────────────────────────────────────────

    private float getRandomPitchSpeed() {
        double min = pitchSpeedMin.get();
        double max = pitchSpeedMax.get();
        return (float) (min + random.nextDouble() * (max - min));
    }

    private float getRandomYawSpeed() {
        double min = yawSpeedMin.get();
        double max = yawSpeedMax.get();
        return (float) (min + random.nextDouble() * (max - min));
    }

    private Player findNearestPlayer(Player toPlayer, double range, boolean checkLineOfSight) {
        Minecraft mc = Minecraft.getInstance();
        double minRange = Double.MAX_VALUE;
        Player minPlayer = null;

        for (Player player : mc.level.players()) {
            if (player == toPlayer) continue;
            double distance = toPlayer.distanceTo(player);

            if (distance <= range && (!checkLineOfSight || toPlayer.hasLineOfSight(player))) {
                if (distance < minRange) {
                    minRange = distance;
                    minPlayer = player;
                }
            }
        }
        return minPlayer;
    }

    private Rotation getDirection(Entity entity, Vec3 vec) {
        double dx = vec.x - entity.getX();
        double dy = vec.y - entity.getY();
        double dz = vec.z - entity.getZ();
        double dist = Mth.sqrt((float) (dx * dx + dz * dz));
        return new Rotation(
            Mth.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
            -Mth.wrapDegrees(Math.toDegrees(Math.atan2(dy, dist)))
        );
    }

    private double getAngleToRotation(Player player, Rotation rotation) {
        double currentYaw = Mth.wrapDegrees(player.getYRot());
        double currentPitch = Mth.wrapDegrees(player.getXRot());
        double diffYaw = Mth.wrapDegrees(currentYaw - rotation.yaw());
        double diffPitch = Mth.wrapDegrees(currentPitch - rotation.pitch());
        return Math.sqrt(diffYaw * diffYaw + diffPitch * diffPitch);
    }

    private float lerp(float delta, float start, float end) {
        return start + (Mth.wrapDegrees(end - start) * delta);
    }

    private double easeOutBackDegrees(double start, double end, float speed) {
        double c1 = 1.70158;
        double c3 = 2.70158;
        double x = 1 - Math.pow(1 - speed, 3);
        return start + Mth.wrapDegrees((float)(end - start)) * (1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2));
    }

    private double smoothStepLerp(double delta, double start, double end) {
        delta = Math.max(0, Math.min(1, delta));
        double t = delta * delta * (3 - 2 * delta);
        return start + Mth.wrapDegrees((float)(end - start)) * t;
    }

    private HitResult getHitResult(Player player, double distance, boolean ignoreInvisibles) {
        Vec3 cameraPos = player.getEyePosition(1.0F);
        Vec3 rotationVec = player.getViewVector(1.0F);
        Vec3 end = cameraPos.add(rotationVec.x * distance, rotationVec.y * distance, rotationVec.z * distance);

        HitResult blockHit = player.level().clip(new ClipContext(cameraPos, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        
        double hitDistSq = distance * distance;
        if (blockHit.getType() != HitResult.Type.MISS) {
            hitDistSq = blockHit.getLocation().distanceToSqr(cameraPos);
            end = blockHit.getLocation();
        }

        AABB box = player.getBoundingBox().expandTowards(rotationVec.scale(distance)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player, cameraPos, end, box,
            entity -> !entity.isSpectator() && entity.isPickable() && (!entity.isInvisible() || !ignoreInvisibles),
            hitDistSq
        );

        return entityHit != null ? entityHit : blockHit;
    }
}