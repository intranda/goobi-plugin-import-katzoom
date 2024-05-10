package de.intranda.goobi.plugins;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import org.goobi.production.plugin.interfaces.IImportPluginVersion2;
import org.goobi.production.properties.ImportProperty;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.forms.MassImportForm;
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
public class KatzoomImportPlugin implements IImportPluginVersion2 {

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
    private String workflowTitle;

    private boolean runAsGoobiScript = false;
    private String collection;

    private String importRootFolder;

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

        // TODO: configure for each index, if back side was was scanned

        SubnodeConfiguration myconfig = null;
        try {
            myconfig = xmlConfig.configurationAt("//config[./template = '" + workflowTitle + "']");
        } catch (IllegalArgumentException e) {
            myconfig = xmlConfig.configurationAt("//config[./template = '*']");
        }

        if (myconfig != null) {
            importRootFolder = myconfig.getString("/importRootFolder", "");

            runAsGoobiScript = myconfig.getBoolean("/runAsGoobiScript", false);
            collection = myconfig.getString("/collection", "");
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
        if (StringUtils.isBlank(workflowTitle)) {
            workflowTitle = form.getTemplate().getTitel();
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
        //TODO
        // run through each selected index
        // load *.ind file to check letter index (format it: new line after each number)
        // load *.lli file to check tray index (does not exist for every index)

        // get content from all sub folders

        // order by name

        // for each file prefix (or every second, if back side is scanned)

        // collect all files with the same prefix (and +1 if back is scanned)

        // create process

        // get position in total index

        // find correct letter based on position

        // find correct tray based on position

        // get position within letter

        // get position within tray

        return null;
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