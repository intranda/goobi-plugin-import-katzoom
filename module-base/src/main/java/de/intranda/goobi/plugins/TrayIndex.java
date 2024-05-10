package de.intranda.goobi.plugins;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@AllArgsConstructor
public class TrayIndex {

    String trayName;

    private int order;

    private int startPosition;
    private int numberOfEntries;
    @Setter
    private int currentPosition;

}
