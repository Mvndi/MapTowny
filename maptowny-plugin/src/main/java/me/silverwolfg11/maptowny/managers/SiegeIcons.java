package me.silverwolfg11.maptowny.managers;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

final class SiegeIcons {
    private static final int SIZE = 32;
    private static final Color OUTLINE = new Color(45, 28, 22);
    private static final Color FLAME = new Color(246, 95, 32);
    private static final Color FLAME_CORE = new Color(255, 218, 76);
    private static final Color BLADE = new Color(231, 239, 242);
    private static final Color HILT = new Color(224, 171, 54);

    private SiegeIcons() {
    }

    static BufferedImage createFire() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = createGraphics(image);
        Path2D flame = new Path2D.Double();
        flame.moveTo(17, 2);
        flame.curveTo(20, 12, 29, 13, 27, 23);
        flame.curveTo(25, 33, 5, 33, 5, 21);
        flame.curveTo(5, 16, 10, 14, 10, 10);
        flame.lineTo(13, 15);
        flame.curveTo(17, 11, 13, 8, 17, 2);
        paintFlame(graphics, flame);
        graphics.dispose();
        return image;
    }

    private static void paintFlame(Graphics2D graphics, Path2D flame) {
        graphics.setColor(FLAME);
        graphics.fill(flame);
        graphics.setColor(OUTLINE);
        graphics.setStroke(new BasicStroke(2));
        graphics.draw(flame);
        graphics.setColor(FLAME_CORE);
        graphics.fillOval(12, 18, 10, 11);
    }

    static BufferedImage createSwords() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = createGraphics(image);
        drawSword(graphics, Math.PI / 4);
        drawSword(graphics, -Math.PI / 4);
        graphics.dispose();
        return image;
    }

    private static Graphics2D createGraphics(BufferedImage image) {
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return graphics;
    }

    private static void drawSword(Graphics2D original, double angle) {
        Graphics2D graphics = (Graphics2D) original.create();
        graphics.rotate(angle, SIZE / 2.0, SIZE / 2.0);
        Path2D blade = new Path2D.Double();
        blade.moveTo(16, 1);
        blade.lineTo(19, 6);
        blade.lineTo(19, 21);
        blade.lineTo(13, 21);
        blade.lineTo(13, 6);
        blade.closePath();
        paintSword(graphics, blade);
        graphics.dispose();
    }

    private static void paintSword(Graphics2D graphics, Path2D blade) {
        graphics.setColor(BLADE);
        graphics.fill(blade);
        graphics.setColor(OUTLINE);
        graphics.setStroke(new BasicStroke(1.5f));
        graphics.draw(blade);
        graphics.setColor(HILT);
        graphics.fillRect(10, 21, 12, 3);
        graphics.fillRect(14, 24, 4, 6);
        graphics.setColor(OUTLINE);
        graphics.drawRect(10, 21, 12, 3);
        graphics.drawRect(14, 24, 4, 6);
    }
}
