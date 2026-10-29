package dev.leverage.ui;
import static dev.leverage.i18n.I18n.t;
import static dev.leverage.ui.SkinAnimationRig.*;

import dev.leverage.skin.DefaultSkin;

import javax.swing.JPanel;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Six textured cuboids with orthographic 3D projection; no browser or extra graphics
// runtime.
public final class PlayerSkinView extends JPanel {
    private static final double CAMERA_YAW = Math.toRadians(18),
            CAMERA_PITCH = Math.toRadians(7);
    private SkinIdleMotion.Pose idlePose = SkinIdleMotion.Pose.STILL;
    private final double[][] jointTransforms = new double[JOINTS][12];
    private final double[] localTransform = new double[12];
    private final double[] pivotX = new double[JOINTS];
    private static final double[] PIVOT_Y = { 24, 12, 24, 24, 12, 12, 16 };
    private static final int[] PARENTS = { BODY, ROOT, BODY, BODY, ROOT, ROOT, -1 };
    private static final int[] TRANSFORM_ORDER = { ROOT, BODY, HEAD,
            RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG };
    private boolean galleryPainted;
    private List<Face> faces;

    public PlayerSkinView() {
        setOpaque(false);
        setSkin(DefaultSkin.image(), false);
        getAccessibleContext().setAccessibleName(t("skins.previewAccessible"));
    }

    // Poses come exclusively from the data-authored animation player and the shared
    // gallery clock.
    void setIdlePose(SkinIdleMotion.Pose pose) {
        idlePose = java.util.Objects.requireNonNull(pose);
        if (!galleryPainted) repaint();
    }

    // The gallery paints the model between all card backgrounds and their controls.
    void setGalleryPainted(boolean value) {
        galleryPainted = value;
        repaint();
    }
    void paintOverflow(Graphics graphics) { paintScene(graphics); }

    private double modelScale() {
        return Math.min(getHeight() * .74 / 34, Math.max(1, getWidth() - 36) / 20d);
    }

    public void setSkin(BufferedImage skin, boolean slim) {
        if (skin == null || skin.getWidth() != 64 || (skin.getHeight() != 64
                && skin.getHeight() != 32)) {
            throw new IllegalArgumentException(t("skins.dimensions"));
        }
        boolean legacy = skin.getHeight() == 32;
        int arm = slim && !legacy ? 3 : 4;
        pivotX[RIGHT_ARM] = -4 - arm / 2d;
        pivotX[LEFT_ARM] = 4 + arm / 2d;
        pivotX[RIGHT_LEG] = -2;
        pivotX[LEFT_LEG] = 2;
        List<Face> model = new ArrayList<>();
        cube(model, skin, 0, 28, 0, 8, 8, 8, 0, 0, false, 0, 0, Part.HEAD);
        cube(model, skin, 0, 28, 0, 8, 8, 8, 32, 0, true, 0, 0, Part.HEAD);
        cube(model, skin, 0, 18, 0, 8, 12, 4, 16, 16, false, 0, 0, Part.BODY);
        cube(model, skin, -4 - arm / 2d, 18, 0, arm, 12, 4,
                40, 16, false, -5, 0, Part.ARM);
        cube(model, skin, 4 + arm / 2d, 18, 0, arm, 12, 4, legacy ? 40 : 32,
                legacy ? 16 : 48, false, 5, legacy ? 1 : 0, Part.ARM);
        cube(model, skin, -2, 6, 0, 4, 12, 4, 0, 16, false, -1, 0, Part.LEG);
        cube(model, skin, 2, 6, 0, 4, 12, 4, legacy ? 0 : 16,
                legacy ? 16 : 48, false, 1, legacy ? 1 : 0, Part.LEG);
        if (!legacy) {
            cube(model, skin, 0, 18, 0, 8, 12, 4, 16, 32, true, 0, 0, Part.BODY);
            cube(model, skin, -4 - arm / 2d, 18, 0, arm, 12, 4,
                    40, 32, true, -5, 0, Part.ARM);
            cube(model, skin, 4 + arm / 2d, 18, 0, arm, 12, 4,
                    48, 48, true, 5, 0, Part.ARM);
            cube(model, skin, -2, 6, 0, 4, 12, 4, 0, 32, true, -1, 0, Part.LEG);
            cube(model, skin, 2, 6, 0, 4, 12, 4, 0, 48, true, 1, 0, Part.LEG);
        }
        faces = List.copyOf(model);
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (galleryPainted) return;
        super.paintComponent(graphics);
        paintScene(graphics);
    }

    private void paintScene(Graphics graphics) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            double scale = modelScale();
            double cx = getWidth() / 2d;
            double cy = getHeight() / 2d - 5;
            g.setColor(new java.awt.Color(0, 0, 0, 120));
            g.fillOval((int)(cx - 7 * scale), (int)(cy + 16 * scale - scale / 2),
                    (int)(14 * scale), (int)(1.6 * scale));
            List<Projected> projected = new ArrayList<>();
            prepareJointTransforms();
            for (Face face : faces) {
                Vec[] points = new Vec[4];
                for (int point = 0; point < 4; point++) {
                    points[point] = camera(pose(face.points()[point], face.joint()));
                }
                Vec a = points[0], b = points[1], c = points[2];
                if ((c.x - a.x) * (b.y - a.y) - (c.y - a.y) * (b.x - a.x) <= 0) continue;
                double z = (a.z + b.z + c.z + points[3].z) / 4;
                projected.add(new Projected(face.image(), points, z));
            }
            projected.sort(Comparator.comparingDouble(Projected::z));
            for (Projected face : projected) {
                Vec a = face.points()[0], b = face.points()[1], d = face.points()[3];
                AffineTransform transform = new AffineTransform((b.x
                        - a.x) * scale / face.image().getWidth(),
                        - (b.y - a.y) * scale / face.image().getWidth(), (d.x
                        - a.x) * scale / face.image().getHeight(), - (d.y - a.y) * scale
                        / face.image().getHeight(), cx + a.x * scale, cy - a.y * scale);
                g.drawImage(face.image(), transform, null);
            }
        } finally { g.dispose(); }
    }

    private Vec camera(Vec p) {
        double ry = CAMERA_YAW, rx = CAMERA_PITCH;
        double x = p.x * Math.cos(ry) + p.z * Math.sin(ry);
        double z = -p.x * Math.sin(ry) + p.z * Math.cos(ry);
        double y = p.y - 16;
        return new Vec(x, y * Math.cos(rx) - z * Math.sin(rx),
                y * Math.sin(rx) + z * Math.cos(rx));
    }

    // Compile local X/Y/Z rotations and translations once per joint, not for every
    // textured vertex.
    private void prepareJointTransforms() {
        double[] values = idlePose.values();
        for (int index : TRANSFORM_ORDER) {
            int offset = index * STRIDE;
            double pitch = values[offset + PITCH], yaw = values[offset + YAW],
                    roll = values[offset + ROLL];
            double rx = Math.toRadians(pitch), ry = Math.toRadians(yaw),
                    rz = Math.toRadians(roll);
            double sx = Math.sin(rx), cx = Math.cos(rx), sy = Math.sin(ry),
                    cy = Math.cos(ry), sz = Math.sin(rz), cz = Math.cos(rz);
            double[] m = localTransform;
            // Rz * Ry * Rx preserves the original arm, leg and neck rotation convention.
            m[0] = cz * cy;
            m[1] = cz * sy * sx - sz * cx;
            m[2] = cz * sy * cx + sz * sx;
            m[4] = sz * cy;
            m[5] = sz * sy * sx + cz * cx;
            m[6] = sz * sy * cx - cz * sx;
            m[8] = -sy;
            m[9] = cy * sx;
            m[10] = cy * cx;
            double x = pivotX[index], y = PIVOT_Y[index];
            m[3] = x + values[offset + X] - m[0] * x - m[1] * y;
            m[7] = y + values[offset + Y] - m[4] * x - m[5] * y;
            m[11] = values[offset + Z] - m[8] * x - m[9] * y;
            double[] target = jointTransforms[index];
            int parent = PARENTS[index];
            if (parent < 0) System.arraycopy(m, 0, target, 0, 12);
            else {
                double[] p = jointTransforms[parent];
                for (int row = 0; row < 3; row++) {
                    int base = row * 4;
                    for (int col = 0; col < 3; col++) target[base + col] = p[base] *
                            m[col] + p[base + 1] * m[4 + col] + p[base + 2] * m[8 + col];
                    target[base + 3] = p[base] * m[3] + p[base + 1] * m[7]
                            + p[base + 2] * m[11] + p[base + 3];
                }
            }
        }
    }

    private Vec pose(Vec p, Joint joint) {
        double[] m = jointTransforms[joint.index()];
        return new Vec(m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3], m[4] * p.x + m[5] *
                p.y + m[6] * p.z + m[7], m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11]);
    }

    private static void cube(List<Face> faces, BufferedImage skin, double cx,
            double cy, double cz, int w, int h, int d, int u, int v,
            boolean overlay, double tilt, int mirror, Part part) {
        Joint joint = new Joint(switch (part) {
            case HEAD -> HEAD;
            case BODY -> BODY;
            case ARM -> cx < 0 ? RIGHT_ARM : LEFT_ARM;
            case LEG -> cx < 0 ? RIGHT_LEG : LEFT_LEG;
        });
        double grow = overlay ? .25 : 0;
        double x0 = cx - w / 2d - grow, x1 = cx + w / 2d + grow;
        double y0 = cy - h / 2d - grow, y1 = cy + h / 2d + grow;
        double z0 = cz - d / 2d - grow, z1 = cz + d / 2d + grow;
        Vec[][] sides = {
                { new Vec(x0, y1, z1), new Vec(x1, y1, z1), new Vec(x1, y0, z1),
                    new Vec(x0, y0, z1) }, { new Vec(x1, y1, z0), new Vec(x0, y1, z0),
                    new Vec(x0, y0, z0), new Vec(x1, y0, z0) }, { new Vec(x0, y1, z0),
                    new Vec(x0, y1, z1), new Vec(x0, y0, z1), new Vec(x0, y0, z0) },
                    { new Vec(x1, y1, z1), new Vec(x1, y1, z0), new Vec(x1, y0, z0),
                    new Vec(x1, y0, z1) }, { new Vec(x0, y1, z0), new Vec(x1, y1, z0),
                    new Vec(x1, y1, z1), new Vec(x0, y1, z1) }, { new Vec(x0, y0, z1),
                    new Vec(x1, y0, z1), new Vec(x1, y0, z0), new Vec(x0, y0, z0) }
        };
        int[][] uv = { { u + d, v + d, w, h }, { u + 2 * d + w, v + d,
                w, h }, { u, v + d, d, h }, { u + d + w, v + d, d, h },
                { u + d, v, w, d }, { u + d + w, v, w, d } };
        double[] light = { 1, .72, .8, .9, 1.06, .6 };
        for (int side = 0; side < 6; side++) {
            int source = mirror == 1 && side == 2 ? 3 : mirror == 1
                    && side == 3 ? 2 : side;
            int[] rect = uv[source];
            BufferedImage image = new BufferedImage(rect[2],
                    rect[3], BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < rect[3]; y++) for (int x = 0; x < rect[2]; x++) {
                int pixel = skin.getRGB(rect[0] + (mirror == 1
                        ? rect[2] - x - 1 : x), rect[1] + y);
                int alpha = overlay ? pixel >>> 24 : 255;
                int r = Math.min(255, (int)(((pixel >>> 16) & 255) * light[side]));
                int g = Math.min(255, (int)(((pixel >>> 8) & 255) * light[side]));
                int b = Math.min(255, (int)((pixel & 255) * light[side]));
                image.setRGB(x, y, alpha << 24 | r << 16 | g << 8 | b);
            }
            Vec[] points = sides[side];
            double angle = Math.toRadians(tilt);
            double pivot = cy + h / 2d;
            for (int p = 0; p < 4; p++) {
                Vec a = points[p];
                double dx = a.x - cx, dy = a.y - pivot;
                points[p] = new Vec(cx + dx * Math.cos(angle) - dy * Math.sin(angle),
                        pivot + dx * Math.sin(angle) + dy * Math.cos(angle), a.z);
            }
            faces.add(new Face(image, points, joint));
        }
    }

    private record Vec(double x, double y, double z) { }
    private enum Part { HEAD, BODY, ARM, LEG }
    private record Joint(int index) { }
    private record Face(BufferedImage image, Vec[] points, Joint joint) { }
    private record Projected(BufferedImage image, Vec[] points, double z) { }
}
