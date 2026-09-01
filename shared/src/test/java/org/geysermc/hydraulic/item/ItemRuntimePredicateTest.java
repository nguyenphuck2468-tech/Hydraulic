package org.geysermc.hydraulic.item;

import org.junit.jupiter.api.Test;
import team.unnamed.creative.item.property.ItemBooleanProperty;
import team.unnamed.creative.item.property.ItemNumericProperty;
import team.unnamed.creative.item.property.ItemStringProperty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRuntimePredicateTest {
    @Test
    void mapsHighValueConditionPredicates() {
        assertTrue(ItemPackModule.supportsCondition(ItemBooleanProperty.broken()));
        assertTrue(ItemPackModule.supportsCondition(ItemBooleanProperty.damaged()));
        assertTrue(ItemPackModule.supportsCondition(ItemBooleanProperty.hasComponent("minecraft:damage")));
        assertFalse(ItemPackModule.supportsCondition(ItemBooleanProperty.selected()));
    }

    @Test
    void mapsDamageCountAndCustomModelDataRanges() {
        assertTrue(ItemPackModule.supportsRange(ItemNumericProperty.damage()));
        assertTrue(ItemPackModule.supportsRange(ItemNumericProperty.damage(true)));
        assertTrue(ItemPackModule.supportsRange(ItemNumericProperty.count()));
        assertTrue(ItemPackModule.supportsRange(ItemNumericProperty.bundleFullness()));
        assertTrue(ItemPackModule.supportsRange(ItemNumericProperty.customModelData(2)));
    }

    @Test
    void mapsCrossbowChargeAndCustomModelDataSelections() {
        assertTrue(ItemPackModule.supportsSelect(ItemStringProperty.chargeType()));
        assertTrue(ItemPackModule.supportsSelect(ItemStringProperty.trimMaterial()));
        assertTrue(ItemPackModule.supportsSelect(ItemStringProperty.customModelData(1)));
    }
}
