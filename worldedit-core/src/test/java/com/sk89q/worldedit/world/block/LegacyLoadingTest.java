/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.world.block;

import com.sk89q.worldedit.BaseWorldEditTest;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.factory.parser.DefaultBlockParser;
import com.sk89q.worldedit.extension.factory.parser.DefaultItemParser;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.util.PropertiesConfiguration;
import com.sk89q.worldedit.util.formatting.WorldEditText;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.registry.LegacyMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class LegacyLoadingTest extends BaseWorldEditTest {

    @BeforeAll
    static void registerTestBlocks() {
        // Match the existing BlockMapTest fixture before BlockTypes caches these constants.
        for (String id : new String[] { "minecraft:air", "minecraft:oak_wood", "minecraft:chest" }) {
            BlockType.REGISTRY.register(id, new BlockType(id));
        }
    }

    @AfterAll
    static void clearTestBlocks() {
        BlockType.REGISTRY.clear();
    }

    @TempDir
    Path directory;

    @Test
    void modernAndMalformedInputsDoNotInitializeLegacyMapper() {
        WorldEdit worldEdit = WorldEdit.getInstance();
        ParserContext context = new ParserContext();
        context.setTryLegacy(true);
        context.setRestricted(false);
        try (MockedStatic<LegacyMapper> legacy = mockStatic(LegacyMapper.class);
             MockedStatic<WorldEditText> text = mockStatic(WorldEditText.class)) {
            // The standalone tests have no platform translation service.
            text.when(() -> WorldEditText.reduceToText(any(), any())).thenReturn("Unknown input");
            for (String input : new String[] { "test:unknown", "unknown", "1:invalid" }) {
                assertThrows(InputParseException.class,
                    () -> new DefaultItemParser(worldEdit).parseFromInput(input, context));
                assertThrows(InputParseException.class,
                    () -> new DefaultBlockParser(worldEdit).parseFromInput(input, context));
            }
            legacy.verifyNoInteractions();
        }
    }

    @Test
    void numericItemsStillResolveOnDemand() throws InputParseException {
        LegacyMapper mapper = mock(LegacyMapper.class);
        ItemType item = new ItemType("test:legacy_item");
        when(mapper.getItemFromLegacy(1)).thenReturn(item);
        when(mapper.getItemFromLegacy(1, 2)).thenReturn(item);
        ParserContext context = new ParserContext();
        context.setTryLegacy(true);
        try (MockedStatic<LegacyMapper> legacy = mockStatic(LegacyMapper.class)) {
            legacy.when(LegacyMapper::getInstance).thenReturn(mapper);
            DefaultItemParser parser = new DefaultItemParser(WorldEdit.getInstance());
            assertSame(item, parser.parseFromInput("1", context).getType());
            assertSame(item, parser.parseFromInput("1:2", context).getType());
        }
    }

    @Test
    void modernConfigurationDoesNotInitializeLegacyMapper() throws Exception {
        Path file = directory.resolve("worldedit.properties");
        Files.writeString(file, "wand-item=minecraft:wooden_axe\nnav-wand-item=minecraft:compass\n");
        try (MockedStatic<LegacyMapper> legacy = mockStatic(LegacyMapper.class)) {
            PropertiesConfiguration configuration = new PropertiesConfiguration(file);
            configuration.load();
            assertEquals("minecraft:wooden_axe", configuration.wandItem);
            assertEquals("minecraft:compass", configuration.navigationWand);
            legacy.verifyNoInteractions();
        }
    }

    @Test
    void numericConfigurationStillResolvesOnDemand() throws Exception {
        Path file = directory.resolve("legacy.properties");
        Files.writeString(file, "wand-item=271\nnav-wand-item=345\n");
        LegacyMapper mapper = mock(LegacyMapper.class);
        when(mapper.getItemFromLegacy(271, 0)).thenReturn(new ItemType("minecraft:wooden_axe"));
        when(mapper.getItemFromLegacy(345, 0)).thenReturn(new ItemType("minecraft:compass"));
        try (MockedStatic<LegacyMapper> legacy = mockStatic(LegacyMapper.class)) {
            legacy.when(LegacyMapper::getInstance).thenReturn(mapper);
            PropertiesConfiguration configuration = new PropertiesConfiguration(file);
            configuration.load();
            assertEquals("minecraft:wooden_axe", configuration.wandItem);
            assertEquals("minecraft:compass", configuration.navigationWand);
        }
    }
}
