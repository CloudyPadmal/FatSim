package org.contikios.inbody;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.contikios.cooja.ClassDescription;
import org.contikios.cooja.RadioConnection;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.interfaces.Radio;
import org.contikios.cooja.radiomediums.AbstractRadioMedium;
import org.contikios.cooja.util.EventTriggers;
import org.contikios.inbody.InBodyChannelModel.Parameter;
import org.jdom2.Element;

/**
 * Fat Tissue-based In-Body Radio Medium (Fat-IBC).
 * <p>
 * Uses ray tracing through a layered tissue phantom (skin / fat / muscle) to
 * compute signal strength and reception probability between motes.  The
 * channel model mirrors the approach used in MRM, but uses tissue-specific electromagnetic
 * attenuation constants instead of generic obstacle parameters.
 */
@ClassDescription("Fat Tissue-based In-Body Radio Medium (Fat-IBC)")
public class InBody extends AbstractRadioMedium {

    private final InBodyChannelModel channelModel;
    private final Random random;

    private final Map<Radio, Double> rxSensitivity = Collections.synchronizedMap(new HashMap<>());

    // When a radio has an entry in this map, its value is used instead of the radio's firmware
    private final Map<Radio, Double> txPowerOverride = Collections.synchronizedMap(new HashMap<>());

    private boolean WITH_CAPTURE_EFFECT;
    private double CAPTURE_EFFECT_THRESHOLD;
    private double CAPTURE_EFFECT_PREAMBLE_DURATION;

    public InBody(Simulation simulation) {
        super(simulation);
        random = simulation.getRandomGenerator();
        channelModel = new InBodyChannelModel(simulation);

        WITH_CAPTURE_EFFECT = channelModel.getParameterBooleanValue(Parameter.captureEffect);
        CAPTURE_EFFECT_THRESHOLD = channelModel.getParameterDoubleValue(Parameter.captureEffectSignalThreshold);
        CAPTURE_EFFECT_PREAMBLE_DURATION = channelModel.getParameterDoubleValue(Parameter.captureEffectPreambleDuration);

        channelModel.getSettingsTriggers().addTrigger(this, (event, arg) -> {
            WITH_CAPTURE_EFFECT = channelModel.getParameterBooleanValue(Parameter.captureEffect);
            CAPTURE_EFFECT_THRESHOLD = channelModel.getParameterDoubleValue(Parameter.captureEffectSignalThreshold);
            CAPTURE_EFFECT_PREAMBLE_DURATION = channelModel.getParameterDoubleValue(Parameter.captureEffectPreambleDuration);
            radioMediumTriggers.trigger(EventTriggers.AddRemove.ADD, null);
        });
    }

    public InBodyChannelModel getChannelModel() {
        return channelModel;
    }
    
    public double getRxSensitivity(Radio radio) {
        return rxSensitivity.getOrDefault(radio,
                channelModel.getParameterDoubleValue(Parameter.rx_sensitivity));
    }
    
    public void setRxSensitivity(Radio radio, double sensitivityDbm) {
        rxSensitivity.put(radio, sensitivityDbm);
        radioMediumTriggers.trigger(EventTriggers.AddRemove.ADD, null);
    }
    
    public double getTxPowerOverride(Radio radio) {
        return txPowerOverride.getOrDefault(radio, radio.getCurrentOutputPower());
    }
    
    public void setTxPowerOverride(Radio radio, double powerDbm) {
        txPowerOverride.put(radio, powerDbm);
        radioMediumTriggers.trigger(EventTriggers.AddRemove.ADD, null);
    }
    
    public boolean hasTxPowerOverride(Radio radio) {
        return txPowerOverride.containsKey(radio);
    }

    @Override
    public void removed() {
        super.removed();
        channelModel.getSettingsTriggers().deleteTriggers(this);
    }

    @Override
    public List<Radio> getNeighbors(Radio radio) {
        return super.getNeighbors(radio);
    }
    
    @Override
    protected RadioConnection createConnections(final Radio sender) {
        InBodyRadioConnection conn = new InBodyRadioConnection(sender);

        for (final var recv : getRegisteredRadios()) {
            if (sender == recv) continue;

            var srcCh = sender.getChannel();
            var dstCh = recv.getChannel();
            if (srcCh >= 0 && dstCh >= 0 && srcCh != dstCh) {
                conn.addInterfered(recv);
                continue;
            }

            InBodyChannelModel.TxPair txPair = new InBodyChannelModel.RadioPair() {
                @Override
                public Radio getFromRadio() {
                    return sender;
                }

                @Override
                public Radio getToRadio() {
                    return recv;
                }

                @Override
                public double getTxPower() {
                    return getTxPowerOverride(sender);
                }
            };

            double[] probData = channelModel.getProbability(txPair, -Double.MAX_VALUE, getRxSensitivity(recv));
            double recvProb = probData[0];
            double recvSS = probData[1];

            if (recvProb == 1.0 || random.nextDouble() < recvProb) {
                // Strong enough to receive
                if (!recv.isRadioOn()) {
                    conn.addInterfered(recv);
                    recv.interfereAnyReception();
                } else if (recv.isInterfered()) {
                    conn.addInterfered(recv, recvSS);
                } else if (recv.isTransmitting()) {
                    conn.addInterfered(recv, recvSS);
                } else if (recv.isReceiving()) {
                    if (!WITH_CAPTURE_EFFECT) {
                        conn.addInterfered(recv, recvSS);
                        recv.interfereAnyReception();
                        for (RadioConnection active : getActiveConnections()) {
                            if (active.isDestination(recv)) active.addInterfered(recv);
                        }
                    } else {
                        double currSS = recv.getCurrentSignalStrength();
                        if (recvSS >= currSS - CAPTURE_EFFECT_THRESHOLD) {
                            long startTime = conn.getReceptionStartTime();
                            boolean interfering =
                                    (simulation.getSimulationTime() - startTime) >= CAPTURE_EFFECT_PREAMBLE_DURATION;
                            if (interfering) {
                                conn.addInterfered(recv, recvSS);
                                recv.interfereAnyReception();
                                for (RadioConnection active : getActiveConnections()) {
                                    if (active.isDestination(recv)) active.addInterfered(recv);
                                }
                            } else {
                                for (RadioConnection active : getActiveConnections()) {
                                    if (active.isDestination(recv)) active.removeDestination(recv);
                                }
                                conn.addDestination(recv, recvSS);
                            }
                        }
                    }
                } else {
                    conn.addDestination(recv, recvSS);
                }
            } else if (recvSS > channelModel.getParameterDoubleValue(Parameter.bg_noise_mean)) {
                if (!WITH_CAPTURE_EFFECT) {
                    conn.addInterfered(recv, recvSS);
                    recv.interfereAnyReception();
                }
            }
        }
        return conn;
    }
    
    @Override
    protected void updateSignalStrengths() {
        for (Radio r : getRegisteredRadios()) {
            r.setCurrentSignalStrength(getBaseRssi(r));
        }

        RadioConnection[] connections = getActiveConnections();

        // Destinations
        for (RadioConnection c : connections) {
            var srcCh = c.getSource().getChannel();
            for (Radio dst : c.getDestinations()) {
                var dstCh = dst.getChannel();
                if (srcCh >= 0 && dstCh >= 0 && srcCh != dstCh) continue;
                double ss = ((InBodyRadioConnection) c).getDestinationSS(dst);
                if (dst.getCurrentSignalStrength() < ss) dst.setCurrentSignalStrength(ss);
            }
        }

        // Interfered radios
        for (RadioConnection c : connections) {
            var srcCh = c.getSource().getChannel();
            for (Radio intf : c.getInterfered()) {
                var intfCh = intf.getChannel();
                if (srcCh >= 0 && intfCh >= 0 && srcCh != intfCh) continue;
                double ss = ((InBodyRadioConnection) c).getInterferenceSS(intf);
                if (intf.getCurrentSignalStrength() < ss) intf.setCurrentSignalStrength(ss);
                if (!intf.isInterfered()) intf.interfereAnyReception();
            }
        }
    }

    /** Configuration persistence */
    @Override
    public Collection<Element> getConfigXML() {
        Collection<Element> config = new ArrayList<>(channelModel.getConfigXML());
        // Persist per-mote receiver sensitivity overrides.
        for (var entry : rxSensitivity.entrySet()) {
            Element el = new Element("RxSensitivityConfig");
            el.setAttribute("Mote", String.valueOf(entry.getKey().getMote().getID()));
            el.addContent(String.valueOf(entry.getValue()));
            config.add(el);
        }
        // Persist per-mote TX power overrides.
        for (var entry : txPowerOverride.entrySet()) {
            Element el = new Element("TxPowerConfig");
            el.setAttribute("Mote", String.valueOf(entry.getKey().getMote().getID()));
            el.addContent(String.valueOf(entry.getValue()));
            config.add(el);
        }
        return config;
    }

    @Override
    public boolean setConfigXML(Collection<Element> configXML, boolean visAvailable) {
        List<Element> channelElements = new ArrayList<>();
        List<Element> sensitivityElements = new ArrayList<>();
        List<Element> txPowerElements = new ArrayList<>();
        for (var el : configXML) {
            if (el.getName().equals("RxSensitivityConfig")) {
                sensitivityElements.add(el);
            } else if (el.getName().equals("TxPowerConfig")) {
                txPowerElements.add(el);
            } else {
                channelElements.add(el);
            }
        }
        boolean ok = channelModel.setConfigXML(channelElements);
        for (var el : sensitivityElements) {
            int moteId = Integer.parseInt(el.getAttribute("Mote").getValue());
            var mote = simulation.getMoteWithID(moteId);
            if (mote != null) {
                setRxSensitivity(mote.getInterfaces().getRadio(),
                        Double.parseDouble(el.getText()));
            }
        }
        for (var el : txPowerElements) {
            int moteId = Integer.parseInt(el.getAttribute("Mote").getValue());
            var mote = simulation.getMoteWithID(moteId);
            if (mote != null) {
                setTxPowerOverride(mote.getInterfaces().getRadio(),
                        Double.parseDouble(el.getText()));
            }
        }
        return ok;
    }

    static class InBodyRadioConnection extends RadioConnection {
        private final HashMap<Radio, Double> signalStrengths = new HashMap<>();

        InBodyRadioConnection(Radio source) {
            super(source);
        }

        void addDestination(Radio r, double ss) {
            signalStrengths.put(r, ss);
            addDestination(r);
        }

        void addInterfered(Radio r, double ss) {
            signalStrengths.put(r, ss);
            addInterfered(r);
        }

        double getDestinationSS(Radio r) {
            return signalStrengths.getOrDefault(r, Double.MIN_VALUE);
        }

        double getInterferenceSS(Radio r) {
            return signalStrengths.getOrDefault(r, Double.MIN_VALUE);
        }
    }
}
