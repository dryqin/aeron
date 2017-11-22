/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.status;

import org.agrona.UnsafeAccess;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.status.CountersReader;

import static org.agrona.BitUtil.SIZE_OF_LONG;

/**
 * Readonly View of a {@link io.aeron.Counter}.
 */
public class ReadableCounter implements AutoCloseable
{
    private final CountersReader countersReader;
    private final byte[] buffer;
    private final int id;
    private final long addressOffset;
    private volatile boolean isClosed = false;

    /**
     * Construct a view of an existing counter.
     *
     * @param countersReader for getting access to the buffers.
     * @param id             for the counter to be viewed.
     * @throws IllegalStateException if the id has for the counter has not been allocated.
     */
    public ReadableCounter(final CountersReader countersReader, final int id)
    {
        if (countersReader.getCounterState(id) != CountersReader.RECORD_ALLOCATED)
        {
            throw new IllegalStateException("Counter id has not been allocated: " + id);
        }

        this.countersReader = countersReader;
        this.id = id;

        final AtomicBuffer valuesBuffer = countersReader.valuesBuffer();
        final int counterOffset = CountersReader.counterOffset(id);
        valuesBuffer.boundsCheck(counterOffset, SIZE_OF_LONG);

        this.buffer = valuesBuffer.byteArray();
        this.addressOffset = valuesBuffer.addressOffset() + counterOffset;
    }

    /**
     * Return the counter Id.
     *
     * @return counter Id.
     */
    public int id()
    {
        return id;
    }

    /**
     * Return the state of the counter.
     *
     * @see CountersReader#RECORD_ALLOCATED
     * @see CountersReader#RECORD_LINGERING
     * @see CountersReader#RECORD_RECLAIMED
     * @see CountersReader#RECORD_UNUSED
     *
     * @return state for the counter.
     */
    public int state()
    {
        return countersReader.getCounterState(id);
    }

    /**
     * Return the counter label.
     *
     * @return the counter label.
     */
    public String label()
    {
        return countersReader.getCounterLabel(id);
    }

    /**
     * Get the latest value for the counter with volatile semantics.
     *
     * @return the latest value for the counter.
     */
    public long get()
    {
        return UnsafeAccess.UNSAFE.getLongVolatile(buffer, addressOffset);
    }

    /**
     * Get the value of the counter using weak ordering semantics. This is the same a standard read of a field.
     *
     * @return the  value for the counter.
     */
    public long getWeak()
    {
        return UnsafeAccess.UNSAFE.getLong(buffer, addressOffset);
    }

    /**
     * Close this counter. This has no impact on the {@link io.aeron.Counter} it is viewing.
     */
    public void close()
    {
        isClosed = true;
    }

    /**
     * Has this counters been closed and should no longer be used?
     *
     * @return true if it has been closed otherwise false.
     */
    public boolean isClosed()
    {
        return isClosed;
    }
}
