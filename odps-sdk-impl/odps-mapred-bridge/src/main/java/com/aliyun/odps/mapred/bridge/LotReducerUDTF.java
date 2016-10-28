/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.odps.mapred.bridge;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;

import org.apache.commons.lang.ArrayUtils;

import com.aliyun.odps.Column;
import com.aliyun.odps.data.Record;
import com.aliyun.odps.data.TableInfo;
import com.aliyun.odps.io.LongWritable;
import com.aliyun.odps.data.RecordComparator;
import com.aliyun.odps.io.Writable;
import com.aliyun.odps.mapred.Partitioner;
import com.aliyun.odps.mapred.Reducer;
import com.aliyun.odps.mapred.Reducer.TaskContext;
import com.aliyun.odps.mapred.bridge.type.ColumnBasedRecordComparator;
import com.aliyun.odps.mapred.bridge.utils.MapReduceUtils;
import com.aliyun.odps.mapred.conf.BridgeJobConf;
import com.aliyun.odps.udf.ExecutionContext;
import com.aliyun.odps.udf.annotation.NotReuseArgumentObject;
import com.aliyun.odps.udf.annotation.PreferWritable;
import com.aliyun.odps.utils.ReflectionUtils;

/**
 * Reducer Implementation wrapper UDTF.
 */
@PreferWritable
@NotReuseArgumentObject
public class LotReducerUDTF extends LotTaskUDTF {

  private TaskContext ctx;

  class ReduceContextImpl extends UDTFTaskContextImpl implements TaskContext {

    // key 和 value将被复用，减少重复创建对象的开销
    private Record key;
    private Record value;

    private Comparator<Object[]> keyGroupingComparator;
    private LotGroupingRecordIterator itr;
    Partitioner partitioner;
    private long nextRecordCntr = 1;
    private long inputValueCounter;
    private long inputKeyCounter;

    public ReduceContextImpl(BridgeJobConf conf) {
      super(conf);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void configure(ExecutionContext ctx) {
      super.configure(ctx);
      String[] keyGrpColumns;
      Column[] keyRS;
      Class< ? extends RecordComparator> keyComparatorClass = null;
      Class< ? extends RecordComparator> keyGroupingComparatorClass = null;

      if (pipeMode) {
        conf.setReducerClass(pipeNode.getTransformClass());
        key = new WritableRecord(pipeNode.getInputKeySchema());
        value = new WritableRecord(pipeNode.getInputValueSchema());
        keyGrpColumns = pipeNode.getInputGroupingColumns();
        keyRS = pipeNode.getInputKeySchema();
        // hotfix for sprint21, should remove in sprint23
//        keyComparatorClass = pipeNode.getInputKeyComparatorClass();
//        keyGroupingComparatorClass = pipeNode.getInputKeyGroupingComparatorClass();
        keyComparatorClass = conf.getPipelineOutputKeyComparatorClass(pipeIndex - 1);
        keyGroupingComparatorClass = conf.getPipelineOutputKeyGroupingComparatorClass(pipeIndex - 1);
        // for reducer, only if pipeline mode should have partitioner
        Class<? extends Partitioner> partitionerClass = pipeNode.getPartitionerClass();
        if (partitionerClass != null) {
          partitioner = ReflectionUtils.newInstance(partitionerClass, getJobConf());
          partitioner.configure(conf);
        }
      } else {
        key = new WritableRecord(conf.getMapOutputKeySchema());
        value = new WritableRecord(conf.getMapOutputValueSchema());
        keyGrpColumns = conf.getOutputGroupingColumns();
        keyRS = conf.getMapOutputKeySchema();
        keyComparatorClass = conf.getOutputKeyComparatorClass();
        keyGroupingComparatorClass = conf.getOutputKeyGroupingComparatorClass();
      }

      if (keyGroupingComparatorClass != null) {
        keyGroupingComparator = ReflectionUtils.newInstance(keyGroupingComparatorClass, getJobConf());
      } else if (keyComparatorClass != null) {
        keyGroupingComparator = ReflectionUtils.newInstance(keyComparatorClass, getJobConf());
      } else {
        keyGroupingComparator = new ColumnBasedRecordComparator(keyGrpColumns, keyRS);
      }

      // for inner output
      if (innerOutput && pipeMode && 
          pipeNode != null && pipeNode.getNextNode() != null) {
        Column[] keyCols = pipeNode.getOutputKeySchema();
        Column[] valCols = pipeNode.getOutputValueSchema();

        Column[] outputFields = new Column[keyCols.length + valCols.length + packagedOutputSchema.length];
        int len = 0;
        for (Column col : keyCols) {
          outputFields[len++] = col;
        }
        for (Column col : valCols) {
          outputFields[len++] = col;
        }
        innerOutputIndex = len;
        for (Column col : packagedOutputSchema) {
          outputFields[len++] = col;
        }
        packagedOutputSchema = outputFields;
      }
    }

    @Override
    public void write(Record r) throws IOException {
      write(r, TableInfo.DEFAULT_LABEL);
    }

    @Override
    public void write(Record r, String label) throws IOException {
      if (!hasLabel(label)) {
        throw new IOException(ErrorCode.NO_SUCH_LABEL.toString() + " " + label);
      }
      if (innerOutput) {
        write(createInnerOutputRow(((WritableRecord)r).toWritableArray(), true, TableInfo.INNER_OUTPUT_LABEL, label));
      } else {
        write(createOutputRow(r, label));
      }
    }

    protected void write(Object[] record) {
      collect(record);
    }

    @Override
    public boolean nextKeyValue() {
      inputKeyCounter++;
      // 第一次获取数据
      if (itr == null) {
        Object[] init = getData();
        if (init == null) {
          return false;
        }

        key.set(Arrays.copyOf(init, key.getColumnCount()));
        value.set(Arrays.copyOfRange(init, key.getColumnCount(), init.length));
        itr = new LotGroupingRecordIterator(this, keyGroupingComparator, init,
                                            (WritableRecord) key, (WritableRecord) value);
      } else {
        while (itr.hasNext()) {
          itr.remove();
        }
        if (!itr.reset()) {
          return false;
        }
      }

      return true;
    }

    @Override
    public Record getCurrentKey() {
      return key;
    }

    @Override
    public Iterator<Record> getValues() {
      return itr;
    }

    @Override
    public void write(Record key, Record value) {
      if (!pipeMode || pipeNode.getNextNode() == null) {
        throw new UnsupportedOperationException(
            ErrorCode.INTERMEDIATE_OUTPUT_IN_REDUCER.toString());
      }
      // pipeline mode
      Writable[] keyArray = ((WritableRecord) key).toWritableArray();
      Writable[] valueArray = ((WritableRecord) value).toWritableArray();
      Writable[] result;
      int idx = 0;
      if (partitioner != null) {
        int part = partitioner.getPartition(key, value, getNumReduceTasks());
        if (part < 0 || part >= getNumReduceTasks()) {
          throw new RuntimeException("partitioner return invalid partition value:" + part);
        }
        result = new Writable[1 + keyArray.length + valueArray.length];
        result[idx++] = new LongWritable(part);
      } else {
        result = new Writable[keyArray.length + valueArray.length];
      }
      for (Writable obj : keyArray) {
        result[idx++] = obj;
      }
      for (Writable obj : valueArray) {
        result[idx++] = obj;
      }

      if (innerOutput) {
        write(createInnerOutputRow(result, false, TableInfo.DEFAULT_LABEL, TableInfo.DEFAULT_LABEL));
      } else {
        write(result);
      }
    }

    public Object[] getData() {
      inputValueCounter++;
      if (inputValueCounter == nextRecordCntr) {
        StateInfo.updateMemStat("after processed " + inputKeyCounter + " keys, "
            + inputValueCounter + " values");
        nextRecordCntr = getNextCntr(inputValueCounter, false);
      }
      return getNextRowWapper();
    }
  }

  @Override
  public void setup(ExecutionContext eCtx) {
    ctx = new ReduceContextImpl(conf);
    Column[] ks = conf.getMapOutputKeySchema();
    Column[] vs = conf.getMapOutputValueSchema();
    inputSchema = (Column[]) ArrayUtils.addAll(ks, vs);
    UDTFTaskContextImpl udtfCtx = (UDTFTaskContextImpl) ctx;
    udtfCtx.configure(eCtx);
  }

  public void run() throws IOException {
    StateInfo.init();
    MapReduceUtils.runReducer((Class<Reducer>) conf.getReducerClass(), ctx);
    StateInfo.updateMemStat("reducer end");
    StateInfo.printMaxMemo();
  }

  // 对父类的final方法getNextRow进行封装，便于测试
  public Object[] getNextRowWapper() {
    Object[] data = getNextRow();
    if (data != null) {
      return data.clone();
    } else {
      return null;
    }
  }
}
