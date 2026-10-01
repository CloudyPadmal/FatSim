package org.contikios.inbody;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Stroke;
import java.util.Set;
import org.contikios.cooja.Mote;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.interfaces.Radio;
import org.contikios.cooja.plugins.Visualizer;

/**
 * Draws colour-coded reception-probability lines from a set of selected motes to every other mote in the simulation
 */
class ProbabilityLinePainter {

    /**
     * Paints probability lines for all pairs involving {@code selectedMotes}.
     *
     * @param g the graphics context (already cast to Graphics2D)
     * @param selectedMotes the set of source motes to draw lines from
     * @param simulation the running simulation (provides the full mote list)
     * @param visualizer COOJA visualiser (provides coordinate transforms)
     * @param inBody the Fat-IBC radio medium (provides both the channel model and per-mote receiver sensitivity overrides)
     */
    static void paint(Graphics2D g, Set<Mote> selectedMotes, Simulation simulation, Visualizer visualizer,
                      InBody inBody) {
        InBodyChannelModel channelModel = inBody.getChannelModel();
        FontMetrics fm = g.getFontMetrics();
        Mote[] allMotes = simulation.getMotes();

        for (Mote fromMote : selectedMotes) {
            Radio fromRadio = fromMote.getInterfaces().getRadio();
            if (fromRadio == null) continue;

            Point fromPx = visualizer.transformPositionToPixel(fromMote.getInterfaces().getPosition());

            for (Mote toMote : allMotes) {
                if (toMote == fromMote) continue;
                Radio toRadio = toMote.getInterfaces().getRadio();
                if (toRadio == null) continue;
                
                InBodyChannelModel.TxPair pair = new InBodyChannelModel.RadioPair() {
                    @Override
                    public Radio getFromRadio() {
                        return fromRadio;
                    }

                    @Override
                    public Radio getToRadio() {
                        return toRadio;
                    }

                    @Override
                    public double getTxPower() {
                        return inBody.getTxPowerOverride(fromRadio);
                    }
                };
                double[] probData = channelModel.getProbability(pair,
                        Double.NEGATIVE_INFINITY, inBody.getRxSensitivity(toRadio));
                double prob = probData[0];
                double ss = probData[1];

                if (prob == 0.0) continue;

                Point toPx = visualizer.transformPositionToPixel(toMote.getInterfaces().getPosition());

                float r = Math.max(0f, Math.min(1f, 2f * (1f - (float) prob)));
                float gr = Math.max(0f, Math.min(1f, 2f * (float) prob));
                g.setColor(new Color(r, gr, 0f, 0.85f));

                Stroke saved = g.getStroke();
                g.setStroke(new BasicStroke(1.8f));
                g.drawLine(fromPx.x, fromPx.y, toPx.x, toPx.y);
                g.setStroke(saved);

                String label = String.format("%1.1f%%  %1.1f dBm", 100.0 * prob, ss);
                int labelW = fm.stringWidth(label);
                int labelX = toPx.x - labelW / 2;
                int labelY = toPx.y + 2 * Visualizer.MOTE_RADIUS + 3 + fm.getAscent();

                g.setColor(new Color(255, 255, 255, 180));
                g.fillRect(labelX - 2, labelY - fm.getAscent() - 1,
                        labelW + 4, fm.getHeight() + 2);

                g.setColor(Color.BLACK);
                g.drawString(label, labelX, labelY);
            }
        }
    }

    private ProbabilityLinePainter() {
    }
}
