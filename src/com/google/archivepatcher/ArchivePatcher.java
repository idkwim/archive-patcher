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

package com.google.archivepatcher;

import com.google.archivepatcher.compression.Compressor;
import com.google.archivepatcher.compression.Uncompressor;
import com.google.archivepatcher.delta.BuiltInDeltaEngine;
import com.google.archivepatcher.delta.DeltaApplier;
import com.google.archivepatcher.delta.DeltaGenerator;
import com.google.archivepatcher.delta.DeltaUtils;
import com.google.archivepatcher.parts.CentralDirectoryFile;
import com.google.archivepatcher.parts.CentralDirectorySection;
import com.google.archivepatcher.patcher.BeginMetadata;
import com.google.archivepatcher.patcher.NewMetadata;
import com.google.archivepatcher.patcher.PatchDirective;
import com.google.archivepatcher.patcher.PatchMetadata;
import com.google.archivepatcher.patcher.PatchParser;
import com.google.archivepatcher.patcher.RefreshMetadata;
import com.google.archivepatcher.reporting.PatchGenerationReport;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tool for generating and applying archive-based patches.
 */
public class ArchivePatcher extends AbstractArchiveTool {

    /**
     * Runs the standalone tool.
     * @param args command line arguments
     * @throws Exception if anything goes wrong
     */
    public final static void main(String... args) throws Exception {
        new ArchivePatcher().run(args);
    }

    /**
     * Used to pretty-print numbers.
     */
    private final NumberFormat prettyDecimalFormat =
        NumberFormat.getNumberInstance(Locale.US);

    /**
     * Convenience method to pretty-format a number with decimal places and
     * commas so that it appears in a human-friendly format.
     * 
     * @param number the number to be formatted
     * @return the formatted numbers
     */
    private String f(Number number) {
        return prettyDecimalFormat.format(number);
    }

    @Override
    protected void configureOptions(MicroOptions options) {
        super.configureOptions(options);
        options.option("makepatch").isUnary()
            .describedAs("generate a patch to transform --old into --new");
        options.option("report").isUnary()
            .describedAs("with --makepatch, show detailed report on patch generation");
        options.option("applypatch").isUnary()
            .describedAs("apply a patch file to --old, creating --new");
        options.option("explain").isUnary()
            .describedAs("explain a patch in the context of --old and --new");
        options.option("old").isRequired()
            .describedAs("the old archive to read from");
        options.option("new").isRequired()
            .describedAs("the archive that --old is being transformed into");
        options.option("patch").isRequired()
            .describedAs("the path to the patchfile to create or apply");
    }

    @Override
    protected final void run(MicroOptions options) throws Exception {
        int numModeArgs = 0;
        if (options.has("explain")) numModeArgs++;
        if (options.has("makepatch")) numModeArgs++;
        if (options.has("applypatch")) numModeArgs++;
        if (numModeArgs != 1) {
            throw new MicroOptions.OptionException(
                "Must specify exactly one of the following options: " +
                "--makepatch, --applypatch, --explain");
        }

        if (options.has("explain")) {
            explain(options.getArg("old"),
                    options.getArg("new"),
                    options.getArg("patch"));
            return;
        }

        if (options.has("makepatch")) {
            PatchGenerationReport report = makePatch(options.getArg("old"),
                    options.getArg("new"),
                    options.getArg("patch"),
                    DeltaUtils.getBuiltInDeltaGenerators(),
                    DeltaUtils.getBuiltInCompressors());
            if (options.has("report")) {
                log(report.toString());
            }
            return;
        }

        if (options.has("applypatch")) {
            applyPatch(options.getArg("old"),
                    options.getArg("new"),
                    options.getArg("patch"),
                    DeltaUtils.getBuiltInDeltaAppliers(),
                    DeltaUtils.getBuiltInUncompressors());
            return;
        }
    }

    /**
     * Create a patch at the specified path (patchFile) that will transform
     * one archive (oldFile) to another (newFile) when applied with
     * {@link #applyPatch(String, String, String, List, List)} using a
     * compatible {@link DeltaApplier}.
     * 
     * @param oldFile the file that the generated patch will transform from
     * @param newFile the file that the generated patch will transform to
     * @param patchFile the path to write the generated patch to
     * @param deltaGenerators optionally, a list of {@link DeltaGenerator}s that
     * can produce efficient deltas for individual file resources that are found
     * in both oldFile and newFile archives.
     * @param compressors optionally, a list of {@link Compressor}s that can
     * compress binary artifacts.
     * @return a {@link PatchGenerationReport} detailing the process of patch
     * generation
     * @throws IOException if there is a problem reading or writing
     */
    public PatchGenerationReport makePatch(String oldFile, String newFile, String patchFile,
        List<DeltaGenerator> deltaGenerators, List<Compressor> compressors)
            throws IOException {
        if (isVerbose()) {
            log("generating patch");
            log("  old:              " + oldFile);
            log("  new:              " + newFile);
            log("  patch:            " + patchFile);
            String deltaGeneratorList = "";
            if (deltaGenerators == null || deltaGenerators.isEmpty()) {
                deltaGeneratorList = "none";
            } else {
                for (int x=0; x<deltaGenerators.size(); x++) {
                    deltaGeneratorList += deltaGenerators.get(x).getClass().getName();
                    if (x < deltaGenerators.size() - 1) {
                        deltaGeneratorList += ", ";
                    }
                }
            }
            log("  delta generators: " + deltaGeneratorList);

            String compressorList = "";
            if (compressors == null || compressors.isEmpty()) {
                compressorList = "none";
            } else {
                for (int x=0; x<compressors.size(); x++) {
                    compressorList += compressors.get(x).getClass().getName();
                    if (x < compressors.size() - 1) {
                        compressorList += ", ";
                    }
                }
            }
            log("  compressors: " + compressorList);

        }
        FileOutputStream out = null;
        DataOutputStream dos = null;

        try {
            out = new FileOutputStream(patchFile);
            dos = new DataOutputStream(out);
            PatchGenerator generator = new PatchGenerator(
                oldFile, newFile, dos, deltaGenerators, compressors);
            generator.init();
            return generator.generateAll();
        } finally {
            if (dos != null) try {
                dos.close(); // flushes and closes both streams
            } catch (Exception discard) {}
        }
    }

    /**
     * Apply a patch at the specified path (patchFile) to a specified input
     * archive (oldFile) to generate a new, patched archive at the specified
     * path (newFile) using an optional {@link DeltaApplier} to apply process
     * deltas. The specified {@link DeltaApplier} must be compatible with the
     * {@link DeltaGenerator} that was specified to
     * {@link #makePatch(String, String, String, List, List)}.
     * 
     * @param oldFile the archive to be patched
     * @param newFile the path to which the new, patched archive will be written
     * @param patchFile the path to the patch file that will be used
     * @param deltaAppliers optionally, a list of {@link DeltaApplier}s that can
     * apply deltas for individual file resources that were found in both
     * oldFile and newFile archives when the patch was generated
     * @param uncompressors optionally, a list of {@link Uncompressor}s that can
     * uncompress patch bits that have been compressed by the patch generation
     * process
     * @throws IOException if there is a problem reading or writing
     */
    public void applyPatch(String oldFile, String newFile, String patchFile,
        List<DeltaApplier> deltaAppliers, List<Uncompressor> uncompressors)
            throws IOException {
        if (isVerbose()) {
            log("applying patch");
            log("  old:   " + oldFile);
            log("  new:   " + newFile);
            log("  patch: " + patchFile);
            String deltaApplierList = "";
            if (deltaAppliers == null || deltaAppliers.isEmpty()) {
                deltaApplierList = "none";
            } else {
                for (int x=0; x<deltaAppliers.size(); x++) {
                    deltaApplierList += deltaAppliers.get(x).getClass().getName();
                    if (x < deltaAppliers.size() - 1) {
                        deltaApplierList += ", ";
                    }
                }
            }
            log("  delta appliers: " + deltaApplierList);
            String uncompressorList = "";
            if (uncompressors == null || uncompressors.isEmpty()) {
                uncompressorList = "none";
            } else {
                for (int x=0; x<uncompressors.size(); x++) {
                    uncompressorList += uncompressors.get(x).getClass().getName();
                    if (x < uncompressors.size() - 1) {
                        uncompressorList += ", ";
                    }
                }
            }
            log("  uncompressors: " + uncompressorList);
        }
        PatchApplier applier = new PatchApplier(
            oldFile, patchFile, deltaAppliers, uncompressors);
        Archive result = applier.applyPatch();
        FileOutputStream out = new FileOutputStream(newFile);
        result.writeArchive(out);
        out.flush();
        out.close();
    }

    /**
     * Explain the patching process that would take place transforming the old
     * archive (oldPath) to the new archive (newPath) in the form of a patch
     * file (patchFilePath)
     * @param oldFile
     * @param newFile
     * @param patchFile
     * @throws IOException
     */
    public void explain(String oldFile, String newFile, String patchFile)
        throws IOException {
        if (isVerbose()) {
            log("explain patch");
            log("  old:   " + oldFile);
            log("  new:   " + newFile);
            log("  patch: " + patchFile);
        }
        Archive oldArchive = Archive.fromFile(oldFile);
        final Map<Integer, CentralDirectoryFile> oldCDFByOffset =
                new HashMap<Integer, CentralDirectoryFile>();
        List<CentralDirectoryFile> cdEntries =
            oldArchive.getCentralDirectory().entries();
        for (CentralDirectoryFile entry : cdEntries) {
            int offset = (int) entry.getRelativeOffsetOfLocalHeader_32bit();
            oldCDFByOffset.put(offset, entry);
        }

        Archive newArchive = Archive.fromFile(newFile);
        CentralDirectorySection patchCd = null;
        File patch = new File(patchFile);
        PatchParser parser = new PatchParser(patch);
        parser.init();
        int numCopy = 0;
        // the records themselves, price paid in patch
        int sizeOfCopyRecords = 0;
        // the compressed data we avoided having to copy
        int sizeOfDataSavedByCopy = 0;
        int numNew = 0;
        // the records themselves, price paid in patch
        int sizeOfNewRecords = 0;
        // the compressed data we had to insert
        int sizeOfDataInNew = 0;
        int numRefresh = 0;
        // the records themselves, price paid in patch
        int sizeOfRefreshRecords = 0;
        // the compressed data we avoided having to copy
        int sizeOfDataSavedByRefresh = 0;
        int numPatch = 0;
        // the records themselves, price paid in patch
        int sizeOfPatchRecords = 0;
        // the size reduction from using delta instead of copy
        int sizeOfDataSavedByPatch = 0;
        PatchDirective directive = null;
        while( (directive = parser.read()) != null) {
            switch(directive.getCommand()) {
                case BEGIN:
                    patchCd = ((BeginMetadata) directive.getPart()).getCd();
                    break;
                case COPY:
                    numCopy++;
                    final CentralDirectoryFile copiedCdf =
                        oldCDFByOffset.get(directive.getOffset());
                    final int copiedSavings =
                        (int) copiedCdf.getCompressedSize_32bit();
                    if (isVerbose()) {
                        log("COPY " + copiedCdf.getFileName());
                        log("  cost:    5 bytes");
                        log("  savings: " + f(copiedSavings) + " bytes");
                    }
                    sizeOfCopyRecords += 1 + 4; // type + offset
                    sizeOfDataSavedByCopy += copiedSavings;
                    break;
                case PATCH:
                    numPatch++;
                    final CentralDirectoryFile patchedCdf =
                            newArchive.getCentralDirectory().getByPath(
                                    oldCDFByOffset.get(directive.getOffset())
                                    .getFileName());
                    final PatchMetadata patchMetadata =
                        (PatchMetadata) directive.getPart();
                    final String engine = BuiltInDeltaEngine.getById(
                            patchMetadata.getDeltaGeneratorId()).toString();
                    final int sizeOfPatchRecord =
                        1 + patchMetadata.getStructureLength();
                    // [cost - savings = delta versus new]
                    final int patchSavings =
                        (int) patchedCdf.getCompressedSize_32bit();
                    if (isVerbose()) {
                        log("PATCH " + patchedCdf.getFileName());
                        log("  type:    " + engine);
                        log("  cost:    " + f(sizeOfPatchRecord) + " bytes");
                        log("  savings: " + f(patchSavings) + " bytes");
                    }
                    sizeOfPatchRecords += sizeOfPatchRecord;
                    sizeOfDataSavedByPatch += patchSavings;
                    break;
                case NEW:
                    numNew++;
                    final NewMetadata newMetadata =
                        (NewMetadata) directive.getPart();
                    final int sizeOfNewRecord =
                        1 + newMetadata.getStructureLength();
                    final int sizeOfNewData =
                        newMetadata.getDataLength();
                    if (isVerbose()) {
                        log ("NEW " +
                            newMetadata.getLocalFilePart().getFileName());
                        log ("  cost:    " + f(sizeOfNewRecord) +
                            " bytes (of which " + f(sizeOfNewData) +
                            " was compressed data)");
                    }
                    sizeOfNewRecords += sizeOfNewRecord;
                    sizeOfDataInNew += sizeOfNewData;
                    break;
                case REFRESH:
                    numRefresh++;
                    final CentralDirectoryFile refreshedCdf =
                        oldCDFByOffset.get(directive.getOffset());
                    final RefreshMetadata refreshMetadata =
                        (RefreshMetadata) directive.getPart();
                    final int sizeOfRefreshRecord =
                        1 + refreshMetadata.getStructureLength();
                    final int refreshSavings =
                        (int) refreshedCdf.getCompressedSize_32bit();
                    if (isVerbose()) {
                        log("REFRESH " + refreshedCdf.getFileName());
                        log("  cost:    " + f(sizeOfRefreshRecord) + " bytes");
                        log("  savings: " + f(refreshSavings) + " bytes");
                    }
                    sizeOfRefreshRecords += sizeOfRefreshRecord;
                    sizeOfDataSavedByRefresh += refreshSavings;
                    break;
            }
        }

        // Output stats
        final int patchSize = (int) patch.length();
        final List<CentralDirectoryFile> patchCdEntries = patchCd.entries();
        int patchCdSize = 0;
        int numPatchCdEntries = 0;
        for (CentralDirectoryFile patchCdEntry : patchCdEntries) {
            patchCdSize += patchCdEntry.getStructureLength();
            numPatchCdEntries++;
        }
        log("Patch size      : " + f(patchSize) + " bytes");
        log("Patch EOCD size : " + f(patchCd.getEocd().getStructureLength()) +
            " bytes");
        log("Patch CD size   : " + f(patchCdSize) + " bytes");
        log("Patch CD entries: " + f(numPatchCdEntries));
        log("Totals:");
        final int averageSizeOfCopyRecord = numCopy == 0 ?
            0 : sizeOfCopyRecords / numCopy;
        final int averageSizeOfDataSavedByCopy = numCopy == 0 ?
            0 : sizeOfDataSavedByCopy / numCopy;
        final int averageSizeOfRefreshRecord = numRefresh == 0 ?
            0 : sizeOfRefreshRecords / numRefresh;
        final int averageSizeOfDataSavedByRefresh = numRefresh == 0
            ? 0 : sizeOfDataSavedByRefresh / numRefresh;
        final int averageSizeOfPatchRecord = numPatch == 0 ?
            0 : sizeOfPatchRecords / numPatch;
        final int averageSizeOfDataSavedByPatch = numPatch == 0 ?
            0 : sizeOfDataSavedByPatch / numPatch;
        log("COPY (" + f(numCopy) + " entries):");
        log("  cost:    " + f(sizeOfCopyRecords) + " bytes (average " +
            f(averageSizeOfCopyRecord) + ")");
        log("  savings: " + f(sizeOfDataSavedByCopy) + " bytes (average " +
            f(averageSizeOfDataSavedByCopy) + ")");
        log("REFRESH (" + f(numRefresh) + " entries):");
        log("  cost:    " + f(sizeOfRefreshRecords) + " bytes (average " +
            f(averageSizeOfRefreshRecord) + ")");
        log("  savings: " + f(sizeOfDataSavedByRefresh) + " bytes (average " +
            f(averageSizeOfDataSavedByRefresh) + ")");
        log("PATCH (" + f(numPatch) + " entries):");
        log("  cost:    " + f(sizeOfPatchRecords) + " bytes (average " +
            f(averageSizeOfPatchRecord) + ")");
        log("  savings: " + f(sizeOfDataSavedByPatch) + " bytes (average " +
            f(averageSizeOfDataSavedByPatch) + ")");
        log("NEW (" + numNew + " entries):");
        log("  cost:    " + f(sizeOfNewRecords) + " bytes (of which " +
            f(sizeOfDataInNew) + " was compressed data)");
    }

}
