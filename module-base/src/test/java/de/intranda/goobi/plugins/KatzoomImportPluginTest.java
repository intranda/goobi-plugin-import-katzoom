package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.easymock.EasyMock;
import org.goobi.production.enums.ImportType;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
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
import ugh.dl.Prefs;
import ugh.fileformats.mets.MetsMods;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ ConfigurationHelper.class })
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
        Path masterFolder = Paths.get(io.getMetsFilename().replace(".xml", "/images/master_b0000001_media"));
        assertTrue(Files.exists(masterFolder));
        assertTrue(Files.exists(Paths.get(masterFolder.toString(), "b0000001.tif")));

        // read metadata
        MetsMods mm = new MetsMods(prefs);
        mm.read(io.getMetsFilename());

        // two page elements where created for b0000001.tif and b0000002.tif
        assertEquals(2, mm.getDigitalDocument().getPhysicalDocStruct().getAllChildren().size());
        assertEquals("b0000001.tif", mm.getDigitalDocument().getPhysicalDocStruct().getAllChildren().get(0).getImageName());
        assertEquals("b0000002.tif", mm.getDigitalDocument().getPhysicalDocStruct().getAllChildren().get(1).getImageName());

    }
}
