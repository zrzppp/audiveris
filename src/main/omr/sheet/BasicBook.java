//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        B a s i c B o o k                                       //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Hervé Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.sheet;

import omr.OMR;

import omr.image.FilterDescriptor;
import omr.image.ImageLoading;

import omr.score.OpusExporter;
import omr.score.Score;
import omr.score.ScoreExporter;
import omr.score.ScoreReduction;
import omr.score.entity.Page;
import omr.score.ui.BookPdfOutput;

import omr.script.BookStepTask;
import omr.script.ExportTask;
import omr.script.PrintTask;
import omr.script.Script;
import omr.script.ScriptActions;

import omr.sheet.ui.BookBrowser;
import omr.sheet.ui.SheetActions;
import omr.sheet.ui.SheetsController;

import omr.step.ProcessingCancellationException;
import omr.step.Step;
import omr.step.StepException;
import static omr.step.ui.StepMonitoring.notifyStart;
import static omr.step.ui.StepMonitoring.notifyStop;

import omr.text.Language;

import omr.util.FileUtil;
import omr.util.OmrExecutors;
import omr.util.Param;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import javax.swing.JFrame;

/**
 * Class {@code BasicBook} is a basic implementation of Book interface.
 *
 * @author Hervé Bitteur
 */
public class BasicBook
        implements Book
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(Book.class);

    //~ Instance fields ----------------------------------------------------------------------------
    /** The recording of key processing data. */
    private final BookBench bench;

    /** Browser on this book. */
    private BookBrowser bookBrowser;

    /** Flag to indicate this book is being closed. */
    private volatile boolean closing;

    /** Abstract path (sans extension) where the MusicXML output is to be stored. */
    private Path exportPath;

    /** Handling of binarization filter parameter. */
    private final Param<FilterDescriptor> filterParam = new Param<FilterDescriptor>(
            FilterDescriptor.defaultFilter);

    /** Input path of the related image(s) file, if any. */
    private final Path imagePath;

    /** Dedicated image loader. */
    private ImageLoading.Loader loader;

    /** True if the book contains several sheets. */
    private boolean multiSheet;

    /** Sheet offset of image file with respect to full work. */
    private int offset;

    /** Where the book PDF data is to be stored. */
    private Path printPath;

    /** The related file radix (name w/o extension). */
    private final String radix;

    /** Logical scores for this book. */
    private final List<Score> scores = new ArrayList<Score>();

    /** The script of user actions on this book. */
    private Script script;

    /** Where the script is to be stored. */
    private File scriptFile;

    /** Sequence of sheets from image file. */
    private final List<Sheet> sheets = new ArrayList<Sheet>();

    /** Sub books, if any. */
    private final List<Book> subBooks;

    /** Handling of dominant language(s) parameter. */
    private final Param<String> languageParam = new Param<String>(Language.defaultSpecification);

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Create a Book with a path to an input images file.
     *
     * @param imagePath the input image path (which may contain several images)
     */
    public BasicBook (Path imagePath)
    {
        Objects.requireNonNull(imagePath, "Trying to create a Book with null imagePath");

        this.imagePath = imagePath;

        radix = FileUtil.getNameSansExtension(imagePath);
        subBooks = null;

        bench = new BookBench(this);

        //
        //        // Register this book instance
        //        BookManager.getInstance().addBook(this);
    }

    /**
     * Create a meta Book, to be later populated with sub-books.
     * <p>
     * NOTA: This meta-book feature is not yet in use.
     *
     * @param radix a radix name for this book
     */
    public BasicBook (String radix)
    {
        Objects.requireNonNull(radix, "Trying to create a meta Book with null radix");

        this.radix = radix;

        imagePath = null;
        subBooks = new ArrayList<Book>();

        // Related bench
        bench = new BookBench(this);

        //
        //        // Register this book instance
        //        BookManager.getInstance().addBook(this);
    }

    //~ Methods ------------------------------------------------------------------------------------
    //-------------//
    // buildScores //
    //-------------//
    @Override
    public void buildScores ()
    {
        scores.clear();

        // Current score
        Score score = null;

        // Group all sheets pages into scores
        for (Sheet sheet : sheets) {
            for (Page page : sheet.getPages()) {
                if (page.isMovementStart()) {
                    scores.add(score = new Score());
                } else if (score == null) {
                    scores.add(score = new Score());
                }

                score.addPage(page);
            }
        }
    }

    //-------//
    // close //
    //-------//
    @Override
    public void close ()
    {
        logger.info("Closing {}", this);

        setClosing(true);

        // Check whether the book script has been saved (or user has declined)
        if ((OMR.getGui() != null) && !ScriptActions.checkStored(getScript())) {
            return;
        }

        // Close contained sheets (and pages)
        for (Sheet sheet : new ArrayList<Sheet>(sheets)) {
            sheet.close(true);
        }

        // Close browser if any
        if (bookBrowser != null) {
            bookBrowser.close();
        }

        // Complete and store all bench data
        BookManager.storeBench(bench, true);

        // Remove from OMR instances
        OMR.getEngine().removeBook(this);
    }

    //--------------//
    // createSheets //
    //--------------//
    @Override
    public void createSheets (SortedSet<Integer> sheetIds)
    {
        loader = ImageLoading.getLoader(imagePath);

        if (loader != null) {
            Sheet firstSheet = null;
            multiSheet = loader.getImageCount() > 1; // Several images in file

            if (sheetIds == null) {
                sheetIds = new TreeSet<Integer>();
            }

            if (sheetIds.isEmpty()) {
                for (int i = 1; i <= loader.getImageCount(); i++) {
                    sheetIds.add(i);
                }
            }

            for (int id : sheetIds) {
                Sheet sheet = null;

                try {
                    sheet = new BasicSheet(this, id, null);
                    sheets.add(sheet);

                    if (firstSheet == null) {
                        firstSheet = sheet;

                        // Let the UI focus on first sheet
                        if (OMR.getGui() != null) {
                            SheetsController.getInstance().showAssembly(firstSheet);
                        }
                    }
                } catch (StepException ex) {
                    // Remove sheet from book, if already included
                    if ((sheet != null) && sheets.remove(sheet)) {
                        logger.info("Sheet #{} removed", id);
                    }
                }
            }

            // Remember (even across runs) the parent directory
            BookManager.setDefaultInputDirectory(imagePath.getParent().toString());

            // Insert in sheet history
            BookManager.getInstance().getHistory().add(getInputPath().toString());

            if (OMR.getGui() != null) {
                SheetActions.HistoryMenu.getInstance().setEnabled(true);
            }
        }
    }

    //--------------//
    // deleteExport //
    //--------------//
    @Override
    public void deleteExport ()
    {
        // Determine the output path for the provided book: path/to/scores/Book
        Path bookPath = BookManager.getActualPath(
                getExportPath(),
                BookManager.getDefaultExportPath(this));

        // One-sheet book: <bookname>.mxl
        // One-sheet book: <bookname>.mvt<M>.mxl
        // One-sheet book: <bookname>/... (perhaps some day: 1 directory per book)
        //
        // Multi-sheet book: <bookname>/sheet#<N>.mxl
        // Multi-sheet book: <bookname>/sheet#<N>.mvt<M>.mxl
        // Multi-sheet book: <bookname>/sheet#<N>/... (perhaps some day: 1 directory per sheet)
        final Path folder = isMultiSheet() ? bookPath : bookPath.getParent();
        final Path bookName = bookPath.getFileName(); // bookname

        final String dirGlob = "glob:**/" + bookName + "{/**,}";
        final String filGlob = "glob:**/" + bookName + "{/**,.*}";
        final List<Path> paths = FileUtil.walkDown(folder, dirGlob, filGlob);

        if (!paths.isEmpty()) {
            BookManager.deletePaths(bookName + " deletion", paths);
        } else {
            logger.info("Nothing to delete");
        }
    }

    //--------//
    // doStep //
    //--------//
    @Override
    public boolean doStep (Step target,
                           SortedSet<Integer> sheetIds)
    {
        logger.debug("doStep {} on {}", target, this);

        try {
            if (getSheets().isEmpty()) {
                // Create book sheets if not yet done
                // This will usually trigger the early step on first sheet in synchronous mode
                createSheets(sheetIds);
            }

            if (target == null) {
                return true; // Nothing to
            }

            // Find the least advanced step performed across all book sheets
            Step least = Step.last();

            for (Sheet sheet : getSheets()) {
                Step latest = sheet.getLatestStep();

                if (latest == null) {
                    // This sheet has not been processed at all
                    least = null;

                    break;
                }

                if (latest.compareTo(least) < 0) {
                    least = latest;
                }
            }

            if ((least != null) && (least.compareTo(target) >= 0)) {
                return true; // Nothing to do
            }

            // Launch the steps on each sheet
            logger.info("{}Book processing {}", getLogPrefix(), target);

            long startTime = System.currentTimeMillis();

            try {
                notifyStart();

                if (isMultiSheet()) {
                    boolean failure = false;

                    if (OmrExecutors.defaultParallelism.getTarget() == true) {
                        // Process all sheets in parallel
                        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();

                        for (final Sheet sheet : new ArrayList<Sheet>(getSheets())) {
                            if (!sheet.isDone(target)) {
                                tasks.add(
                                        new Callable<Void>()
                                        {
                                            @Override
                                            public Void call ()
                                            throws StepException
                                            {
                                                sheet.doStep(target, null);

                                                return null;
                                            }
                                        });
                            }
                        }

                        try {
                            List<Future<Void>> futures = OmrExecutors.getCachedLowExecutor()
                                    .invokeAll(tasks);

                            for (Future future : futures) {
                                try {
                                    future.get();
                                } catch (Exception ex) {
                                    logger.warn("Future exception", ex);
                                    failure = true;
                                }
                            }

                            return !failure;
                        } catch (InterruptedException ex) {
                            logger.warn("Error in parallel doScoreStepSet", ex);
                            failure = true;
                        }
                    } else {
                        // Process one sheet after the other
                        for (Sheet sheet : new ArrayList<Sheet>(getSheets())) {
                            if (!sheet.isDone(target)) {
                                if (!sheet.doStep(target, null)) {
                                    failure = true;
                                }
                            }
                        }
                    }

                    return !failure;
                } else {
                    // Process the single sheet
                    return getSheets().get(0).doStep(target, null);
                }
            } finally {
                notifyStop();

                long stopTime = System.currentTimeMillis();
                logger.debug("End of step set in {} ms.", (stopTime - startTime));

                // Record the step tasks to script
                getScript().addTask(new BookStepTask(target));
            }
        } catch (ProcessingCancellationException pce) {
            throw pce;
        } catch (Exception ex) {
            logger.warn("Error in performing " + target, ex);
        }

        return false;
    }

    //--------//
    // export //
    //--------//
    @Override
    public void export ()
    {
        // Make sure all sheets have been transcribed
        for (Sheet sheet : getSheets()) {
            sheet.ensureStep(Step.PAGE);
        }

        // Group book pages into scores
        buildScores();

        for (Score score : scores) {
            // Merges pages into their containing movement score (connecting the parts across pages)
            // TODO: this may need the addition of dummy parts in some pages
            new ScoreReduction(score).reduce();

            for (Page page : score.getPages()) {
                //                // - Retrieve the actual duration of every measure
                //                page.accept(new DurationRetriever());
                //
                //                // - Check all voices timing, assign forward items if needed.
                //                // - Detect special measures and assign proper measure ids
                //                // If needed, we can trigger a reprocessing of this page
                //                page.accept(new MeasureFixer());
                //
                // Check whether time signatures are consistent accross all pages in score
                // TODO: to be implemented
                //
                // Connect slurs across pages
                page.getFirstSystem().connectPageInitialSlurs(score);
            }
        }

        // path/to/scores/Book
        Path bookPath = BookManager.getActualPath(
                getExportPath(),
                BookManager.getDefaultExportPath(this));

        final boolean compressed = BookManager.useCompression();
        final String ext = compressed ? OMR.COMPRESSED_SCORE_EXTENSION : OMR.SCORE_EXTENSION;
        final boolean sig = BookManager.useSignature();

        // Export each movement score
        String bookName = bookPath.getFileName().toString();
        final boolean multiMovements = scores.size() > 1;

        if (BookManager.useOpus()) {
            // Export the book as one opus
            final Path opusPath = bookPath.resolveSibling(bookName + OMR.OPUS_EXTENSION);

            try {
                if (!BookManager.confirmed(opusPath)) {
                    return;
                }

                new OpusExporter(this).export(opusPath, bookName, sig);
                BookManager.setDefaultExportDirectory(bookPath.getParent().toString());
            } catch (Exception ex) {
                logger.warn("Could not export opus " + opusPath, ex);
            }
        } else {
            for (Score score : scores) {
                final String scoreName = (!multiMovements) ? bookName
                        : (bookName + OMR.MOVEMENT_EXTENSION + score.getId());
                final Path scorePath = bookPath.resolveSibling(scoreName + ext);

                try {
                    if (!BookManager.confirmed(scorePath)) {
                        return;
                    }

                    new ScoreExporter(score).export(scorePath, scoreName, sig, compressed);
                    BookManager.setDefaultExportDirectory(bookPath.getParent().toString());
                    getScript().addTask(new ExportTask(bookPath.getParent().toFile()));
                } catch (Exception ex) {
                    logger.warn("Could not export score " + scoreName, ex);
                }
            }
        }

        getScript().addTask(new ExportTask(bookPath.getParent().toFile()));
    }

    //----------//
    // getBench //
    //----------//
    @Override
    public BookBench getBench ()
    {
        return bench;
    }

    //-----------------//
    // getBrowserFrame //
    //-----------------//
    @Override
    public JFrame getBrowserFrame ()
    {
        if (bookBrowser == null) {
            // Build the BookBrowser on the score
            bookBrowser = new BookBrowser(this);
        }

        return bookBrowser.getFrame();
    }

    //
    //    //----------------------//
    //    // getDefaultExportPath //
    //    //----------------------//
    //    /**
    //     * Report the path to which the book would be written by default.
    //     *
    //     * @return the default book path for export
    //     */
    //    public Path getDefaultExportPath ()
    //    {
    //        if (getExportPath() != null) {
    //            return getExportPath();
    //        }
    //
    //        Path mainPath = Main.getExportFolder();
    //
    //        if (mainPath != null) {
    //            if (Files.isDirectory(mainPath)) {
    //                return mainPath.resolve(getRadix());
    //            } else {
    //                return mainPath;
    //            }
    //        }
    //
    //        return Paths.get(BookManager.getDefaultExportDirectory(), getRadix());
    //    }
    //
    //---------------//
    // getExportPath //
    //---------------//
    @Override
    public Path getExportPath ()
    {
        return exportPath;
    }

    //----------------//
    // getFilterParam //
    //----------------//
    @Override
    public Param<FilterDescriptor> getFilterParam ()
    {
        return filterParam;
    }

    //--------------//
    // getInputPath //
    //--------------//
    @Override
    public Path getInputPath ()
    {
        return imagePath;
    }

    //------------------//
    // getLanguageParam //
    //------------------//
    @Override
    public Param<String> getLanguageParam ()
    {
        return languageParam;
    }

    //--------------//
    // getLogPrefix //
    //--------------//
    @Override
    public String getLogPrefix ()
    {
        if (BookManager.isMultiBook()) {
            return "[" + radix + "] ";
        } else {
            return "";
        }
    }

    //-----------//
    // getOffset //
    //-----------//
    @Override
    public int getOffset ()
    {
        return offset;
    }

    //--------------//
    // getPrintPath //
    //--------------//
    @Override
    public Path getPrintPath ()
    {
        return printPath;
    }

    //----------//
    // getRadix //
    //----------//
    @Override
    public String getRadix ()
    {
        return radix;
    }

    //-----------//
    // getScores //
    //-----------//
    @Override
    public List<Score> getScores ()
    {
        return Collections.unmodifiableList(scores);
    }

    //-----------//
    // getScript //
    //-----------//
    @Override
    public Script getScript ()
    {
        if (script == null) {
            script = new Script(this);
        }

        return script;
    }

    //---------------//
    // getScriptFile //
    //---------------//
    @Override
    public File getScriptFile ()
    {
        return scriptFile;
    }

    //----------//
    // getSheet //
    //----------//
    @Override
    public Sheet getSheet (int sheetIndex)
    {
        return sheets.get(sheetIndex);
    }

    //-----------//
    // getSheets //
    //-----------//
    @Override
    public List<Sheet> getSheets ()
    {
        return Collections.unmodifiableList(sheets);
    }

    //-------------//
    // includeBook //
    //-------------//
    @Override
    public void includeBook (Book book)
    {
        subBooks.add(book);
    }

    //-----------//
    // isClosing //
    //-----------//
    @Override
    public boolean isClosing ()
    {
        return closing;
    }

    //--------------//
    // isMultiSheet //
    //--------------//
    @Override
    public boolean isMultiSheet ()
    {
        return multiSheet;
    }

    //----------------//
    // loadSheetImage //
    //----------------//
    @Override
    public BufferedImage loadSheetImage (int id)
    {
        try {
            BufferedImage img = loader.getImage(id);
            logger.info("{} loaded sheet#{} {}x{}", this, id, img.getWidth(), img.getHeight());

            if (loader.allImagesLoaded()) {
                loader.dispose();
                logger.debug("{} all images loaded, loader disposed.", this);
            }

            return img;
        } catch (IOException ex) {
            logger.warn("Error in book.readImage", ex);

            return null;
        }
    }

    //-------//
    // print //
    //-------//
    @Override
    public void print ()
    {
        // Make sure all sheets have been transcribed
        for (Sheet sheet : getSheets()) {
            sheet.ensureStep(Step.PAGE);
        }

        // path/to/prints/Book
        Path bookPath = BookManager.getActualPath(
                getPrintPath(),
                BookManager.getDefaultPrintPath(this));

        final String rootName = bookPath.getFileName().toString();
        final Path pdfPath = bookPath.resolveSibling(rootName + ".pdf");

        // Actually write the PDF
        try {
            // Prompt for overwrite?
            if (!BookManager.confirmed(pdfPath)) {
                return;
            }

            new BookPdfOutput(this, pdfPath.toFile()).write(null);
            logger.info("Book printed to {}", pdfPath);

            setPrintPath(bookPath);
            BookManager.setDefaultPrintDirectory(bookPath.getParent().toString());
            getScript().addTask(new PrintTask(bookPath.getParent().toFile()));
        } catch (Exception ex) {
            logger.warn("Cannot write PDF to " + pdfPath, ex);
        }
    }

    //-------------//
    // removeSheet //
    //-------------//
    @Override
    public boolean removeSheet (Sheet sheet)
    {
        return sheets.remove(sheet);
    }

    //------------//
    // setClosing //
    //------------//
    @Override
    public void setClosing (boolean closing)
    {
        this.closing = closing;
    }

    //---------------//
    // setExportPath //
    //---------------//
    @Override
    public void setExportPath (Path exportPath)
    {
        this.exportPath = exportPath;
    }

    //-----------//
    // setOffset //
    //-----------//
    @Override
    public void setOffset (int offset)
    {
        this.offset = offset;

        if (script != null) {
            // Forward to related script
            script.setOffset(offset);
        }
    }

    //--------------//
    // setPrintPath //
    //--------------//
    @Override
    public void setPrintPath (Path printPath)
    {
        this.printPath = printPath;
    }

    //---------------//
    // setScriptFile //
    //---------------//
    @Override
    public void setScriptFile (File scriptFile)
    {
        this.scriptFile = scriptFile;
    }

    //----------//
    // toString //
    //----------//
    @Override
    public String toString ()
    {
        StringBuilder sb = new StringBuilder("{Book");
        sb.append(" ").append(radix);

        if (offset > 0) {
            sb.append(" offset:").append(offset);
        }

        sb.append("}");

        return sb.toString();
    }

    //------------//
    // transcribe //
    //------------//
    @Override
    public boolean transcribe (SortedSet<Integer> sheetIds)
    {
        return doStep(Step.last(), sheetIds);
    }
}