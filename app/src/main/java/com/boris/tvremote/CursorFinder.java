package com.boris.tvremote;

import java.util.ArrayDeque;

/**
 * Finds the TV mouse pointer by comparing two screenshots (before / after the pointer moved).
 * Works on small luminance grids (GW×GH) so it is fast and tolerant of JPEG noise. No Android dependencies.
 */
public final class CursorFinder {
    public static final int GW = 320, GH = 180;
    static final int THRESHOLD = 38;           // luminance difference that counts as "changed"
    static final int MIN_CELLS = 4;            // smaller blobs are noise
    static final int MAX_CELLS = 900;          // bigger blobs are video / animations, not the pointer

    private CursorFinder() { }

    /** ARGB pixels of a GW×GH image → luminance 0..255. */
    public static int[] luminance(int[] argb) {
        int[] l = new int[argb.length];
        for (int k = 0; k < argb.length; k++) {
            int c = argb[k];
            l[k] = (((c >> 16) & 0xff) * 299 + ((c >> 8) & 0xff) * 587 + (c & 0xff) * 114) / 1000;
        }
        return l;
    }

    /**
     * Returns the pointer tip in TV pixels (1920×1080 space) — the top-left of the changed blob nearest to
     * (nearX, nearY) within radius — or null if nothing pointer-like changed.
     */
    public static float[] find(int[] before, int[] after, float nearX, float nearY, float radius) {
        if (before == null || after == null || before.length != GW * GH || after.length != GW * GH) return null;
        boolean[] diff = new boolean[GW * GH];
        int changed = 0;
        for (int k = 0; k < diff.length; k++) {
            if (Math.abs(before[k] - after[k]) >= THRESHOLD) { diff[k] = true; changed++; }
        }
        if (changed == 0 || changed > GW * GH / 3) return null;   // nothing, or the whole picture changed

        float sx = 1920f / GW, sy = 1080f / GH;
        boolean[] seen = new boolean[diff.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        float[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int start = 0; start < diff.length; start++) {
            if (!diff[start] || seen[start]) continue;
            int minX = GW, minY = GH, maxX = -1, maxY = -1, cells = 0;
            int bMinX = GW, bMinY = GH;   // bounds of cells where the AFTER picture is pointer-bright
            q.add(start);
            seen[start] = true;
            while (!q.isEmpty()) {
                int c = q.poll();
                int x = c % GW, y = c / GW;
                cells++;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (after[c] >= 200 && after[c] - before[c] >= THRESHOLD) {
                    if (x < bMinX) bMinX = x;
                    if (y < bMinY) bMinY = y;
                }
                // 8-neighbourhood, with a 1-cell gap allowed so a thin outline stays one blob
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= GW || ny >= GH) continue;
                        int n = ny * GW + nx;
                        if (diff[n] && !seen[n]) { seen[n] = true; q.add(n); }
                    }
                }
            }
            if (cells < MIN_CELLS || cells > MAX_CELLS) continue;
            if (maxX - minX > 40 || maxY - minY > 40) continue;      // far too big for a pointer
            // When old and new positions overlap, the bright cells belong to the NEW pointer only.
            boolean useBright = bMinX < GW;
            float tipX = (useBright ? bMinX : minX) * sx, tipY = (useBright ? bMinY : minY) * sy;
            float cx = (minX + maxX) / 2f * sx, cy = (minY + maxY) / 2f * sy;
            double d = Math.hypot(cx - nearX, cy - nearY);
            if (d <= radius && d < bestD) { bestD = d; best = new float[]{tipX, tipY}; }
        }
        return best;
    }

    /**
     * Least-squares fit px = o + k·u for one axis. Returns {k, o}, or null if the samples don't spread enough.
     */
    public static float[] fit(float[] u, float[] px, int n) {
        if (n < 2) return null;
        double su = 0, sp = 0, suu = 0, sup = 0;
        for (int i = 0; i < n; i++) { su += u[i]; sp += px[i]; suu += u[i] * u[i]; sup += u[i] * px[i]; }
        double den = n * suu - su * su;
        if (Math.abs(den) < 1e-6) return null;
        double k = (n * sup - su * sp) / den;
        double o = (sp - k * su) / n;
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) { minU = Math.min(minU, u[i]); maxU = Math.max(maxU, u[i]); }
        if (maxU - minU < 150 || k < 0.1 || k > 10) return null;
        return new float[]{(float) k, (float) o};
    }
}
