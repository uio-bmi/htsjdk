package htsjdk.variant.vcf;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Interval;
import htsjdk.samtools.util.IntervalList;
import htsjdk.tribble.AbstractFeatureReader;
import htsjdk.tribble.FeatureCodec;
import htsjdk.tribble.FeatureReader;
import htsjdk.tribble.TribbleException;
import htsjdk.variant.bcf2.BCF2Codec;
import htsjdk.variant.variantcontext.VariantContext;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Simplified interface for reading from VCF/BCF files.
 */
public class VCFPathReader implements Closeable, Iterable<VariantContext> {

    private final FeatureReader<VariantContext> reader;

    /**
     * Returns true if the given path appears to be a BCF file.
     */
    public static boolean isBCF(final Path path) {
        return path.toUri().getRawPath().endsWith(".bcf");
    }

    /**
     * returns Correct Feature codec for Path depending whether
     * the name seems to indicate that it's a BCF.
     *
     * @param path to vcf/bcf
     * @return FeatureCodec for input Path
     */
    private static FeatureCodec<VariantContext, ?> getCodecForPath(Path path) {
        return isBCF(path) ? new BCF2Codec() : new VCFCodec();
    }

    /**
     * Returns the SAMSequenceDictionary from the provided VCF file.
     */
    public static SAMSequenceDictionary getSequenceDictionary(final Path path) {
        final SAMSequenceDictionary dict = new VCFPathReader(path, false).getFileHeader().getSequenceDictionary();
        return dict;
    }

    /**
     * Constructs a VCFFileReader that requires the index to be present.
     */
    public VCFPathReader(final Path path) {
        this(path, true);
    }

    /**
     * Constructs a VCFFileReader with a specified index.
     */
    public VCFPathReader(final Path path, final Path indexPath) {
        this(path, indexPath, true);
    }

    /**
     * Allows construction of a VCFFileReader that will or will not assert the presence of an index as desired.
     */
    public VCFPathReader(final Path path, final boolean requireIndex) {
        this.reader = AbstractFeatureReader.getFeatureReader(
                path.toUri().toString(),
                getCodecForPath(path),
                requireIndex);
    }

    /**
     * Allows construction of a VCFFileReader with a specified index path.
     */
    public VCFPathReader(final Path path, final Path indexPath, final boolean requireIndex) {
        this.reader = AbstractFeatureReader.getFeatureReader(
                path.toUri().toString(),
                indexPath.toUri().toString(),
                getCodecForPath(path),
                requireIndex);
    }


    /**
     * Parse a VCF file and convert to an IntervalList The name field of the IntervalList is taken from the ID field of the variant, if it exists. if not,
     * creates a name of the format interval-n where n is a running number that increments only on un-named intervals
     *
     * @param path
     * @return
     */
    public static IntervalList toIntervalList(final Path path) {
        return toIntervalList(path, false);
    }

    public static IntervalList toIntervalList(final Path path, final boolean includeFiltered) {
        try(final VCFPathReader vcfReader = new VCFPathReader(path, false)) {
            return vcfReader.toIntervalList(includeFiltered);
        }
    }


    /**
     * Converts a vcf to an IntervalList. The name field of the IntervalList is taken from the ID field of the variant, if it exists. If not,
     * creates a name of the format interval-n where n is a running number that increments only on un-named intervals
     * Will use a "END" tag in the info field as the end of the interval (if exists).
     *
     * @return an IntervalList constructed from input vcf
     */

    public IntervalList toIntervalList() {
        return toIntervalList(false);
    }

    /**
     * Converts a vcf to an IntervalList. The name field of the IntervalList is taken from the ID field of the variant, if it exists. If not,
     * creates a name of the format interval-n where n is a running number that increments only on un-named intervals
     * Will use a "END" tag in the info field as the end of the interval (if exists).
     *
     * @param includeFiltered a flag indicating if filtered variants should also be included in the result
     * @return an IntervalList constructed from input vcf
     */
    public IntervalList toIntervalList(final boolean includeFiltered) {

        //grab the dictionary from the VCF and use it in the IntervalList
        final SAMSequenceDictionary dict = getFileHeader().getSequenceDictionary();
        final SAMFileHeader samFileHeader = new SAMFileHeader();
        samFileHeader.setSequenceDictionary(dict);
        final IntervalList list = new IntervalList(samFileHeader);

        int intervals = 0;
        for (final VariantContext vc : this) {
            if (includeFiltered || !vc.isFiltered()) {
                String name = vc.getID();
                final Integer intervalEnd = vc.getCommonInfo().getAttributeAsInt("END", vc.getEnd());
                if (name == null || name.equals(".")) {
                    name = "interval-" + (++intervals);
                }
                list.add(new Interval(vc.getContig(), vc.getStart(), intervalEnd, false, name));
            }
        }

        return list;
    }

    /**
     * Returns the VCFHeader associated with this VCF/BCF file.
     */
    public VCFHeader getFileHeader() {
        return (VCFHeader) reader.getHeader();
    }

    /**
     * Returns an iterator over all records in this VCF/BCF file.
     */
    @Override
    public CloseableIterator<VariantContext> iterator() {
        try {
            return reader.iterator();
        } catch (final IOException ioe) {
            throw new TribbleException("Could not create an iterator from a feature reader.", ioe);
        }
    }

    /**
     * Queries for records overlapping the region specified.
     * Note that this method requires VCF files with an associated index.  If no index exists a TribbleException will be thrown.
     *
     * @param chrom the chomosome to query
     * @param start query interval start
     * @param end   query interval end
     * @return non-null iterator over VariantContexts
     */
    public CloseableIterator<VariantContext> query(final String chrom, final int start, final int end) {
        try {
            return reader.query(chrom, start, end);
        } catch (final IOException ioe) {
            throw new TribbleException("Could not create an iterator from a feature reader.", ioe);
        }
    }

    @Override
    public void close() {
        try {
            this.reader.close();
        } catch (final IOException ioe) {
            throw new TribbleException("Could not close a variant context feature reader.", ioe);
        }
    }
}