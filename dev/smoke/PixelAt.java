// P2.T10: the colour spot check needs a pixel average from a screenshot PNG,
// and babashka (a GraalVM native image) can't resolve javax.imageio.ImageIO --
// probed directly: `bb -e '(import (javax.imageio ImageIO)) ...)'` fails with
// "Unable to resolve classname". A full JDK is on PATH here (`java -version`:
// GraalVM CE 25), and JDK single-file source-code programs (JEP 330, JDK 11+)
// run a .java file directly with no separate javac step, so
// dev/smoke/binary_smoke.clj shells out to `java dev/smoke/PixelAt.java`
// instead. sips (macOS's built-in image tool) was the other option this
// task's own instructions named, but it has no per-pixel query -- only
// whole-image property/profile queries -- so it can't do this job.
//
// Args: <png-path> <x> <y> <w> <h> [dark | near <r> <g> <b>] -- a rectangle
// in the PNG's own pixel space (screenshots are native pixels; the caller is
// responsible for scaling window-relative points by the window's :scale
// first). mode is "dark" (default, unchanged from P2.T10) or "near".
//
// A plain average of every pixel in the rectangle was tried first and
// measured wrong: over a "smoke-run" console-output line (rect 18,1035,
// 128x18 against a real screenshot), the full-box average came back
// 202,202,202 -- close to white, because most of a text line's own
// bounding box is background, not ink, so the average is dominated by it
// rather than by the glyph strokes anyone actually cares about. The same
// rectangle's DARKEST pixel measured 40,40,40 -- an exact match for
// jonquil.console/styles' :out fg "#282828" -- so "dark" mode samples the
// darkest cluster (by luminance) instead of the whole box: sorts every
// opaque pixel by luminance, keeps the darkest of (max 5, 5% of the
// pixel count), and averages just those, which rides out a single
// anti-aliased outlier without diluting into the background the way a
// full-box average does.
//
// P3.T6 added "near <r> <g> <b>": checking a find-bar match highlight
// (jonquil.gtk.find-bar's search-match tag, #fff2a8) can't reuse "dark"
// (that isolates the glyph ink on top of the fill, not the fill itself) and
// a "lightest cluster" mode measured wrong the same way the plain average
// did -- #fff2a8's own mean channel value (221) is LOWER than plain white's
// (255), so the lightest-by-luminance pixels in a rect that also contains
// any plain white background are the white ones, not the yellow fill
// (measured live against a real find-bar screenshot: a rect over a
// highlighted match read back 255,255,255 under a lightest-cluster sort).
// "near" instead sorts by distance to the given target color and averages
// the closest 5% (same n formula as "dark"), which finds the fill
// regardless of whether it or the background has the higher luminance.
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

public class PixelAt {
    public static void main(String[] args) throws Exception {
        if (args.length != 5 && args.length != 6 && args.length != 9) {
            System.err.println("usage: PixelAt <png-path> <x> <y> <w> <h> [dark | near <r> <g> <b>]");
            System.exit(2);
        }
        String path = args[0];
        int x = Integer.parseInt(args[1]);
        int y = Integer.parseInt(args[2]);
        int w = Integer.parseInt(args[3]);
        int h = Integer.parseInt(args[4]);
        String mode = args.length >= 6 ? args[5] : "dark";
        int targetR = 0, targetG = 0, targetB = 0;
        if (mode.equals("near")) {
            if (args.length != 9) {
                System.err.println("usage: PixelAt <png-path> <x> <y> <w> <h> near <r> <g> <b>");
                System.exit(2);
            }
            targetR = Integer.parseInt(args[6]);
            targetG = Integer.parseInt(args[7]);
            targetB = Integer.parseInt(args[8]);
        }
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) {
            System.err.println("PixelAt: could not read image " + path);
            System.exit(1);
        }
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(img.getWidth(), x + w);
        int y1 = Math.min(img.getHeight(), y + h);
        java.util.List<int[]> pixels = new java.util.ArrayList<>();
        for (int py = y0; py < y1; py++) {
            for (int px = x0; px < x1; px++) {
                int argb = img.getRGB(px, py);
                int alpha = (argb >>> 24) & 0xff;
                if (alpha == 0) continue;
                int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
                pixels.add(new int[]{r, g, b, (r + g + b) / 3});
            }
        }
        if (pixels.isEmpty()) {
            System.err.println("PixelAt: rectangle sampled zero opaque pixels");
            System.exit(1);
        }
        if (mode.equals("near")) {
            final int tr = targetR, tg = targetG, tb = targetB;
            pixels.sort((a, b) -> Integer.compare(dist(a, tr, tg, tb), dist(b, tr, tg, tb)));
        } else {
            pixels.sort((a, b) -> Integer.compare(a[3], b[3]));
        }
        int n = Math.max(5, pixels.size() / 20);
        n = Math.min(n, pixels.size());
        long rSum = 0, gSum = 0, bSum = 0;
        for (int i = 0; i < n; i++) {
            int[] p = pixels.get(i);
            rSum += p[0]; gSum += p[1]; bSum += p[2];
        }
        System.out.println((rSum / n) + "," + (gSum / n) + "," + (bSum / n));
    }

    private static int dist(int[] p, int r, int g, int b) {
        return Math.abs(p[0] - r) + Math.abs(p[1] - g) + Math.abs(p[2] - b);
    }
}
