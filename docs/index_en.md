---
title: Import of card catalogues from Katzoom
identifier: intranda_import_katzoom
github: https://github.com/intranda/plugin-import-katzoom
description: Import plugin for card catalogues from folder structures of the Katzoom system
published: true
---

## Introduction
This documentation describes the installation, configuration and use of the plugin for transferring card catalogues from the Katzoom system to Goobi workflow.


## Installation
The following files must be installed in order to use the plugin:

```bash
/opt/digiverso/goobi/plugins/import/plugin-import-katzoom.jar
/opt/digiverso/goobi/config/plugin_intranda_import_katzoom.xml
```

The archive management plugin must also be installed and configured. Instructions for this can be found at the following address: https://docs.goobi.io/goobi-workflow-plugins-en/administration/intranda_administration_archive_management


## Overview and functionality
This plugin is a so-called `Import plugin`. If you open the mass import area, you can then select the plugin in the `Import from folder` tab.

![Selected plugin in the `Import from folder` area](screen_en.png)

The plugin expects the following structure for its execution within the configured import folder:

```bash
ank bis 45 Nominal/
	ank.ind
	ank.lli
	m001/
		z001/
			h001/
				c0000001.pdf
				c0000001.png
				c0000001.tif
				c0000001.txt
				c0000002.pdf
				c0000002.png
				c0000002.tif
				c0000002.txt
				o0000001.png
				[...]
				o0000001.png
			h002/
				[...]
ask bis 45 Schlagwort/
	ask.ind
	ask.lli
	m001/	
		[...]
hhn HHStA Nominal/
[...]
```

The user can now select the main folder to be imported in the lower area of the plugin in order to import the corresponding card catalogue. Please note that a complete card catalogue is always imported at once. Partial imports are not provided.

A `*.ind` file and optionally a `*.lli` file are expected within the selected folder. The ind file contains the number of the first data record for each letter. The lli file, on the other hand, contains the number of the first data record for a drawer. As the shops do not exist for all card catalogues, this file is optional.
Furthermore, a folder structure with up to 3 subfolders is expected, in which the individual files are located. The files always begin with a letter followed by a consecutive number and the file extension.
Different derivatives can exist for each object, which then have the same name apart from the file extension. An exception is a downsampled preview image, which starts with a different letter.

All file names are collected and sorted in ascending order by number. For each card catalogue, you can specify whether only the front (e.g. `hhn HHStA Nominal`) or the front and back were scanned (e.g. `ank to 45 Nominal`). In the first case, a data record is generated from each number, in the second case from each odd number. The following even number is then the reverse of the data record.

For each data record, the corresponding letter, if available, the drawer and the positions within the catalogue, the letter and the drawer are determined. This information is saved with the original folder structure as metadata.

In addition, a stock is created in archive management for each card catalogue. The main node corresponds to the catalogue, followed by the letters. Within the letters, there are optional individual shops, followed by the individual data records.  

The fonds are named after the individual catalogues.

### Configuration
The plugin is configured in the file `plugin_intranda_import_katzoom.xml` as shown here:

```xml
    <config>
        <!-- which workflow template shall be used -->
        <template>*</template>
        <!-- define if import shall use GoobiScript to run in the background -->
        <runAsGoobiScript>false</runAsGoobiScript>
        
        <eadDatabaseName>eadStore</eadDatabaseName>
        <generateEadFile>true</generateEadFile>
        
        <!-- root folder, contains all index folder -->
        <importRootFolder>/opt/digiverso/import/</importRootFolder>

        <!-- list all indexes where backside was scanned -->
        <backsideScan>ask bis 45 Schlagwort</backsideScan>
        <backsideScan>ssk ÖSTA Schlagwort</backsideScan>
        <backsideScan>swk BKA Schlagwort</backsideScan>
        <backsideScan>ank bis 45 Nominal</backsideScan>
        <backsideScan>nka BKA Nominal</backsideScan>
        <!-- collection name -->
        <collection>Zettelkatalog</collection>
        <!-- docstruct type -->
        <doctype>Note</doctype>

        <!-- metadata -->
        <!-- contains the folder structure -->
        <folderStructure>FolderStructure</folderStructure>
        <!-- contains the total position -->
        <position>TotalPosition</position>
        <!-- assigned letter -->
        <letter>Letter</letter>
        <!-- position within the letter -->
        <letterPosition>LetterPosition</letterPosition>
        <!-- assigned tray -->
        <tray>Tray</tray>
        <!-- position within tray -->
        <trayPosition>TrayPosition</trayPosition>
    </config>
```

Firstly, the production templates for which the import is to apply are defined within `<template>`. 

The archive stock is then configured within the archive management plugin and the import folder in which the folders for the individual card catalogues are expected is specified. The element `<backsideScan>` contains the names of the card catalogues for which the backside has also been digitised. If a catalogue is missing from this list, the import assumes that only the front side exists.

The name of the collection can be specified in the `<collection>` element. This information is written to each data record. The `<doctype>` element contains the structure type to be generated and the other information contains the names of the individual metadata.