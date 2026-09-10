package com.silporestockai.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which catalog variants a bare staple line never means — the deterministic guard in front of the matcher. */
class CartBuildingServiceVariantTest {

    @Test
    void aBareRiceLineDropsTheColouredAndRisottoVariants() {
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("рис"))
                .contains("червон", "чорн", "карнарол", "арборіо");
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("Рис 1 кг".toLowerCase()))
                .contains("червон");
    }

    @Test
    void aLineThatNamesTheVariantKeepsIt() {
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("рис басматі"))
                .isEmpty();
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("рис для різото"))
                .isEmpty();
    }

    @Test
    void otherLinesAreUntouched() {
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("рисові чіпси"))
                .isEmpty();
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("молоко")).isEmpty();
    }

    /** «Рис Sacramento» with no colour in its name is plain rice and must stay: only named variants are dropped. */
    @Test
    void colouredRiceIsDroppedByNameOnly() {
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("рис")).contains("рожев", "бур", "коричнев");
    }

    @Test
    void bareNoodlesNeverMeanInstantOnes() {
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("локшина")).contains("швидкого приготування");
    }

    @Test
    void aBareCabbageIsTheVegetableNotTheJar() {
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("капуста")).contains("квашен", "маринован");
        assertThat(CartBuildingService.variantsABareLineDoesNotMean("капуста квашена"))
                .isEmpty();
    }
}
