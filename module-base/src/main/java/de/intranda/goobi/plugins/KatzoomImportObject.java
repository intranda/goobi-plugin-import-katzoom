package de.intranda.goobi.plugins;

import java.io.Serializable;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Holds everything that is known about a single card of a card index.
 *
 * The object deliberately does not keep the absolute path of each file that belongs to the card. A card index can contain several hundred thousand
 * cards with up to nine files each, and all of those files share the same folder. Keeping the folder once and resolving the file names from the folder
 * listing when they are actually needed (see {@link KatzoomImportPlugin#resolveFiles(KatzoomImportObject)}) keeps the memory footprint of a card at a
 * few dozen bytes instead of about a kilobyte.
 */
@Getter
@Setter
@NoArgsConstructor
public class KatzoomImportObject implements Serializable {

    private static final long serialVersionUID = 2352609476455769849L;

    private int id;

    private String label;

    private int totalPosition;

    private String letterName;
    private int letterPosition;

    private String trayName;
    private int trayPosition;

    /**
     * folder that holds the files of this card. The same instance is shared by all cards of that folder.
     */
    private String folder;

    /**
     * true if the back side of the cards of this index was scanned as well. Needed to map a file name back to the card it belongs to.
     */
    private boolean backsideScanned;

    /**
     * only set when the files of this card are spread over more than one folder, which is not expected to happen
     */
    private List<String> additionalFolders;
}
