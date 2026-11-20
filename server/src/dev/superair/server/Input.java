package dev.superair.server;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Injection into one panel's display. The shell user holds INJECT_EVENTS.
 *
 * This is the mechanism miniMont's INPUT.md settled on after a UHID mouse failed: a UHID device
 * belongs to the system rather than to a display, so its pointer could never activate anything on
 * ours. Injected events carry setDisplayId, and a hover stream while no button is down is what
 * makes apps answer the pointer the way they answer a real mouse.
 */
public final class Input {
    private final InputManager manager;
    private final Method inject;
    private final Method setDisplayId;
    private final Method setActionButton;
    private final KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    private long downTime;

    public Input() throws Exception {
        manager = (InputManager) Sys.context().getSystemService(android.content.Context.INPUT_SERVICE);
        inject = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
    }

    private void send(InputEvent event, int display) {
        if (display < 0) return;
        try {
            setDisplayId.invoke(event, display);
            inject.invoke(manager, event, 0);
        } catch (ReflectiveOperationException e) {
            Log.e("inject failed", e);
        }
    }

    private static MotionEvent motion(long down, int action, float x, float y, int buttons, int tool,
                                      int source, float vscroll, float hscroll) {
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = tool;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = action == MotionEvent.ACTION_UP ? 0f : 1f;
        coords[0].size = 1f;
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, buttons,
                1f, 1f, -1, 0, source, 0);
    }

    /** Primary button as a finger, so a drag scrolls a list the way a phone app expects. */
    public void touch(int display, String phase, float x, float y) {
        int action;
        switch (phase) {
            case "down": action = MotionEvent.ACTION_DOWN; downTime = SystemClock.uptimeMillis(); break;
            case "move": action = MotionEvent.ACTION_MOVE; break;
            case "up": action = MotionEvent.ACTION_UP; break;
            default: action = MotionEvent.ACTION_CANCEL; break;
        }
        send(motion(downTime, action, x, y, 0, MotionEvent.TOOL_TYPE_FINGER,
                InputDevice.SOURCE_TOUCHSCREEN, 0, 0), display);
    }

    public void hover(int display, float x, float y) {
        long now = SystemClock.uptimeMillis();
        send(motion(now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0, MotionEvent.TOOL_TYPE_MOUSE,
                InputDevice.SOURCE_MOUSE, 0, 0), display);
    }

    public void scroll(int display, float x, float y, float dx, float dy) {
        long now = SystemClock.uptimeMillis();
        send(motion(now, MotionEvent.ACTION_SCROLL, x, y, 0, MotionEvent.TOOL_TYPE_MOUSE,
                InputDevice.SOURCE_MOUSE, dy, dx), display);
    }

    /** A real secondary click, which desktop-aware apps answer with a context menu. */
    public void rightClick(int display, float x, float y) {
        long down = SystemClock.uptimeMillis();
        int button = MotionEvent.BUTTON_SECONDARY;
        int tool = MotionEvent.TOOL_TYPE_MOUSE, src = InputDevice.SOURCE_MOUSE;
        send(motion(down, MotionEvent.ACTION_DOWN, x, y, button, tool, src, 0, 0), display);
        pressButton(down, MotionEvent.ACTION_BUTTON_PRESS, x, y, button, button, display);
        pressButton(down, MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, button, display);
        send(motion(down, MotionEvent.ACTION_UP, x, y, 0, tool, src, 0, 0), display);
    }

    private void pressButton(long down, int action, float x, float y, int state, int button, int display) {
        MotionEvent e = motion(down, action, x, y, state, MotionEvent.TOOL_TYPE_MOUSE,
                InputDevice.SOURCE_MOUSE, 0, 0);
        try {
            setActionButton.invoke(e, button);
        } catch (ReflectiveOperationException ex) {
            Log.e("action button", ex);
        }
        send(e, display);
    }

    public void key(int display, boolean down, int code, int meta) {
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), display);
    }

    public void tapKey(int display, int code) {
        key(display, true, code, 0);
        key(display, false, code, 0);
    }

    /**
     * A whole string at once.
     *
     * The cover keyboard sends words, not keystrokes, so this turns each character into the events
     * its key map says produce it. Characters the map cannot reach are dropped rather than typed
     * wrong — there is no IME attached to a panel display to fall back on.
     */
    public void text(int display, String text) {
        KeyEvent[] events = map.getEvents(text.toCharArray());
        if (events == null) {
            Log.d("no key events for " + text.length() + " characters");
            return;
        }
        for (KeyEvent e : events) send(KeyEvent.changeTimeRepeat(e, SystemClock.uptimeMillis(), 0), display);
    }
}
