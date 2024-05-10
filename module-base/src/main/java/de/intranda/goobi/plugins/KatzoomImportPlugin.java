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
import org.apache.commons.lang.StringUtils;
import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.DocstructElement;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.interfaces.IImportPluginVersion3;
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
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;

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

    private boolean runAsGoobiScript = false;
    private String collection;

    private String importRootFolder;

    private List<String> backsideScans;

    private static Pattern letterIndexFilePattern = Pattern.compile("([A-Z]\\/?J?)\\s+(\\d+)");
    private static Pattern trayIndexFilePattern = Pattern.compile("(\\d+)\\s(\\w+)\\s(\\d+)\\s(\\d+)");

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

            runAsGoobiScript = myconfig.getBoolean("/runAsGoobiScript", false);
            collection = myconfig.getString("/collection", "");

            backsideScans = Arrays.asList(myconfig.getStringArray("/backsideScan"));
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
        if (StringUtils.isBlank(workflowName)) {
            workflowName = form.getTemplate().getTitel();
        }
        readConfig();

        // some general preparations
        DocStructType physicalType = prefs.getDocStrctTypeByName("BoundBook");
        DocStructType logicalType = prefs.getDocStrctTypeByName("Monograph");
        MetadataType pathimagefilesType = prefs.getMetadataTypeByName("pathimagefiles");
        List<ImportObject> answer = new ArrayList<>();

        return answer;
    }

    /**
     * decide if the import shall be executed in the background via GoobiScript or not
     */
    @Override
    public boolean isRunnableAsGoobiScript() {
        readConfig();
        return runAsGoobiScript;
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
                        .forEach(p -> allFiles.add(p));
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

            int position = 0;
            for (Entry<Integer, List<String>> entry : contentMap.entrySet()) {
                // get position in total index
                position++;
                // find correct letter based on position
                LetterIndex ind = findLetterIndexForPosition(position, letterIndex);
                String currentLetter = ind.getLetter();
                // get position within letter
                int positionInLetterIndex = ind.getCurrentPosition();
                ind.setCurrentPosition(positionInLetterIndex + 1);

                // find correct tray based on position
                TrayIndex ind2 = findTrayIndexForPosition(position, trayIndex);

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
                kip.setTotalPosition(position);

                kip.setLetterName(currentLetter);
                kip.setLetterPosition(positionInLetterIndex);

                kip.setTrayName(currentTray);
                kip.setTrayPosition(positionInTrayIndex);

                kip.setFiles(entry.getValue());

                Record rec = new Record();
                rec.setId(String.valueOf(entry.getKey()));
                rec.setData(rec.getId());
                rec.setObject(kip);
                records.add(rec);
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