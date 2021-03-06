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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.io.hfile;

import java.io.DataOutputStream;
import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.io.hfile.HFile.FileInfo;
import org.apache.hadoop.hbase.io.hfile.HFile.Writer;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.WritableUtils;

/**
 * This is an extension of HFileWriterV2 that is tags aware.
 */
@InterfaceAudience.Private
public class HFileWriterV3 extends HFileWriterV2 {

  // TODO : Use this to track maxtaglength
  private int maxTagsLength = 0;

  static class WriterFactoryV3 extends HFile.WriterFactory {
    WriterFactoryV3(Configuration conf, CacheConfig cacheConf) {
      super(conf, cacheConf);
    }

    @Override
    public Writer createWriter(FileSystem fs, Path path, FSDataOutputStream ostream,
        final KVComparator comparator, HFileContext fileContext)
        throws IOException {
      return new HFileWriterV3(conf, cacheConf, fs, path, ostream, comparator, fileContext);
    }
  }

  /** Constructor that takes a path, creates and closes the output stream. */
  public HFileWriterV3(Configuration conf, CacheConfig cacheConf, FileSystem fs, Path path,
      FSDataOutputStream ostream, final KVComparator comparator,
      final HFileContext fileContext) throws IOException {
    super(conf, cacheConf, fs, path, ostream, comparator, fileContext);
  }

  /**
   * Add key/value to file. Keys must be added in an order that agrees with the
   * Comparator passed on construction.
   * 
   * @param kv
   *          KeyValue to add. Cannot be empty nor null.
   * @throws IOException
   */
  @Override
  public void append(final KeyValue kv) throws IOException {
    // Currently get the complete arrays
    append(kv.getMvccVersion(), kv.getBuffer(), kv.getKeyOffset(), kv.getKeyLength(),
        kv.getBuffer(), kv.getValueOffset(), kv.getValueLength(), kv.getBuffer(),
        kv.getTagsOffset(), kv.getTagsLength());
    this.maxMemstoreTS = Math.max(this.maxMemstoreTS, kv.getMvccVersion());
  }
  
  /**
   * Add key/value to file. Keys must be added in an order that agrees with the
   * Comparator passed on construction.
   * @param key
   *          Key to add. Cannot be empty nor null.
   * @param value
   *          Value to add. Cannot be empty nor null.
   * @throws IOException
   */
  @Override
  public void append(final byte[] key, final byte[] value) throws IOException {
    append(key, value, HConstants.EMPTY_BYTE_ARRAY);
  }

  /**
   * Add key/value to file. Keys must be added in an order that agrees with the
   * Comparator passed on construction.
   * @param key
   *          Key to add. Cannot be empty nor null.
   * @param value
   *          Value to add. Cannot be empty nor null.
   * @param tag
   *          Tag t add. Cannot be empty or null.
   * @throws IOException
   */
  @Override
  public void append(final byte[] key, final byte[] value, byte[] tag) throws IOException {
    append(0, key, 0, key.length, value, 0, value.length, tag, 0, tag.length);
  }

  /**
   * Add key/value to file. Keys must be added in an order that agrees with the
   * Comparator passed on construction.
   * @param key
   * @param koffset
   * @param klength
   * @param value
   * @param voffset
   * @param vlength
   * @param tag
   * @param tagsOffset
   * @param tagLength
   * @throws IOException
   */
  private void append(final long memstoreTS, final byte[] key, final int koffset,
      final int klength, final byte[] value, final int voffset, final int vlength,
      final byte[] tag, final int tagsOffset, final int tagsLength) throws IOException {
    boolean dupKey = checkKey(key, koffset, klength);
    checkValue(value, voffset, vlength);
    if (!dupKey) {
      checkBlockBoundary();
    }

    if (!fsBlockWriter.isWriting())
      newBlock();

    // Write length of key and value and then actual key and value bytes.
    // Additionally, we may also write down the memstoreTS.
    {
      DataOutputStream out = fsBlockWriter.getUserDataStream();
      out.writeInt(klength);
      totalKeyLength += klength;
      out.writeInt(vlength);
      totalValueLength += vlength;
      out.write(key, koffset, klength);
      out.write(value, voffset, vlength);
      // Write the additional tag into the stream
      if (hFileContext.shouldIncludeTags()) {
        out.writeShort((short) tagsLength);
        if (tagsLength > 0) {
          out.write(tag, tagsOffset, tagsLength);
          if (tagsLength > maxTagsLength) {
            maxTagsLength = tagsLength;
          }
        }
      }
      if (this.hFileContext.shouldIncludeMvcc()) {
        WritableUtils.writeVLong(out, memstoreTS);
      }
    }

    // Are we the first key in this block?
    if (firstKeyInBlock == null) {
      // Copy the key.
      firstKeyInBlock = new byte[klength];
      System.arraycopy(key, koffset, firstKeyInBlock, 0, klength);
    }

    lastKeyBuffer = key;
    lastKeyOffset = koffset;
    lastKeyLength = klength;
    entryCount++;
  }
  
  protected void finishFileInfo() throws IOException {
    super.finishFileInfo();
    if (hFileContext.shouldIncludeTags()) {
      // When tags are not being written in this file, MAX_TAGS_LEN is excluded
      // from the FileInfo
      fileInfo.append(FileInfo.MAX_TAGS_LEN, Bytes.toBytes(this.maxTagsLength), false);
    }
  }

  @Override
  protected HFileBlock.Writer createBlockWriter() {
    // HFile filesystem-level (non-caching) block writer
    hFileContext.setIncludesTags(true);
    hFileContext.setUsesHBaseChecksum(true);
    return new HFileBlock.Writer(blockEncoder, hFileContext);
  }

  @Override
  protected int getMajorVersion() {
    return 3;
  }

  @Override
  protected int getMinorVersion() {
    return HFileReaderV3.MAX_MINOR_VERSION;
  }
}