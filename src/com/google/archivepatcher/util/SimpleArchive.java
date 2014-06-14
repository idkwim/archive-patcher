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

package com.google.archivepatcher.util;

import com.google.archivepatcher.Archive;
import com.google.archivepatcher.meta.CompressionMethod;
import com.google.archivepatcher.meta.DeflateCompressionOption;
import com.google.archivepatcher.meta.Flag;
import com.google.archivepatcher.parts.LocalSectionParts;
import com.google.archivepatcher.parts.CentralDirectoryFile;
import com.google.archivepatcher.parts.FileData;
import com.google.archivepatcher.parts.DataDescriptor;
import com.google.archivepatcher.parts.EndOfCentralDirectory;
import com.google.archivepatcher.parts.LocalFile;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * A feature-poor implementation that allows building up an archive at run
 * time. Things like the changing the compression level or preserving linux
 * permissions are not supported. The result will be a valid archive, but may
 * drop important meta-information.
 * 
 * This class is also very useful for unit testsing, as it can produce
 * nontrivial archives in memory.
 */
public class SimpleArchive extends Archive {
    private boolean useDataDescriptors = true;
    private boolean finished = false;

    protected void compressAndSetData(LocalFile lf, FileData fd,
            DataDescriptor dd, InputStream in) throws IOException {
        if (finished) throw new IllegalStateException("archive has been finished");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DeflaterOutputStream out = new DeflaterOutputStream(buffer,
                new Deflater(Deflater.DEFAULT_COMPRESSION, true));
        byte[] temp = new byte[4096];
        int numRead = -1;
        int uncompressedSize = 0;
        Checksum tempCrc32 = new CRC32();
        while ( (numRead = in.read(temp)) >= 0) {
            if (numRead == 0) continue;
            out.write(temp, 0, numRead);
            tempCrc32.update(temp, 0, numRead);
            uncompressedSize += numRead;
        }
        final int crc32 = (int) tempCrc32.getValue();
        out.close();
        byte[] data = buffer.toByteArray();
        fd.setData(data);
        lf.setCompressionMethod_16bit(CompressionMethod.DEFLATED.value);
        if (dd != null) {
            lf.setUncompressedSize_32bit(0);
            lf.setCompressedSize_32bit(0);
            lf.setCrc32_32bit(0);
            dd.setUncompressedSize_32bit(uncompressedSize);
            dd.setCompressedSize_32bit(data.length);
            dd.setCrc32_32bit(crc32);
            int flags = lf.getGeneralPurposeBitFlag_16bit();
            flags |= Flag.set(Flag.USE_DATA_DESCRIPTOR_FOR_SIZES_AND_CRC32, (short) flags);
            lf.setGeneralPurposeBitFlag_16bit(flags);
        } else {
            lf.setUncompressedSize_32bit(uncompressedSize);
            lf.setCompressedSize_32bit(data.length);
            lf.setCrc32_32bit(crc32);
            int flags = lf.getGeneralPurposeBitFlag_16bit();
            flags &= Flag.unset(Flag.USE_DATA_DESCRIPTOR_FOR_SIZES_AND_CRC32, (short) flags);
            lf.setGeneralPurposeBitFlag_16bit(flags);
        }
    }

    public void add(String path,
            InputStream uncompressedData) throws IOException {
        add(path, System.currentTimeMillis(), uncompressedData);
    }
    public void add(String path, final long millisUtc,
            InputStream uncompressedData) throws IOException {
        LocalFile lf = new LocalFile();
        lf.setExtraField(new byte[0]);
        lf.setFileName(path);
        short flags = 0;
        flags = Flag.setCompressionOption(DeflateCompressionOption.NORMAL, flags);
        if (useDataDescriptors) {
            flags = Flag.set(Flag.USE_DATA_DESCRIPTOR_FOR_SIZES_AND_CRC32, flags);
        }
        lf.setGeneralPurposeBitFlag_16bit(flags);
        lf.setLastModifiedFileDate_16bit(MsDosDate
                .fromMillisecondsSinceEpoch(millisUtc).to16BitPackedValue());
        lf.setLastModifiedFileTime_16bit(MsDosTime
                .fromMillisecondsSinceEpoch(millisUtc).to16BitPackedValue());
        lf.setVersionNeededToExtract_16bit(STANDARD_VERSION);
        FileData fd = new FileData(0);
        DataDescriptor dd = null;
        if (useDataDescriptors) {
            dd = new DataDescriptor();
        }
        compressAndSetData(lf, fd, dd, uncompressedData);
        
        CentralDirectoryFile cdf = new CentralDirectoryFile();
        if (dd != null) {
            cdf.setCompressedSize_32bit(dd.getCompressedSize_32bit());
            cdf.setCrc32_32bit(dd.getCrc32_32bit());
            cdf.setUncompressedSize_32bit(dd.getUncompressedSize_32bit());
        } else {
            cdf.setCompressedSize_32bit(lf.getCompressedSize_32bit());
            cdf.setCrc32_32bit(lf.getCrc32_32bit());
            cdf.setUncompressedSize_32bit(lf.getUncompressedSize_32bit());
        }
        cdf.setCompressionMethod_16bit(lf.getCompressionMethod_16bit());
        cdf.setDiskNumberStart_16bit(0);
        cdf.setExternalFileAttributes_32bit(0);
        cdf.setExtraField(lf.getExtraField());
        cdf.setFileComment("");
        cdf.setFileName(lf.getFileName());
        cdf.setGeneralPurposeBitFlag_16bit(lf.getGeneralPurposeBitFlag_16bit());
        cdf.setInternalFileAttributes_16bit(0);
        cdf.setLastModifiedFileDate_16bit(lf.getLastModifiedFileDate_16bit());
        cdf.setLastModifiedFileTime_16bit(lf.getLastModifiedFileTime_16bit());
        cdf.setVersionMadeBy_16bit(STANDARD_VERSION);
        cdf.setVersionNeededToExtract_16bit(lf.getVersionNeededToExtract_16bit());

        LocalSectionParts alp = new LocalSectionParts(null);
        alp.setFileDataPart(fd);
        alp.setDataDescriptorPart(dd);
        alp.setLocalFilePart(lf);
        local.append(alp);
        centralDirectory.append(cdf);
    }

    public void finishArchive() {
        if (finished) return;
        finished = true;

        // Iterator over local entries, aligning everything.
        int offset=0;
        for (LocalSectionParts alp : local.entries()) {
            CentralDirectoryFile cdf = centralDirectory.getByPath(
                    alp.getLocalFilePart().getFileName());
            cdf.setRelativeOffsetOfLocalHeader_32bit(offset);
            offset += alp.getStructureLength();
        }
        final int startOfCentralDirectory = offset;
        for (CentralDirectoryFile cdf : centralDirectory.entries()) {
            offset += cdf.getStructureLength();
        }
        final int lengthOfCentralDirectory = offset - startOfCentralDirectory;
        EndOfCentralDirectory eocd = new EndOfCentralDirectory();
        eocd.setDiskNumber_16bit(0);
        eocd.setDiskNumberOfStartOfCentralDirectory_16bit(0);
        eocd.setLengthOfCentralDirectory_32bit(lengthOfCentralDirectory);
        eocd.setNumEntriesInCentralDir_16bit(centralDirectory.entries().size());
        eocd.setOffsetOfStartOfCentralDirectoryRelativeToDisk_32bit(startOfCentralDirectory);
        eocd.setZipFileComment("");
        centralDirectory.setEocd(eocd);
    }

    @Override
    public void writeArchive(DataOutput out) throws IOException {
        finishArchive();
        super.writeArchive(out);
    }
}