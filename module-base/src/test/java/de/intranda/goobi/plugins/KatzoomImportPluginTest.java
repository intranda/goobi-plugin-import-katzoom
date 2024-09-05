package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.easymock.EasyMock;
import org.goobi.interfaces.IArchiveManagementAdministrationPlugin;
import org.goobi.interfaces.IEadEntry;
import org.goobi.interfaces.IFieldValue;
import org.goobi.interfaces.IMetadataField;
import org.goobi.interfaces.INodeType;
import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.PluginLoader;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import de.sub.goobi.config.ConfigurationHelper;
import ugh.dl.DocStruct;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ ConfigurationHelper.class, PluginLoader.class })
@PowerMockIgnore({ "javax.management.*", "javax.net.ssl.*", "jdk.internal.reflect.*" })
public class KatzoomImportPluginTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();
    private File tempFolder;
    private static String resourcesFolder;

    @BeforeClass
    public static void setUpClass() throws Exception {
        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse

        if (!Files.exists(Paths.get(resourcesFolder))) {
            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
        }

        String log4jFile = resourcesFolder + "log4j2.xml"; // for junit tests in eclipse

        System.setProperty("log4j.configurationFile", log4jFile);
    }

    @Before
    public void setUp() throws Exception {
        tempFolder = folder.newFolder("tmp");

        resourcesFolder = "src/test/resources/"; // for junit tests in eclipse

        if (!Files.exists(Paths.get(resourcesFolder))) {
            resourcesFolder = "target/test-classes/"; // to run mvn test from cli or in jenkins
        }

        PowerMock.mockStatic(ConfigurationHelper.class);

        ConfigurationHelper configurationHelper = EasyMock.createMock(ConfigurationHelper.class);
        EasyMock.expect(ConfigurationHelper.getInstance()).andReturn(configurationHelper).anyTimes();
        EasyMock.expect(configurationHelper.getConfigurationFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.useS3()).andReturn(false).anyTimes();
        EasyMock.replay(configurationHelper);
        PowerMock.replay(ConfigurationHelper.class);

    }

    @Test
    public void testConstructor() {
        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        assertNotNull(plugin);
        assertEquals(ImportType.FOLDER, plugin.getImportTypes().get(0));
        plugin.setImportFolder(tempFolder.getAbsolutePath());
    }

    @Test
    public void testGetAllFilenames() {
        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        List<String> folderList = plugin.getAllFilenames();
        assertEquals(1, folderList.size());
        assertEquals("nka BKA Nominal", folderList.get(0));
    }

    @Test
    public void testGenerateRecordsFromFilenames() {
        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        List<String> folderList = plugin.getAllFilenames();
        List<Record> recordList = plugin.generateRecordsFromFilenames(folderList);
        assertEquals(500, recordList.size());

        // first object
        Record rec = recordList.get(0);
        KatzoomImportObject kip = (KatzoomImportObject) rec.getObject();
        assertEquals("1", rec.getId());
        assertEquals(1, kip.getTotalPosition());
        assertEquals("A", kip.getLetterName());
        assertEquals(1, kip.getLetterPosition());
        assertEquals("A", kip.getTrayName());
        assertEquals(1, kip.getTrayPosition());
        assertEquals(9, kip.getFiles().size());
        // last in 'A'
        rec = recordList.get(199);
        kip = (KatzoomImportObject) rec.getObject();
        assertEquals("399", rec.getId());
        assertEquals(200, kip.getTotalPosition());
        assertEquals("A", kip.getLetterName());
        assertEquals(200, kip.getLetterPosition());
        assertEquals("Ahammer", kip.getTrayName());
        assertEquals(12, kip.getTrayPosition());

        // first in 'B'
        rec = recordList.get(200);
        kip = (KatzoomImportObject) rec.getObject();
        assertEquals("401", rec.getId());
        assertEquals("B", kip.getLetterName());
        assertEquals(1, kip.getLetterPosition());

        // last entry
        rec = recordList.get(499);
        kip = (KatzoomImportObject) rec.getObject();
        assertEquals("999", rec.getId());
        assertEquals(500, kip.getTotalPosition());
        assertEquals("B", kip.getLetterName());
        assertEquals(300, kip.getLetterPosition());
        assertEquals("Amon", kip.getTrayName());
        assertEquals(112, kip.getTrayPosition());
    }

    @Test
    public void testGenerateFiles() throws Exception {
        File importFolder = folder.newFolder();
        importFolder.mkdir();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.setImportFolder(importFolder.getAbsolutePath());
        Prefs prefs = new Prefs();
        prefs.loadPrefs(resourcesFolder + "ruleset.xml");
        plugin.setPrefs(prefs);

        List<String> folderList = plugin.getAllFilenames();
        List<Record> recordList = plugin.generateRecordsFromFilenames(folderList);

        List<ImportObject> imports = plugin.generateFiles(recordList.subList(0, 10));
        assertEquals(10, imports.size());

        ImportObject io = imports.get(0);
        assertEquals("b0000001", io.getProcessTitle());
        assertTrue(io.getMetsFilename().endsWith("b0000001.xml"));

        // check if files where copied
        Path masterFolder = Paths.get(io.getMetsFilename().replace(".xml", "/images/b0000001_master"));
        assertTrue(Files.exists(masterFolder));
        assertTrue(Files.exists(Paths.get(masterFolder.toString(), "b0000001.tif")));

        // read metadata
        MetsMods mm = new MetsMods(prefs);
        mm.read(io.getMetsFilename());
        DocStruct logical = mm.getDigitalDocument().getLogicalDocStruct();
        assertEquals("Note", logical.getType().getName());

        // two page elements where created for b0000001.tif and b0000002.tif
        assertEquals(2, mm.getDigitalDocument().getPhysicalDocStruct().getAllChildren().size());
        assertEquals("b0000001.tif", mm.getDigitalDocument().getPhysicalDocStruct().getAllChildren().get(0).getImageName());
        assertEquals("b0000002.tif", mm.getDigitalDocument().getPhysicalDocStruct().getAllChildren().get(1).getImageName());

        // metadata
        assertEquals(8, logical.getAllMetadata().size());

        // identifier
        Metadata md = logical.getAllMetadata().get(0);
        assertEquals("CatalogIDDigital", md.getType().getName());
        assertEquals("b0000001", md.getValue());

        // collection
        md = logical.getAllMetadata().get(1);
        assertEquals("singleDigCollection", md.getType().getName());
        assertEquals("Zettelkatalog", md.getValue());

        // structure
        md = logical.getAllMetadata().get(2);
        assertEquals("FolderStructure", md.getType().getName());
        assertEquals("m001/z001/h001", md.getValue());

        // total pos
        md = logical.getAllMetadata().get(3);
        assertEquals("TotalPosition", md.getType().getName());
        assertEquals("1", md.getValue());

        // letter
        md = logical.getAllMetadata().get(4);
        assertEquals("Letter", md.getType().getName());
        assertEquals("A", md.getValue());
        md = logical.getAllMetadata().get(5);
        assertEquals("LetterPosition", md.getType().getName());
        assertEquals("1", md.getValue());

        // tray
        md = logical.getAllMetadata().get(6);
        assertEquals("Tray", md.getType().getName());
        assertEquals("A", md.getValue());
        md = logical.getAllMetadata().get(7);
        assertEquals("TrayPosition", md.getType().getName());
        assertEquals("1", md.getValue());
    }

    @Test
    public void testCreateEadStructure() {
        mockArchivePlugin();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        List<String> folderList = plugin.getAllFilenames();
        List<Record> recordList = plugin.generateRecordsFromFilenames(folderList);

        IArchiveManagementAdministrationPlugin archive = plugin.getArchivePlugin();
        assertNull(archive);

        // empty list, archive is not initialized
        plugin.generateEadStructure(new ArrayList<>(), "sample");
        archive = plugin.getArchivePlugin();
        assertNull(archive);

        // no file name, archive is not initialized
        plugin.generateEadStructure(recordList, "");
        archive = plugin.getArchivePlugin();
        assertNull(archive);

        // valid parameter, archive can be initialized
        plugin.generateEadStructure(recordList, "sample");
        archive = plugin.getArchivePlugin();
        assertNotNull(archive);

    }

    private void mockArchivePlugin() {
        PowerMock.mockStatic(PluginLoader.class);
        IArchiveManagementAdministrationPlugin plugin = EasyMock.createMock(IArchiveManagementAdministrationPlugin.class);
        IEadEntry rootElement = EasyMock.createMock(IEadEntry.class);

        IEadEntry letterElement = EasyMock.createMock(IEadEntry.class);
        IEadEntry trayElement = EasyMock.createMock(IEadEntry.class);
        List<IEadEntry> letters = new ArrayList<>();
        letters.add(letterElement);
        List<IEadEntry> trays = new ArrayList<>();
        trays.add(trayElement);
        EasyMock.expect(PluginLoader.getPluginByTitle(PluginType.Administration, "intranda_administration_archive_management"))
                .andReturn(plugin)
                .anyTimes();
        plugin.setDatabaseName(EasyMock.anyString());
        plugin.createNewDatabase();
        EasyMock.expect(plugin.getRootElement()).andReturn(rootElement).anyTimes();

        EasyMock.expect(plugin.getSelectedEntry()).andReturn(rootElement).anyTimes();
        IMetadataField field = EasyMock.createMock(IMetadataField.class);
        EasyMock.expect(field.getName()).andReturn("unittitle").anyTimes();
        EasyMock.expect(field.isFilled()).andReturn(true).anyTimes();

        IFieldValue val = EasyMock.createMock(IFieldValue.class);
        List<IFieldValue> valList = new ArrayList<>();
        valList.add(val);
        for (int i = 0; i < 1200; i++) {
            rootElement.setNodeType(EasyMock.anyObject());
            plugin.setSelectedEntry(EasyMock.anyObject());
            plugin.addNode();
            rootElement.setGoobiProcessTitle(EasyMock.anyString());
            val.setValue(EasyMock.anyString());
        }
        EasyMock.expect(field.getValues()).andReturn(valList).anyTimes();
        List<IMetadataField> fields = new ArrayList<>();
        fields.add(field);

        EasyMock.expect(rootElement.getIdentityStatementAreaList()).andReturn(fields).anyTimes();
        EasyMock.expect(rootElement.getSubEntryList()).andReturn(letters).anyTimes();
        EasyMock.expect(letterElement.getLabel()).andReturn("A").anyTimes();
        EasyMock.expect(letterElement.getSubEntryList()).andReturn(trays).anyTimes();
        EasyMock.expect(trayElement.getLabel()).andReturn("A").anyTimes();
        INodeType t1 = EasyMock.createMock(INodeType.class);
        INodeType t2 = EasyMock.createMock(INodeType.class);
        List<INodeType> lst = new ArrayList<>();
        lst.add(t1);
        lst.add(t2);

        EasyMock.expect(plugin.getConfiguredNodes()).andReturn(lst).anyTimes();
        EasyMock.expect(t1.getNodeName()).andReturn("folder").anyTimes();
        EasyMock.expect(t2.getNodeName()).andReturn("file").anyTimes();

        EasyMock.replay(t1, t2, rootElement, plugin, field, val, letterElement, trayElement);
        PowerMock.replay(PluginLoader.class);
    }

}
