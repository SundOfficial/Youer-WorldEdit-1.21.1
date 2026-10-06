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

import com.sk89q.worldedit.internal.block.BlockStateIdAccess;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable class that represents the state a block can be in.
 *
 * <p>Property values are not stored per state; they are derived from the state's index in its
 * {@link BlockTypeStateList}. {@link #getStates()} exposes them through a lazily created view.</p>
 */
@SuppressWarnings("unchecked")
public class BlockState implements BlockStateHolder<BlockState> {

    static {
        BlockStateIdAccess.setBlockStateInternalId(new BlockStateIdAccess.BlockStateInternalId() {
            @Override
            public int getInternalId(BlockState blockState) {
                return blockState.internalId;
            }

            @Override
            public void setInternalId(BlockState blockState, int internalId) {
                blockState.internalId = internalId;
            }
        });
    }

    private final BlockType blockType;
    /**
     * The owning state list, or {@code null} for states that store their own values (fuzzy states).
     */
    private final BlockTypeStateList stateList;
    private final int stateListIndex;

    private final BaseBlock emptyBaseBlock;
    private final LazyReference<String> lazyStringRepresentation;

    /**
     * Created on the first {@link #getStates()} call. Immutable, so a racy publication is harmless.
     */
    private Map<Property<?>, Object> valuesView;

    /**
     * The internal ID of the block state.
     */
    private volatile int internalId = BlockStateIdAccess.invalidId();

    BlockState(BlockType blockType, BlockTypeStateList stateList, int stateListIndex) {
        this.blockType = blockType;
        this.stateList = stateList;
        this.stateListIndex = stateListIndex;
        this.emptyBaseBlock = new BaseBlock(this);
        this.lazyStringRepresentation = LazyReference.from(this::computeAsString);
    }

    @Override
    public BlockType getBlockType() {
        return this.blockType;
    }

    @Override
    public <V> BlockState with(final Property<V> property, final V value) {
        if (this.stateList == null || this.stateListIndex == -1) {
            return this;
        }
        int slot = stateList.slotOf(property);
        if (slot == -1) {
            return this;
        }
        Object currentValue = stateList.valueAt(stateListIndex, slot);
        if (Objects.equals(currentValue, value)) {
            return this;
        }

        int newIndex = stateList.updateIndexOrInvalid(this.stateListIndex, property, currentValue, value);
        if (newIndex == -1) {
            return this;
        }
        return stateList.get(newIndex);
    }

    @Override
    public <V> V getState(final Property<V> property) {
        if (this.stateList == null) {
            return (V) getStates().get(property);
        }
        int slot = stateList.slotOf(property);
        return slot == -1 ? null : (V) stateList.valueAt(stateListIndex, slot);
    }

    @Override
    public Map<Property<?>, Object> getStates() {
        Map<Property<?>, Object> view = this.valuesView;
        if (view == null) {
            if (stateList == null || stateList.propertyCount() == 0) {
                view = Collections.emptyMap();
            } else {
                view = new BlockStateValuesView(stateList, stateListIndex);
            }
            this.valuesView = view;
        }
        return view;
    }

    @Override
    public boolean equalsFuzzy(BlockStateHolder<?> o) {
        if (null == o) {
            return false;
        }
        if (this == o) {
            // Added a reference equality check for speediness
            return true;
        }
        if (!getBlockType().equals(o.getBlockType())) {
            return false;
        }
        if (this.stateList == null) {
            // Fuzzy states hold their own values
            for (Map.Entry<Property<?>, Object> entry : getStates().entrySet()) {
                Object otherValue = o.getState(entry.getKey());
                if (otherValue != null && !Objects.equals(entry.getValue(), otherValue)) {
                    return false;
                }
            }
            return true;
        }
        if (o instanceof BlockState other && other.stateList == this.stateList) {
            // Same state list: every property is present on both, so the index identifies the values
            return this.stateListIndex == other.stateListIndex;
        }
        // Only properties present on both sides are compared
        for (int slot = 0; slot < stateList.propertyCount(); slot++) {
            Object otherValue = o.getState(stateList.propertyAt(slot));
            if (otherValue != null && !Objects.equals(stateList.valueAt(stateListIndex, slot), otherValue)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public BlockState toImmutableState() {
        return this;
    }

    @Override
    public BaseBlock toBaseBlock() {
        return this.emptyBaseBlock;
    }

    @Override
    public BaseBlock toBaseBlock(LazyReference<LinCompoundTag> compoundTag) {
        if (compoundTag == null) {
            return toBaseBlock();
        }
        return new BaseBlock(this, compoundTag);
    }

    @Override
    public String getAsString() {
        return lazyStringRepresentation.getValue();
    }

    private String computeAsString() {
        if (this.stateList == null) {
            return BlockStateHolder.super.getAsString();
        }
        int count = stateList.propertyCount();
        if (count == 0) {
            return blockType.id();
        }
        // Same format as BlockStateHolder#getAsString, without going through getStates()
        StringBuilder builder = new StringBuilder(blockType.id()).append('[');
        for (int slot = 0; slot < count; slot++) {
            if (slot > 0) {
                builder.append(',');
            }
            builder.append(stateList.propertyAt(slot).getName())
                .append('=')
                .append(stateList.valueAt(stateListIndex, slot).toString().toLowerCase(Locale.ROOT));
        }
        return builder.append(']').toString();
    }

    @Override
    public String toString() {
        return getAsString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BlockState blockState)) {
            return false;
        }

        return equalsFuzzy(blockState);
    }

    @Override
    public int hashCode() {
        if (this.stateList == null) {
            return Objects.hash(blockType, getStates());
        }
        // The index identifies the property values within a block type
        return 31 * blockType.hashCode() + stateListIndex;
    }
}
