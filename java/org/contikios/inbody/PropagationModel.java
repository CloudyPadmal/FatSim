package org.contikios.inbody;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.contikios.inbody.InBodyChannelModel.Parameter;
import org.contikios.inbody.InBodyChannelModel.TxPair;

/**
 * Ray-tracing propagation model for the Fat-IBC channel
 * 
 * All paths within 30 dB of the strongest are combined with incoherent (power) summation
 */
class PropagationModel {

    private static final double C = 299792458.0;

    private final InBodyChannelModel cm;

    private enum BoundaryKind {AIR_SKIN, SKIN_FAT, FAT_MUSCLE}

    private record BoundarySeg(Line2D line, BoundaryKind kind) {
    }

    PropagationModel(InBodyChannelModel cm) {
        this.cm = cm;
    }

    double[] compute(TxPair txPair) {
        Point2D source = txPair.getFrom();
        Point2D dest = txPair.getTo();

        if (cm.getTissueAt(source) == TissueType.RESTRICTED || cm.getTissueAt(dest) == TissueType.RESTRICTED) {
            return new double[]{-Double.MAX_VALUE, 0};
        }

        double totalDist = source.distance(dest);
        if (totalDist < 0.001) { // Motes at the same position will return TX power plus set gain
            double var = cm.getParameterBooleanValue(Parameter.apply_random)
                    ? 0 : cm.getParameterDoubleValue(Parameter.system_gain_var);
            return new double[]{txPair.getTxPower() + cm.getParameterDoubleValue(Parameter.system_gain_mean), var};
        }
        
        List<double[]> paths = collectPaths(source, dest, totalDist);
        
        double combinedGain = combinePaths(paths);
        combinedGain = Math.min(0.0, combinedGain);

        double variance = 0;
        double systemGain = cm.getParameterDoubleValue(Parameter.system_gain_mean);
        if (cm.getParameterBooleanValue(Parameter.apply_random)) {
            systemGain += Math.sqrt(cm.getParameterDoubleValue(Parameter.system_gain_var)) * new Random().nextGaussian();
        } else {
            variance = cm.getParameterDoubleValue(Parameter.system_gain_var);
        }

        return new double[]{txPair.getTxPower() + systemGain + combinedGain, variance};
    }
    
    private List<double[]> collectPaths(Point2D source, Point2D dest, double totalDist) {
        List<double[]> paths = new ArrayList<>();

        // 1. Direct path through tissue.
        PathGain direct = computeTissueGain(source, dest);
        paths.add(new double[]{
                direct.gain() + fsplForPath(totalDist, direct.airMm()), totalDist});

        List<BoundarySeg> boundaries = getTissueBoundarySegments();
        int maxRefl = cm.getParameterIntegerValue(Parameter.rt_max_reflections);
        int maxDiffr = cm.getParameterIntegerValue(Parameter.rt_max_diffractions);

        // 2. First-order specular reflections.
        if (maxRefl >= 1) {
            for (BoundarySeg b : boundaries) {
                double[] p = computeReflectedPath(source, dest, b);
                if (p != null) paths.add(p);
            }
        }

        // 3. Second-order specular reflections (double-bounce).
        if (maxRefl >= 2) {
            for (int i = 0; i < boundaries.size(); i++) {
                for (int j = 0; j < boundaries.size(); j++) {
                    if (i == j) continue;
                    double[] p = computeDoubleReflectedPath(source, dest, boundaries.get(i), boundaries.get(j));
                    if (p != null) paths.add(p);
                }
            }
        }

        // 4. Single-edge diffraction at phantom corners.
        if (maxDiffr >= 1) {
            double diffrCoeff = cm.getParameterDoubleValue(Parameter.rt_diffr_coefficient);
            for (Point2D corner : getDiffractionPoints()) {
                double len = source.distance(corner) + corner.distance(dest);
                PathGain pg1 = computeTissueGain(source, corner);
                PathGain pg2 = computeTissueGain(corner, dest);
                double gain = pg1.gain() + diffrCoeff + pg2.gain() + fsplForPath(len, pg1.airMm() + pg2.airMm());
                paths.add(new double[]{gain, len});
            }
        }

        return paths;
    }
    
    private double combinePaths(List<double[]> paths) {
        double bestGain = -Double.MAX_VALUE;
        for (double[] p : paths) {
            if (p[0] > bestGain) bestGain = p[0];
        }
        if (bestGain == -Double.MAX_VALUE) return bestGain;

        double totalLinear = 0;
        for (double[] p : paths) {
            if (p[0] < bestGain - 30) continue;                  // skip negligible paths (<0.1 %)
            totalLinear += Math.pow(10.0, p[0] / 10.0);          // sum linear powers
        }
        return (totalLinear > 0) ? 10.0 * Math.log10(totalLinear) : bestGain - 30;
    }

    private record PathGain(double gain, double airMm) {
    }
    
    private PathGain computeTissueGain(Point2D src, Point2D dst) {
        List<Point2D> crossings = new ArrayList<>();
        crossings.add(src);
        crossings.add(dst);

        for (Rectangle2D rect : new Rectangle2D[]{cm.getSkinRect(), cm.getFatRect(), cm.getMuscleRect()}) {
            if (rect == null) continue;
            Line2D inside = GeometryHelpers.getIntersectionLine(src.getX(), src.getY(), dst.getX(), dst.getY(), rect);
            if (inside == null) continue;
            crossings.add(inside.getP1());
            crossings.add(inside.getP2());
        }

        crossings.sort(Comparator.comparingDouble(p -> p.distance(src)));

        double gain = 0;
        double airMm = 0;
        TissueType prev = null;

        for (int i = 0; i < crossings.size() - 1; i++) {
            Point2D p1 = crossings.get(i), p2 = crossings.get(i + 1);
            double segLen = p1.distance(p2);
            if (segLen < 0.001) continue;
            Point2D mid = new Point2D.Double((p1.getX() + p2.getX()) / 2.0, (p1.getY() + p2.getY()) / 2.0);
            TissueType t = cm.getTissueAt(mid);

            gain += cm.getTissueAttenuation(t) * segLen;
            if (t == TissueType.AIR) airMm += segLen;
            if (prev != null && prev != t) gain += getRefractionLoss(prev, t);
            prev = t;
        }
        return new PathGain(gain, airMm);
    }

    private double fsplForPath(double totalMm, double airMm) {
        return cm.getParameterBooleanValue(Parameter.rt_fspl_on_total_length)
                ? cm.getFSPL(totalMm)
                : cm.getFSPL(airMm);
    }

    private double getRefractionLoss(TissueType from, TissueType to) {
        boolean airSkin = (from == TissueType.AIR && to == TissueType.SKIN)
                || (from == TissueType.SKIN && to == TissueType.AIR);
        boolean skinFat = (from == TissueType.SKIN && to == TissueType.FAT)
                || (from == TissueType.FAT && to == TissueType.SKIN);
        boolean fatMuscle = (from == TissueType.FAT && to == TissueType.MUSCLE)
                || (from == TissueType.MUSCLE && to == TissueType.FAT);

        if (airSkin) return cm.getParameterDoubleValue(Parameter.rt_refrac_air_skin);
        if (skinFat) return cm.getParameterDoubleValue(Parameter.rt_refrac_skin_fat);
        if (fatMuscle) return cm.getParameterDoubleValue(Parameter.rt_refrac_fat_muscle);

        return cm.getParameterDoubleValue(Parameter.rt_refrac_air_skin);
    }

    private double getReflectionLoss(BoundaryKind kind) {
        return switch (kind) {
            case AIR_SKIN -> cm.getParameterDoubleValue(Parameter.rt_reflec_air_skin);
            case SKIN_FAT -> cm.getParameterDoubleValue(Parameter.rt_reflec_skin_fat);
            case FAT_MUSCLE -> cm.getParameterDoubleValue(Parameter.rt_reflec_fat_muscle);
        };
    }

    private List<BoundarySeg> getTissueBoundarySegments() {
        var segs = new ArrayList<BoundarySeg>();
        var skinRect = cm.getSkinRect();
        if (skinRect == null) return segs;

        double ox = skinRect.getMinX();
        double len = skinRect.getWidth();
        double y0 = skinRect.getMinY();
        double y1 = skinRect.getMaxY();
        var fat = cm.getFatRect();
        double y2 = (fat != null) ? fat.getMaxY() : y1;

        segs.add(new BoundarySeg(new Line2D.Double(ox, y0, ox + len, y0), BoundaryKind.AIR_SKIN));
        segs.add(new BoundarySeg(new Line2D.Double(ox, y1, ox + len, y1), BoundaryKind.SKIN_FAT));
        segs.add(new BoundarySeg(new Line2D.Double(ox, y2, ox + len, y2), BoundaryKind.FAT_MUSCLE));
        return segs;
    }

    private List<Point2D> getDiffractionPoints() {
        var pts = new ArrayList<Point2D>();
        var skinRect = cm.getSkinRect();
        if (skinRect == null) return pts;

        double ox = skinRect.getMinX();
        double len = skinRect.getWidth();
        double y0 = skinRect.getMinY();

        pts.add(new Point2D.Double(ox, y0));
        pts.add(new Point2D.Double(ox + len, y0));
        return pts;
    }

    private double[] computeReflectedPath(Point2D src, Point2D dst, BoundarySeg b) {
        Line2D boundary = b.line();
        if (GeometryHelpers.signedSideOfLine(src, boundary) * GeometryHelpers.signedSideOfLine(dst, boundary) <= 0) {
            return null;
        }

        Point2D srcImg = GeometryHelpers.reflectPoint(src, boundary);
        Point2D P = GeometryHelpers.getIntersectionPoint(new Line2D.Double(srcImg, dst), boundary);
        if (P == null || !GeometryHelpers.isWithinSegmentBounds(P, boundary)) return null;

        double reflLoss = getReflectionLoss(b.kind());
        double totalLen = src.distance(P) + P.distance(dst);
        PathGain pg1 = computeTissueGain(src, P);
        PathGain pg2 = computeTissueGain(P, dst);
        double gain = pg1.gain() + reflLoss + pg2.gain() + fsplForPath(totalLen, pg1.airMm() + pg2.airMm());
        return new double[]{gain, totalLen};
    }
    
    private double[] computeDoubleReflectedPath(Point2D src, Point2D dst, BoundarySeg b1, BoundarySeg b2) {
        Line2D l1 = b1.line(), l2 = b2.line();

        Point2D img1 = GeometryHelpers.reflectPoint(src, l1);
        Point2D img2 = GeometryHelpers.reflectPoint(img1, l2);

        Point2D P2 = GeometryHelpers.getIntersectionPoint(new Line2D.Double(img2, dst), l2);
        if (P2 == null || !GeometryHelpers.isWithinSegmentBounds(P2, l2)) return null;
        Point2D P1 = GeometryHelpers.getIntersectionPoint(new Line2D.Double(img1, P2), l1);
        if (P1 == null || !GeometryHelpers.isWithinSegmentBounds(P1, l1)) return null;

        double refl1 = getReflectionLoss(b1.kind());
        double refl2 = getReflectionLoss(b2.kind());
        double totalLen = src.distance(P1) + P1.distance(P2) + P2.distance(dst);
        PathGain pg1 = computeTissueGain(src, P1);
        PathGain pg2 = computeTissueGain(P1, P2);
        PathGain pg3 = computeTissueGain(P2, dst);
        double gain = pg1.gain() + refl1 + pg2.gain() + refl2 + pg3.gain() + fsplForPath(totalLen, pg1.airMm() + pg2.airMm() + pg3.airMm());
        return new double[]{gain, totalLen};
    }
}
