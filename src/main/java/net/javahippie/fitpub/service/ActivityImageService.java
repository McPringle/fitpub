package net.javahippie.fitpub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.LineString;
import net.javahippie.fitpub.model.entity.Activity;
import net.javahippie.fitpub.model.entity.PrivacyZone;
import net.javahippie.fitpub.model.entity.User;
import net.javahippie.fitpub.repository.UserRepository;
import net.javahippie.fitpub.util.ActivityFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for generating activity preview images for ActivityPub federation.
 *
 * <p>The output image is the one attached to outbound ActivityPub notes (and
 * served as the {@code og:image} for activity detail pages). The current design
 * is specified by {@code SPEC-share-image-redesign.md}: a 1080×800 PNG with a
 * square 800×800 map area on the left and a 280px dark stats panel on the right.
 * Indoor activities without GPS data fall back to a striped background with an
 * optional HR sparkline as atmosphere.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityImageService {

    private final OsmTileRenderer osmTileRenderer;
    private final PrivacyZoneService privacyZoneService;
    private final TrackPrivacyFilter trackPrivacyFilter;
    private final UserRepository userRepository;

    @Value("${fitpub.storage.images.path:${java.io.tmpdir}/fitpub/images}")
    private String imagesPath;

    @Value("${fitpub.base-url}")
    private String baseUrl;

    @Value("${fitpub.image.osm-tiles.enabled:true}")
    private boolean osmTilesEnabled;

    // ─── Layout constants (per spec) ────────────────────────────────────────
    private static final int IMAGE_WIDTH       = 1080;
    private static final int IMAGE_HEIGHT      = 800;
    private static final int MAP_SIZE          = 800;     // square
    private static final int PANEL_WIDTH       = IMAGE_WIDTH - MAP_SIZE; // 280
    private static final int PANEL_PAD_X       = 18;
    private static final int PANEL_PAD_Y       = 20;

    // ─── Color palette (per spec) ───────────────────────────────────────────
    private static final Color PANEL_BG       = new Color(0x0f, 0x05, 0x20);
    private static final Color PANEL_BORDER   = new Color(0x3d, 0x20, 0x60);
    private static final Color BRAND_PINK     = new Color(0xff, 0x14, 0x93);
    private static final Color BRAND_CYAN     = new Color(0x00, 0xff, 0xff);
    private static final Color BRAND_ORANGE   = new Color(0xff, 0x66, 0x00);
    private static final Color BRAND_GREEN    = new Color(0x39, 0xff, 0x14);
    private static final Color TEXT_PRIMARY   = new Color(0xe8, 0xe8, 0xf0);
    private static final Color TEXT_MUTED     = new Color(0xa8, 0xa8, 0xc0);
    private static final Color HANDLE_MUTED   = new Color(0x5a, 0x48, 0x70);

    // ─── Fonts (Heavy + Regular tiers per the design system) ────────────────
    private static final String HEAVY_FAMILY = "Arial Black";
    private static final String REG_FAMILY   = "Arial";

    /**
     * Generate a preview image for an activity showing the track outline and metadata.
     * Applies privacy zone filtering to ensure GPS coordinates within zones are not rendered.
     *
     * @param activity the activity to generate an image for
     * @return the URL of the generated image, or null on failure
     */
    public String generateActivityImage(Activity activity) {
        try {
            Activity filteredActivity = applyPrivacyFiltering(activity);

            BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            try {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,    RenderingHints.VALUE_STROKE_PURE);

                boolean isIndoor = filteredActivity.getSimplifiedTrack() == null
                    && (filteredActivity.getTrackPointsJson() == null || filteredActivity.getTrackPointsJson().isEmpty());

                if (isIndoor) {
                    drawIndoorFallback(g2d, activity);
                } else {
                    drawSquareMap(g2d, filteredActivity, activity);
                }

                drawStatsPanel(g2d, activity, isIndoor);
            } finally {
                g2d.dispose();
            }

            File imagesDir = new File(imagesPath);
            if (!imagesDir.exists()) {
                imagesDir.mkdirs();
            }
            File imageFile = new File(imagesDir, activity.getId() + ".png");
            ImageIO.write(image, "png", imageFile);

            log.info("Generated activity image: {}", imageFile.getAbsolutePath());
            return baseUrl + "/api/activities/" + activity.getId() + "/image";

        } catch (Exception e) {
            log.error("Failed to generate activity image for {}", activity.getId(), e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Alt text for the share image (accessibility)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a plain-text description of the activity suitable for use as
     * the {@code alt} text on the share image. Mastodon, other ActivityPub
     * servers, and screen readers expose the {@code name} field of an
     * {@code Image} attachment as the image description, so this is what
     * blind / low-vision users actually hear when an outbound post is read.
     *
     * <p>The description is built from the same data the visual renderer
     * uses ({@link #selectMetricsForActivity}), so the alt text and the
     * pixels stay in sync if the metric selection ever changes. The result
     * is a single sentence (or two) of natural prose, not a CSV of numbers.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "Run titled 'Morning loop'. 4.50 km in 25 minutes 24 seconds, pace 5:39 per kilometer, elevation gain 36 meters. Mainz-Bretzenheim."}</li>
     *   <li>{@code "Indoor cycling titled 'Zwift workout'. 12.30 km in 45 minutes 12 seconds, average speed 16.3 km/h, average heart rate 142 bpm."}</li>
     * </ul>
     *
     * @param activity the activity (must not be null; metrics may be partially missing)
     * @return a non-null human-readable description, never empty
     */
    public String buildImageAltText(Activity activity) {
        boolean isIndoor = activity.getSimplifiedTrack() == null
            && (activity.getTrackPointsJson() == null || activity.getTrackPointsJson().isEmpty());

        // Lead with the activity type + title.
        String typeLabel = ActivityFormatter.formatActivityType(activity.getActivityType());
        if (isIndoor && !typeLabel.toLowerCase().startsWith("indoor")) {
            typeLabel = "Indoor " + typeLabel.toLowerCase();
        }
        String title = activity.getTitle() != null && !activity.getTitle().isBlank()
            ? activity.getTitle().trim()
            : null;

        StringBuilder sb = new StringBuilder();
        if (title != null) {
            sb.append(typeLabel).append(" titled \"").append(title).append("\". ");
        } else {
            sb.append(typeLabel).append(". ");
        }

        // Convert the four selected metrics into prose. The metric labels in
        // MetricEntry are already lowercase ("distance", "moving time", etc.)
        // so we can just stitch them with " ... " separators.
        List<MetricEntry> metrics = selectMetricsForActivity(activity, isIndoor);
        List<String> phrases = new ArrayList<>();
        for (MetricEntry m : metrics) {
            phrases.add(metricToPhrase(m));
        }
        if (!phrases.isEmpty()) {
            sb.append(joinPhrases(phrases)).append(".");
        }

        // Optional trailing location.
        if (activity.getActivityLocation() != null && !activity.getActivityLocation().isBlank()) {
            sb.append(" ").append(activity.getActivityLocation().trim()).append(".");
        }

        return sb.toString();
    }

    /**
     * Convert one {@link MetricEntry} into a short prose phrase. Distance and
     * elevation get their values stated directly; durations are spoken as
     * "X minutes Y seconds"; pace and speed are stated with the appropriate
     * preposition.
     */
    private String metricToPhrase(MetricEntry m) {
        String label = m.label == null ? "" : m.label;
        switch (label) {
            case "distance":
                // value is "X.XX", unit is "km"
                return m.value + " " + m.unit;
            case "moving time":
                // value is "M:SS" or "H:MM:SS"
                return "in " + speakDuration(m.value);
            case "pace":
                // value is "M:SS", unit is "/km"
                return "pace " + m.value + " per kilometer";
            case "avg speed":
                // value is "X.X", unit is "km/h"
                return "average speed " + m.value + " " + m.unit;
            case "elevation":
                // value is "X", unit is "m"
                return "elevation gain " + m.value + " meters";
            case "avg heart rate":
                // value is "X", unit is "bpm"
                return "average heart rate " + m.value + " bpm";
            default:
                // Unknown label — fall back to a generic phrase so we never
                // emit a malformed sentence.
                String suffix = (m.unit != null && !m.unit.isEmpty()) ? " " + m.unit : "";
                return label + " " + m.value + suffix;
        }
    }

    /**
     * Convert a colon-formatted duration ({@code "M:SS"} or {@code "H:MM:SS"})
     * into spoken prose ({@code "25 minutes 24 seconds"} /
     * {@code "1 hour 5 minutes 12 seconds"}). Falls back to the original
     * input if the format isn't recognised.
     */
    private static String speakDuration(String formatted) {
        if (formatted == null) return "";
        String[] parts = formatted.split(":");
        try {
            if (parts.length == 2) {
                int m = Integer.parseInt(parts[0]);
                int s = Integer.parseInt(parts[1]);
                return pluralise(m, "minute") + " " + pluralise(s, "second");
            } else if (parts.length == 3) {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                int s = Integer.parseInt(parts[2]);
                return pluralise(h, "hour") + " " + pluralise(m, "minute") + " " + pluralise(s, "second");
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return formatted;
    }

    private static String pluralise(int n, String singular) {
        return n + " " + singular + (n == 1 ? "" : "s");
    }

    /**
     * Stitch a list of phrases with sentence-friendly separators:
     * {@code "a"} → {@code "a"};
     * {@code "a", "b"} → {@code "a, b"};
     * {@code "a", "b", "c"} → {@code "a, b, c"}.
     * The Oxford comma stays in for parser-friendliness.
     */
    private static String joinPhrases(List<String> phrases) {
        return String.join(", ", phrases);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Map area (outdoor activities)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Render the square 800×800 map area: tiles + GPS trace + start/end markers
     * + optional location name. The {@code filteredActivity} provides the
     * GPS data (with privacy zones removed), the {@code originalActivity}
     * provides metadata like the location name.
     */
    private void drawSquareMap(Graphics2D g2d, Activity filteredActivity, Activity originalActivity) {
        TrackBounds bounds = calculateTrackBounds(filteredActivity);

        // Always start with a dark fill so any tile-loading failures still
        // produce a panel-matching backdrop instead of a glaring white square.
        g2d.setColor(PANEL_BG);
        g2d.fillRect(0, 0, MAP_SIZE, MAP_SIZE);

        if (bounds == null) {
            // No GPS but not formally indoor (defensive — shouldn't happen).
            return;
        }

        // Render OSM tiles into the square map area when enabled. The tile
        // renderer's letterbox transform is what we use later to project the
        // GPS polyline into pixel space.
        if (osmTilesEnabled) {
            try {
                BufferedImage mapTiles = osmTileRenderer.renderMapWithTiles(
                        bounds.minLat, bounds.maxLat,
                        bounds.minLon, bounds.maxLon,
                        MAP_SIZE, MAP_SIZE);
                g2d.drawImage(mapTiles, 0, 0, null);

                // Subtle dark overlay so the route reads on light tile areas
                // and so the look matches the dark stats panel.
                g2d.setColor(new Color(15, 5, 32, 90));
                g2d.fillRect(0, 0, MAP_SIZE, MAP_SIZE);
            } catch (Exception e) {
                log.warn("Failed to render OSM tiles, using dark background: {}", e.getMessage());
            }
        }

        drawTrack(g2d, filteredActivity, bounds);

        // Subtle location label in the bottom-left of the map area.
        if (originalActivity.getActivityLocation() != null && !originalActivity.getActivityLocation().isBlank()) {
            g2d.setFont(new Font(REG_FAMILY, Font.PLAIN, 11));
            g2d.setColor(new Color(255, 255, 255, 64)); // ~25% white
            g2d.drawString(originalActivity.getActivityLocation(), 10, MAP_SIZE - 10);
        }
    }

    /**
     * Draw the GPS polyline on top of the map tiles. Uses the same Web Mercator
     * projection as the OSM tile renderer so the line aligns with the tiles.
     * Privacy fade (hide first/last 100m, fade-in over 100–200m) is preserved
     * from the previous design — the spec doesn't speak to it but it's a privacy
     * feature we don't want to lose.
     */
    private void drawTrack(Graphics2D g2d, Activity activity, TrackBounds bounds) {
        List<Map<String, Object>> trackPoints = parseTrackPoints(activity.getTrackPointsJson());
        if (trackPoints == null || trackPoints.isEmpty()) {
            return;
        }

        OsmTileRenderer.LetterboxTransform letterbox = osmTileRenderer.getLastLetterboxTransform();
        if (letterbox == null) {
            log.warn("No letterbox transform available, track overlay may be misaligned");
            return;
        }

        double minMx = longitudeToWebMercatorX(bounds.minLon);
        double maxMx = longitudeToWebMercatorX(bounds.maxLon);
        double minMy = latitudeToWebMercatorY(bounds.maxLat); // maxLat → minY (inverted)
        double maxMy = latitudeToWebMercatorY(bounds.minLat); // minLat → maxY (inverted)
        double pxPerMx = letterbox.scaledWidth  / (maxMx - minMx);
        double pxPerMy = letterbox.scaledHeight / (maxMy - minMy);

        // Per-segment privacy fade.
        double[] cumDist = calculateCumulativeDistances(trackPoints);
        double total = cumDist[cumDist.length - 1];
        final double HIDDEN = 100.0;
        final double FADE   = 200.0;

        // Capture each segment's pixel coordinates + opacity once and reuse for
        // the start/end markers below (no duplicate projection math).
        int n = trackPoints.size();
        double[] px = new double[n];
        double[] py = new double[n];
        for (int i = 0; i < n; i++) {
            Double lat = getDouble(trackPoints.get(i), "latitude");
            Double lon = getDouble(trackPoints.get(i), "longitude");
            if (lat == null || lon == null) {
                px[i] = Double.NaN;
                py[i] = Double.NaN;
                continue;
            }
            double mx = longitudeToWebMercatorX(lon);
            double my = latitudeToWebMercatorY(lat);
            px[i] = (mx - minMx) * pxPerMx + letterbox.offsetX;
            py[i] = (my - minMy) * pxPerMy + letterbox.offsetY;
        }

        // Stroke: 3.5px round, pink (matches FitPub brand). The previous cyan
        // washed out on light tiles; pink reads cleanly against both green
        // landscape tiles and dark overlays.
        g2d.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < n - 1; i++) {
            if (Double.isNaN(px[i]) || Double.isNaN(px[i + 1])) continue;

            double distFromStart = cumDist[i];
            double distFromEnd   = total - cumDist[i];
            float opacity = 1.0f;
            if (distFromStart < HIDDEN)      opacity = 0.0f;
            else if (distFromStart < FADE)   opacity = Math.min(opacity, (float) ((distFromStart - HIDDEN) / (FADE - HIDDEN)));
            if (distFromEnd < HIDDEN)        opacity = 0.0f;
            else if (distFromEnd < FADE)     opacity = Math.min(opacity, (float) ((distFromEnd - HIDDEN) / (FADE - HIDDEN)));
            if (opacity <= 0.0f) continue;

            int alpha = Math.max(0, Math.min(255, (int) (opacity * 255)));
            g2d.setColor(new Color(BRAND_PINK.getRed(), BRAND_PINK.getGreen(), BRAND_PINK.getBlue(), alpha));
            g2d.drawLine((int) px[i], (int) py[i], (int) px[i + 1], (int) py[i + 1]);
        }

        // Start/end markers — placed at the first/last *visible* point after
        // the privacy fade (not raw index 0 / n-1, which would defeat the
        // purpose of hiding the home location). For very short activities
        // where no point survives the fade, fall back to indices 0 and n-1
        // so the markers are at least visible somewhere.
        int startIdx = firstVisibleIndex(cumDist, total, HIDDEN);
        int endIdx   = lastVisibleIndex(cumDist, total, HIDDEN);
        if (startIdx < 0) startIdx = 0;
        if (endIdx   < 0) endIdx   = n - 1;

        // Use SrcOver explicitly so the translucent end-marker blends correctly.
        Composite prevComposite = g2d.getComposite();
        g2d.setComposite(AlphaComposite.SrcOver);

        if (startIdx >= 0 && startIdx < n && !Double.isNaN(px[startIdx])) {
            g2d.setColor(BRAND_PINK);
            int r = 6; // radius 6 → 12px diameter — matches spec "5–6px"
            g2d.fillOval((int) px[startIdx] - r, (int) py[startIdx] - r, r * 2, r * 2);
        }
        if (endIdx >= 0 && endIdx < n && endIdx != startIdx && !Double.isNaN(px[endIdx])) {
            g2d.setColor(withAlpha(BRAND_PINK, 0.40f)); // 40% opacity per spec
            int r = 4; // radius 4 → 8px diameter
            g2d.fillOval((int) px[endIdx] - r, (int) py[endIdx] - r, r * 2, r * 2);
        }

        g2d.setComposite(prevComposite);
    }

    private static int firstVisibleIndex(double[] cumDist, double total, double hidden) {
        for (int i = 0; i < cumDist.length; i++) {
            if (cumDist[i] >= hidden && (total - cumDist[i]) >= hidden) return i;
        }
        return -1;
    }

    private static int lastVisibleIndex(double[] cumDist, double total, double hidden) {
        for (int i = cumDist.length - 1; i >= 0; i--) {
            if (cumDist[i] >= hidden && (total - cumDist[i]) >= hidden) return i;
        }
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Indoor fallback (no GPS)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Render the indoor fallback for activities with no GPS data: dark
     * background with a subtle diagonal pink stripe pattern, plus an HR
     * sparkline (if heart-rate data is available) as atmospheric chrome.
     */
    private void drawIndoorFallback(Graphics2D g2d, Activity activity) {
        // Solid dark background
        g2d.setColor(PANEL_BG);
        g2d.fillRect(0, 0, MAP_SIZE, MAP_SIZE);

        // Diagonal stripe texture at ~5% pink. We draw individual lines rather
        // than a paint pattern because Graphics2D's TexturePaint requires a
        // BufferedImage anchor and the manual loop is faster for one-off use.
        g2d.setColor(new Color(BRAND_PINK.getRed(), BRAND_PINK.getGreen(), BRAND_PINK.getBlue(), 13));
        g2d.setStroke(new BasicStroke(1.5f));
        // Draw lines along the 135° diagonal — cover the full canvas plus an
        // overhang so corners are filled.
        for (int offset = -MAP_SIZE; offset < MAP_SIZE * 2; offset += 30) {
            g2d.drawLine(offset, 0, offset + MAP_SIZE, MAP_SIZE);
        }

        // Optional HR sparkline as atmosphere.
        List<Integer> hrSeries = extractHeartRateSeries(activity);
        if (hrSeries != null && hrSeries.size() > 1) {
            drawHeartRateSparkline(g2d, hrSeries, activity);
        }
    }

    private List<Integer> extractHeartRateSeries(Activity activity) {
        if (activity.getTrackPointsJson() == null || activity.getTrackPointsJson().isEmpty()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(activity.getTrackPointsJson());
            if (!root.isArray()) return null;
            List<Integer> series = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode n : root) {
                if (n.has("heartRate") && !n.get("heartRate").isNull()) {
                    int hr = n.get("heartRate").asInt();
                    if (hr > 0) series.add(hr);
                }
            }
            return series.isEmpty() ? null : series;
        } catch (Exception e) {
            return null;
        }
    }

    private void drawHeartRateSparkline(Graphics2D g2d, List<Integer> hr, Activity activity) {
        // Use the middle 70% of the map area, leaving room for axis labels and a legend.
        int leftPad = 60;
        int rightPad = 30;
        int topPad = 100;
        int bottomPad = 80;
        int chartW = MAP_SIZE - leftPad - rightPad;
        int chartH = MAP_SIZE - topPad - bottomPad;

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int v : hr) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (max - min < 5) max = min + 5; // avoid degenerate range

        // Axis labels (HR range, time range) — very muted, in the dark border color
        // so they read as atmosphere rather than UI chrome.
        g2d.setColor(PANEL_BORDER);
        g2d.setFont(new Font(REG_FAMILY, Font.PLAIN, 12));
        g2d.drawString(max + " bpm", 8, topPad + 4);
        g2d.drawString(min + " bpm", 8, topPad + chartH);

        // HR sparkline at ~35% opacity.
        g2d.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.setColor(new Color(BRAND_PINK.getRed(), BRAND_PINK.getGreen(), BRAND_PINK.getBlue(), 89));

        int n = hr.size();
        int prevX = leftPad;
        int prevY = (int) (topPad + chartH * (1.0 - (hr.get(0) - min) / (double) (max - min)));
        for (int i = 1; i < n; i++) {
            int x = (int) (leftPad + chartW * (i / (double) (n - 1)));
            int y = (int) (topPad + chartH * (1.0 - (hr.get(i) - min) / (double) (max - min)));
            g2d.drawLine(prevX, prevY, x, y);
            prevX = x;
            prevY = y;
        }

        // Tiny "♡ heart rate" legend swatch at bottom-center.
        g2d.setFont(new Font(REG_FAMILY, Font.PLAIN, 11));
        g2d.setColor(PANEL_BORDER);
        String legendLabel = "Heart rate";
        FontMetrics fm = g2d.getFontMetrics();
        int textW = fm.stringWidth(legendLabel);
        int swatchW = 12;
        int gap = 6;
        int totalW = swatchW + gap + textW;
        int legendX = (MAP_SIZE - totalW) / 2;
        int legendY = MAP_SIZE - 30;
        g2d.setColor(new Color(BRAND_PINK.getRed(), BRAND_PINK.getGreen(), BRAND_PINK.getBlue(), 89));
        g2d.fillRect(legendX, legendY - 8, swatchW, 3);
        g2d.setColor(PANEL_BORDER);
        g2d.drawString(legendLabel, legendX + swatchW + gap, legendY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats panel (right side)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Draw the 280px-wide dark stats panel: pink left border, type badge,
     * activity title, four metrics (vertically centered as a tight block),
     * and a footer with brand + Fediverse handle anchored to the bottom.
     *
     * <p>Layout from top to bottom (per SPEC-share-image-redesign.md):
     * <pre>
     *   20px  top padding
     *         [badge pill]
     *    4px  gap
     *         ACTIVITY TITLE (up to 3 lines, 18px)
     *   16px  gap
     *         ┌── metrics group (vertically centered) ──┐
     *         │ 4 metrics, 16px gap between each        │
     *         └─────────────────────────────────────────┘
     *   12px  padding above separator
     *         ──── 1px line in #3d2060 ────
     *   12px  padding below separator
     *         FITPUB              (gradient brand)
     *    2px  gap
     *         @user@host          (muted handle)
     *   20px  bottom padding
     * </pre>
     * The metrics are sized exactly (no flex stretching) and centered in the
     * vertical space between the title and the footer separator. Empty space
     * above and below is OK — the spec explicitly says "do NOT distribute
     * them evenly" in the available area.
     */
    private void drawStatsPanel(Graphics2D g2d, Activity activity, boolean isIndoor) {
        int x = MAP_SIZE;

        // Background — solid panel color, full height.
        g2d.setColor(PANEL_BG);
        g2d.fillRect(x, 0, PANEL_WIDTH, IMAGE_HEIGHT);

        // 2px pink left border (separates panel from map)
        g2d.setColor(BRAND_PINK);
        g2d.fillRect(x, 0, 2, IMAGE_HEIGHT);

        int contentX = x + PANEL_PAD_X;
        int contentW = PANEL_WIDTH - PANEL_PAD_X * 2;

        // ── Top section: badge + title ─────────────────────────────────────
        int cursorY = PANEL_PAD_Y;
        cursorY = drawActivityTypeBadge(g2d, activity, contentX, cursorY, isIndoor);
        cursorY += 4;
        cursorY = drawActivityTitle(g2d, activity, contentX, cursorY, contentW);
        cursorY += 16;
        int titleEndY = cursorY;

        // ── Bottom section: footer (anchored, fixed height) ────────────────
        // Compute the footer's outer rectangle so we know where the metric
        // block's "available space" ends. The footer is rendered last so it
        // sits on top of any overflowing content.
        int footerInnerHeight = drawPanelFooter(g2d, activity, contentX, contentW);
        int footerTopY = IMAGE_HEIGHT - PANEL_PAD_Y - footerInnerHeight;
        // Reserve 12px above the footer's separator for breathing room.
        int metricsBottomLimit = footerTopY - 12;

        // ── Middle section: metrics group, vertically centered ─────────────
        // Compute the exact height of the metrics block, then center it in
        // the (titleEndY .. metricsBottomLimit) range. The metrics do NOT
        // stretch to fill — short content sits in a tight group with extra
        // space above and below.
        List<MetricEntry> metrics = selectMetricsForActivity(activity, isIndoor);
        int metricsBlockHeight = computeMetricsBlockHeight(metrics);
        int availableHeight = metricsBottomLimit - titleEndY;
        int metricsStartY = titleEndY + Math.max(0, (availableHeight - metricsBlockHeight) / 2);
        drawMetrics(g2d, metrics, contentX, metricsStartY);
    }

    /**
     * Draw the translucent-tint activity type badge. Pill shape with a 1px
     * border, subtle background tint matching the activity type, and the
     * type label in the brand color. Returns the Y position immediately
     * below the rendered badge.
     *
     * <p>Alpha values per spec: 12% fill / 25% border for cyan / green;
     * 15% / 30% for pink (which is brighter so it can carry slightly more).
     * Composition is forced to {@code AlphaComposite.SrcOver} so the
     * translucency renders correctly even on a TYPE_INT_RGB target.
     */
    private int drawActivityTypeBadge(Graphics2D g2d, Activity activity, int x, int y, boolean isIndoor) {
        Color tint;
        Color border;
        Color text;
        if (isIndoor) {
            tint   = withAlpha(BRAND_ORANGE, 0.12f);
            border = withAlpha(BRAND_ORANGE, 0.25f);
            text   = BRAND_ORANGE;
        } else {
            switch (activity.getActivityType()) {
                case RIDE:
                    tint   = withAlpha(BRAND_CYAN, 0.12f);
                    border = withAlpha(BRAND_CYAN, 0.25f);
                    text   = BRAND_CYAN;
                    break;
                case HIKE:
                case WALK:
                case MOUNTAINEERING:
                    tint   = withAlpha(BRAND_GREEN, 0.12f);
                    border = withAlpha(BRAND_GREEN, 0.25f);
                    text   = BRAND_GREEN;
                    break;
                case RUN:
                default:
                    tint   = withAlpha(BRAND_PINK, 0.15f);
                    border = withAlpha(BRAND_PINK, 0.30f);
                    text   = BRAND_PINK;
                    break;
            }
        }

        Font badgeFont = new Font(HEAVY_FAMILY, Font.BOLD, 11);
        g2d.setFont(badgeFont);
        FontMetrics fm = g2d.getFontMetrics();
        String label = ActivityFormatter.formatActivityType(activity.getActivityType()).toUpperCase();
        int labelW = fm.stringWidth(label);
        int padX = 10;
        int padY = 5;
        int badgeW = labelW + padX * 2;
        int badgeH = fm.getAscent() + padY * 2;

        // Force standard source-over compositing so translucent fills blend
        // correctly against the dark panel background. (Default on Graphics2D,
        // but set explicitly here so it survives any prior state changes.)
        Composite prevComposite = g2d.getComposite();
        g2d.setComposite(AlphaComposite.SrcOver);

        g2d.setColor(tint);
        g2d.fillRoundRect(x, y, badgeW, badgeH, 10, 10);
        g2d.setColor(border);
        g2d.setStroke(new BasicStroke(1.0f));
        g2d.drawRoundRect(x, y, badgeW, badgeH, 10, 10);

        g2d.setColor(text);
        g2d.drawString(label, x + padX, y + padY + fm.getAscent());

        g2d.setComposite(prevComposite);
        return y + badgeH;
    }

    /**
     * Build a Color with the given fractional alpha (0.0–1.0) on top of the
     * RGB values of {@code base}. Avoids the noisy
     * {@code new Color(c.getRed(), c.getGreen(), c.getBlue(), 31)} pattern
     * and centralises the alpha-conversion math.
     */
    private static Color withAlpha(Color base, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), a);
    }

    /**
     * Draw the activity title in heavy 18px, word-wrapping to a maximum of
     * 3 lines. Only the third line is truncated with an ellipsis if the
     * input is still too long — short titles use a single line and the
     * extra vertical room flows to the metric block. Returns the Y position
     * immediately below the rendered title block.
     */
    private static final int TITLE_MAX_LINES = 3;

    private int drawActivityTitle(Graphics2D g2d, Activity activity, int x, int y, int maxWidth) {
        String title = activity.getTitle();
        if (title == null || title.isBlank()) title = "Activity";

        Font titleFont = new Font(HEAVY_FAMILY, Font.BOLD, 18);
        g2d.setFont(titleFont);
        g2d.setColor(TEXT_PRIMARY);
        FontMetrics fm = g2d.getFontMetrics();
        // Tight line-height (1.15× ascent) so 3 lines don't dominate the panel.
        int lineHeight = (int) Math.round(fm.getAscent() * 1.15);

        // Heavy tier: render uppercase per the design system.
        title = title.toUpperCase();

        List<String> lines = wrapToLines(title, fm, maxWidth, TITLE_MAX_LINES);
        int cursorY = y + fm.getAscent();
        for (String line : lines) {
            g2d.drawString(line, x, cursorY);
            cursorY += lineHeight;
        }
        return y + lines.size() * lineHeight;
    }

    /**
     * Greedy word-wrap to a maximum of {@code maxLines}. The last line is
     * truncated with an ellipsis if the input doesn't fit.
     */
    private static List<String> wrapToLines(String text, FontMetrics fm, int maxWidth, int maxLines) {
        List<String> result = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (fm.stringWidth(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
            } else {
                if (current.length() > 0) {
                    result.add(current.toString());
                    if (result.size() == maxLines) {
                        // Out of lines — truncate the last one with the
                        // remaining input as suffix.
                        StringBuilder remaining = new StringBuilder(word);
                        // (we don't need to walk further; we just need an
                        // ellipsis on the last line)
                        return ellipsizeLast(result, remaining.toString(), fm, maxWidth);
                    }
                    current.setLength(0);
                    current.append(word);
                } else {
                    // Single word longer than maxWidth — add it as-is and
                    // ellipsize on the next iteration.
                    result.add(word);
                    if (result.size() == maxLines) {
                        return ellipsizeLast(result, "", fm, maxWidth);
                    }
                }
            }
        }
        if (current.length() > 0) result.add(current.toString());
        return result;
    }

    private static List<String> ellipsizeLast(List<String> lines, String overflowSuffix, FontMetrics fm, int maxWidth) {
        if (lines.isEmpty()) return lines;
        String last = lines.get(lines.size() - 1);
        String ellipsis = "…";
        // Strip characters from the end of the last line until it + ellipsis fits.
        while (!last.isEmpty() && fm.stringWidth(last + ellipsis) > maxWidth) {
            last = last.substring(0, last.length() - 1);
        }
        lines.set(lines.size() - 1, last + ellipsis);
        return lines;
    }

    /**
     * Choose the four metrics shown in the panel based on activity type.
     * Distance is always first (and is the hero metric — pink). Pace is
     * shown for runs/walks/hikes; rides show average speed instead.
     * Indoor activities replace elevation with average heart rate.
     */
    private List<MetricEntry> selectMetricsForActivity(Activity activity, boolean isIndoor) {
        List<MetricEntry> metrics = new ArrayList<>();

        // 1. Distance — hero
        if (activity.getTotalDistance() != null) {
            double km = activity.getTotalDistance().doubleValue() / 1000.0;
            metrics.add(new MetricEntry(formatTwoDecimals(km), "km", "distance", true));
        } else {
            metrics.add(new MetricEntry("—", "", "distance", true));
        }

        // 2. Moving time / total time
        Long movingTime = activity.getMetrics() != null ? activity.getMetrics().getMovingTimeSeconds() : null;
        Long totalTime  = activity.getTotalDurationSeconds();
        Long timeToShow = movingTime != null && totalTime != null && movingTime < totalTime ? movingTime : totalTime;
        if (timeToShow != null) {
            metrics.add(new MetricEntry(formatDuration(timeToShow), "", "moving time", false));
        }

        // 3. Pace OR avg speed (sport-dependent)
        Activity.ActivityType type = activity.getActivityType();
        boolean isRideLike = type == Activity.ActivityType.RIDE || type == Activity.ActivityType.INLINE_SKATING;
        if (isRideLike) {
            BigDecimalAvgSpeed avg = readAverageSpeed(activity);
            if (avg.kmh > 0) {
                metrics.add(new MetricEntry(String.format("%.1f", avg.kmh), "km/h", "avg speed", false));
            }
        } else {
            // Pace from total distance / total time. Falls back to N/A if either is missing.
            if (activity.getTotalDistance() != null && timeToShow != null) {
                double km = activity.getTotalDistance().doubleValue() / 1000.0;
                if (km > 0) {
                    double paceMin = timeToShow / 60.0 / km;
                    metrics.add(new MetricEntry(formatPace(paceMin), "/km", "pace", false));
                }
            }
        }

        // 4. Elevation OR (for indoor) heart rate
        if (isIndoor) {
            Integer hr = activity.getMetrics() != null ? activity.getMetrics().getAverageHeartRate() : null;
            if (hr != null) {
                metrics.add(new MetricEntry(String.valueOf(hr), "bpm", "avg heart rate", false));
            }
        } else {
            if (activity.getElevationGain() != null) {
                metrics.add(new MetricEntry(String.format("%.0f", activity.getElevationGain().doubleValue()), "m", "elevation", false));
            }
        }

        // Cap at 4 entries
        while (metrics.size() > 4) metrics.remove(metrics.size() - 1);
        return metrics;
    }

    /** Read average speed in km/h from the metrics row. Returns 0 if absent. */
    private BigDecimalAvgSpeed readAverageSpeed(Activity activity) {
        BigDecimalAvgSpeed s = new BigDecimalAvgSpeed();
        if (activity.getMetrics() == null) return s;
        // ActivityMetrics.averageSpeed is already in km/h (parser converts from m/s).
        if (activity.getMetrics().getAverageSpeed() != null) {
            s.kmh = activity.getMetrics().getAverageSpeed().doubleValue();
        }
        return s;
    }

    /** Tiny mutable container so the metric-selection helper stays one-line. */
    private static class BigDecimalAvgSpeed {
        double kmh = 0;
    }

    // ── Metric layout constants ─────────────────────────────────────────
    // The metric block is sized exactly so the caller can vertically center
    // it in the available space. These constants are the source of truth
    // shared by computeMetricsBlockHeight() and drawMetrics().
    private static final int METRIC_VALUE_FONT_SIZE = 28;
    private static final int METRIC_UNIT_FONT_SIZE  = 13;
    private static final int METRIC_LABEL_FONT_SIZE = 11;
    /** Vertical distance from the value baseline to the top of the label text. */
    private static final int METRIC_VALUE_TO_LABEL_GAP = 4;
    /** Gap between consecutive metric entries (per spec). */
    private static final int METRIC_INTER_GAP = 16;

    /**
     * Compute the exact pixel height of the metrics block for {@code n}
     * entries with the constants above. Used by the panel layout to
     * vertically center the block in the available space.
     */
    private int computeMetricsBlockHeight(List<MetricEntry> metrics) {
        if (metrics == null || metrics.isEmpty()) return 0;
        // One entry = ascent of value font (28px ≈ 22 ascent) + label gap +
        // ascent of label font (11px ≈ 9 ascent). Approximate via the font
        // sizes since FontMetrics aren't available without a Graphics context.
        int valueAscent = (int) Math.round(METRIC_VALUE_FONT_SIZE * 0.78);
        int labelAscent = (int) Math.round(METRIC_LABEL_FONT_SIZE * 0.82);
        int entryHeight = valueAscent + METRIC_VALUE_TO_LABEL_GAP + labelAscent;
        return entryHeight * metrics.size() + METRIC_INTER_GAP * (metrics.size() - 1);
    }

    /**
     * Render the metric block as a tight vertical group, with exactly
     * {@link #METRIC_INTER_GAP} pixels between entries. The block does NOT
     * stretch to fill its container — the caller is responsible for choosing
     * the {@code top} Y so the block appears centered (or wherever it should
     * sit) in the available space.
     */
    private void drawMetrics(Graphics2D g2d, List<MetricEntry> metrics, int x, int top) {
        if (metrics.isEmpty()) return;

        Font valueFont = new Font(HEAVY_FAMILY, Font.BOLD, METRIC_VALUE_FONT_SIZE);
        Font unitFont  = new Font(REG_FAMILY,  Font.PLAIN, METRIC_UNIT_FONT_SIZE);
        Font labelFont = new Font(REG_FAMILY,  Font.PLAIN, METRIC_LABEL_FONT_SIZE);

        int cursorY = top;
        for (int i = 0; i < metrics.size(); i++) {
            MetricEntry m = metrics.get(i);

            // Value (heavy 28px) — pink for the hero, white for the rest.
            g2d.setFont(valueFont);
            g2d.setColor(m.hero ? BRAND_PINK : TEXT_PRIMARY);
            FontMetrics vfm = g2d.getFontMetrics();
            int valueY = cursorY + vfm.getAscent();
            g2d.drawString(m.value, x, valueY);

            // Inline unit suffix (regular 13px, muted) — drawn on the same
            // baseline as the value so "3.01 km" reads as a single line.
            if (m.unit != null && !m.unit.isEmpty()) {
                int valueW = vfm.stringWidth(m.value);
                g2d.setFont(unitFont);
                g2d.setColor(TEXT_MUTED);
                g2d.drawString(" " + m.unit, x + valueW, valueY);
            }

            // Label (regular 11px, muted) directly below the value.
            g2d.setFont(labelFont);
            g2d.setColor(TEXT_MUTED);
            FontMetrics lfm = g2d.getFontMetrics();
            int labelY = valueY + METRIC_VALUE_TO_LABEL_GAP + lfm.getAscent();
            g2d.drawString(m.label, x, labelY);

            // Advance cursor to the next entry's top Y. The +4 below the
            // label baseline approximates the descent of the 11px font.
            cursorY = labelY + 4;
            if (i < metrics.size() - 1) {
                cursorY += METRIC_INTER_GAP;
            }
        }
    }

    /**
     * Footer at the bottom of the panel: 1px separator with 12px breathing
     * room above and below, then the FitPub brand in gradient text, then
     * the user's Fediverse handle. The bottom of the handle text sits
     * exactly {@link #PANEL_PAD_Y} pixels above the panel's bottom edge.
     *
     * <p>Returns the total inner height of the footer (separator → bottom of
     * handle text), which the caller uses to determine where the metric
     * block's available space ends.
     */
    private int drawPanelFooter(Graphics2D g2d, Activity activity, int contentX, int contentW) {
        // Build everything from the bottom up so the bottom padding is exact.
        Font brandFont  = new Font(HEAVY_FAMILY, Font.BOLD,  13);
        Font handleFont = new Font(REG_FAMILY,   Font.PLAIN, 11);

        FontMetrics bfm = g2d.getFontMetrics(brandFont);
        FontMetrics hfm = g2d.getFontMetrics(handleFont);

        String handle = buildFediverseHandle(activity);
        boolean hasHandle = handle != null;

        // Vertical layout (anchored to the bottom):
        //   ... separator
        //   12px gap
        //   brand (ascent of brandFont)
        //    2px gap
        //   handle (ascent of handleFont)   ← only if hasHandle
        //   20px bottom padding
        int handleAscent = hasHandle ? hfm.getAscent() : 0;
        int brandToHandleGap = hasHandle ? 2 : 0;
        int brandAscent = bfm.getAscent();
        int separatorToBrandGap = 12;

        // Y of the handle baseline (= bottom of the handle text).
        int handleBaselineY = IMAGE_HEIGHT - PANEL_PAD_Y;
        // Y of the brand baseline.
        int brandBaselineY  = handleBaselineY - handleAscent - brandToHandleGap;
        // Y of the separator line (1px tall).
        int separatorY      = brandBaselineY - brandAscent - separatorToBrandGap;

        // 1px top separator across the panel content width.
        g2d.setColor(PANEL_BORDER);
        g2d.fillRect(contentX, separatorY, contentW, 1);

        // FitPub brand — gradient pink → cyan via GradientPaint, drawn
        // through the existing string renderer (not character-by-character)
        // so the gradient covers the whole word as a single shape.
        g2d.setFont(brandFont);
        String brand = "FITPUB";
        int brandWidth = bfm.stringWidth(brand);
        GradientPaint brandGradient = new GradientPaint(
                contentX,                          brandBaselineY - brandAscent, BRAND_PINK,
                contentX + (float) brandWidth,     brandBaselineY,               BRAND_CYAN
        );
        Paint prevPaint = g2d.getPaint();
        g2d.setPaint(brandGradient);
        g2d.drawString(brand, contentX, brandBaselineY);
        g2d.setPaint(prevPaint);

        // Fediverse handle
        if (hasHandle) {
            g2d.setFont(handleFont);
            g2d.setColor(HANDLE_MUTED);
            g2d.drawString(handle, contentX, handleBaselineY);
        }

        // Inner height = from the separator's top to the handle's baseline.
        return (handleBaselineY - separatorY);
    }

    /**
     * Build a Fediverse-style handle (@username@host) from the activity's
     * owner. Returns null if the user can't be looked up — the footer just
     * omits the handle row in that case.
     */
    private String buildFediverseHandle(Activity activity) {
        try {
            User user = userRepository.findById(activity.getUserId()).orElse(null);
            if (user == null || user.getUsername() == null) return null;
            String host = baseUrlHost();
            if (host == null) return "@" + user.getUsername();
            return "@" + user.getUsername() + "@" + host;
        } catch (Exception e) {
            log.debug("Could not resolve Fediverse handle for activity {}: {}", activity.getId(), e.getMessage());
            return null;
        }
    }

    private String baseUrlHost() {
        try {
            return URI.create(baseUrl).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String formatTwoDecimals(double v) {
        return String.format("%.2f", v);
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    /** Format pace minutes (decimal) as M:SS. */
    private static String formatPace(double minutes) {
        if (!Double.isFinite(minutes) || minutes <= 0) return "—";
        int totalSeconds = (int) Math.round(minutes * 60);
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return String.format("%d:%02d", m, s);
    }

    /** Tiny container for a metric to render. */
    private static class MetricEntry {
        final String value;
        final String unit;
        final String label;
        final boolean hero;
        MetricEntry(String value, String unit, String label, boolean hero) {
            this.value = value;
            this.unit  = unit;
            this.label = label;
            this.hero  = hero;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Existing helpers preserved from the previous implementation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Helper to safely extract Double from Map.
     */
    private Double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    /**
     * Get the file path for an activity image.
     */
    public File getActivityImageFile(UUID activityId) {
        return new File(imagesPath, activityId + ".png");
    }

    /**
     * Parses track points from JSONB string. Only fields needed for the
     * map rendering (lat / lon / elevation) are pulled out.
     */
    private List<Map<String, Object>> parseTrackPoints(String trackPointsJson) {
        if (trackPointsJson == null || trackPointsJson.isEmpty()) {
            return null;
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(trackPointsJson);

            if (root.isArray()) {
                List<Map<String, Object>> trackPoints = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode node : root) {
                    Map<String, Object> point = new java.util.LinkedHashMap<>();

                    if (node.has("latitude"))  point.put("latitude",  node.get("latitude").asDouble());
                    if (node.has("longitude")) point.put("longitude", node.get("longitude").asDouble());
                    if (node.has("elevation")) point.put("elevation", node.get("elevation").asDouble());

                    trackPoints.add(point);
                }
                return trackPoints;
            }
        } catch (Exception e) {
            log.error("Error parsing track points JSON: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Calculate cumulative distances along the track using Haversine formula.
     * Returns an array where each element is the total distance from the start to that point.
     */
    private double[] calculateCumulativeDistances(List<Map<String, Object>> trackPoints) {
        double[] distances = new double[trackPoints.size()];
        if (trackPoints.isEmpty()) return distances;
        distances[0] = 0.0;

        for (int i = 1; i < trackPoints.size(); i++) {
            Map<String, Object> point1 = trackPoints.get(i - 1);
            Map<String, Object> point2 = trackPoints.get(i);

            Double lat1 = getDouble(point1, "latitude");
            Double lon1 = getDouble(point1, "longitude");
            Double lat2 = getDouble(point2, "latitude");
            Double lon2 = getDouble(point2, "longitude");

            if (lat1 != null && lon1 != null && lat2 != null && lon2 != null) {
                double segmentDistance = haversineDistance(lat1, lon1, lat2, lon2);
                distances[i] = distances[i - 1] + segmentDistance;
            } else {
                distances[i] = distances[i - 1];
            }
        }

        return distances;
    }

    /** Distance between two GPS coordinates using Haversine. Meters. */
    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final double EARTH_RADIUS = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS * c;
    }

    /**
     * Calculate the geographic bounding box of the track points, with 15%
     * padding on every side per the spec. The padding ensures the route
     * doesn't touch the edges of the square map area.
     */
    private TrackBounds calculateTrackBounds(Activity activity) {
        List<Map<String, Object>> trackPoints = parseTrackPoints(activity.getTrackPointsJson());
        if (trackPoints == null || trackPoints.isEmpty()) {
            return null;
        }

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;

        for (Map<String, Object> point : trackPoints) {
            Double lat = getDouble(point, "latitude");
            Double lon = getDouble(point, "longitude");
            if (lat != null && lon != null) {
                minLat = Math.min(minLat, lat);
                maxLat = Math.max(maxLat, lat);
                minLon = Math.min(minLon, lon);
                maxLon = Math.max(maxLon, lon);
            }
        }

        if (minLat == Double.MAX_VALUE) return null;

        // 15% padding on every side per spec — was 10% in the previous design.
        double latRange = maxLat - minLat;
        double lonRange = maxLon - minLon;
        double padding = 0.15;
        minLat -= latRange * padding;
        maxLat += latRange * padding;
        minLon -= lonRange * padding;
        maxLon += lonRange * padding;

        return new TrackBounds(minLat, maxLat, minLon, maxLon);
    }

    /** Longitude → Web Mercator X (normalized 0–1). Matches OsmTileRenderer. */
    private double longitudeToWebMercatorX(double lon) {
        return (lon + 180.0) / 360.0;
    }

    /** Latitude → Web Mercator Y (normalized 0–1). Matches OsmTileRenderer. */
    private double latitudeToWebMercatorY(double lat) {
        return (1.0 - Math.log(Math.tan(Math.toRadians(lat)) +
                1.0 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2.0;
    }

    /**
     * Apply privacy zone filtering to an activity's GPS data. The filtered
     * copy is used for rendering; the original is used for metrics like total
     * distance and elevation gain (since the unfiltered values are the
     * "official" numbers regardless of how the route is displayed).
     */
    private Activity applyPrivacyFiltering(Activity activity) {
        List<PrivacyZone> privacyZones = privacyZoneService.getActivePrivacyZones(activity.getUserId());
        if (privacyZones == null || privacyZones.isEmpty()) {
            return activity;
        }

        Activity filtered = new Activity();
        filtered.setId(activity.getId());
        filtered.setUserId(activity.getUserId());
        filtered.setActivityType(activity.getActivityType());
        filtered.setTitle(activity.getTitle());
        filtered.setDescription(activity.getDescription());
        filtered.setStartedAt(activity.getStartedAt());
        filtered.setEndedAt(activity.getEndedAt());
        filtered.setTimezone(activity.getTimezone());
        filtered.setVisibility(activity.getVisibility());
        filtered.setTotalDistance(activity.getTotalDistance());
        filtered.setTotalDurationSeconds(activity.getTotalDurationSeconds());
        filtered.setElevationGain(activity.getElevationGain());
        filtered.setElevationLoss(activity.getElevationLoss());
        filtered.setMetrics(activity.getMetrics());
        filtered.setActivityLocation(activity.getActivityLocation());
        filtered.setCreatedAt(activity.getCreatedAt());
        filtered.setUpdatedAt(activity.getUpdatedAt());

        if (activity.getSimplifiedTrack() != null) {
            LineString filteredTrack = trackPrivacyFilter.filterLineString(
                activity.getSimplifiedTrack(),
                privacyZones
            );
            filtered.setSimplifiedTrack(filteredTrack);
        }

        if (activity.getTrackPointsJson() != null && !activity.getTrackPointsJson().isEmpty()) {
            String filteredJson = trackPrivacyFilter.filterTrackPointsJson(
                activity.getTrackPointsJson(),
                privacyZones
            );
            filtered.setTrackPointsJson(filteredJson);
        }

        log.debug("Applied privacy filtering to activity {} for image generation", activity.getId());
        return filtered;
    }

    /**
     * Helper class to store track geographic bounds.
     */
    private static class TrackBounds {
        final double minLat;
        final double maxLat;
        final double minLon;
        final double maxLon;

        TrackBounds(double minLat, double maxLat, double minLon, double maxLon) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
        }
    }
}
