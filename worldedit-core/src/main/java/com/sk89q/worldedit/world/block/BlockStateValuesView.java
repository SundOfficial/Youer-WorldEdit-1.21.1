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

import com.sk89q.worldedit.registry.state.Property;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * An immutable, virtual {@code Property -> value} view of a {@link BlockState}.
 *
 * <p>Nothing is copied: every lookup is derived from the state index and the
 * {@link BlockTypeStateList}. Iteration follows the property declaration order.</p>
 */
final class BlockStateValuesView extends AbstractMap<Property<?>, Object> {

    private final BlockTypeStateList stateList;
    private final int stateIndex;
    private Set<Entry<Property<?>, Object>> entrySet;

    BlockStateValuesView(BlockTypeStateList stateList, int stateIndex) {
        this.stateList = stateList;
        this.stateIndex = stateIndex;
    }

    @Override
    public int size() {
        return stateList.propertyCount();
    }

    @Override
    public boolean isEmpty() {
        return stateList.propertyCount() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return stateList.slotOf(key) != -1;
    }

    @Override
    public Object get(Object key) {
        int slot = stateList.slotOf(key);
        return slot == -1 ? null : stateList.valueAt(stateIndex, slot);
    }

    @Override
    public Set<Entry<Property<?>, Object>> entrySet() {
        Set<Entry<Property<?>, Object>> result = entrySet;
        if (result == null) {
            result = new EntrySet();
            entrySet = result;
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Map<?, ?> other) || other.size() != size()) {
            return false;
        }
        try {
            for (int slot = 0; slot < stateList.propertyCount(); slot++) {
                if (!stateList.valueAt(stateIndex, slot).equals(other.get(stateList.propertyAt(slot)))) {
                    return false;
                }
            }
        } catch (ClassCastException | NullPointerException ignored) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (int slot = 0; slot < stateList.propertyCount(); slot++) {
            hash += stateList.propertyAt(slot).hashCode() ^ stateList.valueAt(stateIndex, slot).hashCode();
        }
        return hash;
    }

    private final class EntrySet extends AbstractSet<Entry<Property<?>, Object>> {
        @Override
        public int size() {
            return stateList.propertyCount();
        }

        @Override
        public Iterator<Entry<Property<?>, Object>> iterator() {
            return new Iterator<>() {
                private int slot;

                @Override
                public boolean hasNext() {
                    return slot < stateList.propertyCount();
                }

                @Override
                public Entry<Property<?>, Object> next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    int current = slot++;
                    return Map.entry(stateList.propertyAt(current), stateList.valueAt(stateIndex, current));
                }
            };
        }
    }
}
