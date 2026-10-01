package org.contikios.inbody;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;

/**
 * Helper methods for geometric calculations in the Fat-IBC channel model.
 * These include line intersections, point reflections, and side-of-line tests.
 * <hr />
 * <h3>Line intersections</h3>
 * {@code getIntersectionLine} computes the portion of a line segment that lies
 * within a rectangle, while {@code getIntersectionPoint} computes the single
 * intersection point of two line segments (if it exists).
 * <hr />
 * <h3>Reflections</h3>
 * {@code reflectPoint} computes the reflection of a point across an infinite
 * line.
 * <hr />
 * <h3>Side-of-line tests</h3>
 * {@code signedSideOfLine} returns a positive or negative value depending on
 * which side of an infinite line a point lies on.
 * <hr />
 * <h3>Segment bounds</h3>
 * {@code isWithinSegmentBounds} tests whether a point lies within the bounding
 * box of a line segment, with a small tolerance to account for floating-point
 * imprecision.
 * <hr />
 * <p>All methods are static and stateless; they operate purely on their
 * parameters and do not access any shared state.
 */
public class GeometryHelpers {
    /**
     * Returns the portion of the segment {@code (x1,y1) -> (x2,y2)} that lies
     * inside {@code rectangle}, or {@code null} if they do not intersect.
     */
    static Line2D getIntersectionLine(double x1, double y1, double x2, double y2,
                                      Rectangle2D rectangle) {
        if (rectangle.contains(x1, y1) && rectangle.contains(x2, y2)) {
            return new Line2D.Double(x1, y1, x2, y2);
        }

        Line2D bottom = new Line2D.Double(
                rectangle.getMinX(), rectangle.getMaxY(),
                rectangle.getMaxX(), rectangle.getMaxY());
        Line2D top = new Line2D.Double(
                rectangle.getMinX(), rectangle.getMinY(),
                rectangle.getMaxX(), rectangle.getMinY());
        Line2D left = new Line2D.Double(
                rectangle.getMinX(), rectangle.getMinY(),
                rectangle.getMinX(), rectangle.getMaxY());
        Line2D right = new Line2D.Double(
                rectangle.getMaxX(), rectangle.getMinY(),
                rectangle.getMaxX(), rectangle.getMaxY());
        Line2D test = new Line2D.Double(x1, y1, x2, y2);

        var sides = new ArrayList<Line2D>();
        if (bottom.intersectsLine(test)) sides.add(bottom);
        if (top.intersectsLine(test)) sides.add(top);
        if (left.intersectsLine(test)) sides.add(left);
        if (right.intersectsLine(test)) sides.add(right);
        // If there are no intersections, return null.
        if (sides.isEmpty()) return null;

        var pts = new ArrayList<Point2D>();
        for (Line2D s : sides) {
            Point2D p = getIntersectionPoint(test, s);
            if (p != null) pts.add(p);
        }

        // If there is exactly one intersection point, we need to check if the other endpoint is inside the rectangle.
        if (pts.size() == 1) {
            if (rectangle.contains(x1, y1)) pts.add(new Point2D.Double(x1, y1));
            else if (rectangle.contains(x2, y2)) pts.add(new Point2D.Double(x2, y2));
            else return null;
        }
        // If there are not exactly two intersection points, treat it as no intersection.
        if (pts.size() != 2) return null;
        // If the two intersection points are very close together, treat it as no intersection.
        if (pts.get(0).distance(pts.get(1)) < 0.001) return null;

        return new Line2D.Double(pts.get(0), pts.get(1));
    }

    /**
     * Returns the intersection of finite segment {@code a} with finite segment
     * {@code b}, or {@code null} if they do not intersect within their bounds.
     */
    static Point2D getIntersectionPoint(Line2D a, Line2D b) {
        double dx1 = a.getX2() - a.getX1();
        double dy1 = a.getY2() - a.getY1();
        double dx2 = b.getX2() - b.getX1();
        double dy2 = b.getY2() - b.getY1();
        // Compute the determinant of the system of equations to find the intersection point.
        double det = dx2 * dy1 - dy2 * dx1;
        if (det == 0.0) return null;
        // Compute the parameter mu for the second line segment.
        // If it is outside [0, 1], the intersection point is not within the bounds of segment b.
        double mu = ((a.getX1() - b.getX1()) * dy1 - (a.getY1() - b.getY1()) * dx1) / det;
        if (mu < 0.0 || mu > 1.0) return null;
        return new Point2D.Double(b.getX1() + mu * dx2, b.getY1() + mu * dy2);
    }

    /**
     * Reflects point {@code p} across the infinite line passing through the endpoints of {@code line}.
     */
    static Point2D reflectPoint(Point2D p, Line2D line) {
        double dx = line.getX2() - line.getX1();
        double dy = line.getY2() - line.getY1();
        double len2 = dx * dx + dy * dy;
        // If the line has zero length, return the original point (no reflection).
        if (len2 == 0.0) return p;
        // Compute the projection of p onto the line, then reflect across that point.
        double t = ((p.getX() - line.getX1()) * dx
                + (p.getY() - line.getY1()) * dy) / len2;
        double projX = line.getX1() + t * dx;
        double projY = line.getY1() + t * dy;
        return new Point2D.Double(2.0 * projX - p.getX(), 2.0 * projY - p.getY());
    }

    /**
     * Returns the signed distance from {@code p} to the infinite line through
     * {@code line} (positive on one side, negative on the other).
     */
    static double signedSideOfLine(Point2D p, Line2D line) {
        double dx = line.getX2() - line.getX1();
        double dy = line.getY2() - line.getY1();
        return dx * (p.getY() - line.getY1()) - dy * (p.getX() - line.getX1());
    }

    /**
     * Returns {@code true} if {@code p} lies within the axis-aligned bounding
     * box of {@code seg} (with a small floating-point tolerance).
     */
    static boolean isWithinSegmentBounds(Point2D p, Line2D seg) {
        final double tolerance = 0.001;
        double minX = Math.min(seg.getX1(), seg.getX2()) - tolerance;
        double maxX = Math.max(seg.getX1(), seg.getX2()) + tolerance;
        double minY = Math.min(seg.getY1(), seg.getY2()) - tolerance;
        double maxY = Math.max(seg.getY1(), seg.getY2()) + tolerance;
        return p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY;
    }
}
