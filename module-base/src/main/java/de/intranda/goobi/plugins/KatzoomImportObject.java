package de.intranda.goobi.plugins;

import java.io.Serializable;
import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class KatzoomImportObject implements Serializable {

    private static final long serialVersionUID = 2352609476455769849L;

    private int id;

    private int totalPosition;

    private String letterName;
    private int letterPosition;

    private String trayName;
    private int trayPosition;

    private List<String> files;
}
