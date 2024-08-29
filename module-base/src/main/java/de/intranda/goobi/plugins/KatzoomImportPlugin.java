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
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

            List<String> files = kip.getFiles();
            Collections.sort(files);
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

        for (INodeType nodeType : archivePlugin.getConfiguredNodes()) {
            if ("folder".equals(nodeType.getNodeName())) {
                folderType = nodeType;
            } else if ("file".equals(nodeType.getNodeName())) {
                fileType = nodeType;
            }
        }

        IEadEntry rootEntry = archivePlugin.getRootElement();
        rootEntry.setNodeType(folderType);
        for (IMetadataField meta : rootEntry.getIdentityStatementAreaList()) {
            if ("unittitle".equals(meta.getName())) {
                if (!meta.isFilled()) {
                    meta.addValue();
                }
                meta.getValues().get(0).setValue(filename);
            }
        }

        for (Record rec : records) {
            KatzoomImportObject kip = (KatzoomImportObject) rec.getObject();
            // find subnode in root for current letter
            IEadEntry letterNode = null;
            IEadEntry trayNode = null;
            for (IEadEntry e : rootEntry.getSubEntryList()) {
                if (e.getLabel().equals(kip.getLetterName())) {
                    letterNode = e;
                    break;
                }
            }
            // if subnode does not exist, create it
            if (letterNode == null) {
                // select root entry
                archivePlugin.setSelectedEntry(rootEntry);
                // create new node
                archivePlugin.addNode();
                letterNode = archivePlugin.getSelectedEntry();
                letterNode.setNodeType(folderType);

                for (IMetadataField meta : letterNode.getIdentityStatementAreaList()) {
                    if ("unittitle".equals(meta.getName())) {
                        if (!meta.isFilled()) {
                            meta.addValue();
                        }
                        meta.getValues().get(0).setValue(kip.getLetterName());
                    }
                }

            }
            // if current data uses trays
            if (StringUtils.isNotBlank(kip.getTrayName())) {
                // find tray node in letter sub nodes
                for (IEadEntry e : letterNode.getSubEntryList()) {
                    if (e.getLabel().equals(kip.getTrayName())) {
                        trayNode = e;
                        break;
                    }
                }
                // if subnode does not exist, create it
                if (trayNode == null) {
                    // select root entry
                    archivePlugin.setSelectedEntry(letterNode);
                    // create new node
                    archivePlugin.addNode();
                    trayNode = archivePlugin.getSelectedEntry();
                    trayNode.setNodeType(folderType);

                    for (IMetadataField meta : trayNode.getIdentityStatementAreaList()) {
                        if ("unittitle".equals(meta.getName())) {
                            if (!meta.isFilled()) {
                                meta.addValue();
                            }
                            meta.getValues().get(0).setValue(kip.getTrayName());
                        }
                    }
                }
            }
            // create new node within subnode

            if (trayNode != null) {
                archivePlugin.setSelectedEntry(trayNode);
            } else {
                archivePlugin.setSelectedEntry(letterNode);
            }
            archivePlugin.addNode();
            IEadEntry node = archivePlugin.getSelectedEntry();
            node.setNodeType(fileType);
            node.setGoobiProcessTitle(kip.getLabel());

            for (IMetadataField meta : node.getIdentityStatementAreaList()) {
                if ("unittitle".equals(meta.getName()) || "unitid".equals(meta.getName())) {
                    if (!meta.isFilled()) {
                        meta.addValue();
                    }
                    meta.getValues().get(0).setValue(kip.getLabel());
                }
            }
        }
        archivePlugin.setSelectedEntry(rootEntry);
    }

    private Path copyFiles(List<String> files, String processName) throws IOException {
        // create folder structure

        Path processFolder = Paths.get(importFolder, processName);
        Path mediaFolder = Paths.get(processFolder.toString(), "images", processName + "_media");
        Path masterFolder = Paths.get(processFolder.toString(), "images", processName + "_master");

        Path textFolder = Paths.get(processFolder.toString(), "ocr", processName + "txt");
        Path pdfFolder = Paths.get(processFolder.toString(), "ocr", processName + "_pdf");
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
            List<TrayIndex> trayIndex = readTrayIndexFile(folder, trayIndexFile);

            // get the actual content from all sub folders
            List<Path> allFiles = new ArrayList<>();

            try {
                Files.find(folder, 5,
                        (p, found) -> found.isRegularFile())
                        .forEach(allFiles::add);
            } catch (IOException e) {
                log.error(e);
            }

            Map<Integer, List<String>> contentMap = new TreeMap<>(); // TreeMap to sort entries by key
            // files always follow the pattern letter - number - .extension

            for (Path p : allFiles) {
                String filename = p.getFileName().toString();
                if (filename.matches("\\w\\d+\\.\\w+")) {
                    Integer id = Integer.parseInt(filename.substring(1, filename.indexOf(".")));

                    // if back side was scanned and we have an even number, than the common identifier is number -1
                    if (backsideScanned && (id % 2 == 0)) {
                        id = id - 1;
                    }
                    // add file to the list grouped by the common number
                    List<String> files = contentMap.getOrDefault(id, new ArrayList<>());
                    files.add(p.toString());
                    contentMap.put(id, files);
                }
            }

            int totalPosition = 0;
            for (Entry<Integer, List<String>> entry : contentMap.entrySet()) {
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

                kip.setFiles(entry.getValue());

                List<String> files = kip.getFiles();
                Collections.sort(files);
                String filename = files.get(0);
                // get process title
                String processName = filename.substring(filename.lastIndexOf("/") + 1, filename.indexOf("."));
                kip.setLabel(processName);
                Record rec = new Record();
                rec.setId(String.valueOf(entry.getKey()));
                rec.setData(rec.getId());
                rec.setObject(kip);
                records.add(rec);
            }
            if (generateEadFile) {
                generateEadStructure(records, index);
            }
        }

        return records;
    }

    private TrayIndex findTrayIndexForPosition(int position, List<TrayIndex> trayIndex) {
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

}