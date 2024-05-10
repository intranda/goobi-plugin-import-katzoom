package de.intranda.goobi.plugins;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TrayIndex {

    String trayName;

    private int order;

    private int startPosition;
    private int numberOfEntries;

}
