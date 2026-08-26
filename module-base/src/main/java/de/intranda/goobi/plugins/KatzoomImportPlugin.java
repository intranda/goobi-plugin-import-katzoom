package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang3.StringUtils;
import org.goobi.interfaces.IArchiveManagementAdministrationPlugin;
import org.goobi.interfaces.IEadEntry;
import org.goobi.interfaces.IMetadataField;
import org.goobi.interfaces.INodeType;
import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.DocstructElement;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IImportPluginVersion3;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.properties.ImportProperty;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.forms.MassImportForm;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.UGHException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j2
public class KatzoomImportPlugin implements IImportPluginVersion3 {

    private static final long serialVersionUID = 163324837122210323L;

    @Getter
    private String title = "intranda_import_katzoom";
    @Getter
    private PluginType type = PluginType.Import;

    @Getter
    private List<ImportType> importTypes;

    @Getter
    @Setter
    private Prefs prefs;
    @Getter
    @Setter
    private String importFolder;

    @Setter
    private MassImportForm form;

    @Setter
    private boolean testMode = false;

    @Getter
    @Setter
    private File file;

    @Setter
    private String workflowName;

    private String collection;
    private String doctype;

    private String folderStructure;
    private String letter;
    private String letterPosition;
    private String tray;
    private String trayPosition;
    private String position;

    private String importRootFolder;
    // remove this after plugin changes from basex to database store
    private boolean generateEadFile;
    private List<String> backsideScans;

    // number of card nodes that are collected before they are written to the database
    private static final int EAD_BATCH_SIZE = 500;

    // caches the listing of the folder that is currently processed, see listFolder(String)
    private String cachedFolder;
    private List<String> cachedFolderContent;

    private static Pattern letterIndexFilePattern = Pattern.compile("([A-Z]\\/?J?)\\s+(\\d+)");
    private static Pattern trayIndexFilePattern = Pattern.compile("(\\d+)\\s(\\w+)\\s(\\d+)\\s(\\d+)");

    @Getter
    private IArchiveManagementAdministrationPlugin archivePlugin;

    /**
     * define what kind of import plugin this is
     */
    public KatzoomImportPlugin() {
        importTypes = new ArrayList<>();
        importTypes.add(ImportType.FOLDER);
    }

    /**
     * read the configuration file
     */
    private void readConfig() {
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration myconfig = null;
        try {
            myconfig = xmlConfig.configurationAt("//config[./template = '" + workflowName + "']");
        } catch (IllegalArgumentException e) {
            myconfig = xmlConfig.configurationAt("//config[./template = '*']");
        }

        if (myconfig != null) {
            importRootFolder = myconfig.getString("/importRootFolder", "");

            generateEadFile = myconfig.getBoolean("/generateEadFile", true);

            collection = myconfig.getString("/collection", "");

            backsideScans = Arrays.asList(myconfig.getStringArray("/backsideScan"));

            doctype = myconfig.getString("/doctype");
            folderStructure = myconfig.getString("/folderStructure");
            letter = myconfig.getString("/letter");
            letterPosition = myconfig.getString("/letterPosition");
            tray = myconfig.getString("/tray");
            trayPosition = myconfig.getString("/trayPosition");
            position = myconfig.getString("/position");

        }
    }

    /**
     * This method is used to generate records based on the imported data these records will then be used later to generate the Goobi processes
     */
    @Override
    public List<Record> generateRecordsFromFile() {
        return Collections.emptyList();
    }

    /**
     * This method is used to actually create the Goobi processes this is done based on previously created records
     */
    @Override
    public List<ImportObject> generateFiles(List<Record> records) {

        readConfig();
        List<ImportObject> answer = new ArrayList<>();

        // some general preparations
        DocStructType physicalType = prefs.getDocStrctTypeByName("BoundBook");
        DocStructType logicalType = prefs.getDocStrctTypeByName(doctype);
        DocStructType pageType = prefs.getDocStrctTypeByName("page");

        MetadataType pathimagefilesType = prefs.getMetadataTypeByName("pathimagefiles");
        MetadataType idType = prefs.getMetadataTypeByName("CatalogIDDigital");
        MetadataType collectionType = prefs.getMetadataTypeByName("singleDigCollection");

        MetadataType folderStructureType = prefs.getMetadataTypeByName(folderStructure);
        MetadataType letterType = prefs.getMetadataTypeByName(letter);
        MetadataType letterPositionType = prefs.getMetadataTypeByName(letterPosition);
        MetadataType trayType = prefs.getMetadataTypeByName(tray);
        MetadataType trayPositionType = prefs.getMetadataTypeByName(trayPosition);
        MetadataType positionType = prefs.getMetadataTypeByName(position);

        for (Record rec : records) {
            ImportObject io = new ImportObject();

            KatzoomImportObject kip = (KatzoomImportObject) rec.getObject();

            List<String> files = resolveFiles(kip);
            String filename = files.get(0);
            // get process title
            String processName = kip.getLabel();
            io.setProcessTitle(processName);

            io.setMetsFilename(importFolder + "/" + processName + ".xml");

            // folder structure
            Path folder = Paths.get(filename).getParent();
            String last = folder.getFileName().toString();
            String prev = folder.getParent().getFileName().toString();
            String third = folder.getParent().getParent().getFileName().toString();
            try {
                Fileformat fileformat = new MetsMods(prefs);
                DigitalDocument dd = new DigitalDocument();
                fileformat.setDigitalDocument(dd);

                DocStruct logical = dd.createDocStruct(logicalType);
                dd.setLogicalDocStruct(logical);
                // identifier
                Metadata id = new Metadata(idType);
                id.setValue(processName);
                logical.addMetadata(id);
                // collection
                if (StringUtils.isNotBlank(collection)) {
                    Metadata md = new Metadata(collectionType);
                    md.setValue(collection);
                    logical.addMetadata(md);
                }

                // folder structure
                Metadata folderMd = new Metadata(folderStructureType);
                folderMd.setValue(third + "/" + prev + "/" + last);
                logical.addMetadata(folderMd);
                // letter
                Metadata letterMd = new Metadata(letterType);
                letterMd.setValue(kip.getLetterName());
                logical.addMetadata(letterMd);
                Metadata letterPos = new Metadata(letterPositionType);
                letterPos.setValue(String.valueOf(kip.getLetterPosition()));
                logical.addMetadata(letterPos);
                // tray
                if (StringUtils.isNotBlank(kip.getTrayName())) {
                    Metadata trayMd = new Metadata(trayType);
                    trayMd.setValue(kip.getTrayName());
                    logical.addMetadata(trayMd);
                    Metadata trayPositionMd = new Metadata(trayPositionType);
                    trayPositionMd.setValue(String.valueOf(kip.getTrayPosition()));
                    logical.addMetadata(trayPositionMd);
                }

                // position
                Metadata pos = new Metadata(positionType);
                pos.setValue(String.valueOf(kip.getTotalPosition()));
                logical.addMetadata(pos);

                DocStruct physical = dd.createDocStruct(physicalType);
                dd.setPhysicalDocStruct(physical);
                Metadata path = new Metadata(pathimagefilesType);
                path.setValue(processName);
                physical.addMetadata(path);

                Path masterFolder = copyFiles(files, processName);

                List<Path> filesInMaster = StorageProvider.getInstance().listFiles(masterFolder.toString());
                int currentPhysicalOrder = 0;
                for (Path p : filesInMaster) {
                    // create new page element for each image file
                    DocStruct page = dd.createDocStruct(pageType);
                    page.setImageName(p.getFileName().toString());

                    MetadataType mdt = prefs.getMetadataTypeByName("physPageNumber");
                    Metadata mdTemp = new Metadata(mdt);
                    mdTemp.setValue(String.valueOf(++currentPhysicalOrder));
                    page.addMetadata(mdTemp);

                    // logical page no
                    mdt = prefs.getMetadataTypeByName("logicalPageNumber");
                    mdTemp = new Metadata(mdt);
                    mdTemp.setValue("uncounted");

                    page.addMetadata(mdTemp);
                    physical.addChild(page);
                    logical.addReferenceTo(page, "logical_physical");
                }
                // add metadata

                fileformat.write(io.getMetsFilename());
            } catch (UGHException | IOException e) {
                log.error(e);
            }

            answer.add(io);
        }

        return answer;
    }

    public void generateEadStructure(List<Record> records, String filename) {

        if (records.isEmpty()) {
            return;
        }
        if (StringUtils.isEmpty(filename)) {
            return;
        }

        // open archive plugin, create new ead file

        IPlugin ia = PluginLoader.getPluginByTitle(PluginType.Administration, "intranda_administration_archive_management");
        archivePlugin = (IArchiveManagementAdministrationPlugin) ia;

        archivePlugin.setDatabaseName(filename);
        archivePlugin.createNewDatabase();
        INodeType fileType = null;
        INodeType folderType = null;

        for (INodeType nodeType : archivePlugin.getConfig().getConfiguredNodes()) {
            if ("folder".equals(nodeType.getNodeName())) {
                folderType = nodeType;
            } else if ("file".equals(nodeType.getNodeName())) {
                fileType = nodeType;
            }
        }

        IEadEntry rootEntry = archivePlugin.getRootElement();
        rootEntry.setNodeType(folderType);
        setFields(rootEntry, filename, "unittitle");
        // createNewDatabase() stored the root before its title was known
        archivePlugin.saveNodes(Collections.singletonList(rootEntry));

        // remember the letter and tray nodes by their name. Looking them up in the sub entry list would not work, because the card nodes are
        // detached from the tree as soon as they are stored.
        Map<String, IEadEntry> folderNodes = new HashMap<>();
        // number of cards that were already added below a letter or tray node
        Map<String, Integer> cardsPerFolderNode = new HashMap<>();

        // the card nodes are collected and written in batches, one statement per node would mean one database round trip per card
        List<IEadEntry> unsavedNodes = new ArrayList<>();

        for (Record rec : records) {
            KatzoomImportObject kip = (KatzoomImportObject) rec.getObject();
            // find or create the subnode of the root for the current letter
            IEadEntry letterNode = folderNodes.get(kip.getLetterName());
            if (letterNode == null) {
                letterNode = addFolderNode(rootEntry, folderType, kip.getLetterName());
                folderNodes.put(kip.getLetterName(), letterNode);
            }

            // if current data uses trays, find or create the tray below the letter
            IEadEntry parentNode = letterNode;
            String parentName = kip.getLetterName();
            if (StringUtils.isNotBlank(kip.getTrayName())) {
                parentName = kip.getLetterName() + "/" + kip.getTrayName();
                IEadEntry trayNode = folderNodes.get(parentName);
                if (trayNode == null) {
                    trayNode = addFolderNode(letterNode, folderType, kip.getTrayName());
                    folderNodes.put(parentName, trayNode);
                }
                parentNode = trayNode;
            }

            // create the node of the card within the letter or tray
            IEadEntry node = archivePlugin.addNodeWithoutSaving(parentNode);
            node.setNodeType(fileType);
            node.setGoobiProcessTitle(kip.getLabel());
            setFields(node, kip.getLabel(), "unittitle", "unitid");

            // the order can not be derived from the number of children, the card nodes do not stay in the tree
            node.setOrderNumber(cardsPerFolderNode.merge(parentName, 1, Integer::sum) - 1);

            // a card index can hold several hundred thousand cards, their nodes must not pile up in the tree
            parentNode.removeSubEntry(node);
            unsavedNodes.add(node);
            if (unsavedNodes.size() >= EAD_BATCH_SIZE) {
                archivePlugin.saveNodes(unsavedNodes);
                unsavedNodes.clear();
            }
        }
        if (!unsavedNodes.isEmpty()) {
            archivePlugin.saveNodes(unsavedNodes);
            unsavedNodes.clear();
        }

        archivePlugin.setSelectedEntry(rootEntry);
    }

    /**
     * create a new folder node with the given title below the parent node. Letters and trays are stored right away, because the nodes of their cards
     * reference them as parent.
     */
    private IEadEntry addFolderNode(IEadEntry parentNode, INodeType folderType, String title) {
        IEadEntry node = archivePlugin.addNodeWithoutSaving(parentNode);
        node.setNodeType(folderType);
        setFields(node, title, "unittitle");
        archivePlugin.saveNodes(Collections.singletonList(node));
        return node;
    }

    /**
     * write the given value into the named metadata fields of a node
     */
    private void setFields(IEadEntry node, String value, String... fieldNames) {
        List<String> names = Arrays.asList(fieldNames);
        for (IMetadataField meta : node.getIdentityStatementAreaList()) {
            if (names.contains(meta.getName())) {
                if (!meta.isFilled()) {
                    meta.addValue();
                }
                meta.getValues().get(0).setValue(value);
            }
        }
    }

    private Path copyFiles(List<String> files, String processName) throws IOException {
        // create folder structure

        ConfigurationHelper config = ConfigurationHelper.getInstance();

        Path processFolder = Paths.get(importFolder, processName);
        Path mediaFolder = Paths.get(processFolder.toString(), "images", getFolderName(config.getProcessImagesMainDirectoryName(), processName));
        Path masterFolder =
                Paths.get(processFolder.toString(), "images", getFolderName(config.getProcessImagesMasterDirectoryName(), processName));

        Path textFolder = Paths.get(processFolder.toString(), "ocr", getFolderName(config.getProcessOcrTxtDirectoryName(), processName));
        Path pdfFolder = Paths.get(processFolder.toString(), "ocr", getFolderName(config.getProcessOcrPdfDirectoryName(), processName));
        StorageProvider.getInstance().createDirectories(mediaFolder);
        StorageProvider.getInstance().createDirectories(masterFolder);
        StorageProvider.getInstance().createDirectories(textFolder);
        StorageProvider.getInstance().createDirectories(pdfFolder);

        for (String fileToImport : files) {
            Path fileToCopy = Paths.get(fileToImport);
            // tif -> images/master
            if (fileToImport.endsWith(".tif")) {
                StorageProvider.getInstance().copyFile(fileToCopy, Paths.get(masterFolder.toString(), fileToCopy.getFileName().toString()));
            }
            // png -> images/media
            else if (fileToImport.endsWith(".png")) {
                StorageProvider.getInstance().copyFile(fileToCopy, Paths.get(mediaFolder.toString(), fileToCopy.getFileName().toString()));
            }
            // txt -> ocr/text
            else if (fileToImport.endsWith(".txt")) {
                StorageProvider.getInstance().copyFile(fileToCopy, Paths.get(textFolder.toString(), fileToCopy.getFileName().toString()));
            }
            // pdf -> ocr/pdf
            else if (fileToImport.endsWith(".pdf")) {
                StorageProvider.getInstance().copyFile(fileToCopy, Paths.get(pdfFolder.toString(), fileToCopy.getFileName().toString()));
            }
        }
        return masterFolder;
    }

    /**
     * resolve a configured folder name rule like '{processtitle}_txt' for the given process title
     */
    private String getFolderName(String folderNameRule, String processName) {
        return folderNameRule.replace("{processtitle}", processName);
    }

    /**
     * decide if the import shall be executed in the background via GoobiScript or not
     */
    @Override
    public boolean isRunnableAsGoobiScript() {
        return false;
    }

    /* *************************************************************** */
    /*                                                                 */
    /* the following methods are mostly not needed for typical imports */
    /*                                                                 */
    /* *************************************************************** */

    @Override
    public List<Record> splitRecords(String string) {
        return Collections.emptyList();
    }

    @Override
    public List<String> splitIds(String ids) {
        return Collections.emptyList();
    }

    @Override
    public String addDocstruct() {
        return null;
    }

    @Override
    public String deleteDocstruct() {
        return null;
    }

    @Override
    public void deleteFiles(List<String> arg0) {
        // do nothing
    }

    @Override
    public List<Record> generateRecordsFromFilenames(List<String> indexes) {
        List<Record> records = new ArrayList<>();
        // run through each selected index
        for (String index : indexes) {
            log.debug("Read index  {}", index);
            boolean backsideScanned = backsideScans.contains(index);
            Path folder = Paths.get(importRootFolder, index);
            // load *.ind file to check letter index (format it: new line after each number)
            // load *.lli file to check tray index (does not exist for every index)
            String letterIndexFile = null;
            String trayIndexFile = null;
            for (String fileInFolder : StorageProvider.getInstance().list(folder.toString(), NIOFileUtils.fileFilter)) {
                if (fileInFolder.endsWith(".ind") && !fileInFolder.contains("adm")) {
                    letterIndexFile = fileInFolder;
                } else if (fileInFolder.endsWith(".lli")) {
                    trayIndexFile = fileInFolder;
                }
            }
            List<LetterIndex> letterIndex = readLetterIndexFile(folder, letterIndexFile);
            log.debug("letter size: {}", letterIndex.size());
            List<TrayIndex> trayIndex = readTrayIndexFile(folder, trayIndexFile);
            log.debug("tray size: {}", trayIndex.size());

            // scan all sub folders and remember for each card in which folder its files are located. The file names themselves are not kept, they
            // are resolved from the folder listing when they are needed. Files always follow the pattern letter - number - .extension.
            Map<Integer, String> contentMap = new TreeMap<>(); // TreeMap to sort entries by key
            Map<Integer, List<String>> spreadCards = new HashMap<>();
            Map<String, String> knownFolders = new HashMap<>(); // makes sure that every folder name exists only once in memory

            try (Stream<Path> foundFiles = Files.find(folder, 5, (p, found) -> found.isRegularFile())) {
                foundFiles.forEach(p -> collectCardFolder(p, backsideScanned, contentMap, spreadCards, knownFolders));
            } catch (IOException e) {
                log.error(e);
            }

            log.debug("Number of folders: {}", knownFolders.size());

            log.debug("Start record generation");
            List<Record> indexRecords = new ArrayList<>();
            int totalPosition = 0;
            for (Entry<Integer, String> entry : contentMap.entrySet()) {
                // get position in total index
                totalPosition++;
                // find correct letter based on position
                LetterIndex ind = findLetterIndexForPosition(totalPosition, letterIndex);
                String currentLetter = ind.getLetter();
                // get position within letter
                int positionInLetterIndex = ind.getCurrentPosition();
                ind.setCurrentPosition(positionInLetterIndex + 1);

                // find correct tray based on position
                TrayIndex ind2 = findTrayIndexForPosition(totalPosition, trayIndex);

                String currentTray = "";
                int positionInTrayIndex = 0;
                if (ind2 != null) {
                    currentTray = ind2.getTrayName();
                    // get position within tray
                    positionInTrayIndex = ind2.getCurrentPosition();
                    ind2.setCurrentPosition(positionInTrayIndex + 1);
                }

                KatzoomImportObject kip = new KatzoomImportObject();
                kip.setId(entry.getKey());
                kip.setTotalPosition(totalPosition);

                kip.setLetterName(currentLetter);
                kip.setLetterPosition(positionInLetterIndex);

                kip.setTrayName(currentTray);
                kip.setTrayPosition(positionInTrayIndex);

                kip.setFolder(entry.getValue());
                kip.setBacksideScanned(backsideScanned);
                kip.setAdditionalFolders(spreadCards.get(entry.getKey()));

                // get process title
                List<String> files = resolveFiles(kip);
                String filename = Paths.get(files.get(0)).getFileName().toString();
                kip.setLabel(filename.substring(0, filename.indexOf('.')));
                Record rec = new Record();
                rec.setId(String.valueOf(entry.getKey()));
                rec.setData(rec.getId());
                rec.setObject(kip);
                indexRecords.add(rec);
            }

            log.debug("Generated {} records", totalPosition);
            if (generateEadFile) {
                log.debug("Start ead archive creation");
                // only the records of the current index belong into this archive
                generateEadStructure(indexRecords, index);
                // the archive plugin holds the node tree, it must not stay alive while the processes are created
                archivePlugin = null;
                log.debug("Finished archive creation");
            }
            records.addAll(indexRecords);
        }

        return records;
    }

    /**
     * remember in which folder the files of a card are located. Only the folder is kept, the file names are resolved again when they are needed.
     */
    private void collectCardFolder(Path file, boolean backsideScanned, Map<Integer, String> contentMap, Map<Integer, List<String>> spreadCards,
            Map<String, String> knownFolders) {
        int id = getCardId(file.getFileName().toString(), backsideScanned);
        if (id < 0) {
            return;
        }
        // reuse the folder name, all cards of a folder share the same instance
        String cardFolder = knownFolders.computeIfAbsent(file.getParent().toString(), name -> name);

        String knownFolder = contentMap.get(id);
        if (knownFolder == null) {
            contentMap.put(id, cardFolder);
        } else if (!knownFolder.equals(cardFolder)) {
            // the files of this card are spread over several folders, which is not expected. Remember the additional folders, so that no file gets
            // lost when they are resolved later on.
            List<String> additionalFolders = spreadCards.computeIfAbsent(id, key -> new ArrayList<>());
            if (!additionalFolders.contains(cardFolder)) {
                additionalFolders.add(cardFolder);
            }
        }
    }

    /**
     * get the id of the card a file belongs to, or -1 if the file is not part of a card.
     *
     * Files always follow the pattern letter - number - .extension. If the back side was scanned as well, the even number belongs to the card with
     * the previous, odd number.
     */
    private static int getCardId(String filename, boolean backsideScanned) {
        if (!filename.matches("\\w\\d+\\.\\w+")) {
            return -1;
        }
        int id = Integer.parseInt(filename.substring(1, filename.indexOf('.')));
        if (backsideScanned && (id % 2 == 0)) {
            id = id - 1;
        }
        return id;
    }

    /**
     * get all files that belong to the given card. The folder of the card holds the files of 50 - 100 cards, the card id selects the right ones. The
     * folder listing is cached, because the cards are processed in the same order in which they are stored in the folders.
     */
    protected List<String> resolveFiles(KatzoomImportObject kip) {
        List<String> files = collectFilesInFolder(kip, kip.getFolder());
        if (kip.getAdditionalFolders() != null) {
            for (String additionalFolder : kip.getAdditionalFolders()) {
                files.addAll(collectFilesInFolder(kip, additionalFolder));
            }
        }
        Collections.sort(files);
        return files;
    }

    private List<String> collectFilesInFolder(KatzoomImportObject kip, String folderName) {
        List<String> files = new ArrayList<>();
        for (String filename : listFolder(folderName)) {
            if (getCardId(filename, kip.isBacksideScanned()) == kip.getId()) {
                files.add(Paths.get(folderName, filename).toString());
            }
        }
        return files;
    }

    /**
     * list the content of a folder, caching the last listing. All cards of a folder are processed in a row, so a single cached listing is enough to
     * read every folder only once.
     */
    private List<String> listFolder(String folderName) {
        if (!folderName.equals(cachedFolder)) {
            cachedFolderContent = StorageProvider.getInstance().list(folderName, NIOFileUtils.fileFilter);
            cachedFolder = folderName;
        }
        return cachedFolderContent;
    }

    private TrayIndex findTrayIndexForPosition(int position, List<TrayIndex> trayIndex) {
        if (trayIndex.isEmpty()) {
            return null;
        }
        TrayIndex current = null;
        if (position == 1) {
            current = trayIndex.get(0);
        } else {
            for (TrayIndex li : trayIndex) {
                if (position > li.getStartPosition()) {
                    current = li;
                }
            }
        }
        return current;
    }

    private LetterIndex findLetterIndexForPosition(int position, List<LetterIndex> letterIndex) {
        LetterIndex current = null;
        if (position == 1) {
            current = letterIndex.get(0);
        } else {
            for (LetterIndex li : letterIndex) {
                if (position > li.getStartPosition()) {
                    current = li;
                }
            }
        }
        return current;
    }

    private List<LetterIndex> readLetterIndexFile(Path folder, String indexFileName) {
        List<LetterIndex> index = new ArrayList<>();
        if (indexFileName == null) {
            // missing file, abort
            return Collections.emptyList();
        }
        try {
            String indexFileContent = Files.readString(Paths.get(folder.toString(), indexFileName));

            Matcher matcher = letterIndexFilePattern.matcher(indexFileContent);
            while (matcher.find()) {
                MatchResult mr = matcher.toMatchResult();
                index.add(new LetterIndex(mr.group(1), Integer.valueOf(mr.group(2)), 1));
            }

        } catch (IOException e) {
            log.error(e);
        }
        return index;
    }

    private List<TrayIndex> readTrayIndexFile(Path folder, String indexFileName) {
        List<TrayIndex> index = new ArrayList<>();
        if (indexFileName == null) {
            // missing file, abort
            return Collections.emptyList();
        }

        Path p = Paths.get(folder.toString(), indexFileName);

        try {
            List<String> content = Files.readAllLines(p, StandardCharsets.ISO_8859_1);
            for (String line : content) {
                Matcher matcher = trayIndexFilePattern.matcher(line);

                while (matcher.find()) {
                    MatchResult mr = matcher.toMatchResult();
                    int order = Integer.parseInt(mr.group(1));
                    String label = mr.group(2);
                    int startPosition = Integer.parseInt(mr.group(3));
                    int numberOfEntries = Integer.parseInt(mr.group(4));
                    index.add(new TrayIndex(label, order, startPosition, numberOfEntries, 1));
                }
            }

        } catch (IOException e) {
            log.error(e);
        }
        return index;
    }

    @Override
    public List<String> getAllFilenames() {
        readConfig();
        // display content of import folder, it should contain a list of all card indexes
        return StorageProvider.getInstance().list(importRootFolder);
    }

    @Override
    public List<? extends DocstructElement> getCurrentDocStructs() {
        return null; //NOSONAR
    }

    @Override
    public DocstructElement getDocstruct() {
        return null;
    }

    @Override
    public List<String> getPossibleDocstructs() {
        return null; //NOSONAR
    }

    @Override
    public String getProcessTitle() {
        return null;
    }

    @Override
    public List<ImportProperty> getProperties() {
        return null; //NOSONAR
    }

    @Override
    public void setData(Record arg0) {
        // do nothing
    }

    @Override
    public void setDocstruct(DocstructElement arg0) {
        // do nothing
    }

    @Override
    public Fileformat convertData() throws ImportPluginException {
        return null;
    }

    @Override
    public void setWorkflowTitle(String workflowTitle) {
        workflowName = workflowTitle;
    }

}