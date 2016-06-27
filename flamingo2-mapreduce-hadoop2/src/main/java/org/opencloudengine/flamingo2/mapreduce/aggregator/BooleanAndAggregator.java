/**
 * Copyright (C) 2011 Flamingo Project (http://www.cloudine.io).
 * <p/>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p/>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.opencloudengine.flamingo2.mapreduce.aggregator;


import org.apache.hadoop.io.BooleanWritable;

/**
 * Aggregator for calculating the AND function over boolean values.
 * The default value when nothing is aggregated is true.
 */
public class BooleanAndAggregator implements Aggregator<BooleanWritable> {

    /**
     * Internal result
     */
    private boolean result = true;

    /**
     * Aggregate with a primitive boolean.
     *
     * @param value Boolean value to aggregate.
     */
    public void aggregate(boolean value) {
        result = result && value;
    }

    @Override
    public void aggregate(BooleanWritable value) {
        result = result && value.get();
    }

    /**
     * Set aggregated value using a primitive boolean.
     *
     * @param value Boolean value to set.
     */
    public void setAggregatedValue(boolean value) {
        result = value;
    }

    @Override
    public void setAggregatedValue(BooleanWritable value) {
        result = value.get();
    }

    @Override
    public BooleanWritable getAggregatedValue() {
        return new BooleanWritable(result);
    }

    @Override
    public BooleanWritable createAggregatedValue() {
        return new BooleanWritable();
    }
}