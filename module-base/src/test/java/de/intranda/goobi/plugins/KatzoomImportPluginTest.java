package de.intranda.goobi.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import de.intranda.goobi.plugins.model.ArchiveManagementConfiguration;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.StorageProvider;
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

    // folder name rules as returned by ConfigurationHelper; individual tests may override them
    private String mediaFolderRule;
    private String masterFolderRule;
    private String txtFolderRule;
    private String pdfFolderRule;

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

        mediaFolderRule = "{processtitle}_media";
        masterFolderRule = "{processtitle}_master";
        txtFolderRule = "{processtitle}_txt";
        pdfFolderRule = "{processtitle}_pdf";

        PowerMock.mockStatic(ConfigurationHelper.class);

        ConfigurationHelper configurationHelper = EasyMock.createMock(ConfigurationHelper.class);
        EasyMock.expect(ConfigurationHelper.getInstance()).andReturn(configurationHelper).anyTimes();
        EasyMock.expect(configurationHelper.getConfigurationFolder()).andReturn(resourcesFolder).anyTimes();
        EasyMock.expect(configurationHelper.useS3()).andReturn(false).anyTimes();
        EasyMock.expect(configurationHelper.getProcessImagesMainDirectoryName()).andAnswer(() -> mediaFolderRule).anyTimes();
        EasyMock.expect(configurationHelper.getProcessImagesMasterDirectoryName()).andAnswer(() -> masterFolderRule).anyTimes();
        EasyMock.expect(configurationHelper.getProcessOcrTxtDirectoryName()).andAnswer(() -> txtFolderRule).anyTimes();
        EasyMock.expect(configurationHelper.getProcessOcrPdfDirectoryName()).andAnswer(() -> pdfFolderRule).anyTimes();
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
        assertEquals(2, folderList.size());
        assertEquals("nka BKA Nominal", folderList.get(0));
        assertEquals("zzz Test Nominal", folderList.get(1));
    }

    @Test
    public void testGenerateRecordsFromFilenames() {
        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.getAllFilenames();
        List<Record> recordList = plugin.generateRecordsFromFilenames(Collections.singletonList("nka BKA Nominal"));
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

    /**
     * a record must not carry the absolute path of each of its files, the folder and the id are enough to find them again
     */
    @Test
    public void testRecordStoresFolderAndIdInsteadOfAbsolutePaths() {
        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        List<Record> recordList = plugin.generateRecordsFromFilenames(plugin.getAllFilenames());

        KatzoomImportObject kip = (KatzoomImportObject) recordList.get(0).getObject();
        assertEquals(1, kip.getId());
        assertTrue("unexpected folder " + kip.getFolder(), kip.getFolder().endsWith("m001/z001/h001"));

        // the last record lives in the last sub folder
        kip = (KatzoomImportObject) recordList.get(499).getObject();
        assertEquals(999, kip.getId());
        assertTrue("unexpected folder " + kip.getFolder(), kip.getFolder().endsWith("m001/z001/h010"));
    }

    /**
     * the files of a single card are collected from the folder listing, although that folder holds the files of 100 cards
     */
    @Test
    public void testFilesOfACardAreResolvedFromFolderAndId() {
        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        List<Record> recordList = plugin.generateRecordsFromFilenames(plugin.getAllFilenames());

        List<String> files = plugin.resolveFiles((KatzoomImportObject) recordList.get(0).getObject());

        // front side, back side and the downscaled preview image
        assertEquals(9, files.size());
        assertTrue(files.get(0).endsWith("b0000001.pdf"));
        assertTrue(files.contains(Paths.get(resourcesFolder, "data/nka BKA Nominal/m001/z001/h001/o0000001.png").toString()));

        // none of the neighbouring cards in the same folder
        for (String file : files) {
            assertFalse(file + " does not belong to the first card", file.contains("b0000003"));
        }
    }

    /**
     * not every card has the same set of derivates, b0000032.png is missing in the test data
     */
    @Test
    public void testResolvingFilesHandlesMissingDerivates() {
        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        List<Record> recordList = plugin.generateRecordsFromFilenames(plugin.getAllFilenames());

        KatzoomImportObject kip = (KatzoomImportObject) recordList.get(15).getObject();
        assertEquals(31, kip.getId());

        List<String> files = plugin.resolveFiles(kip);
        assertEquals(8, files.size());
        for (String file : files) {
            assertFalse("b0000032.png does not exist and must not be resolved", file.endsWith("b0000032.png"));
        }
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

    /**
     * every derivate of a card has to be copied: front side, back side and the downscaled preview image, each into the folder of its file type
     */
    @Test
    public void testCopyFilesCopiesEveryDerivateOfTheCard() throws Exception {
        Path processFolder = importFirstRecord();

        Path master = processFolder.resolve("images").resolve("b0000001_master");
        assertTrue("front side is missing in master", Files.exists(master.resolve("b0000001.tif")));
        assertTrue("back side is missing in master", Files.exists(master.resolve("b0000002.tif")));

        Path media = processFolder.resolve("images").resolve("b0000001_media");
        assertTrue("front side is missing in media", Files.exists(media.resolve("b0000001.png")));
        assertTrue("back side is missing in media", Files.exists(media.resolve("b0000002.png")));
        assertTrue("preview image is missing in media", Files.exists(media.resolve("o0000001.png")));

        Path pdf = processFolder.resolve("ocr").resolve("b0000001_pdf");
        assertTrue("front side is missing in pdf", Files.exists(pdf.resolve("b0000001.pdf")));
        assertTrue("back side is missing in pdf", Files.exists(pdf.resolve("b0000002.pdf")));

        // the folder of the card holds the files of 100 cards, none of the other cards may be copied
        assertFalse("file of another card was copied", Files.exists(master.resolve("b0000003.tif")));
        assertEquals(2, StorageProvider.getInstance().list(master.toString()).size());
    }

    @Test
    public void testCopyFilesCreatesFolderNamesFromConfiguredRules() throws Exception {
        Path processFolder = importFirstRecord();

        assertTrue("images/b0000001_media is missing", Files.exists(processFolder.resolve("images").resolve("b0000001_media")));
        assertTrue("images/b0000001_master is missing", Files.exists(processFolder.resolve("images").resolve("b0000001_master")));
        assertTrue("ocr/b0000001_txt is missing", Files.exists(processFolder.resolve("ocr").resolve("b0000001_txt")));
        assertTrue("ocr/b0000001_pdf is missing", Files.exists(processFolder.resolve("ocr").resolve("b0000001_pdf")));
    }

    @Test
    public void testCopyFilesCopiesFulltextIntoConfiguredFolder() throws Exception {
        Path processFolder = importFirstRecord();

        Path textFolder = processFolder.resolve("ocr").resolve("b0000001_txt");
        assertTrue("b0000001.txt was not copied to ocr/b0000001_txt", Files.exists(textFolder.resolve("b0000001.txt")));
    }

    @Test
    public void testCopyFilesHonoursCustomConfiguredFolderNames() throws Exception {
        mediaFolderRule = "{processtitle}_bilder";
        masterFolderRule = "master_{processtitle}";
        txtFolderRule = "fulltext";
        pdfFolderRule = "pdf_{processtitle}";

        Path processFolder = importFirstRecord();

        assertTrue("images/b0000001_bilder is missing", Files.exists(processFolder.resolve("images").resolve("b0000001_bilder")));
        assertTrue("images/master_b0000001 is missing", Files.exists(processFolder.resolve("images").resolve("master_b0000001")));
        assertTrue("ocr/fulltext is missing", Files.exists(processFolder.resolve("ocr").resolve("fulltext")));
        assertTrue("ocr/pdf_b0000001 is missing", Files.exists(processFolder.resolve("ocr").resolve("pdf_b0000001")));

        assertTrue("b0000001.txt was not copied to ocr/fulltext",
                Files.exists(processFolder.resolve("ocr").resolve("fulltext").resolve("b0000001.txt")));

        // the default names must not be used when the configuration says otherwise
        assertFalse("images/b0000001_media must not be created", Files.exists(processFolder.resolve("images").resolve("b0000001_media")));
        assertFalse("images/b0000001_master must not be created", Files.exists(processFolder.resolve("images").resolve("b0000001_master")));
    }

    /**
     * imports the first record of the test index and returns the folder that was created for it
     */
    private Path importFirstRecord() throws Exception {
        File importFolder = folder.newFolder();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.setImportFolder(importFolder.getAbsolutePath());
        Prefs prefs = new Prefs();
        prefs.loadPrefs(resourcesFolder + "ruleset.xml");
        plugin.setPrefs(prefs);

        List<String> folderList = plugin.getAllFilenames();
        List<Record> recordList = plugin.generateRecordsFromFilenames(folderList);
        List<ImportObject> imports = plugin.generateFiles(recordList.subList(0, 1));

        assertEquals(1, imports.size());
        ImportObject io = imports.get(0);
        assertEquals("b0000001", io.getProcessTitle());
        return Paths.get(io.getMetsFilename().replace(".xml", ""));
    }

    @Test
    public void testCreateEadStructure() {
        FakeArchive archive = mockArchivePlugin();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.getAllFilenames();
        List<Record> recordList = plugin.generateRecordsFromFilenames(Collections.singletonList("nka BKA Nominal"));

        assertNull(plugin.getArchivePlugin());

        // empty list, archive is not initialized
        plugin.generateEadStructure(new ArrayList<>(), "sample");
        assertNull(plugin.getArchivePlugin());

        // no file name, archive is not initialized
        plugin.generateEadStructure(recordList, "");
        assertNull(plugin.getArchivePlugin());

        // valid parameter, archive can be initialized
        plugin.generateEadStructure(recordList, "sample");
        assertNotNull(plugin.getArchivePlugin());

        assertEquals(Collections.singletonList("sample"), archive.databases);
        // one node per card, below the letters and trays it belongs to
        assertEquals(500, archive.processTitles.get(0).size());
        assertEquals("b0000001", archive.processTitles.get(0).get(0));
        assertEquals("b0000999", archive.processTitles.get(0).get(499));
        // the index uses the letters A and B
        assertEquals(2, archive.children.get(archive.roots.get(0)).size());
    }

    /**
     * every index gets its own archive, the records of an index must not show up in the archive of the next one
     */
    @Test
    public void testEadStructureIsGeneratedPerIndex() {
        FakeArchive archive = mockArchivePlugin();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.setWorkflowName("eadtest");
        List<String> folderList = plugin.getAllFilenames();
        assertEquals(2, folderList.size());

        plugin.generateRecordsFromFilenames(folderList);

        assertEquals(folderList, archive.databases);
        assertEquals(500, archive.processTitles.get(0).size());
        // the second index holds four cards, the cards of the first index must not be added again
        assertEquals(4, archive.processTitles.get(1).size());
        assertEquals("a0000001", archive.processTitles.get(1).get(0));
    }

    /**
     * the card nodes are written in batches instead of one statement per node
     */
    @Test
    public void testEadCardNodesAreSavedInBatches() {
        FakeArchive archive = mockArchivePlugin();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.setWorkflowName("eadtest");
        plugin.getAllFilenames();
        plugin.generateRecordsFromFilenames(Collections.singletonList("zzz Test Nominal"));

        // two letters and four cards
        assertEquals(6, archive.addedNodes);
        // root and the two letters are stored on their own, because their children reference them as parent
        assertEquals(Arrays.asList(1, 1, 1, 4), batchSizes(archive));
        // titles reach the database, they are set before the node is stored
        assertEquals("a0000001", archive.labels.get(archive.savedBatches.get(3).get(0)));
    }

    /**
     * the plugin must not fall back to storing every single node
     */
    @Test
    public void testEadNodesAreNotStoredOneByOne() {
        FakeArchive archive = mockArchivePlugin();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.setWorkflowName("eadtest");
        plugin.getAllFilenames();
        plugin.generateRecordsFromFilenames(Collections.singletonList("nka BKA Nominal"));

        assertEquals(0, archive.singleNodeSaves);
        // 500 cards in batches of 500, plus root, two letters and four trays on their own
        assertEquals(8, archive.savedBatches.size());
    }

    private List<Integer> batchSizes(FakeArchive archive) {
        List<Integer> sizes = new ArrayList<>();
        for (List<IEadEntry> batch : archive.savedBatches) {
            sizes.add(batch.size());
        }
        return sizes;
    }

    /**
     * a card index can hold several hundred thousand cards. Their nodes are detached from the tree once they are stored, so that the tree in memory
     * stays as small as the number of letters and trays.
     */
    @Test
    public void testEadNodesAreDetachedFromTheTreeAfterTheyWereSaved() {
        FakeArchive archive = mockArchivePlugin();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.setWorkflowName("eadtest");
        plugin.getAllFilenames();
        plugin.generateRecordsFromFilenames(Collections.singletonList("zzz Test Nominal"));

        IEadEntry root = archive.roots.get(0);
        List<IEadEntry> letters = archive.children.get(root);
        assertEquals(2, letters.size());
        for (IEadEntry letter : letters) {
            assertTrue("card nodes must not be kept in the tree", archive.children.get(letter).isEmpty());
        }

        // the order within the letter is assigned explicitly, because it can no longer be derived from the number of children.
        // the letter of a position is the last one whose start position is smaller than the position, so 'A' holds the first three cards.
        assertEquals(Arrays.asList(0, 1, 2, 0), archive.orderNumbers);
    }

    /**
     * the tree of the archive plugin is the largest structure of the import, it must not stay alive after the archive was written
     */
    @Test
    public void testArchivePluginIsReleasedAfterTheIndexWasProcessed() {
        mockArchivePlugin();

        KatzoomImportPlugin plugin = new KatzoomImportPlugin();
        plugin.setWorkflowName("eadtest");
        plugin.getAllFilenames();
        plugin.generateRecordsFromFilenames(Collections.singletonList("zzz Test Nominal"));

        assertNull(plugin.getArchivePlugin());
    }

    /**
     * a fake of the archive management plugin. It keeps the node tree in the same way the real plugin does, so that the tests can check the structure
     * that was created instead of the calls that were made.
     */
    private class FakeArchive {
        private List<String> databases = new ArrayList<>();
        private List<IEadEntry> roots = new ArrayList<>();
        private List<List<String>> processTitles = new ArrayList<>();
        private Map<IEadEntry, List<IEadEntry>> children = new HashMap<>();
        private Map<IEadEntry, String> labels = new HashMap<>();
        private Map<IEadEntry, List<IMetadataField>> fields = new HashMap<>();
        private Map<IEadEntry, IEadEntry> parents = new HashMap<>();
        private List<Integer> orderNumbers = new ArrayList<>();
        private List<List<IEadEntry>> savedBatches = new ArrayList<>();
        private int addedNodes = 0;
        private int singleNodeSaves = 0;
        private IEadEntry selectedEntry;
    }

    private FakeArchive mockArchivePlugin() {
        FakeArchive archive = new FakeArchive();

        IArchiveManagementAdministrationPlugin archivePlugin = EasyMock.createNiceMock(IArchiveManagementAdministrationPlugin.class);

        archivePlugin.setDatabaseName(EasyMock.anyString());
        EasyMock.expectLastCall().andAnswer(() -> {
            archive.databases.add((String) EasyMock.getCurrentArguments()[0]);
            return null;
        }).anyTimes();

        archivePlugin.createNewDatabase();
        EasyMock.expectLastCall().andAnswer(() -> {
            IEadEntry root = createEntry(archive);
            archive.roots.add(root);
            archive.processTitles.add(new ArrayList<>());
            archive.selectedEntry = root;
            return null;
        }).anyTimes();

        EasyMock.expect(archivePlugin.getRootElement()).andAnswer(() -> archive.roots.get(archive.roots.size() - 1)).anyTimes();

        archivePlugin.setSelectedEntry(EasyMock.anyObject());
        EasyMock.expectLastCall().andAnswer(() -> {
            archive.selectedEntry = (IEadEntry) EasyMock.getCurrentArguments()[0];
            return null;
        }).anyTimes();

        EasyMock.expect(archivePlugin.getSelectedEntry()).andAnswer(() -> archive.selectedEntry).anyTimes();

        archivePlugin.addNode();
        EasyMock.expectLastCall().andAnswer(() -> {
            IEadEntry node = createEntry(archive);
            archive.children.get(archive.selectedEntry).add(node);
            archive.selectedEntry = node;
            archive.addedNodes++;
            return null;
        }).anyTimes();

        EasyMock.expect(archivePlugin.addNodeWithoutSaving(EasyMock.anyObject())).andAnswer(() -> {
            IEadEntry parent = (IEadEntry) EasyMock.getCurrentArguments()[0];
            IEadEntry node = createEntry(archive);
            archive.parents.put(node, parent);
            archive.children.get(parent).add(node);
            archive.addedNodes++;
            return node;
        }).anyTimes();

        archivePlugin.saveNodes(EasyMock.anyObject());
        EasyMock.expectLastCall().andAnswer(() -> {
            @SuppressWarnings("unchecked")
            List<IEadEntry> batch = (List<IEadEntry>) EasyMock.getCurrentArguments()[0];
            archive.savedBatches.add(new ArrayList<>(batch));
            return null;
        }).anyTimes();

        archivePlugin.updateSingleNode();
        EasyMock.expectLastCall().andAnswer(() -> {
            archive.singleNodeSaves++;
            return null;
        }).anyTimes();

        INodeType folderType = EasyMock.createNiceMock(INodeType.class);
        INodeType fileType = EasyMock.createNiceMock(INodeType.class);
        EasyMock.expect(folderType.getNodeName()).andReturn("folder").anyTimes();
        EasyMock.expect(fileType.getNodeName()).andReturn("file").anyTimes();
        EasyMock.replay(folderType, fileType);

        ArchiveManagementConfiguration conf = EasyMock.createNiceMock(ArchiveManagementConfiguration.class);
        EasyMock.expect(conf.getConfiguredNodes()).andReturn(Arrays.asList(folderType, fileType)).anyTimes();
        EasyMock.replay(conf);
        EasyMock.expect(archivePlugin.getConfig()).andReturn(conf).anyTimes();

        EasyMock.replay(archivePlugin);

        PowerMock.mockStatic(PluginLoader.class);
        EasyMock.expect(PluginLoader.getPluginByTitle(PluginType.Administration, "intranda_administration_archive_management"))
                .andReturn(archivePlugin)
                .anyTimes();
        PowerMock.replay(PluginLoader.class);

        return archive;
    }

    /**
     * create a node of the fake archive. It behaves like a real node with respect to its children, its label and the removal of children.
     */
    private IEadEntry createEntry(FakeArchive archive) {
        IEadEntry entry = EasyMock.createNiceMock(IEadEntry.class);
        List<IEadEntry> subEntries = new ArrayList<>();
        archive.children.put(entry, subEntries);

        EasyMock.expect(entry.getSubEntryList()).andReturn(subEntries).anyTimes();
        EasyMock.expect(entry.getParentNode()).andAnswer(() -> archive.parents.get(entry)).anyTimes();
        EasyMock.expect(entry.getLabel()).andAnswer(() -> archive.labels.get(entry)).anyTimes();
        EasyMock.expect(entry.getIdentityStatementAreaList())
                .andAnswer(() -> archive.fields.computeIfAbsent(entry,
                        key -> Arrays.asList(createField(archive, key, "unittitle"), createField(archive, key, "unitid"))))
                .anyTimes();

        entry.setGoobiProcessTitle(EasyMock.anyString());
        EasyMock.expectLastCall().andAnswer(() -> {
            archive.processTitles.get(archive.processTitles.size() - 1).add((String) EasyMock.getCurrentArguments()[0]);
            return null;
        }).anyTimes();

        entry.setOrderNumber(EasyMock.anyObject());
        EasyMock.expectLastCall().andAnswer(() -> {
            archive.orderNumbers.add((Integer) EasyMock.getCurrentArguments()[0]);
            return null;
        }).anyTimes();

        entry.removeSubEntry(EasyMock.anyObject());
        EasyMock.expectLastCall().andAnswer(() -> {
            subEntries.remove(EasyMock.getCurrentArguments()[0]);
            return null;
        }).anyTimes();

        EasyMock.replay(entry);
        return entry;
    }

    /**
     * a metadata field of the fake archive. Setting the value of the unittitle updates the label of the node, just like the real implementation does.
     */
    private IMetadataField createField(FakeArchive archive, IEadEntry entry, String name) {
        IFieldValue value = EasyMock.createNiceMock(IFieldValue.class);
        value.setValue(EasyMock.anyString());
        EasyMock.expectLastCall().andAnswer(() -> {
            if ("unittitle".equals(name)) {
                archive.labels.put(entry, (String) EasyMock.getCurrentArguments()[0]);
            }
            return null;
        }).anyTimes();
        EasyMock.replay(value);

        IMetadataField field = EasyMock.createNiceMock(IMetadataField.class);
        EasyMock.expect(field.getName()).andReturn(name).anyTimes();
        EasyMock.expect(field.isFilled()).andReturn(true).anyTimes();
        EasyMock.expect(field.getValues()).andReturn(Collections.singletonList(value)).anyTimes();
        EasyMock.replay(field);
        return field;
    }

}
