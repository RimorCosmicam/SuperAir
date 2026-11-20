package dev.superair;

/** The app, as the host sees it. Everything here is one-way: the host must never block on the UI. */
oneway interface IAirClient {
    /** The app behind this panel went away on its own — backed out, crashed, or was killed. */
    void panelGone(int panelId);
    /** A panel's app asked for a size the host could not give it, so the host changed it. */
    void panelResized(int panelId, int width, int height);
    /** The cover's screen was switched off (or back on) to keep the glasses' apps running. */
    void coverDark(boolean dark);
}
