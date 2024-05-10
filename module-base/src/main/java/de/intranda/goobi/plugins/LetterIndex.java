package de.intranda.goobi.plugins;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@AllArgsConstructor
public class LetterIndex {

    private String letter;

    private Integer startPosition;

    @Setter
    private int currentPosition;
}
