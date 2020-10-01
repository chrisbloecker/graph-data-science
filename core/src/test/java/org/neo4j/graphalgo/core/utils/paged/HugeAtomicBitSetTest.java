/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.utils.paged;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.neo4j.graphalgo.core.utils.mem.AllocationTracker;

import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

class HugeAtomicBitSetTest {

    @Test
    void testGetSetClear() {
        var bitSet = HugeAtomicBitSet.create(42, AllocationTracker.empty());
        assertFalse(bitSet.get(7));
        assertFalse(bitSet.get(8));
        assertFalse(bitSet.get(9));
        bitSet.set(8);
        assertFalse(bitSet.get(7));
        assertTrue(bitSet.get(8));
        assertFalse(bitSet.get(9));
        bitSet.clear(8);
        assertFalse(bitSet.get(7));
        assertFalse(bitSet.get(8));
        assertFalse(bitSet.get(9));
    }

    @Test
    void getAndSetReturnsTrueIfTheBitWasSet() {
        var bitSet = HugeAtomicBitSet.create(1, AllocationTracker.empty());
        bitSet.set(0);
        assertTrue(bitSet.getAndSet(0));
    }

    @Test
    void getAndSetReturnsFalseIfTheBitWasNotSet() {
        var bitSet = HugeAtomicBitSet.create(1, AllocationTracker.empty());
        assertFalse(bitSet.getAndSet(0));
    }

    @Test
    void getAndSetSetsTheBit() {
        var bitSet = HugeAtomicBitSet.create(1, AllocationTracker.empty());
        assertFalse(bitSet.get(0));
        bitSet.getAndSet(0);
        assertTrue(bitSet.get(0));
    }

    @ParameterizedTest
    @CsvSource({"0,1336", "0,63", "70,140"})
    void setRange(int startIndex, int endIndex) {
        var bitSet = HugeAtomicBitSet.create(1337, AllocationTracker.empty());
        bitSet.set(startIndex, endIndex);
        for (int i = 0; i < bitSet.capacity(); i++) {
            if (i < Math.abs(startIndex) || i > Math.abs(endIndex)) {
                assertFalse(bitSet.get(i), formatWithLocale("index %d expected to be false", i));
            } else {
                assertTrue(bitSet.get(i), formatWithLocale("index %d expected to be true", i));
            }
        }
    }

    @Test
    void setRangeParallel() {
        var bitSet = HugeAtomicBitSet.create(128, AllocationTracker.empty());
        var phaser = new Phaser(5);
        var pool = Executors.newCachedThreadPool();
        pool.submit(new SetTask(bitSet, phaser, 0, 16));
        pool.submit(new SetTask(bitSet, phaser, 16, 32));
        pool.submit(new SetTask(bitSet, phaser, 40, 80));
        pool.submit(new SetTask(bitSet, phaser, 100, 127));
        phaser.arriveAndAwaitAdvance();
        phaser.arriveAndAwaitAdvance();

        for (int i = 0; i < bitSet.capacity(); i++) {
            if (i >= 0 && i < 32) assertTrue(bitSet.get(i));
            else if (i >= 40 && i < 80) assertTrue(bitSet.get(i));
            else if (i >= 100 && i < 127) assertTrue(bitSet.get(i));
            else assertFalse(bitSet.get(i));
        }
    }

    private static final class SetTask implements Runnable {
        private final HugeAtomicBitSet habs;
        private final Phaser phaser;
        private final long startIndex;
        private final long endIndex;

        private SetTask(HugeAtomicBitSet habs, Phaser phaser, long startIndex, long endIndex) {
            this.habs = habs;
            this.phaser = phaser;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }

        @Override
        public void run() {
            phaser.arriveAndAwaitAdvance();
            habs.set(startIndex, endIndex);
            phaser.arrive();
        }
    }

    @Test
    void testFlipping() {
        var bitSet = HugeAtomicBitSet.create(42, AllocationTracker.empty());
        bitSet.flip(41);
        assertTrue(bitSet.get(41));
        bitSet.flip(41);
        assertFalse(bitSet.get(41));
    }

    @Test
    void testCardinality() {
        var bitSet = HugeAtomicBitSet.create(42, AllocationTracker.empty());
        assertEquals(0L, bitSet.cardinality());

        bitSet.set(41);
        assertEquals(1L, bitSet.cardinality());

        for (long i = 0; i < bitSet.size(); i++) {
            bitSet.set(i);
        }
        assertEquals(42L, bitSet.cardinality());
    }

    @Test
    void testClearAll() {
        var bitSet = HugeAtomicBitSet.create(100, AllocationTracker.empty());
        for (long i = 0; i < bitSet.size(); i++) {
            bitSet.set(i);
        }

        bitSet.clear();

        for (long i = 0; i < bitSet.size(); i++) {
            assertFalse(bitSet.get(i));
        }
    }

    @Test
    void testToBitSet() {
        var atomicBitSet = HugeAtomicBitSet.create(42, AllocationTracker.empty());
        atomicBitSet.set(1);
        atomicBitSet.set(9);
        atomicBitSet.set(8);
        atomicBitSet.set(4);

        var bitSet = atomicBitSet.toBitSet();
        assertEquals(atomicBitSet.cardinality(), bitSet.cardinality());
        assertTrue(bitSet.get(1));
        assertTrue(bitSet.get(9));
        assertTrue(bitSet.get(8));
        assertTrue(bitSet.get(4));

        bitSet.set(43);
    }

    @Test
    void testIsEmpty() {
        var atomicBitSet = HugeAtomicBitSet.create(42, AllocationTracker.empty());
        assertTrue(atomicBitSet.isEmpty());
        atomicBitSet.set(23);
        assertFalse(atomicBitSet.isEmpty());
        atomicBitSet.flip(23);
        assertTrue(atomicBitSet.isEmpty());
    }
}
