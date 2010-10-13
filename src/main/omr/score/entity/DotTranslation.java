//----------------------------------------------------------------------------//
//                                                                            //
//                        D o t T r a n s l a t i o n                         //
//                                                                            //
//----------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">                          //
//  Copyright (C) Herve Bitteur 2000-2010. All rights reserved.               //
//  This software is released under the GNU General Public License.           //
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.   //
//----------------------------------------------------------------------------//
// </editor-fold>
package omr.score.entity;

import omr.constant.ConstantSet;

import omr.glyph.Shape;
import static omr.glyph.Shape.*;
import omr.glyph.facets.Glyph;

import omr.log.Logger;

import omr.score.common.PixelPoint;

import omr.sheet.Scale;

import omr.util.TreeNode;

import java.util.*;

/**
 * Class <code>DotTranslation</code> is a set of functions forassigning a dot
 * glyph, since a dot can be an augmentation dot, part of a repeat sign, a
 * staccato sign.
 *
 * @author Hervé Bitteur
 */
public class DotTranslation
{
    //~ Static fields/initializers ---------------------------------------------

    /** Specific application parameters */
    private static final Constants constants = new Constants();

    /** Usual logger utility */
    private static final Logger logger = Logger.getLogger(DotTranslation.class);

    /** Sequence of dot trials */
    private static final List<?extends Trial> trials = Arrays.asList(
        new StaccatoTrial(),
        new RepeatTrial(),
        new AugmentationTrial());

    //~ Methods ----------------------------------------------------------------

    //-------------//
    // populateDot //
    //-------------//
    /**
     * Try to find the best assignment for a dot (variant) glyph.
     *
     * @param glyph the glyph of dot
     * @param measure the containing measure
     * @param dotCenter the location of the dot
     */
    public static void populateDot (Glyph      glyph,
                                    Measure    measure,
                                    PixelPoint dotCenter)
    {
        if (logger.isFineEnabled()) {
            logger.fine(measure.getContextString() + " populateDot " + glyph);
        }

        // Keep specific shape only if manually assigned
        if (!glyph.isManualShape()) {
            glyph.setShape(DOT);
        }

        Shape                   shape = glyph.getShape();

        /** To remember results of trials */
        SortedSet<Trial.Result> results = new TreeSet<Trial.Result>();

        // Try the various possibilities
        for (Trial trial : trials) {
            if ((shape == DOT) || (shape == trial.targetShape)) {
                Trial.Result result = trial.process(glyph, measure, dotCenter);

                if (result != null) {
                    results.add(result);
                }
            }
        }

        // Debug
        if (logger.isFineEnabled()) {
            for (Trial.Result info : results) {
                logger.fine(info.toString());
            }
        }

        // Choose best result, if any
        if (!results.isEmpty()) {
            Trial.Result result = results.first();
            Shape        targetShape = result.getTargetShape();

            // Assign proper glyph shape (and thus color)
            if (glyph.getShape() != targetShape) {
                glyph.setShape(targetShape);
            }

            // Assign proper translation
            result.commit(glyph, measure, dotCenter);
        } else {
            measure.addError(glyph, "Dot unassigned");
        }
    }

    //~ Inner Classes ----------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
        extends ConstantSet
    {
        //~ Instance fields ----------------------------------------------------

        /**
         * Maximum dx between note and augmentation dot
         */
        Scale.Fraction maxAugmentationDotDx = new Scale.Fraction(
            1.7d,
            "Maximum dx between note and augmentation dot");

        /**
         * Maximum absolute dy between note and augmentation dot
         */
        Scale.Fraction maxAugmentationDotDy = new Scale.Fraction(
            1d,
            "Maximum absolute dy between note and augmentation dot");

        /**
         * Maximum dx between two augmentation dots
         */
        Scale.Fraction maxAugmentationDoubleDotsDx = new Scale.Fraction(
            1.5d,
            "Maximum dx between two augmentation dots");

        /**
         * Maximum absolute dy between two augmentation dots
         */
        Scale.Fraction maxAugmentationDoubleDotsDy = new Scale.Fraction(
            0.2d,
            "Maximum absolute dy between two augmentation dots");

        /**
         * Margin for vertical position of a dot againt a repeat barline
         */
        Scale.Fraction maxRepeatDotDy = new Scale.Fraction(
            0.5d,
            "Margin for vertical position of a dot againt a repeat barline");

        /**
         * Maximum dx between dot and edge of repeat barline
         */
        Scale.Fraction maxRepeatDotDx = new Scale.Fraction(
            1.5d,
            "Maximum dx between dot and edge of repeat barline");

        /**
         * Maximum absolute dy between note and staccato dot
         */
        Scale.Fraction maxStaccatoDotDy = new Scale.Fraction(
            6d,
            "Maximum absolute dy between note and staccato dot");

        /**
         * Maximum dx between note and staccato dot
         */
        Scale.Fraction maxStaccatoDotDx = new Scale.Fraction(
            0.75d,
            "Maximum dx between note and staccato dot");
    }

    //-------//
    // Trial //
    //-------//
    private abstract static class Trial
    {
        //~ Instance fields ----------------------------------------------------

        public final Shape targetShape;

        //~ Constructors -------------------------------------------------------

        public Trial (Shape targetShape)
        {
            this.targetShape = targetShape;
        }

        //~ Methods ------------------------------------------------------------

        public Shape getTargetShape ()
        {
            return targetShape;
        }

        abstract Result process (Glyph      glyph,
                                 Measure    measure,
                                 PixelPoint dotCenter);

        //~ Inner Classes ------------------------------------------------------

        /**
         * Remember information about possible assignment of a dot
         */
        public abstract class Result
            implements Comparable<Result>
        {
            //~ Instance fields ------------------------------------------------

            /* The measured distance to the related entity */
            final double dist;

            //~ Constructors ---------------------------------------------------

            public Result (double dist)
            {
                this.dist = dist;
            }

            //~ Methods --------------------------------------------------------

            public abstract void commit (Glyph      glyph,
                                         Measure    measure,
                                         PixelPoint dotCenter);

            public Shape getTargetShape ()
            {
                return targetShape;
            }

            public int compareTo (Result other)
            {
                return Double.compare(this.dist, other.dist);
            }

            @Override
            public String toString ()
            {
                return "{" + getClass()
                                 .getSimpleName() + " dist:" + (float) dist +
                       " " + internals() + "}";
            }

            protected String internals ()
            {
                return "";
            }
        }
    }

    //-------------------//
    // AugmentationTrial //
    //-------------------//
    /**
     * Try to assign a dot as a chord augmentation dot
     */
    private static class AugmentationTrial
        extends Trial
    {
        //~ Constructors -------------------------------------------------------

        public AugmentationTrial ()
        {
            super(COMBINING_AUGMENTATION_DOT);
        }

        //~ Methods ------------------------------------------------------------

        @Override
        Result process (Glyph      glyph,
                        Measure    measure,
                        PixelPoint dotCenter)
        {
            Scale                            scale = measure.getScale();
            final int                        maxDx = scale.toPixels(
                constants.maxAugmentationDotDx);
            final int                        maxDy = scale.toPixels(
                constants.maxAugmentationDotDy);
            SortedMap<Double, ChordNotePair> distances = new TreeMap<Double, ChordNotePair>();

            // Check for a note/rest nearby:
            // - on the left w/ same even pitch (note w/ even pitch)
            // - slighly above or below (note with odd pitch = on a staff line)
            ChordLoop: 
            for (TreeNode node : measure.getChords()) {
                Chord chord = (Chord) node;

                for (TreeNode n : chord.getNotes()) {
                    Note note = (Note) n;

                    if (!note.getShape()
                             .isMeasureRest()) {
                        PixelPoint noteRef = note.getCenterRight();
                        PixelPoint toDot = new PixelPoint(
                            dotCenter.x - noteRef.x,
                            dotCenter.y - noteRef.y);

                        if (logger.isFineEnabled()) {
                            logger.fine("Augmentation " + toDot + " " + note);
                        }

                        if (((glyph.getShape() == getTargetShape()) &&
                            glyph.isManualShape()) ||
                            ((toDot.x > 0) && (toDot.x <= maxDx) &&
                            (Math.abs(toDot.y) <= maxDy))) {
                            distances.put(
                                toDot.distanceSq(0, 0),
                                new ChordNotePair(chord, note));
                        } else if (toDot.x < (-2 * maxDx)) {
                            break ChordLoop; // Speed up
                        }
                    }
                }
            }

            if (!distances.isEmpty()) {
                Double firstKey = distances.firstKey();

                return new AugmentationResult(
                    distances.get(firstKey),
                    firstKey);
            } else {
                return null;
            }
        }

        //~ Inner Classes ------------------------------------------------------

        public class AugmentationResult
            extends Result
        {
            //~ Instance fields ------------------------------------------------

            final ChordNotePair pair;

            //~ Constructors ---------------------------------------------------

            public AugmentationResult (ChordNotePair pair,
                                       double        dist)
            {
                super(dist);
                this.pair = pair;
            }

            //~ Methods --------------------------------------------------------

            @Override
            public void commit (Glyph      glyph,
                                Measure    measure,
                                PixelPoint dotCenter)
            {
                // Is there a second dot on the right?
                Glyph second = secondDot(glyph, measure, dotCenter);
                pair.note.setDots(glyph, second);
                glyph.setTranslation(pair.note);
                pair.chord.setDotsNumber((second != null) ? 2 : 1);

                if (logger.isFineEnabled()) {
                    logger.fine(
                        pair.note.getContextString() + " dot#" + glyph.getId() +
                        " Augmented " + pair.note);
                }
            }

            @Override
            protected String internals ()
            {
                return "chord:" + pair.chord + " note:" + pair.note;
            }

            private Glyph secondDot (Glyph      glyph,
                                     Measure    measure,
                                     PixelPoint dotCenter)
            {
                Scale     scale = measure.getScale();
                final int maxDx = scale.toPixels(
                    constants.maxAugmentationDoubleDotsDx);
                final int maxDy = scale.toPixels(
                    constants.maxAugmentationDoubleDotsDy);

                boolean   started = false;

                // Check for a suitable second dot nearby
                for (Glyph g : measure.getSystem()
                                      .getInfo()
                                      .getGlyphs()) {
                    if (g == glyph) {
                        started = true;

                        continue;
                    }

                    if (!started) {
                        continue;
                    }

                    if (!g.isTranslated() &&
                        ((g.getShape() == DOT) ||
                        (g.getShape() == COMBINING_AUGMENTATION_DOT))) {
                        // Check relative position
                        PixelPoint gCenter = g.getLocation();
                        int        dx = gCenter.x - dotCenter.x;
                        int        dy = gCenter.y - dotCenter.y;

                        if (dx > maxDx) {
                            return null;
                        }

                        if ((dx > 0) && (Math.abs(dy) <= maxDy)) {
                            if (logger.isFineEnabled()) {
                                logger.fine("Double dot with " + g);
                            }

                            g.setTranslation(pair.note);

                            // Assign proper glyph shape (and thus color)
                            if (g.getShape() != targetShape) {
                                g.setShape(targetShape);
                            }

                            return g;
                        }
                    }
                }

                return null;
            }
        }

        private static class ChordNotePair
        {
            //~ Instance fields ------------------------------------------------

            final Chord chord;
            final Note  note;

            //~ Constructors ---------------------------------------------------

            public ChordNotePair (Chord chord,
                                  Note  note)
            {
                this.chord = chord;
                this.note = note;
            }
        }
    }

    //-------------//
    // RepeatTrial //
    //-------------//
    /**
     * Try to assign a dot to the relevant repeat barline if any
     */
    private static class RepeatTrial
        extends Trial
    {
        //~ Constructors -------------------------------------------------------

        public RepeatTrial ()
        {
            super(REPEAT_DOTS);
        }

        //~ Methods ------------------------------------------------------------

        @Override
        RepeatResult process (Glyph      glyph,
                              Measure    measure,
                              PixelPoint dotCenter)
        {
            SortedMap<Double, Barline> distances = new TreeMap<Double, Barline>();

            // Check vertical pitch position within the staff: close to +1 or -1
            double pitchDif = Math.abs(Math.abs(glyph.getPitchPosition()) - 1);

            if (pitchDif > (2 * constants.maxRepeatDotDy.getValue())) {
                return null;
            }

            final Scale scale = measure.getScale();
            final int   maxDx = scale.toPixels(constants.maxRepeatDotDx);

            // Check  wrt starting barline on left and ending barline on right
            Measure prevMeasure = (Measure) measure.getPreviousSibling();
            Barline leftBar = (prevMeasure != null) ? prevMeasure.getBarline()
                              : measure.getPart()
                                       .getStartingBarline();
            Barline rightBar = measure.getBarline();

            for (Barline bar : Arrays.asList(leftBar, rightBar)) {
                if (bar != null) {
                    final int dx = (bar == leftBar)
                                   ? (dotCenter.x - bar.getRightX())
                                   : (bar.getLeftX() - dotCenter.x);

                    if (logger.isFineEnabled()) {
                        logger.fine("Repeat dx:" + dx + " " + bar);
                    }

                    if (((glyph.getShape() == getTargetShape()) &&
                        glyph.isManualShape()) ||
                        ((dx > 0) && (dx <= maxDx))) {
                        distances.put(new Double(dx * dx), bar);
                    }
                }
            }

            // Take the best, if any
            if (!distances.isEmpty()) {
                Double firstKey = distances.firstKey();

                return new RepeatResult(distances.get(firstKey), firstKey);
            } else {
                return null;
            }
        }

        //~ Inner Classes ------------------------------------------------------

        public class RepeatResult
            extends Trial.Result
        {
            //~ Instance fields ------------------------------------------------

            final Barline barline;

            //~ Constructors ---------------------------------------------------

            public RepeatResult (Barline barline,
                                 double  dist)
            {
                super(dist);
                this.barline = barline;
            }

            //~ Methods --------------------------------------------------------

            @Override
            public void commit (Glyph      glyph,
                                Measure    measure,
                                PixelPoint dotCenter)
            {
                barline.addGlyph(glyph);

                if (logger.isFineEnabled()) {
                    logger.fine(
                        barline.getContextString() + " dot#" + glyph.getId() +
                        " Repeat dot for " + barline);
                }
            }

            @Override
            protected String internals ()
            {
                return "barline:" + barline;
            }
        }
    }

    //---------------//
    // StaccatoTrial //
    //---------------//
    /**
     * Try to assign a dot as a staccato
     */
    private static class StaccatoTrial
        extends Trial
    {
        //~ Constructors -------------------------------------------------------

        public StaccatoTrial ()
        {
            super(STACCATO);
        }

        //~ Methods ------------------------------------------------------------

        @Override
        StaccatoResult process (Glyph      glyph,
                                Measure    measure,
                                PixelPoint dotCenter)
        {
            Scale                    scale = measure.getScale();
            final int                maxDx = scale.toPixels(
                constants.maxStaccatoDotDx);
            final int                maxDy = scale.toPixels(
                constants.maxStaccatoDotDy);
            SortedMap<Double, Chord> distances = new TreeMap<Double, Chord>();

            ChordLoop: 
            for (TreeNode node : measure.getChords()) {
                Chord chord = (Chord) node;

                for (TreeNode n : chord.getNotes()) {
                    Note note = (Note) n;

                    if (!note.isRest()) {
                        // Check distance wrt both top & bottom of note
                        for (PixelPoint noteRef : Arrays.asList(
                            note.getCenterTop(),
                            note.getCenterBottom())) {
                            PixelPoint toDot = new PixelPoint(
                                dotCenter.x - noteRef.x,
                                dotCenter.y - noteRef.y);

                            if (logger.isFineEnabled()) {
                                logger.fine("Staccato " + toDot + " " + note);
                            }

                            if (((glyph.getShape() == getTargetShape()) &&
                                glyph.isManualShape()) ||
                                ((Math.abs(toDot.x) <= maxDx) &&
                                (Math.abs(toDot.y) <= maxDy))) {
                                distances.put(toDot.distanceSq(0, 0), chord);
                            } else if (toDot.x < (-2 * maxDx)) {
                                break ChordLoop; // Speed up
                            }
                        }
                    }
                }
            }

            if (!distances.isEmpty()) {
                Double firstKey = distances.firstKey();

                return new StaccatoResult(distances.get(firstKey), firstKey);
            } else {
                return null;
            }
        }

        //~ Inner Classes ------------------------------------------------------

        private class StaccatoResult
            extends Result
        {
            //~ Instance fields ------------------------------------------------

            final Chord chord;

            //~ Constructors ---------------------------------------------------

            public StaccatoResult (Chord  chord,
                                   double dist)
            {
                super(dist);
                this.chord = chord;
            }

            //~ Methods --------------------------------------------------------

            @Override
            public void commit (Glyph      glyph,
                                Measure    measure,
                                PixelPoint dotCenter)
            {
                glyph.setTranslation(
                    new Articulation(measure, dotCenter, chord, glyph));

                if (logger.isFineEnabled()) {
                    logger.fine(
                        chord.getContextString() + " dot#" + glyph.getId() +
                        " Staccato " + chord);
                }
            }

            @Override
            protected String internals ()
            {
                return "chord:" + chord;
            }
        }
    }
}
