//----------------------------------------------------------------------------//
//                                                                            //
//                           L i n e C l u s t e r                            //
//                                                                            //
//----------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">                          //
//  Copyright © Hervé Bitteur and others 2000-2013. All rights reserved.      //
//  This software is released under the GNU General Public License.           //
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.   //
//----------------------------------------------------------------------------//
// </editor-fold>
package omr.grid;

import omr.glyph.Glyphs;

import omr.lag.Section;

import omr.run.Orientation;

import omr.math.GeoUtil;
import omr.util.Vip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Class {@code LineCluster} is meant to aggregate instances of
 * {@link Filament} that are linked by {@link FilamentComb} instances
 * and thus a cluster represents a staff candidate, perhaps augmented
 * above and/or below by "virtual lines" of ledgers.
 *
 * @author Hervé Bitteur
 */
public class LineCluster
        implements Vip
{
    //~ Static fields/initializers ---------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(LineCluster.class);

    /** For comparing LineCluster instances on their true length. */
    public static final Comparator<LineCluster> byReverseLength = new Comparator<LineCluster>()
    {
        @Override
        public int compare (LineCluster c1,
                            LineCluster c2)
        {
            // Sort on reverse length
            return Double.compare(c2.getTrueLength(), c1.getTrueLength());
        }
    };

    //~ Instance fields --------------------------------------------------------
    /** Id for debug. */
    private final int id;

    /** Interline for this cluster. */
    private final int interline;

    /** Reference to cluster this one has been included into, if any. */
    private LineCluster parent;

    /** Composing lines, ordered by their relative position (ordinate). */
    private SortedMap<Integer, FilamentLine> lines;

    /** (Cached) bounding box of this cluster. */
    private Rectangle contourBox;

    /** Cluster true length. */
    private Integer trueLength;

    /** For debugging. */
    private boolean vip = false;

    //~ Constructors -----------------------------------------------------------
    //-------------//
    // LineCluster //
    //-------------//
    /**
     * Creates a new LineCluster object.
     *
     * @param interline the scaling information
     * @param seed      the first filament of the cluster
     */
    public LineCluster (int interline,
                        LineFilament seed)
    {
        if (logger.isDebugEnabled() || seed.isVip()) {
            logger.info("VIP creating cluster with F{}", seed.getId());

            if (seed.isVip()) {
                setVip();
            }
        }

        this.interline = interline;
        this.id = seed.getId();

        lines = new TreeMap<>();

        include(seed, 0);
    }

    //~ Methods ----------------------------------------------------------------
    //---------//
    // destroy //
    //---------//
    /**
     * Remove the link back from filaments to this cluster.
     */
    public void destroy ()
    {
        for (FilamentLine line : lines.values()) {
            line.fil.setCluster(null, 0);
            line.fil.getCombs().clear();
        }
    }

    //-------------//
    // getAncestor //
    //-------------//
    /**
     * Report the top ancestor of this cluster.
     *
     * @return the cluster ancestor
     */
    public LineCluster getAncestor ()
    {
        LineCluster cluster = this;

        while (cluster.parent != null) {
            cluster = cluster.parent;
        }

        return cluster;
    }

    //-----------//
    // getBounds //
    //-----------//
    public Rectangle getBounds ()
    {
        if (contourBox == null) {
            Rectangle box = null;

            for (FilamentLine line : getLines()) {
                if (box == null) {
                    box = new Rectangle(line.getBounds());
                } else {
                    box.add(line.getBounds());
                }
            }

            contourBox = box;
        }

        if (contourBox != null) {
            return new Rectangle(contourBox); // Copy
        } else {
            return null;
        }
    }

    //-----------//
    // getCenter //
    //-----------//
    /**
     * Report the center of cluster.
     *
     * @return the center
     */
    public Point getCenter ()
    {
        Rectangle box = getBounds();

        return new Point(
                box.x + (box.width / 2),
                box.y + (box.height / 2));
    }

    //--------------//
    // getFirstLine //
    //--------------//
    public FilamentLine getFirstLine ()
    {
        return lines.get(lines.firstKey());
    }

    //-------//
    // getId //
    //-------//
    /**
     * @return the id
     */
    public int getId ()
    {
        return id;
    }

    //--------------//
    // getInterline //
    //--------------//
    /**
     * @return the interline
     */
    public int getInterline ()
    {
        return interline;
    }

    //-------------//
    // getLastLine //
    //-------------//
    public FilamentLine getLastLine ()
    {
        return lines.get(lines.lastKey());
    }

    //----------//
    // getLines //
    //----------//
    public Collection<FilamentLine> getLines ()
    {
        return lines.values();
    }

    //-----------//
    // getParent //
    //-----------//
    /**
     * @return the parent
     */
    public LineCluster getParent ()
    {
        return parent;
    }

    //-------------//
    // getPointsAt //
    //-------------//
    /**
     * Report the sequence of points that correspond to a provided
     * abscissa.
     *
     * @param x           the provided abscissa
     * @param xMargin     maximum abscissa margin for horizontal extrapolation
     * @param interline   the standard interline value, used for vertical
     *                    extrapolations
     * @param globalSlope global slope of the sheet
     * @return the sequence of cluster points, from top to bottom, with perhaps
     *         some holes indicated by null values
     */
    public List<Point2D> getPointsAt (double x,
                                      int xMargin,
                                      int interline,
                                      double globalSlope)
    {
        SortedMap<Integer, Point2D> points = new TreeMap<>();
        List<Integer> holes = new ArrayList<>();

        for (Entry<Integer, FilamentLine> entry : lines.entrySet()) {
            int pos = entry.getKey();
            FilamentLine line = entry.getValue();

            if (line.isWithinRange(x)) {
                points.put(
                        pos,
                        new Point2D.Double(x, line.yAt(x)));
            } else {
                holes.add(pos);
            }
        }

        // Interpolate or extrapolate the missing values if any
        for (int pos : holes) {
            Integer prevPos = null;
            Double prevVal = null;

            for (int p = pos - 1; p >= lines.firstKey(); p--) {
                Point2D pt = points.get(p);

                if (pt != null) {
                    prevPos = p;
                    prevVal = pt.getY();

                    break;
                }
            }

            Integer nextPos = null;
            Double nextVal = null;

            for (int p = pos + 1; p <= lines.lastKey(); p++) {
                Point2D pt = points.get(p);

                if (pt != null) {
                    nextPos = p;
                    nextVal = pt.getY();

                    break;
                }
            }

            Double y = null;

            // Interpolate vertically
            if ((prevPos != null) && (nextPos != null)) {
                y = prevVal
                    + (((pos - prevPos) * (nextVal - prevVal)) / (nextPos
                                                                  - prevPos));
            } else {
                // Extrapolate vertically, only for one interline max
                if ((prevPos != null) && ((pos - prevPos) == 1)) {
                    y = prevVal + interline;
                } else if ((nextPos != null) && ((nextPos - pos) == 1)) {
                    y = nextVal - interline;
                } else {
                    // Extrapolate horizontally on a short distance
                    FilamentLine line = lines.get(pos);
                    Point2D point = (x <= line.getStartPoint().getX())
                            ? line.getStartPoint()
                            : line.getStopPoint();
                    double dx = x - point.getX();

                    if (Math.abs(dx) <= xMargin) {
                        y = point.getY() + (dx * globalSlope);
                    }
                }
            }

            points.put(pos, (y != null) ? new Point2D.Double(x, y) : null);
        }

        return new ArrayList<>(points.values());
    }

    //---------//
    // getSize //
    //---------//
    public int getSize ()
    {
        return lines.size();
    }

    //-----------//
    // getStarts //
    //-----------//
    public List<Point2D> getStarts ()
    {
        List<Point2D> points = new ArrayList<>(getSize());

        for (FilamentLine line : lines.values()) {
            points.add(line.getStartPoint());
        }

        return points;
    }

    //----------//
    // getStops //
    //----------//
    public List<Point2D> getStops ()
    {
        List<Point2D> points = new ArrayList<>(getSize());

        for (FilamentLine line : lines.values()) {
            points.add(line.getStopPoint());
        }

        return points;
    }

    //---------------//
    // getTrueLength //
    //---------------//
    /**
     * Report a measurement of the cluster length.
     *
     * @return the mean true length of cluster lines
     */
    public int getTrueLength ()
    {
        if (trueLength == null) {
            // Determine mean true line length in this cluster
            int meanTrueLength = 0;

            for (FilamentLine line : lines.values()) {
                meanTrueLength += line.fil.trueLength();
            }

            meanTrueLength /= lines.size();
            logger.debug("TrueLength: {} for {}", meanTrueLength, this);

            trueLength = meanTrueLength;
        }

        return trueLength;
    }

    //------------------------//
    // includeFilamentByIndex //
    //------------------------//
    /**
     * Include a filament to this cluster, using the provided relative
     * line index counted from zero (rather than the line position).
     * Check this room is "free" on the cluster line
     *
     * @param filament the filament to include
     * @param index    the zero-based line index
     * @return true if there was room for inclusion
     */
    public boolean includeFilamentByIndex (LineFilament filament,
                                           int index)
    {
        final Rectangle filBox = filament.getBounds();
        int i = 0;

        for (Entry<Integer, FilamentLine> entry : lines.entrySet()) {
            if (i++ == index) {
                FilamentLine line = entry.getValue();

                // Check for horizontal room
                // For filaments one above the other, check resulting thickness
                for (Section section : line.fil.getMembers()) {
                    // Horizontal overlap?
                    Rectangle sctBox = section.getBounds();
                    int overlap = GeoUtil.xOverlap(filBox, sctBox);
                    if (overlap > 0) {
                        // Check resulting thickness
                        double thickness = Glyphs.getThicknessAt(
                                Math.max(filBox.x, sctBox.x) + overlap / 2,
                                Orientation.HORIZONTAL,
                                filament,
                                line.fil);

                        if (thickness > line.fil.getScale().getMaxFore()) {
                            if (filament.isVip() || logger.isDebugEnabled()) {
                                logger.info("VIP no room for {} in {}",
                                            filament, this);
                            }

                            return false;
                        }
                    }
                }

                line.add(filament);
                filament.setCluster(this, entry.getKey());
                invalidateCache();

                return true;
            }
        }

        return false; // Should not happen
    }

    //-------//
    // isVip //
    //-------//
    @Override
    public boolean isVip ()
    {
        return vip;
    }

    //-----------//
    // mergeWith //
    //-----------//
    public void mergeWith (LineCluster that,
                           int deltaPos)
    {
        include(
                that,
                deltaPos + (this.lines.firstKey() - that.lines.firstKey()));
    }

    //--------//
    // render //
    //--------//
    public void render (Graphics2D g)
    {
        for (FilamentLine line : lines.values()) {
            line.render(g);
        }
    }

    //---------------//
    // renumberLines //
    //---------------//
    /**
     * Renumber the remaining lines counting from zero.
     */
    public void renumberLines ()
    {
        // Renumbering
        int firstPos = lines.firstKey();

        if (firstPos != 0) {
            SortedMap<Integer, FilamentLine> newLines = new TreeMap<>();

            for (Entry<Integer, FilamentLine> entry : lines.entrySet()) {
                int pos = entry.getKey();
                int newPos = pos - firstPos;
                FilamentLine line = entry.getValue();
                line.fil.setCluster(this, newPos);
                newLines.put(newPos, new FilamentLine(line.fil));
            }

            lines = newLines;
        }

        invalidateCache();
    }

    //--------//
    // setVip //
    //--------//
    @Override
    public void setVip ()
    {
        vip = true;
    }

    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        StringBuilder sb = new StringBuilder("{Cluster#");
        sb.append(getId());

        sb.append(" interline:").append(getInterline());

        sb.append(" size:").append(getSize());

        for (Entry<Integer, FilamentLine> entry : lines.entrySet()) {
            sb.append(" ").append(entry.getValue());
        }

        sb.append("}");

        return sb.toString();
    }

    //------//
    // trim //
    //------//
    /**
     * Remove lines in excess, often due to aligned ledgers.
     * <p>
     * We prune the cluster incrementally, choosing either the top line or the
     * bottom line of the cluster.
     * First criteria is the higher number of combs the filament is part of.
     * Second criteria is the lower "true length", since ledgers are generally
     * thicker than staff lines.
     * We could also use the presence of long segments (much longer than typical
     * ledger length) to differentiate staff lines from sequences of ledgers.
     *
     * @param count the target line count
     */
    public void trim (int count)
    {
        logger.debug("Trim {}", this);

        // Pruning
        while (lines.size() > count) {
            // Remove the top or bottom line
            final FilamentLine top = lines.get(lines.firstKey());
            int topCount = top.fil.getCombs().size();
            int topWL = top.fil.trueLength();

            final FilamentLine bot = lines.get(lines.lastKey());
            int botCount = bot.fil.getCombs().size();
            int botWL = bot.fil.trueLength();

            final FilamentLine line; // Which line to remove?
            if (topCount == botCount) {
                // Use reverse true length
                if (topWL > botWL) {
                    line = top;
                } else {
                    line = bot;
                }
            } else if (topCount < botCount) {
                line = top;
            } else {
                line = bot;
            }

            if (line == top) {
                lines.remove(lines.firstKey());
            } else {
                lines.remove(lines.lastKey());
            }

            // House keeping
            line.fil.setCluster(null, 0);
            line.fil.getCombs().clear();
        }

        renumberLines();
        invalidateCache();
    }

    //---------//
    // getLine //
    //---------//
    private FilamentLine getLine (int pos,
                                  LineFilament fil)
    {
        FilamentLine line = lines.get(pos);

        if (line == null) {
            line = new FilamentLine(fil);
            lines.put(pos, line);
        }

        return line;
    }

    //---------//
    // include //
    //---------//
    /**
     * Include a filament, with all its combs.
     *
     * @param pivot    the filament to include
     * @param pivotPos the imposed position within the cluster
     */
    private void include (LineFilament pivot,
                          int pivotPos)
    {
        if (logger.isDebugEnabled() || pivot.isVip()) {
            logger.info("VIP {} include pivot:{} at pos:{}",
                        this, pivot.getId(), pivotPos);

            if (pivot.isVip()) {
                setVip();
            }
        }

        LineFilament ancestor = (LineFilament) pivot.getAncestor();

        // Loop on all combs that involve this filament
        // Use a copy to avoid concurrent modification error
        List<FilamentComb> combs = new ArrayList<FilamentComb>(pivot.getCombs().values());
        for (FilamentComb comb : combs) {
            if (comb.isProcessed()) {
                continue;
            }

            comb.setProcessed(true);

            int deltaPos = pivotPos - comb.getIndex(pivot);
            logger.debug("{} deltaPos:{}", comb, deltaPos);

            // Dispatch content of comb to proper lines
            for (int i = 0; i < comb.getCount(); i++) {
                LineFilament fil = (LineFilament) comb.getFilament(i).
                        getAncestor();
                LineCluster cluster = fil.getCluster();

                if (cluster == null) {
                    int pos = i + deltaPos;
                    FilamentLine line = getLine(pos, null);
                    line.add(fil);

                    if (fil.isVip()) {
                        logger.info("VIP adding {} to {} at pos {}",
                                    fil, this, pos);
                        setVip();
                    }

                    fil.setCluster(this, pos);

                    if (fil != ancestor) {
                        include(fil, pos); // Recursively
                    }
                } else if (cluster.getAncestor() != this.getAncestor()) {
                    // Need to merge the two clusters
                    include(cluster, (i + deltaPos) - fil.getClusterPos());
                }
            }
        }
    }

    //---------//
    // include //
    //---------//
    /**
     * Merge another cluster with this one.
     *
     * @param that     the other cluster
     * @param deltaPos the delta to apply to that cluster positions
     */
    private void include (LineCluster that,
                          int deltaPos)
    {
        if (logger.isDebugEnabled() || isVip() || that.isVip()) {
            logger.info("VIP inclusion of {} into {} deltaPos:{}",
                        that, this, deltaPos);

            if (that.isVip()) {
                setVip();
            }
        }

        for (Entry<Integer, FilamentLine> entry : that.lines.entrySet()) {
            int pos = entry.getKey() + deltaPos;
            FilamentLine line = entry.getValue();
            getLine(pos, null).include(line);
        }

        that.parent = this;

        if (logger.isDebugEnabled()) {
            logger.debug("Merged:{}", that);
            logger.debug("Merger:{}", this);
        }

        invalidateCache();
    }

    //-----------------//
    // invalidateCache //
    //-----------------//
    private void invalidateCache ()
    {
        contourBox = null;
        trueLength = null;
    }
}
