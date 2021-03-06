/*
 * 
 *  Copyright © 2010, 2011 Inadco, Inc. All rights reserved.
 *  
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *  
 *  
 */
package com.inadco.hbl.mapreduce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import com.inadco.hbl.client.AggregateResultSet;
import com.inadco.hbl.client.HblException;
import com.inadco.hbl.client.HblQueryClient;
import com.inadco.hbl.client.PreparedAggregateQuery;
import com.inadco.hbl.client.PreparedAggregateResult;
import com.inadco.hbl.client.impl.PreparedAggregateQueryImpl;
import com.inadco.hbl.client.impl.scanner.CompositeKeyRowFilter;
import com.inadco.hbl.client.impl.scanner.ScanSpec;
import com.inadco.hbl.util.HblUtil;

/**
 * HblInputFormat : yet another way to query cube data in distributed way and
 * send it off to mappers of a MapReduce task for processing.
 * <P>
 * 
 * Behind the scenes, it is using {@link PreparedAggregateQuery} to execute it
 * and therefore is using hbl query language to set it up. Use
 * {@link #setHblQuery(Job, String)} method to set the query and
 * {@link #setHblParam(Configuration, int, String)} method to initialize
 * parameters for '?'. At this time, only parameters that allow conversion from
 * a String, will work.
 * <P>
 * 
 * Since query execution is a bunch of scans, this class figures out a good way
 * to assign tasks to region locations so that scans are mostly local w.r.t to
 * the data used.
 * <P>
 * 
 * Extending {@link HblMapper} helper is recommended to ensure proper data cast
 * to the map task.
 * <P>
 * 
 * See also MRExample1Query.java in sample module for an example of a MapReduce
 * query set up this way.
 * <P>
 * 
 * @author dmitriy
 * 
 */
public class HblInputFormat extends InputFormat<NullWritable, PreparedAggregateResult> {

    public static final String PROP_QUERY    = "hbl.mapred.query";
    public static final String PROP_PARAM_NO = "hbl.mapred.paramno";
    public static final String PROP_PARAM    = "hbl.mapred.param.";

    public HblInputFormat() {
        super();
    }

    public static void setHblQuery(Job job, String query) {
        job.getConfiguration().set(PROP_QUERY, query);
    }

    static String getHblQuery(Configuration conf) {
        return conf.get(PROP_QUERY);
    }

    /**
     * Set hbl parameters for prepared queries. Only supports typeless string
     * values here. If dimension doesn't happen to support it for slices, for
     * now -- sorry.
     * 
     * @param conf
     *            MR job configuration
     * @param paramNo
     *            the number of query parameter being set
     * @param paramValue
     *            the value of the parameter (as string)
     */
    public static void setHblParam(Configuration conf, int paramNo, String paramValue) {
        String paramName = PROP_PARAM + paramNo;
        conf.set(paramName, paramValue);
        int totalParams = getParamNo(conf);
        if (totalParams <= paramNo)
            /*
             * hbl assumes 0-based param enumeration
             */
            setParamNo(conf, paramNo + 1);
    }

    static String getHblParam(Configuration conf, int paramNo) {
        String paramName = PROP_PARAM + paramNo;
        return conf.get(paramName);
    }

    static void setParamNo(Configuration conf, int paramNo) {
        conf.setInt(PROP_PARAM_NO, paramNo);
    }

    static int getParamNo(Configuration conf) {
        return conf.getInt(PROP_PARAM_NO, 0);
    }

    @Override
    public List<InputSplit> getSplits(JobContext context) throws IOException, InterruptedException {
        try {
            Configuration conf = context.getConfiguration();
            HblQueryClient hblQueryClient = new HblQueryClient(conf);

            PreparedAggregateQueryImpl paq = (PreparedAggregateQueryImpl) hblQueryClient.createPreparedQuery();
            paq.prepare(getHblQuery(conf));

            int paramNo = getParamNo(conf);
            for (int i = 0; i < paramNo; i++)
                paq.setHblParameter(i, getParamNo(conf));

            List<ScanSpec> scanSpecs = paq.generateScanSpecs(null, null);

            String cuboidTableName = null;
            byte[] startKey = null;
            byte[] endKey = null;
            int groupKeyLen = 0;

            List<InputSplit> result = new ArrayList<InputSplit>();

            for (ScanSpec scanSpec : scanSpecs) {
                String tname = scanSpec.getCuboid().getCuboidTableName();
                if (cuboidTableName == null) {
                    cuboidTableName = tname;
                    groupKeyLen = scanSpec.getGroupKeyLen();
                } else if (!cuboidTableName.equals(tname))
                    throw new UnsupportedOperationException("Scan specs to different tables are not yet supported.");

                /*
                 * here, we will venture a little bit into scan spec unravelling
                 * into start and end cuboid keys to figure out our total scan
                 * range.
                 */
                CompositeKeyRowFilter krf = new CompositeKeyRowFilter(scanSpec.getRanges());

                /*
                 * AFAIK this generates closed bounds. Figure the overlapping
                 * range so we have something to go on for splits.
                 */
                byte[] startRow = krf.getCompositeBound(true);
                byte[] endRow = krf.getCompositeBound(false);

                if (startKey == null) {
                    startKey = startRow;
                    endKey = endRow;
                } else {
                    if (Bytes.compareTo(startKey, startRow) > 0)
                        startKey = startRow;
                    else if (Bytes.compareTo(endKey, endRow) < 0)
                        endKey = endRow;
                }
            }

            if (HblUtil.incrementKey(endKey, 0, endKey.length))
                endKey = null; // right-unbounded

            HTable htable = new HTable(context.getConfiguration(), cuboidTableName);
            byte[][] breaks = htable.getStartKeys();

            int splitsNum = breaks.length;

            // DEBUG
            // System.out.printf("splits :%d\n", splitsNum);

            for (int i = 1; i <= splitsNum; i++) {
                byte[] startSplit = breaks[i - 1];
                byte[] endSplit = i == splitsNum ? null : breaks[i];

                /*
                 * adjust split boundary to the group boundary. We kind of
                 * assume that the first region always starts with an empty key
                 * so it is, whatever it is, must be o.k. already.
                 */

                if (endSplit != null && endSplit.length > groupKeyLen) {

                    /*
                     * round end split boundary to the closest group key.
                     * 
                     * Naive test for proximity to choose between rounding up or
                     * down. This "optimization" is of course very crude since
                     * it assumes uniformly distributed dimensional key data.
                     * Which sometimes may be 50% wrong (e.g. calendar-time
                     * dimension will always be rounded down only). However, we
                     * cannot guess how many records may be present in the group
                     * at this point, so... whatever.
                     */
                    if ((endSplit[groupKeyLen] & 0x80) == 0x80) {
                        /*
                         * round up the end split.
                         */
                        if (HblUtil.incrementKey(endSplit, 0, groupKeyLen))
                            /*
                             * if overflow during rounding up the grouping key
                             * then assume null aka till-the-end-of-the-table
                             * key
                             */
                            breaks[i] = endSplit = null;
                    }
                    /*
                     * ... otherwise round down, i.e. do nothing here.
                     */

                    /*
                     * the rounding itself by flushing lower part of the key
                     * with 0s.
                     */
                    if (endSplit != null)
                        Arrays.fill(endSplit, groupKeyLen, endSplit.length, (byte) 0);
                }

                /*
                 * Skip -- and get rid of -- degenerate group splits (e.g. no
                 * single group fits into the split.))
                 */
                if (endSplit != null && 0 == Bytes.compareTo(startSplit, endSplit)) {
                    System.arraycopy(breaks, i + 1, breaks, i, splitsNum-- - i - 1);
                    i--;
                    continue;
                }
                /*
                 * if end split less than start key, skip the split too.
                 */
                if (endSplit != null && Bytes.compareTo(endSplit, startKey) <= 0)
                    continue;
                /*
                 * if start split less than start key, use start key for the
                 * start split
                 */
                if (Bytes.compareTo(startSplit, startKey) < 0)
                    startSplit = startKey;

                /*
                 * if end split larger than end key, then use the end key for
                 * the split.
                 */
                if (endKey != null) {
                    if (endSplit == null || Bytes.compareTo(endSplit, endKey) > 0)
                        endSplit = endKey;
                }
                HRegionLocation hloc = htable.getRegionLocation(startSplit, false);

                result.add(new HblInputSplit(hloc.getHostname(), cuboidTableName, startSplit, endSplit));
            }

            // DEBUG
            // int i = 0;
            // for (InputSplit split : result) {
            // HblInputSplit hblSplit = (HblInputSplit) split;
            // byte[] sk = hblSplit.getStartGroupingKey();
            // byte[] ek = hblSplit.getEndGroupingKey();
            // if (ek == null)
            // ek = new byte[0];
            //
            // System.out.printf("hbl split %d: start %X:%X.\n", ++i, new
            // BigInteger(1, sk), new BigInteger(1, ek));
            // }

            return result;

        } catch (HblException exc) {
            throw new IOException(exc);
        }
    }

    @Override
    public RecordReader<NullWritable, PreparedAggregateResult> createRecordReader(InputSplit split,
                                                                                  TaskAttemptContext context)
        throws IOException, InterruptedException {
        return new RecordReader<NullWritable, PreparedAggregateResult>() {

            private AggregateResultSet ars;

            @Override
            public void initialize(InputSplit split, TaskAttemptContext context) throws IOException,
                InterruptedException {

                try {
                    Configuration conf = context.getConfiguration();
                    HblQueryClient hblQueryClient = new HblQueryClient(conf);
                    HblInputSplit hblSplit = (HblInputSplit) split;

                    PreparedAggregateQueryImpl paq = (PreparedAggregateQueryImpl) hblQueryClient.createPreparedQuery();
                    paq.prepare(getHblQuery(conf));

                    String cuboidTableName = hblSplit.getCuboidTable();
                    if (cuboidTableName == null)
                        throw new HblException("Invalid cuboid name at backend. Something in MR happened wrong.");

                    int paramNo = getParamNo(conf);
                    for (int i = 0; i < paramNo; i++)
                        paq.setHblParameter(i, getParamNo(conf));

                    ars = paq.execute(hblSplit.getStartGroupingKey(), hblSplit.getEndGroupingKey(), cuboidTableName);

                } catch (HblException exc) {
                    throw new IOException(exc);
                }

            }

            @Override
            public boolean nextKeyValue() throws IOException, InterruptedException {
                if (!ars.hasNext())
                    return false;
                ars.next();
                return true;
            }

            @Override
            public NullWritable getCurrentKey() throws IOException, InterruptedException {
                return NullWritable.get();
            }

            @Override
            public PreparedAggregateResult getCurrentValue() throws IOException, InterruptedException {
                return (PreparedAggregateResult) ars.current();
            }

            @Override
            public float getProgress() throws IOException, InterruptedException {
                // TODO Auto-generated method stub
                return 0;
            }

            @Override
            public void close() throws IOException {
                ars.close();
            }

        };
    }
}
