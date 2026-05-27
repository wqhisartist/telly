package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class KeyPearl extends Module {

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgDelays  = this.settings.createGroup("Delays");

    // ── General ────────────────────────────────────────────────────────────────

    private final Setting<String> keybind = sgGeneral.add(new StringSetting.Builder()
        .name("keybind")
        .description("Key to trigger the pearl throw. Type the key name (e.g. r, f, g, SPACE, LEFT_SHIFT, LEFT_ALT, LEFT_CONTROL).")
        .defaultValue("r")
        .build()
    );

    private final Setting<Boolean> requireSurvival = sgGeneral.add(new BoolSetting.Builder()
        .name("require-survival")
        .description("Only activate in Survival or Adventure mode.")
        .defaultValue(true)
        .build()
    );

    // ── Delays ─────────────────────────────────────────────────────────────────

    private final Setting<Integer> delayMin = sgDelays.add(new IntSetting.Builder()
        .name("delay-min-ms")
        .description("Minimum random delay in ms applied between each step of the sequence.")
        .defaultValue(0)
        .min(0)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> delayMax = sgDelays.add(new IntSetting.Builder()
        .name("delay-max-ms")
        .description("Maximum random delay in ms applied between each step of the sequence.")
        .defaultValue(50)
        .min(0)
        .sliderMax(200)
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────────────

    private enum Phase {
        IDLE,
        DELAY_BEFORE_SWAP_TO_PEARL,  // random delay before switching to pearl
        SWAP_TO_PEARL,               // switch hotbar slot to pearl
        THROW,                       // throw the pearl
        DELAY_BEFORE_RESTORE,        // random delay before switching back
        RESTORE,                     // switch back to original slot
    }

    private Phase   phase        = Phase.IDLE;
    private int     originalSlot = -1;
    private long    waitUntilMs  = 0;
    private boolean wasKeyDown   = false;

    private final Random rng = new Random();

    public KeyPearl() {
        super(AddonTemplate.CATEGORY, "key-pearl",
            "Press a configurable key to instantly switch to an ender pearl, throw it, then switch back.");
    }

    @Override
    public void onDeactivate() {
        phase        = Phase.IDLE;
        originalSlot = -1;
        wasKeyDown   = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.screen != null) return;

        if (requireSurvival.get()) {
            GameType gm = mc.gameMode.getPlayerMode();
            if (gm != GameType.SURVIVAL && gm != GameType.ADVENTURE) return;
        }

        boolean keyDown    = isKeyDown();
        boolean justPressed = keyDown && !wasKeyDown;
        wasKeyDown = keyDown;

        // Arm the sequence on key press while idle
        if (phase == Phase.IDLE && justPressed) {
            FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
            if (!pearl.found()) return;

            originalSlot = mc.player.getInventory().getSelectedSlot();
            phase        = Phase.DELAY_BEFORE_SWAP_TO_PEARL;
            waitUntilMs  = System.currentTimeMillis() + randomDelay();
            return;
        }

        if (phase == Phase.IDLE) return;

        // Run all zero-delay phases in the same tick
        boolean keepGoing = true;
        while (keepGoing && phase != Phase.IDLE) {
            keepGoing = false;
            long now  = System.currentTimeMillis();

            switch (phase) {

                case DELAY_BEFORE_SWAP_TO_PEARL -> {
                    if (now < waitUntilMs) return;
                    phase     = Phase.SWAP_TO_PEARL;
                    keepGoing = true;
                }

                case SWAP_TO_PEARL -> {
                    FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
                    if (!pearl.found()) { reset(); return; }

                    if (mc.player.getMainHandItem().getItem() != Items.ENDER_PEARL) {
                        InvUtils.swap(pearl.slot(), false);
                    }
                    phase     = Phase.THROW;
                    keepGoing = true;
                }

                case THROW -> {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    long d = randomDelay();
                    if (d == 0) {
                        phase     = Phase.RESTORE;
                        keepGoing = true;
                    } else {
                        phase       = Phase.DELAY_BEFORE_RESTORE;
                        waitUntilMs = now + d;
                    }
                }

                case DELAY_BEFORE_RESTORE -> {
                    if (now < waitUntilMs) return;
                    phase     = Phase.RESTORE;
                    keepGoing = true;
                }

                case RESTORE -> {
                    if (originalSlot != -1) InvUtils.swap(originalSlot, false);
                    reset();
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void reset() {
        phase        = Phase.IDLE;
        originalSlot = -1;
    }

    private long randomDelay() {
        int lo = Math.min(delayMin.get(), delayMax.get());
        int hi = Math.max(delayMin.get(), delayMax.get());
        return lo == hi ? lo : lo + rng.nextInt(hi - lo + 1);
    }

    /**
     * Resolves the string keybind to a GLFW key code and checks if it is
     * currently pressed. Supports single letters (a-z), digits (0-9), and
     * named keys (SPACE, LEFT_SHIFT, LEFT_CONTROL, LEFT_ALT, RIGHT_SHIFT,
     * RIGHT_CONTROL, RIGHT_ALT, TAB, ENTER, ESCAPE, F1-F12, etc.).
     */
    private boolean isKeyDown() {
        String raw = keybind.get().trim().toUpperCase();
        if (raw.isEmpty()) return false;

        int glfwKey = resolveKey(raw);
        if (glfwKey == GLFW.GLFW_KEY_UNKNOWN) return false;

        long window = GLFW.glfwGetCurrentContext();
        return GLFW.glfwGetKey(window, glfwKey) == GLFW.GLFW_PRESS;
    }

    private int resolveKey(String name) {
        // Single letter A-Z
        if (name.length() == 1) {
            char c = name.charAt(0);
            if (c >= 'A' && c <= 'Z') return GLFW.GLFW_KEY_A + (c - 'A');
            if (c >= '0' && c <= '9') return GLFW.GLFW_KEY_0 + (c - '0');
        }
        return switch (name) {
            // Symbol keys — accept the symbol itself or a written name
            case ";", "SEMICOLON"     -> GLFW.GLFW_KEY_SEMICOLON;
            case "'", "APOSTROPHE"    -> GLFW.GLFW_KEY_APOSTROPHE;
            case "[", "LEFT_BRACKET"  -> GLFW.GLFW_KEY_LEFT_BRACKET;
            case "]", "RIGHT_BRACKET" -> GLFW.GLFW_KEY_RIGHT_BRACKET;
            case "BACKSLASH"          -> GLFW.GLFW_KEY_BACKSLASH; // type: backslash
            case ",", "COMMA"         -> GLFW.GLFW_KEY_COMMA;
            case ".", "PERIOD"        -> GLFW.GLFW_KEY_PERIOD;
            case "/", "SLASH"         -> GLFW.GLFW_KEY_SLASH;
            case "`", "GRAVE_ACCENT"  -> GLFW.GLFW_KEY_GRAVE_ACCENT;
            case "-", "MINUS"         -> GLFW.GLFW_KEY_MINUS;
            case "=", "EQUAL"         -> GLFW.GLFW_KEY_EQUAL;
            // Named keys
            case "SPACE"              -> GLFW.GLFW_KEY_SPACE;
            case "ENTER"              -> GLFW.GLFW_KEY_ENTER;
            case "TAB"                -> GLFW.GLFW_KEY_TAB;
            case "ESCAPE", "ESC"      -> GLFW.GLFW_KEY_ESCAPE;
            case "BACKSPACE"          -> GLFW.GLFW_KEY_BACKSPACE;
            case "DELETE"             -> GLFW.GLFW_KEY_DELETE;
            case "INSERT"             -> GLFW.GLFW_KEY_INSERT;
            case "HOME"               -> GLFW.GLFW_KEY_HOME;
            case "END"                -> GLFW.GLFW_KEY_END;
            case "PAGE_UP"            -> GLFW.GLFW_KEY_PAGE_UP;
            case "PAGE_DOWN"          -> GLFW.GLFW_KEY_PAGE_DOWN;
            case "UP"                 -> GLFW.GLFW_KEY_UP;
            case "DOWN"               -> GLFW.GLFW_KEY_DOWN;
            case "LEFT"               -> GLFW.GLFW_KEY_LEFT;
            case "RIGHT"              -> GLFW.GLFW_KEY_RIGHT;
            case "LEFT_SHIFT"         -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "RIGHT_SHIFT"        -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "LEFT_CONTROL", "LEFT_CTRL"   -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "RIGHT_CONTROL", "RIGHT_CTRL" -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "LEFT_ALT"           -> GLFW.GLFW_KEY_LEFT_ALT;
            case "RIGHT_ALT"          -> GLFW.GLFW_KEY_RIGHT_ALT;
            case "LEFT_SUPER"         -> GLFW.GLFW_KEY_LEFT_SUPER;
            case "RIGHT_SUPER"        -> GLFW.GLFW_KEY_RIGHT_SUPER;
            case "CAPS_LOCK"          -> GLFW.GLFW_KEY_CAPS_LOCK;
            case "F1"  -> GLFW.GLFW_KEY_F1;
            case "F2"  -> GLFW.GLFW_KEY_F2;
            case "F3"  -> GLFW.GLFW_KEY_F3;
            case "F4"  -> GLFW.GLFW_KEY_F4;
            case "F5"  -> GLFW.GLFW_KEY_F5;
            case "F6"  -> GLFW.GLFW_KEY_F6;
            case "F7"  -> GLFW.GLFW_KEY_F7;
            case "F8"  -> GLFW.GLFW_KEY_F8;
            case "F9"  -> GLFW.GLFW_KEY_F9;
            case "F10" -> GLFW.GLFW_KEY_F10;
            case "F11" -> GLFW.GLFW_KEY_F11;
            case "F12" -> GLFW.GLFW_KEY_F12;
            case "KP_0" -> GLFW.GLFW_KEY_KP_0;
            case "KP_1" -> GLFW.GLFW_KEY_KP_1;
            case "KP_2" -> GLFW.GLFW_KEY_KP_2;
            case "KP_3" -> GLFW.GLFW_KEY_KP_3;
            case "KP_4" -> GLFW.GLFW_KEY_KP_4;
            case "KP_5" -> GLFW.GLFW_KEY_KP_5;
            case "KP_6" -> GLFW.GLFW_KEY_KP_6;
            case "KP_7" -> GLFW.GLFW_KEY_KP_7;
            case "KP_8" -> GLFW.GLFW_KEY_KP_8;
            case "KP_9" -> GLFW.GLFW_KEY_KP_9;
            default -> GLFW.GLFW_KEY_UNKNOWN;
        };
    }
}