package org.contikios.inbody;

import java.awt.*;
import java.awt.geom.Area;
import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.Mote;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.SupportedArguments;
import org.contikios.cooja.plugins.Visualizer;
import org.contikios.cooja.plugins.VisualizerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visualiser skin that draws the layered tissue phantom (skin / fat / muscle)
 * and the restricted zone around it
 */
@ClassDescription("Phantom Setup")
@SupportedArguments(radioMediums = {InBody.class})
public class PhantomVisualizerSkin implements VisualizerSkin {
    private static final Logger logger = LoggerFactory.getLogger(PhantomVisualizerSkin.class);

    static final Color DEFAULT_COLOR_SKIN   = new Color(250, 219,  77, 128);
    static final Color DEFAULT_COLOR_FAT    = new Color(245, 166,  35, 255);
    static final Color DEFAULT_COLOR_MUSCLE = new Color(208,   2,  27, 128);

    static Color COLOR_SKIN   = DEFAULT_COLOR_SKIN;
    static Color COLOR_FAT    = DEFAULT_COLOR_FAT;
    static Color COLOR_MUSCLE = DEFAULT_COLOR_MUSCLE;

    private static final Color COLOR_BOUNDARY   = new Color(  0,   0,   0, 128);
    private static final Color COLOR_RESTRICTED = new Color(192, 192, 192,  64);

    private static final double PHANTOM_BOUND_WIDTH = 2;

    private InBodyChannelModel channelModel;
    private Simulation simulation;
    private Visualizer visualizer;

    @Override
    public void setActive(Simulation simulation, Visualizer visualizer) {
        if (!(simulation.getRadioMedium() instanceof InBody)) {
            logger.error("Cannot activate Phantom skin for unknown radio medium: {}",
                    simulation.getRadioMedium());
            return;
        }
        this.simulation  = simulation;
        this.visualizer  = visualizer;
        this.channelModel = ((InBody) simulation.getRadioMedium()).getChannelModel();
    }

    @Override
    public void setInactive() {
        if (simulation == null) return;
        Visualizer.unregisterVisualizerSkin(PhantomVisualizerSkin.class);
    }

    @Override
    public Color[] getColorOf(Mote mote) {
        return null;
    }

    @Override
    public void paintBeforeMotes(Graphics g) {
        if (simulation == null) {
            Visualizer.unregisterVisualizerSkin(PhantomVisualizerSkin.class);
            return;
        }
        
        double phantomLength   = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.phantom_length);
        double skinThickness   = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.skin_thickness);
        double fatThickness    = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.fat_thickness);
        double muscleThickness = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.muscle_thickness);

        Area skinRegion   = new Area();
        Area fatRegion    = new Area();
        Area muscleRegion = new Area();
        Area leftBoundRegion  = new Area();
        Area rightBoundRegion = new Area();

        Point gridCenter = visualizer.transformPositionToPixel(0, 0, 0);

        Point phantomLengthEdge  = visualizer.transformPositionToPixel(phantomLength, 0, 0);
        int   phantomWidth       = phantomLengthEdge.x - gridCenter.x;

        Point phantomSkinEdge    = visualizer.transformPositionToPixel(0, skinThickness, 0);
        int   phantomSkinHeight  = phantomSkinEdge.y - gridCenter.y;

        Point phantomFatEdge     = visualizer.transformPositionToPixel(0, fatThickness + skinThickness, 0);
        int   phantomFatHeight   = visualizer.transformPositionToPixel(0, fatThickness, 0).y - gridCenter.y;

        Point phantomMuscleEdge  = visualizer.transformPositionToPixel(
                0, muscleThickness + fatThickness + skinThickness, 0);
        int   phantomMuscleHeight = visualizer.transformPositionToPixel(0, muscleThickness, 0).y - gridCenter.y;

        Point phantomBoundEdge   = visualizer.transformPositionToPixel(PHANTOM_BOUND_WIDTH, 0, 0);
        int   phantomBoundWidth  = phantomBoundEdge.x - gridCenter.x;
        int   phantomBoundHeight = phantomMuscleEdge.y - gridCenter.y;

        Point restrictedBottomEdge = visualizer.transformPositionToPixel(
                0, skinThickness + fatThickness + muscleThickness, 0);

        // ---- Tissue layers ----
        skinRegion.add(new Area(new Rectangle(
                gridCenter.x, gridCenter.y, phantomWidth, phantomSkinHeight)));
        g.setColor(COLOR_SKIN);
        ((Graphics2D) g).fill(skinRegion);
        g.setColor(Color.GRAY);
        ((Graphics2D) g).draw(skinRegion);

        fatRegion.add(new Area(new Rectangle(
                gridCenter.x, phantomSkinEdge.y, phantomWidth, phantomFatHeight)));
        g.setColor(COLOR_FAT);
        ((Graphics2D) g).fill(fatRegion);
        g.setColor(Color.GRAY);
        ((Graphics2D) g).draw(fatRegion);

        muscleRegion.add(new Area(new Rectangle(
                gridCenter.x, phantomFatEdge.y, phantomWidth, phantomMuscleHeight)));
        g.setColor(COLOR_MUSCLE);
        ((Graphics2D) g).fill(muscleRegion);
        g.setColor(Color.GRAY);
        ((Graphics2D) g).draw(muscleRegion);
        
        Area restrictedRegion = new Area();
        int canvasW  = visualizer.getCurrentCanvas().getWidth();
        int canvasH  = visualizer.getCurrentCanvas().getHeight();
        int cornerX_L = gridCenter.x;           // x-pixel of phantom left edge
        int cornerX_R = phantomLengthEdge.x;    // x-pixel of phantom right edge
        int cornerY   = gridCenter.y;            // y-pixel of skin top surface
        int belowTopY = restrictedBottomEdge.y;  // y-pixel of muscle bottom

        if (belowTopY < canvasH) {
            restrictedRegion.add(new Area(new Rectangle(
                    0, belowTopY, canvasW, canvasH - belowTopY)));
        }
        if (cornerX_L > 0) {
            Area leftRect = new Area(new Rectangle(0, 0, cornerX_L, canvasH));
            leftRect.subtract(new Area(slopeWedgeLeft(cornerX_L, cornerY)));
            restrictedRegion.add(leftRect);
        }
        if (cornerX_R < canvasW) {
            Area rightRect = new Area(new Rectangle(cornerX_R, 0, canvasW - cornerX_R, canvasH));
            rightRect.subtract(new Area(slopeWedgeRight(cornerX_R, cornerY, canvasW)));
            restrictedRegion.add(rightRect);
        }

        g.setColor(COLOR_RESTRICTED);
        ((Graphics2D) g).fill(restrictedRegion);

        // ---- Solid blocking bounds on either side ----
        g.setColor(COLOR_BOUNDARY);

        leftBoundRegion.add(new Area(new Rectangle(
                gridCenter.x - phantomBoundWidth, gridCenter.y,
                phantomBoundWidth, phantomBoundHeight)));
        ((Graphics2D) g).fill(leftBoundRegion);
        ((Graphics2D) g).draw(leftBoundRegion);

        rightBoundRegion.add(new Area(new Rectangle(
                phantomLengthEdge.x, gridCenter.y,
                phantomBoundWidth, phantomBoundHeight)));
        ((Graphics2D) g).fill(rightBoundRegion);
        ((Graphics2D) g).draw(rightBoundRegion);
    }

    @Override
    public void paintAfterMotes(Graphics g) {
    }

    @Override
    public Visualizer getVisualizer() {
        return visualizer;
    }
    
    private static Polygon slopeWedgeLeft(int cornerX, int cornerY) {
        Polygon p = new Polygon();
        if (cornerY <= cornerX) {
            p.addPoint(cornerX - cornerY, 0);
            p.addPoint(cornerX, 0);
            p.addPoint(cornerX, cornerY);
        } else {
            p.addPoint(0, 0);
            p.addPoint(cornerX, 0);
            p.addPoint(cornerX, cornerY);
            p.addPoint(0, cornerY - cornerX);
        }
        return p;
    }

    private static Polygon slopeWedgeRight(int cornerX, int cornerY, int canvasW) {
        int distRight = canvasW - cornerX;
        Polygon p = new Polygon();
        if (cornerY <= distRight) {
            p.addPoint(cornerX, 0);
            p.addPoint(cornerX + cornerY, 0);
            p.addPoint(cornerX, cornerY);
        } else {
            p.addPoint(cornerX, 0);
            p.addPoint(canvasW, 0);
            p.addPoint(canvasW, cornerY - distRight);
            p.addPoint(cornerX, cornerY);
        }
        return p;
    }
}
