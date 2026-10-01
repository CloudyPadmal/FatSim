package org.contikios.inbody;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import org.contikios.cooja.Mote;
import org.contikios.cooja.interfaces.Position;
import org.contikios.cooja.plugins.Visualizer;

/**
 * Paints a radial signal-gradient ellipse for a single mote onto canvas.
 *
 * The gradient is not drawn when the mote is inside muscle or the restricted boundary zone.
 * The gradient is clipped to exclude skin, muscle, and the restricted zone
 * 
 * Fat region: green at the mote fading to warm orange at the gradient edge
 * Air region: steel-blue at the mote fading to transparent at the gradient edge
 */
class SignalGradientPainter {
    
    static final Color COLOR_SIGNAL_FAT = new Color(0, 230, 0, 200);
    static final Color COLOR_FAT_EDGE = new Color(245, 166, 35, 220);
    static final Color COLOR_SIGNAL_AIR = new Color(65, 121, 227, 180);
    static final Color COLOR_AIR_EDGE = new Color(255, 255, 255, 0);

    /**
     * Paints the radial signal gradient for {@code mote} on {@code g}.
     *
     * @param g the graphics context (already cast to Graphics2D)
     * @param mote the mote to draw a gradient for
     * @param visualizer COOJA visualiser (provides coordinate transforms)
     * @param radius signal radius in simulation-coordinate units (mm)
     * @param skinArea pixel-space area of the skin layer
     * @param fatArea pixel-space area of the fat layer
     * @param muscleArea pixel-space area of the muscle layer
     * @param restrictedArea pixel-space area of the restricted zones
     * @param airArea pixel-space area of the air zone above the phantom
     */
    static void paint(Graphics2D g, Mote mote, Visualizer visualizer, double radius,
                      Area skinArea, Area fatArea, Area muscleArea,
                      Area restrictedArea, Area airArea) {
        Position pos = mote.getInterfaces().getPosition();
        if (pos == null) return;

        Point motePx = visualizer.transformPositionToPixel(pos);
        
        if (muscleArea.contains(motePx) || restrictedArea.contains(motePx)) return;

        int x = motePx.x;
        int y = motePx.y;

        Point zeroPx = visualizer.transformPositionToPixel(0.0, 0.0, 0.0);
        Point radiusPx = visualizer.transformPositionToPixel(radius, radius, 0.0);
        int rx = Math.abs(radiusPx.x - zeroPx.x);
        int ry = Math.abs(radiusPx.y - zeroPx.y);
        if (rx == 0 || ry == 0) return;
        
        Area signalArea = new Area(new Ellipse2D.Double(x - rx, y - ry, 2 * rx, 2 * ry));
        signalArea.subtract(skinArea);
        signalArea.subtract(muscleArea);
        signalArea.subtract(restrictedArea);
        
        Area airSignal = new Area(signalArea);
        signalArea.subtract(airArea);
        airSignal.subtract(signalArea);

        // Fat gradient
        if (!signalArea.isEmpty()) {
            g.setPaint(new RadialGradientPaint(
                    x, y, rx,
                    new float[]{0.0f, 1.0f},
                    new Color[]{COLOR_SIGNAL_FAT, COLOR_FAT_EDGE}));
            g.fill(signalArea);
        }

        // Air gradient
        if (!airSignal.isEmpty()) {
            g.setPaint(new RadialGradientPaint(
                    x, y, rx,
                    new float[]{0.0f, 1.0f},
                    new Color[]{COLOR_SIGNAL_AIR, COLOR_AIR_EDGE}));
            g.fill(airSignal);
        }

        // Tissue boundary outlines for spatial reference
        g.setColor(new Color(80, 80, 80, 160));
        g.draw(skinArea);
        g.draw(fatArea);
        g.draw(muscleArea);
    }

    private SignalGradientPainter() { }
}
