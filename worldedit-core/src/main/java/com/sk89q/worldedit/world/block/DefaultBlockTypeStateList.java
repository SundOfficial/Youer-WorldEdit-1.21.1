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

import com.google.common.collect.ImmutableList;
import com.sk89q.worldedit.extension.platform.Watchdog;
import com.sk89q.worldedit.registry.state.Property;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class DefaultBlockTypeStateList extends BlockTypeStateList {
    /**
     * Pre-calculated information about a property.
     *
     * @param property the property
     * @param values the possible values, compared using equality as in WorldEdit 7.3
     * @param stride the stride for this property in the state index calculation, i.e.
     *     how many states are represented by later properties in the list that we must skip for each index increment
     */
    private record PropertyEntry(
        Property<?> property,
        ImmutableList<?> values,
        int stride
    ) {
        int getOffsetForValueOrInvalid(Object value) {
            int valueIndex = values.indexOf(value);
            return valueIndex == -1 ? -1 : valueIndex * stride;
        }
    }

    /*
    This class assembles all block states into a single array.
    The order of the states is such that the last property's values are iterated first,
    preserving the cartesian-product enumeration used by WorldEdit 7.3.
    This allows for fast index calculation by multiplying the value index by the stride for each property.
    For example, given [color: [red, green], shape: [cube, sphere, pyramid]]:
    The states would be ordered as:
    0: color=red, shape=cube (0*3 + 0)
    1: color=red, shape=sphere (0*3 + 1)
    2: color=red, shape=pyramid (0*3 + 2)
    3: color=green, shape=cube (1*3 + 0)
    4: color=green, shape=sphere (1*3 + 1)
    5: color=green, shape=pyramid (1*3 + 2)
     */

    private final ImmutableList<PropertyEntry> propertyEntries;
    private final ImmutableList<BlockState> states;

    DefaultBlockTypeStateList(BlockType blockType, Watchdog watchdog) {
        List<? extends Property<?>> properties = blockType.getProperties();
        ImmutableList.Builder<PropertyEntry> propertyEntriesBuilder =
            ImmutableList.builderWithExpectedSize(properties.size());
        int nextStride = 1;
        // Preserve 7.3's cartesian-product order: the last property changes fastest.
        for (int i = properties.size() - 1; i >= 0; i--) {
            Property<?> property = properties.get(i);
            if (property.getValues().isEmpty()) {
                throw new IllegalArgumentException("No values for property " + property.getName());
            }
            propertyEntriesBuilder.add(new PropertyEntry(
                property,
                ImmutableList.copyOf(property.getValues()),
                nextStride
            ));
            nextStride = Math.multiplyExact(nextStride, property.getValues().size());
        }
        this.propertyEntries = propertyEntriesBuilder.build().reverse();
        this.states = createStates(nextStride, blockType, watchdog);
    }

    private ImmutableList<BlockState> createStates(int totalStates, BlockType blockType, Watchdog watchdog) {
        // States do not store their property values; they are derived from the index on demand
        ImmutableList.Builder<BlockState> statesBuilder = ImmutableList.builderWithExpectedSize(totalStates);
        for (int i = 0; i < totalStates; i++) {
            statesBuilder.add(new BlockState(blockType, this, i));

            if (watchdog != null && (i & 1023) == 0) {
                watchdog.tick();
            }
        }
        return statesBuilder.build();
    }

    @Override
    public int size() {
        return states.size();
    }

    @Override
    public BlockState get(int index) {
        return states.get(index);
    }

    @Override
    public int calculateIndex(Map<Property<?>, ?> state) {
        if (state.size() != propertyEntries.size()) {
            throw new IllegalArgumentException(getDetailedPropertyMismatchException(state));
        }
        int index = 0;
        for (PropertyEntry entry : propertyEntries) {
            Object value = state.get(entry.property);
            if (value == null) {
                if (!state.containsKey(entry.property)) {
                    throw new IllegalArgumentException(getDetailedPropertyMismatchException(state));
                }
                throw new IllegalArgumentException("Null value for property " + entry.property.getName());
            }
            int offset = entry.getOffsetForValueOrInvalid(value);
            if (offset == -1) {
                throw new IllegalArgumentException("Invalid value for property " + entry.property.getName() + ": " + value);
            }
            index += offset;
        }
        return index;
    }

    private String getDetailedPropertyMismatchException(Map<Property<?>, ?> state) {
        // Spend some time computing the incorrect properties for better error messages
        Set<Property<?>> missingProperties = propertyEntries.stream()
            .map(e -> e.property)
            .filter(p -> !state.containsKey(p))
            .collect(Collectors.toSet());
        Set<Property<?>> extraProperties = state.keySet().stream()
            .filter(p -> propertyEntries.stream().noneMatch(e -> e.property.equals(p)))
            .collect(Collectors.toSet());
        StringBuilder errorMessage = new StringBuilder("State has incorrect number of properties.");
        if (!missingProperties.isEmpty()) {
            errorMessage.append(" Missing properties: ").append(missingProperties).append(".");
        }
        if (!extraProperties.isEmpty()) {
            errorMessage.append(" Extra properties: ").append(extraProperties).append(".");
        }
        return errorMessage.toString();
    }

    @Override
    public int updateIndexOrInvalid(int currentIndex, Property<?> property, Object oldValue, Object newValue) {
        if (currentIndex < 0 || currentIndex >= size()) {
            return -1;
        }
        for (PropertyEntry entry : propertyEntries) {
            if (entry.property.equals(property)) {
                int oldOffset = entry.getOffsetForValueOrInvalid(oldValue);
                int newOffset = entry.getOffsetForValueOrInvalid(newValue);
                if (oldOffset == -1 || newOffset == -1) {
                    return -1;
                }
                return currentIndex - oldOffset + newOffset;
            }
        }
        return -1;
    }

    @Override
    int propertyCount() {
        return propertyEntries.size();
    }

    @Override
    Property<?> propertyAt(int slot) {
        return propertyEntries.get(slot).property;
    }

    @Override
    int slotOf(Object property) {
        // Properties are usually the same instances, so try identity before equality
        for (int i = 0; i < propertyEntries.size(); i++) {
            if (propertyEntries.get(i).property == property) {
                return i;
            }
        }
        if (property instanceof Property<?>) {
            for (int i = 0; i < propertyEntries.size(); i++) {
                if (propertyEntries.get(i).property.equals(property)) {
                    return i;
                }
            }
        }
        return -1;
    }

    @Override
    Object valueAt(int stateIndex, int slot) {
        PropertyEntry entry = propertyEntries.get(slot);
        return entry.values.get((stateIndex / entry.stride) % entry.values.size());
    }
}
