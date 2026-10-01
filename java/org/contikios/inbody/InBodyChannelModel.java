package org.contikios.inbody;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Random;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.interfaces.Radio;
import org.contikios.cooja.radiomediums.AbstractRadioMedium;
import org.contikios.cooja.util.EventTriggers;
import org.contikios.mrm.statistics.GaussianWrapper;
import org.jdom2.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Channel model for Fat Tissue-based In-Body Communication (Fat-IBC).
 */
public class InBodyChannelModel {
    private static final Logger logger = LoggerFactory.getLogger(InBodyChannelModel.class);

    public enum Parameter {
        /* ---- General ---- */
        apply_random,
        snr_threshold,
        bg_noise_mean,
        bg_noise_var,
        system_gain_mean,
        system_gain_var,
        tx_power,
        rx_sensitivity,
        frequency,
        /* ---- Ray-tracer ---- */
        rt_max_rays,
        rt_max_refractions,
        rt_max_reflections,
        rt_max_diffractions,
        rt_refrac_air_skin,
        rt_refrac_skin_fat,
        rt_refrac_fat_muscle,
        rt_reflec_air_skin,
        rt_reflec_skin_fat,
        rt_reflec_fat_muscle,
        rt_diffr_coefficient,
        rt_fspl_on_total_length,
        /* ---- Tissue attenuation (dB/mm at the configured frequency) ---- */
        skin_attenuation,
        fat_attenuation,
        muscle_attenuation,
        restricted_attenuation,
        /* ---- Phantom geometry (simulation coordinate units = mm) ---- */
        phantom_origin_x,
        phantom_origin_y,
        phantom_length,
        skin_thickness,
        fat_thickness,
        muscle_thickness,
        phantom_restricted_gap,
        /* ---- Capture effect ---- */
        captureEffect,
        captureEffectPreambleDuration,
        captureEffectSignalThreshold;

        public static Object getDefaultValue(Parameter p) {
            return switch (p) {
                case apply_random -> Boolean.FALSE;
                case snr_threshold -> 6.0;
                case bg_noise_mean -> AbstractRadioMedium.SS_NOTHING;
                case bg_noise_var -> 1.0;
                case system_gain_mean -> 0.0;
                case system_gain_var -> 4.0;
                case tx_power -> -30;
                case rx_sensitivity -> -100.0;
                case frequency -> 2450.0;      // MHz
                case rt_max_rays -> 3;
                case rt_max_refractions -> 2;
                case rt_max_reflections -> 1;
                case rt_max_diffractions -> 0;
                case rt_refrac_air_skin -> -3.0;
                case rt_refrac_skin_fat -> -1.0;
                case rt_refrac_fat_muscle -> -1.5;
                case rt_reflec_air_skin -> -3.0;
                case rt_reflec_skin_fat -> -8.0;
                case rt_reflec_fat_muscle -> -6.5;
                case rt_diffr_coefficient -> -10.0;
                case rt_fspl_on_total_length -> Boolean.FALSE;
                case skin_attenuation -> -13.9;
                case fat_attenuation -> -0.105;
                case muscle_attenuation -> -1.0;
                case restricted_attenuation -> -100.0;
                case phantom_origin_x -> 0.0;
                case phantom_origin_y -> 0.0;
                case phantom_length -> 300.0;
                case skin_thickness -> 2.0;
                case fat_thickness -> 25.0;
                case muscle_thickness -> 30.0;
                case phantom_restricted_gap -> 500.0;
                case captureEffect -> Boolean.TRUE;
                case captureEffectPreambleDuration -> 128.0;
                case captureEffectSignalThreshold -> 3.0;
            };
        }

        public static String getDescription(Parameter p) {
            return switch (p) {
                case apply_random -> "(DEBUG) Apply random values";
                case snr_threshold -> "SNR reception threshold (dB)";
                case bg_noise_mean -> "Background noise mean (dBm)";
                case bg_noise_var -> "Background noise variance (dB)";
                case system_gain_mean -> "Extra system gain mean (dB)";
                case system_gain_var -> "Extra system gain variance (dB)";
                case tx_power -> "Default TX output power (dBm)";
                case rx_sensitivity -> "Receiver sensitivity (dBm)";
                case frequency -> "Frequency (MHz)";
                case rt_max_rays -> "Maximum number of path rays";
                case rt_max_refractions -> "Maximum number of refractions";
                case rt_max_reflections -> "Maximum number of reflections (1 or 2)";
                case rt_max_diffractions -> "Maximum number of diffraction";
                case rt_refrac_air_skin -> "Fresnel Tx loss: air ↔ skin (dB)";
                case rt_refrac_skin_fat -> "Fresnel Tx loss: skin ↔ fat (dB)";
                case rt_refrac_fat_muscle -> "Fresnel Tx loss: fat ↔ muscle (dB)";
                case rt_reflec_air_skin -> "Fresnel reflection loss: air|skin surface (dB)";
                case rt_reflec_skin_fat -> "Fresnel reflection loss: skin|fat surface (dB)";
                case rt_reflec_fat_muscle -> "Fresnel reflection loss: fat|muscle surface (dB)";
                case rt_diffr_coefficient -> "Diffraction loss per edge (dB)";
                case rt_fspl_on_total_length -> "Apply FSPL on total path length";
                case skin_attenuation -> "Skin attenuation (dB/mm)";
                case fat_attenuation -> "Fat attenuation (dB/mm)";
                case muscle_attenuation -> "Muscle attenuation (dB/mm)";
                case restricted_attenuation -> "Restricted region attenuation (dB/mm)";
                case phantom_origin_x -> "Phantom origin X (mm)";
                case phantom_origin_y -> "Phantom origin Y (mm)";
                case phantom_length -> "Phantom length (mm)";
                case skin_thickness -> "Skin thickness (mm)";
                case fat_thickness -> "Fat thickness (mm)";
                case muscle_thickness -> "Muscle thickness (mm)";
                case phantom_restricted_gap -> "Restricted gap around phantom (mm)";
                case captureEffect -> "Use Capture Effect";
                case captureEffectPreambleDuration -> "Capture effect preamble (µs)";
                case captureEffectSignalThreshold -> "Capture effect threshold (dB)";
            };
        }
    }
    
    public static abstract class TxPair {
        public abstract double getFromX();

        public abstract double getFromY();

        public abstract double getToX();

        public abstract double getToY();

        public abstract double getTxPower();

        public Point2D getFrom() {
            return new Point2D.Double(getFromX(), getFromY());
        }

        public Point2D getTo() {
            return new Point2D.Double(getToX(), getToY());
        }

        public double getDistance() {
            double w = getFromX() - getToX(), h = getFromY() - getToY();
            return Math.sqrt(w * w + h * h);
        }

        public double getAngle() {
            return Math.atan2(getToY() - getFromY(), getToX() - getFromX());
        }
    }
    
    public static abstract class RadioPair extends TxPair {
        public abstract Radio getFromRadio();

        public abstract Radio getToRadio();

        @Override
        public double getFromX() {
            return getFromRadio().getPosition().getXCoordinate();
        }

        @Override
        public double getFromY() {
            return getFromRadio().getPosition().getYCoordinate();
        }

        @Override
        public double getToX() {
            return getToRadio().getPosition().getXCoordinate();
        }

        @Override
        public double getToY() {
            return getToRadio().getPosition().getYCoordinate();
        }

        @Override
        public double getTxPower() {
            return getFromRadio().getCurrentOutputPower();
        }
    }


    private final HashMap<Parameter, Object> parameters = new HashMap<>();
    private final HashMap<Parameter, Object> parametersDefaults;

    private boolean needToPrecalculateFSPL = true;
    private double paramFSPL;

    private final PhantomGeometry phantom;
    private final PropagationModel propagation;

    private final EventTriggers<EventTriggers.Update, Parameter> settingsTriggers = new EventTriggers<>();

    public InBodyChannelModel(Simulation simulation) {
        for (Parameter p : Parameter.values()) {
            parameters.put(p, Parameter.getDefaultValue(p));
        }
        parametersDefaults = new HashMap<>(parameters);

        phantom = new PhantomGeometry();
        propagation = new PropagationModel(this);
        rebuildPhantomGeometry();
    }

    public EventTriggers<EventTriggers.Update, Parameter> getSettingsTriggers() {
        return settingsTriggers;
    }

    public Object getParameterValue(Parameter id) {
        return parameters.get(id);
    }

    public double getParameterDoubleValue(Parameter id) {
        return (Double) parameters.get(id);
    }

    public int getParameterIntegerValue(Parameter id) {
        return (Integer) parameters.get(id);
    }

    public boolean getParameterBooleanValue(Parameter id) {
        return (Boolean) parameters.get(id);
    }

    synchronized public void setParameterValue(Parameter id, Object newValue) {
        if (!parameters.containsKey(id)) {
            logger.error("Unknown parameter: {}", id);
            return;
        }
        parameters.put(id, newValue);
        needToPrecalculateFSPL = true;
        if (id == Parameter.phantom_origin_x || id == Parameter.phantom_origin_y
                || id == Parameter.phantom_length || id == Parameter.skin_thickness
                || id == Parameter.fat_thickness || id == Parameter.muscle_thickness
                || id == Parameter.phantom_restricted_gap) {
            rebuildPhantomGeometry();
        }
        settingsTriggers.trigger(EventTriggers.Update.UPDATE, id);
    }
    
    synchronized public void rebuildPhantomGeometry() {
        phantom.rebuild(
                getParameterDoubleValue(Parameter.phantom_origin_x),
                getParameterDoubleValue(Parameter.phantom_origin_y),
                getParameterDoubleValue(Parameter.phantom_length),
                getParameterDoubleValue(Parameter.skin_thickness),
                getParameterDoubleValue(Parameter.fat_thickness),
                getParameterDoubleValue(Parameter.muscle_thickness),
                getParameterDoubleValue(Parameter.phantom_restricted_gap));
    }

    synchronized public void updatePhantomGeometry(double phantomLength, double skinThickness,
                                                   double fatThickness, double muscleThickness,
                                                   double restrictedGap) {
        parameters.put(Parameter.phantom_length, phantomLength);
        parameters.put(Parameter.skin_thickness, skinThickness);
        parameters.put(Parameter.fat_thickness, fatThickness);
        parameters.put(Parameter.muscle_thickness, muscleThickness);
        parameters.put(Parameter.phantom_restricted_gap, restrictedGap);
        rebuildPhantomGeometry();
        settingsTriggers.trigger(EventTriggers.Update.UPDATE, null);
    }

    public TissueType getTissueAt(Point2D p) {
        return phantom.getTissueAt(p);
    }

    public double getTissueAttenuation(TissueType t) {
        return switch (t) {
            case SKIN -> getParameterDoubleValue(Parameter.skin_attenuation);
            case FAT -> getParameterDoubleValue(Parameter.fat_attenuation);
            case MUSCLE -> getParameterDoubleValue(Parameter.muscle_attenuation);
            case RESTRICTED -> getParameterDoubleValue(Parameter.restricted_attenuation);
            case AIR -> 0.0;
        };
    }
    
    public Rectangle2D getSkinRect() {
        return phantom.getSkinRect();
    }

    public Rectangle2D getFatRect() {
        return phantom.getFatRect();
    }

    public Rectangle2D getMuscleRect() {
        return phantom.getMuscleRect();
    }
    
    protected double getFSPL(double distanceMm) {
        if (needToPrecalculateFSPL) {
            double f = getParameterDoubleValue(Parameter.frequency); // MHz
            paramFSPL = -32.44 - 20.0 * Math.log10(f);
            needToPrecalculateFSPL = false;
        }
        return Math.min(0.0, paramFSPL - 20.0 * Math.log10(distanceMm * 1e-6));
    }
    
    public double[] getReceivedSignalStrength(TxPair txPair) {
        return propagation.compute(txPair);
    }
    
    public double[] getSINR(TxPair txPair, double interference) {
        double[] ss = getReceivedSignalStrength(txPair);
        double[] snr = {ss[0], ss[1], ss[0]};

        double noiseVar = getParameterDoubleValue(Parameter.bg_noise_var);
        double noiseMean = getParameterDoubleValue(Parameter.bg_noise_mean);
        if (interference > noiseMean) noiseMean = interference;

        if (getParameterBooleanValue(Parameter.apply_random)) {
            noiseMean += Math.sqrt(noiseVar) * new Random().nextGaussian();
            noiseVar = 0;
        }

        snr[0] -= noiseMean;
        snr[1] += noiseVar;
        return snr;
    }
    
    public double[] getProbability(TxPair txPair, double interference) {
        return getProbability(txPair, interference,
                getParameterDoubleValue(Parameter.rx_sensitivity));
    }
    
    public double[] getProbability(TxPair txPair, double interference, double rxSensitivity) {
        double[] snr = getSINR(txPair, interference);
        double snrMean = snr[0];
        double snrVar = snr[1];
        double sigStr = snr[2];
        double threshold = getParameterDoubleValue(Parameter.snr_threshold);

        if (rxSensitivity > sigStr - snrMean && threshold < rxSensitivity + snrMean - sigStr) {
            threshold = rxSensitivity + snrMean - sigStr;
        }

        if (snrVar == 0) {
            return new double[]{threshold - snrMean > 0 ? 0.0 : 1.0, sigStr};
        }

        double prob = 1.0 - GaussianWrapper.cdfErrorAlgo(threshold, snrMean, Math.sqrt(snrVar));
        return new double[]{prob, sigStr};
    }
    
    public Collection<Element> getConfigXML() {
        var config = new ArrayList<Element>();
        for (var entry : parameters.entrySet()) {
            Parameter p = entry.getKey();
            if (parametersDefaults.get(p).equals(entry.getValue())) continue;
            Element el = new Element(p.toString());
            el.setAttribute("value", entry.getValue().toString());
            config.add(el);
        }
        return config;
    }

    synchronized public boolean setConfigXML(Collection<Element> configXML) {
        for (Element el : configXML) {
            Parameter param;
            try {
                param = Parameter.valueOf(el.getName());
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown InBody parameter: {}", el.getName());
                continue;
            }
            String value = el.getAttributeValue("value");
            if (value == null || value.isEmpty()) value = el.getText();
            Object current = parameters.get(param);
            if (current instanceof Double) parameters.put(param, Double.parseDouble(value));
            else if (current instanceof Boolean) parameters.put(param, Boolean.parseBoolean(value));
            else if (current instanceof Integer) parameters.put(param, Integer.parseInt(value));
        }
        needToPrecalculateFSPL = true;
        rebuildPhantomGeometry();
        settingsTriggers.trigger(EventTriggers.Update.UPDATE, null);
        return true;
    }
}
