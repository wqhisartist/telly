package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallbackI;

import java.lang.reflect.Field;

public class AutoInventoryTotem extends Module {

    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Boolean> fillOffhand = sgGeneral.add(new BoolSetting.Builder()
        .name("fill-offhand")
        .description("Move a totem to the offhand slot if it is empty.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> cursorSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("cursor-speed")
        .description("Speed the cursor moves toward the totem slot (pixels per tick).")
        .defaultValue(15.0)
        .min(1.0)
        .sliderMax(50.0)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay-ms")
        .description("Delay in ms after the cursor reaches the slot before pressing F.")
        .defaultValue(50)
        .min(0)
        .sliderMax(500)
        .build()
    );

    // ── State ──────────────────────────────────────────────────────────────────

    private enum Phase { IDLE, MOVING, DELAY, PRESS_F, DONE }

    private Phase   phase          = Phase.IDLE;
    private int     totemSlotIndex = -1;
    private double  cursorX        = 0;
    private double  cursorY        = 0;
    private boolean started        = false;
    private int     cachedLeftPos  = 0;
    private int     cachedTopPos   = 0;
    private long    reachedTime    = 0;

    public AutoInventoryTotem() {
        super(AddonTemplate.CATEGORY, "auto-inventory-totem",
            "Opens inventory, moves cursor to a totem, and presses F to offhand it.");
    }

    @Override
    public void onDeactivate() { reset(); }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.screen instanceof InventoryScreen screen)) { if (started) reset(); return; }

        var menu = mc.player.inventoryMenu;

        // ── Initialise once when inventory opens ───────────────────────────────
        if (!started) {
            started = true;

            // Cache leftPos/topPos via reflection
            try {
                Field lf = AbstractContainerScreen.class.getDeclaredField("leftPos");
                Field tf = AbstractContainerScreen.class.getDeclaredField("topPos");
                lf.setAccessible(true); tf.setAccessible(true);
                cachedLeftPos = (int) lf.get(screen);
                cachedTopPos  = (int) tf.get(screen);
            } catch (Exception e) {
                cachedLeftPos = (screen.width  - 176) / 2;
                cachedTopPos  = (screen.height - 166) / 2;
            }

            if (!fillOffhand.get() || !mc.player.getOffhandItem().isEmpty()) {
                phase = Phase.DONE; return;
            }

            int slot = findTotemInInventory(mc);
            if (slot == -1) { phase = Phase.DONE; return; }

            totemSlotIndex = slot;
            cursorX = screen.width  / 2.0;
            cursorY = screen.height / 2.0;
            phase   = Phase.MOVING;
        }

        if (phase == Phase.IDLE || phase == Phase.DONE) return;

        switch (phase) {

            case MOVING -> {
                int[] pos = slotPos(menu, totemSlotIndex);
                if (pos == null) { phase = Phase.DONE; return; }
                if (moveCursor(mc, pos[0], pos[1])) {
                    reachedTime = System.currentTimeMillis();
                    phase = actionDelay.get() > 0 ? Phase.DELAY : Phase.PRESS_F;
                }
            }

            case DELAY -> {
                if (System.currentTimeMillis() - reachedTime >= actionDelay.get()) {
                    phase = Phase.PRESS_F;
                }
            }

            case PRESS_F -> {
                pressOffhandKey(mc);
                phase = Phase.DONE;
            }

            case DONE -> {}
        }
    }

    // ── Key simulation ─────────────────────────────────────────────────────────

    /**
     * Simulates pressing and releasing the swap-with-offhand key (F by default)
     * by firing it directly through GLFW's stored key callback.
     *
     * This bypasses the 26.1 Screen.keyPressed(KeyEvent) API change entirely —
     * we inject the event at the GLFW callback level so Minecraft's input handler
     * processes it exactly as a real physical keypress, including the inventory
     * screen's slot-swap logic.
     */
    private void pressOffhandKey(Minecraft mc) {
        long window = GLFW.glfwGetCurrentContext();

        // Get the GLFW key code bound to swap-with-offhand
        // In 26.1 KeyMapping stores the key as an InputConstants.Key;
        // we get the raw GLFW int via the key's getValue() if available,
        // otherwise fall back to GLFW_KEY_F which is the vanilla default.
        int keyCode = getOffhandKeyCode(mc);

        // Fire the GLFW key callback directly with PRESS then RELEASE.
        // GLFW.glfwGetKeyCalls returns the current key callback so we can
        // invoke it as if the OS sent the event.
        GLFWKeyCallbackI callback = GLFW.glfwSetKeyCallback(window, null);
        if (callback != null) {
            // Re-register the original callback immediately after retrieving it
            GLFW.glfwSetKeyCallback(window, callback);
            // Fire press
            callback.invoke(window, keyCode, 0, GLFW.GLFW_PRESS, 0);
            // Fire release
            callback.invoke(window, keyCode, 0, GLFW.GLFW_RELEASE, 0);
        } else {
            // Fallback: use glfwSetInputMode to ensure cursor is visible and
            // post the key event via the native event queue
            GLFW.glfwPostEmptyEvent();
        }
    }

    /**
     * Gets the GLFW key code for the swap-with-offhand binding.
     * Uses reflection to handle the 26.1 InputConstants.Key wrapper.
     */
    private int getOffhandKeyCode(Minecraft mc) {
        try {
            KeyMapping keySwap = mc.options.keySwapOffhand;
            // In 26.1, KeyMapping.key is an InputConstants.Key field
            Field keyField = KeyMapping.class.getDeclaredField("key");
            keyField.setAccessible(true);
            Object inputKey = keyField.get(keySwap);
            // InputConstants.Key has a getValue() method returning the int code
            java.lang.reflect.Method getValue = inputKey.getClass().getMethod("getValue");
            return (int) getValue.invoke(inputKey);
        } catch (Exception e) {
            return GLFW.GLFW_KEY_F; // vanilla default
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private int findTotemInInventory(Minecraft mc) {
        var menu = mc.player.inventoryMenu;
        // Slots 9-35: main inventory (excludes hotbar 0-8 and offhand)
        for (int i = 9; i <= 35; i++) {
            if (i >= menu.slots.size()) break;
            if (menu.slots.get(i).getItem().getItem() == Items.TOTEM_OF_UNDYING) return i;
        }
        return -1;
    }

    private int[] slotPos(net.minecraft.world.inventory.AbstractContainerMenu menu, int idx) {
        if (idx < 0 || idx >= menu.slots.size()) return null;
        var slot = menu.slots.get(idx);
        return new int[]{ cachedLeftPos + slot.x + 8, cachedTopPos + slot.y + 8 };
    }

    private boolean moveCursor(Minecraft mc, int tx, int ty) {
        double dx = tx - cursorX, dy = ty - cursorY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        double spd  = cursorSpeed.get();
        if (dist <= spd) {
            cursorX = tx; cursorY = ty;
            applyPos(mc); return true;
        }
        cursorX += dx / dist * spd;
        cursorY += dy / dist * spd;
        applyPos(mc); return false;
    }

    private void applyPos(Minecraft mc) {
        double scale = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(GLFW.glfwGetCurrentContext(), cursorX * scale, cursorY * scale);
    }

    private void reset() {
        phase = Phase.IDLE;
        totemSlotIndex = -1;
        started = false;
        reachedTime = 0;
    }
}