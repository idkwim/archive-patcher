// Copyright 2014 Google Inc. All rights reserved.
// 
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.archivepatcher.patcher;

import com.google.archivepatcher.compression.BuiltInCompressionEngine;
import com.google.archivepatcher.delta.BuiltInDeltaEngine;
import com.google.archivepatcher.meta.Flag;
import com.google.archivepatcher.parts.DataDescriptor;
import com.google.archivepatcher.parts.LocalFile;
import com.google.archivepatcher.util.IOUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * The manifestation of a {@link PatchCommand#PATCH} in a patch file,
 * consisting of a {@link LocalFile}, optional {@link DataDescriptor}, a unique
 * ID representing the delta engine that created the data, a unique ID
 * representing the compression engine that compressed the data, and a binary
 * blob describing a patch to be applied against a resource in the original
 * archive from which the patch was generated.
 * <p>
 * When this part is written, it first writes the {@link LocalFile}, then
 * the {@link DataDescriptor} (if present), and finally the ID and patch data:
 * <br>[Local File Record]
 * <br>[Data Descriptor Record (if present)]
 * <br>[Delta generator ID]
 * <br>[Compression engine ID]
 * <br>[Patch data blob]
 * <p>
 * The data descriptor record will only be read (or written, when writing) if
 * the {@link LocalFile} part has the
 * {@link Flag#USE_DATA_DESCRIPTOR_FOR_SIZES_AND_CRC32} bit set.
 */
public class PatchMetadata extends RefreshMetadata {
    private int deltaGeneratorId;
    private int compressionEngineId;
    private byte[] patchData;

    /**
     * Creates an empty object with no parts, suitable for reading.
     */
    public PatchMetadata() {
        this(null, null, BuiltInDeltaEngine.NONE.getId(),
            BuiltInCompressionEngine.NONE.getId(), null);
    }

    /**
     * Creates a fully populated object, suitable for writing.
     * 
     * @param localFilePart the part to set
     * @param dataDescriptorPart the part to set (optional)
     * @param deltaGeneratorId the ID of the delta generator that created the
     * patch data.
     * @param compressionEngineId the ID of the compression engine that
     * compressed the data
     * @param patchData the patch blob
     */
    public PatchMetadata(LocalFile localFilePart,
            DataDescriptor dataDescriptorPart,
            int deltaGeneratorId,
            int compressionEngineId,
            byte[] patchData) {
        super(localFilePart, dataDescriptorPart);
        this.deltaGeneratorId = deltaGeneratorId;
        this.compressionEngineId = compressionEngineId;
        this.patchData = patchData;
    }

    @Override
    public void read(DataInput input, ArchivePatcherVersion patchVersion)
        throws IOException {
        super.read(input, patchVersion);
        if(patchVersion.asInteger >= 2) {
            deltaGeneratorId = (int) IOUtils.readUnsignedInt(input);
            compressionEngineId = (int) IOUtils.readUnsignedInt(input);
        } else {
            deltaGeneratorId = BuiltInDeltaEngine.JAVAXDELTA.getId();
            compressionEngineId = BuiltInCompressionEngine.NONE.getId();
        }
        int length = (int) IOUtils.readUnsignedInt(input);
        patchData = new byte[length];
        input.readFully(patchData);
    }

    @Override
    public void write(DataOutput output) throws IOException {
        super.write(output);
        IOUtils.writeUnsignedInt(output, deltaGeneratorId);
        IOUtils.writeUnsignedInt(output, compressionEngineId);
        IOUtils.writeUnsignedInt(output, patchData.length);
        output.write(patchData);
    }

    @Override
    public int getStructureLength() {
        return super.getStructureLength() + 4 + 4 + 4 + patchData.length;
    }

    /**
     * Returns the ID of the delta generator that created the patch data.
     * @return the ID
     */
    public int getDeltaGeneratorId() {
        return deltaGeneratorId;
    }

    /**
     * Returns the ID of the compression engine that compressed the data.
     * @return the ID
     */
    public int getCompressionEngineId() {
        return deltaGeneratorId;
    }

    /**
     * Returns the binary patch data.  This is the actual field within this
     * object; care should be taken not to modify the contents inadvertently.
     * @return the data
     */
    public byte[] getData() {
        return patchData;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + Integer.hashCode(deltaGeneratorId);
        result = prime * result + Integer.hashCode(compressionEngineId);
        result = prime * result + Arrays.hashCode(patchData);
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        PatchMetadata other = (PatchMetadata) obj;
        if (deltaGeneratorId != other.deltaGeneratorId)
            return false;
        if (compressionEngineId != other.compressionEngineId)
            return false;
        if (!Arrays.equals(patchData, other.patchData))
            return false;
        return true;
    }
}