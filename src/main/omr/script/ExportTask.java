//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                      E x p o r t T a s k                                       //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//  Copyright © Hervé Bitteur and others 2000-2014. All rights reserved.
//  This software is released under the GNU General Public License.
//  Goto http://kenai.com/projects/audiveris to report bugs or suggestions.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package omr.script;

import omr.sheet.Book;
import omr.sheet.ExportPattern;
import omr.sheet.Sheet;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;

/**
 * Class {@code ExportTask} exports score entities to a MusicXML file
 *
 * @author Hervé Bitteur
 */
@XmlAccessorType(XmlAccessType.NONE)
public class ExportTask
        extends ScriptTask
{
    //~ Instance fields ----------------------------------------------------------------------------

    /** The full target file used for export. */
    @XmlAttribute
    private File file;

    /** The target folder used for export. */
    @XmlAttribute
    private File folder;

    //~ Constructors -------------------------------------------------------------------------------
    /**
     * Create a task to export the related score entities of a book, providing either
     * the full target file or just the target folder (and using default file name).
     *
     * @param file   the full path of export file, or null
     * @param folder the full path of export folder, or null
     */
    public ExportTask (File file,
                       File folder)
    {
        this.file = file;
        this.folder = folder;
    }

    /** No-arg constructor needed by JAXB. */
    private ExportTask ()
    {
    }

    //~ Methods ------------------------------------------------------------------------------------
    //------//
    // core //
    //------//
    @Override
    public void core (Sheet sheet)
    {
        Book book = sheet.getBook();
        Path exportPath = (file != null) ? file.toPath()
                : ((folder != null) ? Paths.get(folder.toString(), book.getRadix()) : null);
        book.setExportPathSansExt(ExportPattern.getPathSansExt(exportPath));
        book.export();
    }

    //-----------//
    // internals //
    //-----------//
    @Override
    protected String internals ()
    {
        StringBuilder sb = new StringBuilder(super.internals());
        sb.append(" export");

        if (file != null) {
            sb.append(" file=").append(file);
        }

        if (folder != null) {
            sb.append(" folder=").append(folder);
        }

        return sb.toString();
    }
}
