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

import com.google.common.collect.Lists;
import com.sk89q.worldedit.extension.platform.Watchdog;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BlockTypeStateListTest {

    private static IntegerProperty property(String name, int count) {
        return new IntegerProperty(name, IntStream.range(0, count).boxed().toList());
    }

    private static class TestBlockType extends BlockType {
        private final Map<String, Property<?>> properties = new LinkedHashMap<>();
        private final LazyReference<BlockTypeStateList> states;

        TestBlockType(List<? extends Property<?>> properties) {
            this(properties, null, null);
        }

        TestBlockType(List<? extends Property<?>> properties, Function<BlockState, BlockState> defaults,
                      Watchdog watchdog) {
            super("test:states", defaults);
            properties.forEach(p -> this.properties.put(p.getName(), p));
            states = LazyReference.from(() -> BlockTypeStateList.createFor(this, watchdog));
        }

        @Override
        public Map<String, ? extends Property<?>> getPropertyMap() {
            return properties;
        }

        @Override
        BlockTypeStateList getInternalStateList() {
            return states.getValue();
        }
    }

    @Test
    void allCombinationsAndTransitionsMatchCartesianReference() {
        List<IntegerProperty> properties = List.of(property("a", 2), property("b", 3), property("c", 4));
        BlockType type = new TestBlockType(properties);
        List<List<Integer>> combinations = Lists.cartesianProduct(properties.stream().map(Property::getValues).toList());
        assertEquals(24, type.getAllStates().size());
        for (int i = 0; i < combinations.size(); i++) {
            Map<Property<?>, Object> expected = new LinkedHashMap<>();
            for (int p = 0; p < properties.size(); p++) {
                expected.put(properties.get(p), combinations.get(i).get(p));
            }
            BlockState state = type.getAllStates().get(i);
            assertEquals(expected, state.getStates());
            assertSame(state, type.getState(expected));
            for (IntegerProperty property : properties) {
                for (Integer value : property.getValues()) {
                    Map<Property<?>, Object> changed = new HashMap<>(expected);
                    changed.put(property, value);
                    BlockState next = state.with(property, value);
                    assertEquals(changed, next.getStates());
                    assertSame(type.getState(changed), next);
                    assertSame(state, next.with(property, state.getState(property)));
                }
            }
        }
    }

    @Test
    void equivalentPropertiesAndValuesUseEquality() {
        IntegerProperty original = new IntegerProperty("level", List.of(1000, 2000));
        IntegerProperty equivalent = new IntegerProperty("level", List.of(1000, 2000));
        BlockType type = new TestBlockType(List.of(original));
        BlockState state = type.getDefaultState();
        BlockState changed = state.with(equivalent, Integer.valueOf("2000"));
        assertNotEquals(state, changed);
        assertSame(type.getState(Map.of(equivalent, 2000)), changed);
        assertSame(state, state.with(equivalent, Integer.valueOf("1000")));
    }

    @Test
    void invalidTransitionsAreNoOpsAndInvalidMapsAreRejected() {
        IntegerProperty property = property("level", 2);
        IntegerProperty unknown = property("unknown", 2);
        BlockType type = new TestBlockType(List.of(property));
        BlockState state = type.getDefaultState();
        assertSame(state, state.with(property, 99));
        assertSame(state, state.with(property, null));
        assertSame(state, state.with(unknown, 1));
        assertThrows(IllegalArgumentException.class, () -> type.getState(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> type.getState(Map.of(property, 99)));
        assertThrows(IllegalArgumentException.class, () -> type.getState(Map.of(unknown, 1)));
        assertThrows(IllegalArgumentException.class, () -> type.getState(Map.of(property, 0, unknown, 0)));
        Map<Property<?>, Object> nullValue = new HashMap<>();
        nullValue.put(property, null);
        assertThrows(IllegalArgumentException.class, () -> type.getState(nullValue));
    }

    @Test
    void singletonAndSingleValuePropertiesWork() {
        BlockType singleton = new TestBlockType(List.of());
        assertEquals(1, singleton.getAllStates().size());
        assertSame(singleton.getDefaultState(), singleton.getState(Map.of()));
        assertSame(singleton.getDefaultState(), singleton.getDefaultState().with(property("x", 2), 1));
        assertThrows(IllegalArgumentException.class, () -> singleton.getState(Map.of(property("x", 1), 0)));
        assertThrows(IndexOutOfBoundsException.class, () -> singleton.getAllStates().get(1));
        BlockType singleValue = new TestBlockType(List.of(property("a", 1), property("b", 2), property("c", 1)));
        assertEquals(2, singleValue.getAllStates().size());
        assertSame(singleValue.getAllStates().get(1), singleValue.getDefaultState().with(property("b", 2), 1));
    }

    @Test
    void stateCollectionsAreImmutableAndStringsKeepPropertyOrder() {
        IntegerProperty z = property("z", 2);
        IntegerProperty a = property("a", 2);
        BlockType type = new TestBlockType(List.of(z, a));
        BlockState state = type.getDefaultState();
        assertEquals("test:states[z=0,a=0]", state.getAsString());
        assertThrows(UnsupportedOperationException.class, () -> state.getStates().put(z, 1));
        assertThrows(UnsupportedOperationException.class, () -> state.getStates().entrySet().iterator().next().setValue(1));
        assertThrows(UnsupportedOperationException.class, () -> type.getAllStates().set(0, state));
        assertSame(state.toBaseBlock(), state.toBaseBlock());
        assertSame(state, state.toBaseBlock().toImmutableState());
        assertEquals(state.hashCode(), type.getState(state.getStates()).hashCode());
    }

    @Test
    void defaultStateCallbackAndFuzzyBuilderRemainIndependent() {
        IntegerProperty a = property("a", 2);
        IntegerProperty b = property("b", 3);
        BlockType type = new TestBlockType(List.of(a, b), state -> state.with(b, 2), null);
        assertEquals(2, type.getDefaultState().getState(b));
        FuzzyBlockState.Builder builder = FuzzyBlockState.builder().type(type).withProperty(a, 1);
        FuzzyBlockState fuzzy = builder.build();
        builder.withProperty(a, 0).reset();
        assertEquals(Map.of(a, 1), fuzzy.getStates());
        assertSame(type.getState(Map.of(a, 1, b, 2)), fuzzy.getFullState());
        assertTrue(fuzzy.equalsFuzzy(fuzzy.getFullState()));
        assertSame(type.getDefaultState(), type.getFuzzyMatcher().getFullState());
    }

    private static Object valuesView(BlockState state) throws ReflectiveOperationException {
        Field field = BlockState.class.getDeclaredField("valuesView");
        field.setAccessible(true);
        return field.get(state);
    }

    @Test
    void valuesViewIsLazyCachedAndBehavesLikeAMap() {
        IntegerProperty z = property("z", 2);
        IntegerProperty a = property("a", 3);
        IntegerProperty foreign = property("foreign", 2);
        BlockType type = new TestBlockType(List.of(z, a));
        BlockState state = type.getDefaultState().with(z, 1).with(a, 2);

        Map<Property<?>, Object> view = state.getStates();
        assertSame(view, state.getStates());
        assertSame(view, state.toBaseBlock().getStates());

        Map<Property<?>, Object> expected = new LinkedHashMap<>();
        expected.put(z, 1);
        expected.put(a, 2);
        assertEquals(expected, view);
        assertEquals(view, expected);
        assertEquals(Map.of(z, 1, a, 2), view);
        assertEquals(expected.hashCode(), view.hashCode());
        assertEquals(List.copyOf(expected.entrySet()), List.copyOf(view.entrySet()));
        assertEquals(List.of(z, a), List.copyOf(view.keySet()));
        assertEquals(2, view.size());
        assertTrue(view.containsKey(z));
        assertTrue(view.containsKey(property("z", 2)));
        assertFalse(view.containsKey(foreign));
        assertFalse(view.containsKey("z"));
        assertNull(view.get(foreign));
        assertNull(state.getState(foreign));
        assertNotEquals(Map.of(z, 1), view);
        assertNotEquals(Map.of(z, 1, a, 1), view);

        assertThrows(UnsupportedOperationException.class, () -> view.remove(z));
        assertThrows(UnsupportedOperationException.class, view::clear);
        assertThrows(UnsupportedOperationException.class, () -> view.keySet().iterator().remove());
        assertEquals(expected, view);

        assertTrue(new TestBlockType(List.of()).getDefaultState().getStates().isEmpty());
    }

    @Test
    void stateOperationsDoNotMaterializeValuesView() throws ReflectiveOperationException {
        IntegerProperty a = property("a", 2);
        IntegerProperty b = property("b", 3);
        BlockType type = new TestBlockType(List.of(a, b));
        for (BlockState state : type.getAllStates()) {
            state.hashCode();
            state.equals(type.getDefaultState());
            state.equalsFuzzy(type.getDefaultState().toBaseBlock());
            state.equalsFuzzy(FuzzyBlockState.builder().type(type).withProperty(a, 1).build());
            state.getAsString();
            state.getState(a);
            state.with(b, 2);
            type.getState(Map.of(a, state.getState(a), b, state.getState(b)));
        }
        for (BlockState state : type.getAllStates()) {
            assertNull(valuesView(state), state.getAsString());
        }
    }

    @Test
    void equalityAndHashingAreConsistentAcrossHolders() {
        IntegerProperty a = property("a", 2);
        IntegerProperty b = property("b", 3);
        BlockType type = new TestBlockType(List.of(a, b));
        BlockType sameId = new TestBlockType(List.of(a, b));
        Set<BlockState> distinct = new HashSet<>(type.getAllStates());
        assertEquals(type.getAllStates().size(), distinct.size());
        for (BlockState state : type.getAllStates()) {
            BlockState twin = sameId.getState(state.getStates());
            assertEquals(state, twin);
            assertEquals(state.hashCode(), twin.hashCode());
            assertTrue(distinct.contains(twin));
            for (BlockState other : type.getAllStates()) {
                assertEquals(state == other, state.equals(other));
                assertEquals(state == other, state.equals(sameId.getState(other.getStates())));
            }
            FuzzyBlockState fuzzy = FuzzyBlockState.builder().type(type).withProperty(a, state.getState(a)).build();
            assertTrue(state.equalsFuzzy(fuzzy));
            assertTrue(fuzzy.equalsFuzzy(state));
            assertEquals(state.getState(a), fuzzy.getFullState().getState(a));
            assertTrue(state.equalsFuzzy(state.toBaseBlock()));
            assertTrue(state.toBaseBlock().equalsFuzzy(state));
            assertEquals("test:states[a=" + state.getState(a) + ",b=" + state.getState(b) + "]", state.getAsString());
        }
        assertEquals("test:states", new TestBlockType(List.of()).getDefaultState().getAsString());
    }

    @Test
    void baseBlockTransitionsPreserveNbt() {
        IntegerProperty property = property("level", 2);
        BlockType type = new TestBlockType(List.of(property));
        LinCompoundTag tag = LinCompoundTag.builder().putString("id", "test:container").build();
        BaseBlock block = type.getDefaultState().toBaseBlock(tag);
        BaseBlock changed = block.with(property, 1);
        assertEquals(tag, changed.getNbt());
        assertSame(type.getAllStates().get(1), changed.toImmutableState());
        assertEquals(0, block.getState(property));
    }

    @Test
    void highCardinalityStatesRoundTripAndTickWatchdog() {
        for (int[] counts : List.of(new int[] { 8, 8, 8, 8, 6 }, new int[] { 10, 10, 8, 7, 4 })) {
            List<IntegerProperty> properties = new ArrayList<>();
            int expectedSize = 1;
            for (int i = 0; i < counts.length; i++) {
                properties.add(property("p" + i, counts[i]));
                expectedSize *= counts[i];
            }
            Watchdog watchdog = mock(Watchdog.class);
            BlockType type = new TestBlockType(properties, null, watchdog);
            assertEquals(expectedSize, type.getAllStates().size());
            for (BlockState state : type.getAllStates()) {
                assertSame(state, type.getState(state.getStates()));
                for (IntegerProperty property : properties) {
                    int nextValue = (state.getState(property) + 1) % property.getValues().size();
                    Map<Property<?>, Object> expected = new HashMap<>(state.getStates());
                    expected.put(property, nextValue);
                    assertSame(type.getState(expected), state.with(property, nextValue));
                }
            }
            verify(watchdog, atLeastOnce()).tick();
        }
    }

    @Test
    void invalidCardinalitiesFailBeforeAllocatingStates() {
        BlockType empty = new TestBlockType(List.of(property("empty", 0)));
        assertThrows(IllegalArgumentException.class, empty::getAllStates);
        List<IntegerProperty> properties = IntStream.range(0, 31)
            .mapToObj(i -> property("p" + i, 2)).toList();
        BlockType overflow = new TestBlockType(properties);
        assertThrows(ArithmeticException.class, overflow::getAllStates);
    }
}
