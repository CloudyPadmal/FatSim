package org.contikios.inbody;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyVetoException;
import java.util.Set;
import javax.swing.*;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;
import javax.swing.table.AbstractTableModel;
import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.Mote;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.SupportedArguments;
import org.contikios.cooja.interfaces.Radio;
import org.contikios.cooja.plugins.Visualizer;
import org.contikios.cooja.plugins.VisualizerSkin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visualiser skin for the Fat-IBC radio medium
 */
@ClassDescription("In-body signals")
@SupportedArguments(radioMediums = {InBody.class})
public class InBodyVisualizerSkin implements VisualizerSkin {
    private static final Logger logger = LoggerFactory.getLogger(InBodyVisualizerSkin.class);

    private static double RADIUS = 250.0;
    private static boolean SHOW_PROBABILITIES = false;

    private Simulation simulation;
    private Visualizer visualizer;
    private InBody inBody;
    private InBodyChannelModel channelModel;

    private JInternalFrame     channelModelDialog;
    private AbstractTableModel motesTableModel;
    private SpinnerNumberModel phantomLengthModel;
    private SpinnerNumberModel skinThicknessModel;
    private SpinnerNumberModel fatThicknessModel;
    private SpinnerNumberModel muscleThicknessModel;
    private JButton            skinColorButton;
    private JButton            fatColorButton;
    private JButton            muscleColorButton;
    private SpinnerNumberModel reflModel;
    private JCheckBox          fsplCheckBox;
    private SpinnerNumberModel skinAttModel;
    private SpinnerNumberModel fatAttModel;
    private SpinnerNumberModel muscleAttModel;
    private SpinnerNumberModel refracAirSkinModel;
    private SpinnerNumberModel refracSkinFatModel;
    private SpinnerNumberModel refracFatMuscleModel;
    private SpinnerNumberModel reflecAirSkinModel;
    private SpinnerNumberModel reflecSkinFatModel;
    private SpinnerNumberModel reflecFatMuscleModel;

    @Override
    public void setActive(Simulation simulation, Visualizer visualizer) {
        if (!(simulation.getRadioMedium() instanceof InBody)) {
            logger.error("Cannot activate In-body skin for unknown radio medium: {}",
                    simulation.getRadioMedium());
            return;
        }
        this.simulation = simulation;
        this.visualizer = visualizer;
        this.inBody = (InBody) simulation.getRadioMedium();
        this.channelModel = inBody.getChannelModel();

        channelModelDialog = buildChannelModelDialog();
        simulation.getMoteTriggers().addTrigger(this, (event, mote) -> {
            if (motesTableModel != null) motesTableModel.fireTableDataChanged();
        });
        inBody.getRadioMediumTriggers().addTrigger(this, (event, arg) -> {
            if (motesTableModel != null) motesTableModel.fireTableDataChanged();
        });
        visualizer.registerSimulationMenuAction(ChannelModelMenuAction.class);
    }

    @Override
    public void setInactive() {
        if (simulation == null) return;
        simulation.getMoteTriggers().deleteTriggers(this);
        inBody.getRadioMediumTriggers().deleteTriggers(this);
        visualizer.getCurrentCanvas().remove(channelModelDialog);
        visualizer.unregisterSimulationMenuAction(ChannelModelMenuAction.class);
        Visualizer.unregisterVisualizerSkin(InBodyVisualizerSkin.class);
    }

    @Override
    public Color[] getColorOf(Mote mote) {
        return visualizer.getSelectedMotes().contains(mote)
                ? new Color[]{Color.CYAN} : null;
    }

    @Override
    public void paintBeforeMotes(Graphics g) {
        if (simulation == null) {
            Visualizer.unregisterVisualizerSkin(InBodyVisualizerSkin.class);
            return;
        }

        Rectangle2D skinCoord = channelModel.getSkinRect();
        Rectangle2D fatCoord = channelModel.getFatRect();
        Rectangle2D muscleCoord = channelModel.getMuscleRect();
        if (skinCoord == null || fatCoord == null || muscleCoord == null) return;

        double ox = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.phantom_origin_x);
        double oy = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.phantom_origin_y);
        double len = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.phantom_length);
        double st = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.skin_thickness);
        double ft = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.fat_thickness);
        double mt = channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.muscle_thickness);
        double totalT = st + ft + mt;

        Area skinArea = coordToPixelArea(skinCoord);
        Area fatArea = coordToPixelArea(fatCoord);
        Area muscleArea = coordToPixelArea(muscleCoord);

        int canvasW = visualizer.getCurrentCanvas().getWidth();
        int canvasH = visualizer.getCurrentCanvas().getHeight();
        Point skinTL = visualizer.transformPositionToPixel(ox, oy, 0);
        Point skinTR = visualizer.transformPositionToPixel(ox + len, oy, 0);
        Point musBot = visualizer.transformPositionToPixel(ox, oy + totalT, 0);
        int cornerY = skinTL.y;

        Area restrictedArea = new Area();
        if (musBot.y < canvasH)
            restrictedArea.add(new Area(new Rectangle(0, musBot.y, canvasW, canvasH - musBot.y)));
        if (skinTL.x > 0) {
            Area leftRect = new Area(new Rectangle(0, 0, skinTL.x, canvasH));
            leftRect.subtract(new Area(slopeWedgeLeft(skinTL.x, cornerY)));
            restrictedArea.add(leftRect);
        }
        if (skinTR.x < canvasW) {
            Area rightRect = new Area(new Rectangle(skinTR.x, 0, canvasW - skinTR.x, canvasH));
            rightRect.subtract(new Area(slopeWedgeRight(skinTR.x, cornerY, canvasW)));
            restrictedArea.add(rightRect);
        }

        int airLeft = skinTL.x;
        int airRight = skinTR.x;
        int airBot = cornerY;
        Area airArea = new Area();
        if (airBot > 0 && airRight > airLeft)
            airArea.add(new Area(new Rectangle(airLeft, 0, airRight - airLeft, airBot)));
        if (skinTL.x > 0)
            airArea.add(new Area(slopeWedgeLeft(skinTL.x, cornerY)));
        if (skinTR.x < canvasW)
            airArea.add(new Area(slopeWedgeRight(skinTR.x, cornerY, canvasW)));

        Set<Mote> selected = visualizer.getSelectedMotes();
        Graphics2D g2 = (Graphics2D) g;

        for (Mote mote : selected) {
            SignalGradientPainter.paint(g2, mote, visualizer, RADIUS,
                    skinArea, fatArea, muscleArea, restrictedArea, airArea);
        }

        if (SHOW_PROBABILITIES && !selected.isEmpty()) {
            ProbabilityLinePainter.paint(g2, selected, simulation, visualizer, inBody);
        }
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

    private Area coordToPixelArea(Rectangle2D coord) {
        Point minPx = visualizer.transformPositionToPixel(coord.getMinX(), coord.getMinY(), 0);
        Point maxPx = visualizer.transformPositionToPixel(coord.getMaxX(), coord.getMaxY(), 0);
        int px = Math.min(minPx.x, maxPx.x);
        int py = Math.min(minPx.y, maxPx.y);
        int pw = Math.abs(maxPx.x - minPx.x);
        int ph = Math.abs(maxPx.y - minPx.y);
        // Guarantee non-zero size so Area operations do not silently no-op.
        return new Area(new Rectangle(px, py, Math.max(pw, 1), Math.max(ph, 1)));
    }

    private JInternalFrame buildChannelModelDialog() {

        SpinnerNumberModel radiusModel = new SpinnerNumberModel(RADIUS, 25.0, 500.0, 5.0);
        JSpinner radiusSpinner = fmtSpinner(radiusModel, "0 mm");
        radiusSpinner.setToolTipText("Radius of the signal-strength gradient overlay (mm)");
        radiusSpinner.addChangeListener(e -> {
            RADIUS = radiusModel.getNumber().doubleValue();
            visualizer.repaint();
        });

        JCheckBox probCheckBox = new JCheckBox("Show reception probabilities", SHOW_PROBABILITIES);
        probCheckBox.setToolTipText(
                "Draw colour-coded lines from the selected mote to all others, "
                + "labelled with reception probability and received signal strength.");
        probCheckBox.addActionListener(e -> {
            SHOW_PROBABILITIES = probCheckBox.isSelected();
            visualizer.repaint();
        });

        JPanel displaySection = section("Signal Display");
        addRow(displaySection, "Gradient radius:", radiusSpinner, 0);
        GridBagConstraints probGbc = new GridBagConstraints();
        probGbc.gridx = 0; probGbc.gridy = 1; probGbc.gridwidth = 2;
        probGbc.anchor = GridBagConstraints.WEST;
        probGbc.insets = new Insets(2, 6, 4, 6);
        displaySection.add(probCheckBox, probGbc);

        phantomLengthModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.phantom_length),
                50.0, 2000.0, 1.0);
        JSpinner phantomLengthSpinner = fmtSpinner(phantomLengthModel, "0.0 mm");
        phantomLengthSpinner.setToolTipText("Physical length of the phantom along the X axis (mm)");
        phantomLengthSpinner.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.phantom_length,
                    phantomLengthModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        skinThicknessModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.skin_thickness),
                0.1, 20.0, 0.1);
        JSpinner skinThicknessSpinner = fmtSpinner(skinThicknessModel, "0.0 mm");
        skinThicknessSpinner.setToolTipText("Skin layer thickness (mm)");
        skinThicknessSpinner.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.skin_thickness,
                    skinThicknessModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        fatThicknessModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.fat_thickness),
                1.0, 200.0, 0.5);
        JSpinner fatThicknessSpinner = fmtSpinner(fatThicknessModel, "0.0 mm");
        fatThicknessSpinner.setToolTipText("Fat layer thickness (mm)");
        fatThicknessSpinner.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.fat_thickness,
                    fatThicknessModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        muscleThicknessModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.muscle_thickness),
                1.0, 200.0, 0.5);
        JSpinner muscleThicknessSpinner = fmtSpinner(muscleThicknessModel, "0.0 mm");
        muscleThicknessSpinner.setToolTipText("Muscle layer thickness (mm)");
        muscleThicknessSpinner.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.muscle_thickness,
                    muscleThicknessModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        JPanel phantomSection = section("Phantom Geometry  (applies immediately)");
        addRow(phantomSection, "Phantom length:", phantomLengthSpinner, 0);
        addRow(phantomSection, "Skin thickness:", skinThicknessSpinner, 1);
        addRow(phantomSection, "Fat thickness:", fatThicknessSpinner, 2);
        addRow(phantomSection, "Muscle thickness:", muscleThicknessSpinner, 3);

        skinColorButton   = colorButton(PhantomVisualizerSkin.COLOR_SKIN);
        fatColorButton    = colorButton(PhantomVisualizerSkin.COLOR_FAT);
        muscleColorButton = colorButton(PhantomVisualizerSkin.COLOR_MUSCLE);

        skinColorButton.addActionListener(e -> {
            Color c = JColorChooser.showDialog(skinColorButton, "Skin layer colour",
                    skinColorButton.getBackground());
            if (c != null) {
                PhantomVisualizerSkin.COLOR_SKIN = withAlpha(c, PhantomVisualizerSkin.COLOR_SKIN.getAlpha());
                skinColorButton.setBackground(PhantomVisualizerSkin.COLOR_SKIN);
                visualizer.repaint();
            }
        });
        fatColorButton.addActionListener(e -> {
            Color c = JColorChooser.showDialog(fatColorButton, "Fat layer colour",
                    fatColorButton.getBackground());
            if (c != null) {
                PhantomVisualizerSkin.COLOR_FAT = withAlpha(c, PhantomVisualizerSkin.COLOR_FAT.getAlpha());
                fatColorButton.setBackground(PhantomVisualizerSkin.COLOR_FAT);
                visualizer.repaint();
            }
        });
        muscleColorButton.addActionListener(e -> {
            Color c = JColorChooser.showDialog(muscleColorButton, "Muscle layer colour",
                    muscleColorButton.getBackground());
            if (c != null) {
                PhantomVisualizerSkin.COLOR_MUSCLE = withAlpha(c, PhantomVisualizerSkin.COLOR_MUSCLE.getAlpha());
                muscleColorButton.setBackground(PhantomVisualizerSkin.COLOR_MUSCLE);
                visualizer.repaint();
            }
        });

        JPanel colorSection = section("Layer Colours  (applies immediately)");
        addRow(colorSection, "Skin:", skinColorButton, 0);
        addRow(colorSection, "Fat:", fatColorButton, 1);
        addRow(colorSection, "Muscle:", muscleColorButton, 2);

        JButton resetBtn = new JButton("Reset to Defaults");
        resetBtn.setToolTipText(
                "Reset phantom geometry and layer colours to their factory defaults");
        resetBtn.addActionListener(e -> {
            phantomLengthModel.setValue((Double) InBodyChannelModel.Parameter
                    .getDefaultValue(InBodyChannelModel.Parameter.phantom_length));
            skinThicknessModel.setValue((Double) InBodyChannelModel.Parameter
                    .getDefaultValue(InBodyChannelModel.Parameter.skin_thickness));
            fatThicknessModel.setValue((Double) InBodyChannelModel.Parameter
                    .getDefaultValue(InBodyChannelModel.Parameter.fat_thickness));
            muscleThicknessModel.setValue((Double) InBodyChannelModel.Parameter
                    .getDefaultValue(InBodyChannelModel.Parameter.muscle_thickness));
            PhantomVisualizerSkin.COLOR_SKIN   = PhantomVisualizerSkin.DEFAULT_COLOR_SKIN;
            PhantomVisualizerSkin.COLOR_FAT    = PhantomVisualizerSkin.DEFAULT_COLOR_FAT;
            PhantomVisualizerSkin.COLOR_MUSCLE = PhantomVisualizerSkin.DEFAULT_COLOR_MUSCLE;
            skinColorButton.setBackground(PhantomVisualizerSkin.COLOR_SKIN);
            fatColorButton.setBackground(PhantomVisualizerSkin.COLOR_FAT);
            muscleColorButton.setBackground(PhantomVisualizerSkin.COLOR_MUSCLE);
            visualizer.repaint();
        });
        JPanel resetPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        resetPanel.add(resetBtn);

        JPanel displayTab = new JPanel();
        displayTab.setLayout(new BoxLayout(displayTab, BoxLayout.Y_AXIS));
        displayTab.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        displayTab.add(displaySection);
        displayTab.add(Box.createVerticalStrut(4));
        displayTab.add(phantomSection);
        displayTab.add(Box.createVerticalStrut(4));
        displayTab.add(colorSection);
        displayTab.add(Box.createVerticalStrut(4));
        displayTab.add(resetPanel);
        displayTab.add(Box.createVerticalGlue());

        reflModel = new SpinnerNumberModel(
                channelModel.getParameterIntegerValue(InBodyChannelModel.Parameter.rt_max_reflections),
                0, 2, 1);
        JSpinner reflSpinner = new JSpinner(reflModel);
        reflSpinner.setToolTipText("0 = direct path only; 1 = first-order; 2 = also double-bounce");
        reflModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.rt_max_reflections,
                    reflModel.getNumber().intValue());
            visualizer.repaint();
        });

        fsplCheckBox = new JCheckBox("Apply FSPL on full path length",
                channelModel.getParameterBooleanValue(InBodyChannelModel.Parameter.rt_fspl_on_total_length));
        fsplCheckBox.setToolTipText(
                "On: Friis formula applied to total geometric path length.  "
                + "Off: FSPL applied only to air-segment portions; tissue segments omit the spreading term.");
        fsplCheckBox.addActionListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.rt_fspl_on_total_length,
                    fsplCheckBox.isSelected());
            visualizer.repaint();
        });

        skinAttModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.skin_attenuation),
                -200.0, 0.0, 0.1);
        skinAttModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.skin_attenuation,
                    skinAttModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        fatAttModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.fat_attenuation),
                -200.0, 0.0, 0.01);
        fatAttModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.fat_attenuation,
                    fatAttModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        muscleAttModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.muscle_attenuation),
                -200.0, 0.0, 0.1);
        muscleAttModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.muscle_attenuation,
                    muscleAttModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        refracAirSkinModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_refrac_air_skin),
                -30.0, 0.0, 0.1);
        refracAirSkinModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.rt_refrac_air_skin,
                    refracAirSkinModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        refracSkinFatModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_refrac_skin_fat),
                -30.0, 0.0, 0.1);
        refracSkinFatModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.rt_refrac_skin_fat,
                    refracSkinFatModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        refracFatMuscleModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_refrac_fat_muscle),
                -30.0, 0.0, 0.1);
        refracFatMuscleModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.rt_refrac_fat_muscle,
                    refracFatMuscleModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        reflecAirSkinModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_reflec_air_skin),
                -30.0, 0.0, 0.1);
        reflecAirSkinModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.rt_reflec_air_skin,
                    reflecAirSkinModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        reflecSkinFatModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_reflec_skin_fat),
                -30.0, 0.0, 0.1);
        reflecSkinFatModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.rt_reflec_skin_fat,
                    reflecSkinFatModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        reflecFatMuscleModel = new SpinnerNumberModel(
                channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_reflec_fat_muscle),
                -30.0, 0.0, 0.1);
        reflecFatMuscleModel.addChangeListener(e -> {
            channelModel.setParameterValue(InBodyChannelModel.Parameter.rt_reflec_fat_muscle,
                    reflecFatMuscleModel.getNumber().doubleValue());
            visualizer.repaint();
        });

        JPanel raySection = section("Ray Tracer");
        addRow(raySection, "Max reflections (0-2):", reflSpinner, 0);
        GridBagConstraints fsplGbc = new GridBagConstraints();
        fsplGbc.gridx = 0; fsplGbc.gridy = 1; fsplGbc.gridwidth = 2;
        fsplGbc.anchor = GridBagConstraints.WEST;
        fsplGbc.insets = new Insets(2, 6, 4, 6);
        raySection.add(fsplCheckBox, fsplGbc);

        JPanel attSection = section("Tissue Attenuation (dB/mm)");
        addRow(attSection, "Skin   (typical -13.5):", fmtSpinner(skinAttModel,   "0.00 dB/mm"), 0);
        addRow(attSection, "Fat    (typical -0.105):", fmtSpinner(fatAttModel,    "0.000 dB/mm"), 1);
        addRow(attSection, "Muscle (typical -1.0):",  fmtSpinner(muscleAttModel, "0.00 dB/mm"), 2);

        JPanel refracSection = section("Fresnel Transmission Loss (dB)");
        addRow(refracSection, "Air ↔ Skin   (typical -3.0):", fmtSpinner(refracAirSkinModel,   "0.0 dB"), 0);
        addRow(refracSection, "Skin ↔ Fat   (typical -1.0):", fmtSpinner(refracSkinFatModel,   "0.0 dB"), 1);
        addRow(refracSection, "Fat ↔ Muscle (typical -1.5):", fmtSpinner(refracFatMuscleModel, "0.0 dB"), 2);

        JPanel reflecSection = section("Fresnel Reflection Loss (dB)");
        addRow(reflecSection, "Air|Skin   (typical -3.0):", fmtSpinner(reflecAirSkinModel,   "0.0 dB"), 0);
        addRow(reflecSection, "Skin|Fat   (typical -8.0):", fmtSpinner(reflecSkinFatModel,   "0.0 dB"), 1);
        addRow(reflecSection, "Fat|Muscle (typical -6.5):", fmtSpinner(reflecFatMuscleModel, "0.0 dB"), 2);

        JPanel channelTab = new JPanel();
        channelTab.setLayout(new BoxLayout(channelTab, BoxLayout.Y_AXIS));
        channelTab.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        channelTab.add(raySection);
        channelTab.add(Box.createVerticalStrut(4));
        channelTab.add(attSection);
        channelTab.add(Box.createVerticalStrut(4));
        channelTab.add(refracSection);
        channelTab.add(Box.createVerticalStrut(4));
        channelTab.add(reflecSection);
        
        motesTableModel = new AbstractTableModel() {
            private static final String[] COLS =
                    {"Mote", "TX Power (dBm)", "RX Sensitivity (dBm)"};

            @Override
            public int getRowCount() {
                return simulation == null ? 0 : simulation.getMotes().length;
            }

            @Override public int getColumnCount() { return COLS.length; }
            @Override public String getColumnName(int col) { return COLS[col]; }
            @Override public boolean isCellEditable(int row, int col) { return col > 0; }

            @Override
            public Class<?> getColumnClass(int col) {
                return col == 0 ? String.class : Double.class;
            }

            @Override
            public Object getValueAt(int row, int col) {
                if (simulation == null) return null;
                Mote[] motes = simulation.getMotes();
                if (row >= motes.length) return null;
                Mote mote = motes[row];
                Radio radio = mote.getInterfaces().getRadio();
                if (radio == null) return null;
                return switch (col) {
                    case 0 -> "Mote " + mote.getID();
                    case 1 -> inBody.getTxPowerOverride(radio);
                    case 2 -> inBody.getRxSensitivity(radio);
                    default -> null;
                };
            }

            @Override
            public void setValueAt(Object value, int row, int col) {
                if (value == null || simulation == null) return;
                Mote[] motes = simulation.getMotes();
                if (row >= motes.length) return;
                Radio radio = motes[row].getInterfaces().getRadio();
                if (radio == null) return;
                try {
                    double v = Double.parseDouble(value.toString());
                    if (col == 1) inBody.setTxPowerOverride(radio, v);
                    else if (col == 2) inBody.setRxSensitivity(radio, v);
                    fireTableCellUpdated(row, col);
                    visualizer.repaint();
                } catch (NumberFormatException ignored) {
                }
            }
        };

        JTable motesTable = new JTable(motesTableModel);
        motesTable.setFillsViewportHeight(true);
        motesTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        JScrollPane tableScroll = new JScrollPane(motesTable);
        tableScroll.setPreferredSize(new Dimension(400, 130));

        JLabel motesHint = new JLabel(
                "<html><small>"
                + "Double-click a cell to edit.  "
                + "TX Power overrides the firmware output power for that mote.<br>"
                + "RX Sensitivity overrides the global channel-model threshold for that mote."
                + "</small></html>");
        motesHint.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));

        JPanel motesTab = new JPanel(new BorderLayout(4, 4));
        motesTab.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        motesTab.add(tableScroll, BorderLayout.CENTER);
        motesTab.add(motesHint, BorderLayout.SOUTH);
        
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Display",  displayTab);
        tabs.addTab("Channel",  channelTab);
        tabs.addTab("Motes",    motesTab);

        JInternalFrame dialog = new JInternalFrame("Channel Model Settings", false, true);
        dialog.setVisible(false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addInternalFrameListener(new InternalFrameAdapter() {
            @Override public void internalFrameClosing(InternalFrameEvent e) {
                dialog.setVisible(false);
            }
        });
        dialog.getContentPane().add(BorderLayout.CENTER, tabs);
        dialog.pack();
        return dialog;
    }

    private void showChannelModelSettings() {
        // Sync spinners/checkboxes with current channel model state.
        if (motesTableModel != null) motesTableModel.fireTableDataChanged();
        phantomLengthModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.phantom_length));
        skinThicknessModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.skin_thickness));
        fatThicknessModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.fat_thickness));
        muscleThicknessModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.muscle_thickness));
        skinColorButton.setBackground(PhantomVisualizerSkin.COLOR_SKIN);
        fatColorButton.setBackground(PhantomVisualizerSkin.COLOR_FAT);
        muscleColorButton.setBackground(PhantomVisualizerSkin.COLOR_MUSCLE);
        reflModel.setValue(channelModel.getParameterIntegerValue(InBodyChannelModel.Parameter.rt_max_reflections));
        fsplCheckBox.setSelected(channelModel.getParameterBooleanValue(InBodyChannelModel.Parameter.rt_fspl_on_total_length));
        skinAttModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.skin_attenuation));
        fatAttModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.fat_attenuation));
        muscleAttModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.muscle_attenuation));
        refracAirSkinModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_refrac_air_skin));
        refracSkinFatModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_refrac_skin_fat));
        refracFatMuscleModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_refrac_fat_muscle));
        reflecAirSkinModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_reflec_air_skin));
        reflecSkinFatModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_reflec_skin_fat));
        reflecFatMuscleModel.setValue(channelModel.getParameterDoubleValue(InBodyChannelModel.Parameter.rt_reflec_fat_muscle));

        if (channelModelDialog.getDesktopPane() == null) {
            visualizer.getDesktopPane().add(channelModelDialog);
        }
        channelModelDialog.pack();
        Point canvasPos = SwingUtilities.convertPoint(
                visualizer.getCurrentCanvas(),
                visualizer.getCurrentCanvas().getLocation(),
                visualizer.getDesktopPane());
        channelModelDialog.setLocation(
                canvasPos.x + visualizer.getCurrentCanvas().getWidth() - channelModelDialog.getWidth(),
                canvasPos.y);
        channelModelDialog.setLayer(JLayeredPane.MODAL_LAYER);
        channelModelDialog.setVisible(true);
        channelModelDialog.moveToFront();
        try {
            channelModelDialog.setSelected(true);
        } catch (PropertyVetoException ex) {
            logger.warn("Failed to focus channel model settings dialog");
        }
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static void addRow(JPanel panel, String label, JComponent widget, int row) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row;
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(2, 6, 2, 6);
        panel.add(new JLabel(label), lc);

        GridBagConstraints wc = new GridBagConstraints();
        wc.gridx = 1; wc.gridy = row;
        wc.anchor = GridBagConstraints.EAST;
        wc.fill = GridBagConstraints.HORIZONTAL;
        wc.weightx = 1.0;
        wc.insets = new Insets(2, 0, 2, 6);
        panel.add(widget, wc);
    }

    private static JSpinner fmtSpinner(SpinnerNumberModel model, String format) {
        JSpinner s = new JSpinner(model);
        s.setEditor(new JSpinner.NumberEditor(s, format));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(8);
        return s;
    }

    private static JButton colorButton(Color initial) {
        JButton btn = new JButton();
        btn.setBackground(initial);
        btn.setOpaque(true);
        btn.setPreferredSize(new Dimension(60, 22));
        return btn;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public static class ChannelModelMenuAction implements Visualizer.SimulationMenuAction {
        @Override
        public boolean isEnabled(Visualizer v, Simulation s) { return true; }

        @Override
        public String getDescription(Visualizer v, Simulation s) {
            return "Channel model settings ...";
        }

        @Override
        public void doAction(Visualizer v, Simulation s) {
            for (VisualizerSkin skin : v.getCurrentSkins()) {
                if (skin instanceof InBodyVisualizerSkin ibv) {
                    ibv.showChannelModelSettings();
                }
            }
        }
    }
}
